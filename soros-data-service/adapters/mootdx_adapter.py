"""mootdx Adapter（PLAN §5.5 V2 版）。

实现要点（PLAN §5.5）：
- 输入裸数字 + market（6/9 → 沪=1，否则深=0）
- 分页拉取 get_security_bars(category=9, market, code, start=i*800, count=800)
- mootdx 不返回 change_percent，用 last_close 现算（不复权，准确）
- 不输出 prev_close：mootdx 是不复权数据，与库内 qfq 口径不同，
  其 prev_close 参与 §4.6 校验会天天误报（PLAN §5.5 降级说明）
- turnover=0（mootdx 不提供换手率）
- SDK 调用抛错向上抛（不吞）：base.py _call_guarded 分类为 SourceError 计熔断失败，
  修复旧行为吞异常返回 [] 导致 record_success 误计、熔断永不 open、/health 恒 "ok"

⚠️ M3 实测校准说明（探针 probe-units-v2.md：mootdx 未测，本机无可用节点）：
- volume 单位假设=手 ×100 → 股（§2.4 字典口径，MOOTDX_VOLUME_MULTIPLIER）
- 复权：mootdx 仅不复权日K；作为 failover 源写入的 bar 与库内 qfq 序列口径不一致，
  Kotlin 侧对 source=mootdx 的 bar 跳过 §4.6 漂移检测，并写 data_quality_log（RAW_FALLBACK）
- 复权能力守卫（2026-10-04 改动 3）：qfq/hfq 请求一律 CapabilityError（能力型，failover 继续；不计熔断）
  ——TDX 只有 raw 口径，此前静默忽略 adjust 返回 raw，qfq failover 到它会写错误口径行
  （潜伏口径 bug，参照 sse_adapter 守卫模式补齐）
"""

from __future__ import annotations

import logging
from typing import List

from adapters.base import BaseAdapter, CapabilityError, SourceError
from constants import (
    ADJUST_HFQ,
    ADJUST_QFQ,
    MOOTDX_VOLUME_MULTIPLIER,
    MOOTDX_AMOUNT_MULTIPLIER,
    to_mootdx_market,
)

logger = logging.getLogger(__name__)


def _bar_value(bar, key, default=0.0):
    """兼容 dict / pandas Series / 命名元组 的字段读取。"""
    if isinstance(bar, dict):
        return bar.get(key, default)
    try:
        return bar[key]
    except (KeyError, IndexError, TypeError):
        pass
    try:
        return getattr(bar, key, default)
    except Exception:  # noqa: BLE001
        return default


class MootdxAdapter(BaseAdapter):
    source_name = "mootdx"

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        # stock-list/is_st/退市状态永不走 mootdx（PLAN §11.1）
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        # raw 不复权：qfq/hfq 均不支持（mootdx/TDX 仅提供不复权日K；qfq 请求一律 CapabilityError，
        # 参照 sse_adapter 守卫模式——静默返回 raw 会写错误口径行，潜伏口径 bug）
        self._supports_adjust_qfq = False
        self._supports_adjust_hfq = False
        self._client = None

    def _get_client(self):
        if self._client is None:
            try:
                from mootdx.quotes import Quotes

                self._client = Quotes.factory(market="std")
            except Exception as exc:  # noqa: BLE001
                raise SourceError(f"mootdx client 初始化失败: {exc}") from exc
        return self._client

    def _safe_call(self, fn, *args):
        """mootdx SDK 调用直通（新契约，MEDIUM-1）：异常向上抛，不吞。

        由 base.py _call_guarded 统一分类：SDK 抛错 → record_failure + SourceError
        （修复旧行为：吞异常返回 [] → 被 _call_guarded 误 record_success，熔断永不 open）。
        成功返回空列表属「该区间无数据」合法情况，保持返回（Router failover / failed[]）。
        """
        return fn(*args)

    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        # 复权能力守卫：raw 仅支持 none；qfq/hfq 一律 CapabilityError（能力型，不计熔断；
        # ParameterError 不计熔断/台账的语义由 base.py _call_guarded/_fetch_stock_daily 现有守卫保证）
        if adjust in (ADJUST_QFQ, ADJUST_HFQ) and not getattr(self, f"_supports_adjust_{adjust}", False):
            raise CapabilityError(
                f"{self.source_name}: 不支持 {adjust} 复权（mootdx/TDX 仅提供不复权日K）"
            )
        client = self._get_client()
        market = to_mootdx_market(code)
        all_bars = []
        # 分页拉取：最多 20 页 × 800 根（PLAN §5.5）
        for i in range(20):
            bars = self._safe_call(
                client.client.get_security_bars, 9, market, code, i * 800, 800
            )
            if not bars:
                break
            all_bars.extend(bars)

        # 过滤日期区间 + 升序排序
        out = []
        for bar in all_bars:
            dt = str(_bar_value(bar, "datetime", "") or "")[:10]
            if not dt:
                continue
            if dt < start or dt > end:
                continue
            last_close = _bar_value(bar, "last_close", 0.0)
            close = _bar_value(bar, "close", 0.0)
            change_pct = ((close - last_close) / last_close * 100) if last_close > 0 else 0
            out.append({
                "date": dt,
                "code": code,                                        # 裸数字
                "open": float(_bar_value(bar, "open", 0)),
                "high": float(_bar_value(bar, "high", 0)),
                "low": float(_bar_value(bar, "low", 0)),
                "close": float(close),
                "volume": float(_bar_value(bar, "vol", 0)) * MOOTDX_VOLUME_MULTIPLIER,   # 手→股，待 M3 校准
                "amount": float(_bar_value(bar, "amount", 0)) * MOOTDX_AMOUNT_MULTIPLIER,
                "change_percent": round(change_pct, 4),              # 不复权现算
                "turnover": 0,                                       # mootdx 不提供换手率
                # 不输出 prev_close：不复权口径与库内 qfq 不一致（PLAN §5.5）
            })
        out.sort(key=lambda b: b["date"])
        return out
