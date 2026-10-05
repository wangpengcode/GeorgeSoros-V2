"""盘中数据源（§19.13.2 盘中监控页 P1-P3 数据面）。

设计要点：
- 与日K DataRouter 管线（多源轮转/熔断）解耦：盘中是低频实时快照，akshare 单源覆盖
  （东财池子/spot/五档），不参与日K三源均分语义
- 限流红线：每个底层 ak 调用前 acquire() 一次 TokenBucket（失败即抛，绝不无令牌猛打外部源），
  桶参数独立于日K源（rate_intraday + 抖动，config.py 单点）
- 归一化：akshare 中文列 → 裸数字 snake_case 键（与既有 adapter 同风格：输入/输出裸数字）
  - 成交量 手×100 → 股（与日K AKSHARE_VOLUME_MULTIPLIER 口径一致）
- 异常：外部调用失败原样上抛，由 router 层统一收口 503 INTRADAY_FAILED 错误信封
"""

from __future__ import annotations

import logging
from typing import Dict, List

import akshare as ak

from adapters.base import AdapterError
from config import settings
from constants import AKSHARE_VOLUME_MULTIPLIER
from rate_limiter import TokenBucket

logger = logging.getLogger(__name__)


def _f(v) -> float:
    """安全转 float：None / NaN / 非法值 → 0.0。"""
    if v is None:
        return 0.0
    try:
        return float(v)
    except (ValueError, TypeError):
        return 0.0


def _s(v) -> str:
    """安全转 str：None → 空串；数字代码防科学计数法漂移（全部按原样文本化）。"""
    if v is None:
        return ""
    return str(v).strip()


class IntradaySource:
    """盘中快照源：涨停/跌停/炸板池 + 全市场 spot + 单票五档。"""

    source_name = "intraday-akshare"

    def __init__(self, rate_limiter: TokenBucket | None = None):
        self.rate_limiter = rate_limiter or TokenBucket(
            rate=settings.rate_intraday,
            jitter=settings.intraday_jitter_seconds,
            name="intraday",
            acquire_timeout_seconds=settings.rate_acquire_timeout_seconds,
        )

    def _acquire(self, what: str) -> None:
        """取令牌，失败 → AdapterError（router 层收口 503；绝不无令牌发起外部调用）。"""
        if not self.rate_limiter.acquire():
            raise AdapterError(f"intraday 限流等待超时，本轮放弃: {what}")

    # ── 池子（涨停/跌停/炸板） ──────────────────────────────────────────────

    def fetch_pools(self, date_yyyymmdd: str) -> Dict[str, List[dict]]:
        """三池快照：{limit_up, limit_down, broken}；date_yyyymmdd 形如 20260930。"""
        specs = [
            ("limit_up", ak.stock_zt_pool_em),
            ("limit_down", ak.stock_zt_pool_dtgc_em),
            ("broken", ak.stock_zt_pool_zbgc_em),
        ]
        payload: Dict[str, List[dict]] = {}
        for name, fn in specs:
            self._acquire(f"pool/{name}")
            try:
                df = fn(date=date_yyyymmdd)
            except Exception as exc:  # noqa: BLE001
                # 单池失败不炸整端点：该池置空（盘中监控页显示空池可接受）
                logger.warning("盘中池 %s(%s) 拉取失败: %s", name, date_yyyymmdd, exc)
                payload[name] = []
                continue
            payload[name] = [self._norm_pool_row(row) for _, row in df.iterrows()] if df is not None and not df.empty else []
        return payload

    def _norm_pool_row(self, row) -> dict:
        return {
            "code": _s(row.get("代码")),
            "name": _s(row.get("名称")),
            "change_pct": _f(row.get("涨跌幅")),
            "latest_price": _f(row.get("最新价")),
            "amount": _f(row.get("成交额")),
            "turnover_rate": _f(row.get("换手率")),
            "lianban": int(_f(row.get("连板数", row.get("连续跌停", 0)))),
            "first_time": _s(row.get("首次封板时间")),
            "broken_count": int(_f(row.get("炸板次数", row.get("开板次数", 0)))),
            "industry": _s(row.get("所属行业")),
        }

    # ── 全市场 spot 快照 ────────────────────────────────────────────────────

    def fetch_spot(self) -> List[dict]:
        """全市场实时快照（东财 stock_zh_a_spot_em）；成交量 手×100→股。"""
        self._acquire("spot")
        df = ak.stock_zh_a_spot_em()
        if df is None or df.empty:
            return []
        return [
            {
                "code": _s(row.get("代码")),
                "name": _s(row.get("名称")),
                "latest_price": _f(row.get("最新价")),
                "change_pct": _f(row.get("涨跌幅")),
                "volume": int(_f(row.get("成交量")) * AKSHARE_VOLUME_MULTIPLIER),
                "amount": _f(row.get("成交额")),
                "turnover_rate": _f(row.get("换手率")),
            }
            for _, row in df.iterrows()
        ]

    # ── 单票五档 ───────────────────────────────────────────────────────────

    def fetch_bid_ask(self, code: str) -> dict:
        """五档盘口：item/value 行 → bids/asks 各 ≤5 档 [价, 量]（bids 买1→买5 / asks 卖1→卖5）。"""
        self._acquire(f"bid-ask/{code}")
        df = ak.stock_bid_ask_em(symbol=code)
        buy_prices: Dict[int, float] = {}
        buy_vols: Dict[int, float] = {}
        sell_prices: Dict[int, float] = {}
        sell_vols: Dict[int, float] = {}
        if df is not None and not df.empty:
            for _, row in df.iterrows():
                item = _s(row.get("item"))
                value = _f(row.get("value"))
                for i in range(1, 6):
                    if item == f"买{i}价":
                        buy_prices[i] = value
                    elif item == f"买{i}量":
                        buy_vols[i] = value
                    elif item == f"卖{i}价":
                        sell_prices[i] = value
                    elif item == f"卖{i}量":
                        sell_vols[i] = value
        # 空档补 0.0（停牌/异常票档位可能不足 5）
        bids = [[buy_prices.get(i, 0.0), buy_vols.get(i, 0.0)] for i in range(1, 6)]
        asks = [[sell_prices.get(i, 0.0), sell_vols.get(i, 0.0)] for i in range(1, 6)]
        return {"code": code, "bids": bids, "asks": asks}
