"""腾讯行情 Adapter（2026-10-04 第五源：独立转发商 + 分压路由可选成员）。

定位（PLAN §19.3 ① 实测通过 A 级，优先落地）：
- 腾讯为独立转发商，与东财/新浪不同源；proxy.finance.qq.com / web.ifzq.gtimg.cn /
  qt.gtimg.cn 3/3 域全通、无鉴权、qfq 齐全。
  ⚠ akshare 内置 stock_zh_a_hist_tx 对 sz000xxx 有 volume×100 bug → 自建 HTTP 解析，勿直接调包。
- 可选源：Router 缺席不报错；注册后排 failover 序尾（router_order 默认不含，
  _resolve_order 按「已注册」追加）。

端点（2026-10-04 双路实测）：
- https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param={symbol},day,{start},{end},{count},{adjust}
    * symbol=sh600000/sz000001 带市场前缀（to_tencent_symbol）；adjust∈qfq/hfq/空（空=raw）
    * 返回 JSON：data.{symbol}.qfqday（带复权时）或 .day（无复权时）=
      [["2026-09-30","11.36","11.57","11.65","11.33","1045357.00",...],...]
    * 字段序=[date, open, close, high, low, volume(手!), 分红dict?, turnover%, amount(万元), 占位]
      ——注意 close 在第 3 位、high/low 在 4/5 位（与 OHLC 直觉不同！）
    * count 参数给足窗口：(end-start).days+10，上限 800

口径（与设计穿透一致）：
- volume 手 → ×100 转股；amount 万元 → ×1e4 转元；turnover 已是 % 值直接用
- change_percent 无现成列 → 相邻行 close 链式自算（照 yahoo_adapter 的 prev_close chain 先例），
  首行无昨收时 null。注意：腾讯单响应仅含一种复权口径（qfq 或 raw），不复权涨跌幅需权衡
  是否追加一次 raw 请求——见 _to_bars TODO，implementer 定。
- 限流：走 BaseAdapter._call_guarded（rate_tencent 默认 1.0 rps + 抖动；
  外部源间歇性获取铁律，禁连续猛打）。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import List

import requests

from adapters.base import BaseAdapter, CapabilityError, SourceError
from constants import (
    SOURCE_TENCENT,
    TENCENT_VOLUME_MULTIPLIER,
    TENCENT_AMOUNT_MULTIPLIER,
    to_tencent_symbol,
    ADJUST_QFQ,
    ADJUST_HFQ,
)

logger = logging.getLogger(__name__)

# count 窗口前扩（自然日；供首行涨跌幅算昨收——与 sina/yahoo 前扩同思路）
COUNT_LOOKBACK_DAYS = 10
# count 上限（腾讯服务端约束；实测窗口 >800 会截断/报错）
MAX_COUNT = 800
_KLINE_URL = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
_HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"),
}
_TIMEOUT_SECONDS = 15


class TencentAdapter(BaseAdapter):
    source_name = SOURCE_TENCENT

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        # 只做日K腿：不参与 stock-list/退市/指数/业绩/板块
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        self._supports_index = False
        self._supports_fundamentals = False
        self._supports_board_members = False
        # 复权能力：qfq/hfq 均支持（腾讯服务端自算，adjust 参数直传）
        self._supports_adjust_qfq = True
        self._supports_adjust_hfq = True

    # ---- 日 K 线 ----
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        # 复权能力守卫（能力标记 + 显式抛错；raw none 恒支持）
        if adjust in (ADJUST_QFQ, ADJUST_HFQ) and not getattr(self, f"_supports_adjust_{adjust}", False):
            raise CapabilityError(f"{self.source_name}: 不支持 {adjust} 复权")
        symbol = to_tencent_symbol(code)
        kline = self._fetch_kline_json(symbol, start, end, adjust)
        rows = self._parse_kline(kline, symbol)
        if not rows:
            return []
        return self._to_bars(rows, code, start, end)

    # ---- 参数构建（纯函数可测：count 前扩 + adjust 直传）----
    def _count_for_window(self, start: str, end: str) -> int:
        """count 前扩窗口（自然日；首行昨收用）：(end-start).days + 10，上限 800。

        防御：start/end 缺失或非法日期 → 退化为最小前扩窗口（不炸）。
        """
        if not start or not end:
            return COUNT_LOOKBACK_DAYS
        try:
            days = (datetime.strptime(end, "%Y-%m-%d") - datetime.strptime(start, "%Y-%m-%d")).days
        except (TypeError, ValueError):
            return COUNT_LOOKBACK_DAYS
        return min(days + COUNT_LOOKBACK_DAYS, MAX_COUNT)

    def _build_param(self, symbol: str, start: str, end: str, adjust: str) -> str:
        count = self._count_for_window(start, end)
        adjust_param = adjust if adjust in (ADJUST_QFQ, ADJUST_HFQ) else ""
        return f"{symbol},day,{start},{end},{count},{adjust_param}"

    # ---- fqkline API 拉取（独立方法：测试注入缝；requests 异常统一 SourceError）----
    def _fetch_kline_json(self, symbol: str, start: str, end: str, adjust: str) -> dict:
        params = {"param": self._build_param(symbol, start, end, adjust)}
        try:
            resp = requests.get(_KLINE_URL, params=params, headers=_HEADERS, timeout=_TIMEOUT_SECONDS)
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:  # noqa: BLE001 - 网络层异常统一 SourceError 分类
            raise SourceError(f"{self.source_name} 拉取失败: {exc}") from exc

    # ---- kline JSON → 行序列 ----
    def _parse_kline(self, kline: dict, symbol: str) -> List[dict]:
        """解析 data.{symbol}.qfqday/.day → 行序列。

        行字段序=[date, open, close, high, low, volume(手!), 分红dict?, turnover%, amount(万元), 占位]；
        close 在第 3 位、high/low 在 4/5 位（与 OHLC 直觉不同！）。
        键按 adjust 分派：qfq → qfqday / hfq → hfqday / raw → day（服务端单响应仅含一种复权口径）。
        坏结构/空结果 → []（Router「空结果」语义 failover，不炸）；None/缺字段行跳过（对齐 yahoo 防御风格）。
        """
        try:
            node = kline["data"][symbol]
        except (KeyError, TypeError):
            return []
        if not isinstance(node, dict):
            return []
        rows_raw = None
        for key in ("qfqday", "hfqday", "day"):
            if key in node:
                rows_raw = node[key]
                break
        if not isinstance(rows_raw, list):
            return []
        rows: List[dict] = []
        for item in rows_raw:
            if not isinstance(item, (list, tuple)) or len(item) < 9:
                continue  # 坏结构/缺列行跳过
            if item[0] is None:
                continue
            try:
                date = str(item[0])
                open_ = float(item[1])
                close = float(item[2])       # close 第 3 位（与 OHLC 直觉不同！）
                high = float(item[3])
                low = float(item[4])
                volume = float(item[5])      # 手（×100 转股留到 _to_bars）
            except (TypeError, ValueError):
                continue  # None/缺字段行跳过（对齐 yahoo 防御风格）
            amount = float(item[8]) if item[8] is not None else 0.0    # 万元（×1e4 留到 _to_bars）
            turnover = float(item[7]) if item[7] is not None else 0.0  # 已是 % 值，直接用
            rows.append({
                "date": date,
                "open": open_, "close": close, "high": high, "low": low,
                "volume": volume, "turnover": turnover, "amount": amount,
            })
        return rows

    # ---- 行口径转换 ----
    def _to_bars(self, rows: List[dict], code: str, start: str, end: str) -> List[dict]:
        """rows → PLAN §5.6 11 键 bar 列表。

        口径：volume 手 → ×100 转股（TENCENT_VOLUME_MULTIPLIER）；amount 万元 → ×1e4 转元
        （TENCENT_AMOUNT_MULTIPLIER）；turnover 已是 % 直接用；change_percent 相邻行 close
        链式自算、首行 null；窗口 [start, end] 过滤（前扩行只参与昨收）。
        注：qfq/hfq 请求腾讯仅返回对应复权 close，change_percent 如实按响应 close 口径自算
        （不复权涨跌幅需另发 raw 请求，暂不追加；口径冲突由上游归因标注，非本 adapter 兜底）。
        """
        bars: List[dict] = []
        prev_close = None
        for row in rows:
            day = row["date"]
            close = row["close"]
            change = None if prev_close is None else (close / prev_close - 1) * 100
            last_close = prev_close
            prev_close = close  # 前扩行也参与昨收链（供请求窗首行真实昨收）
            if start and day < start:
                continue  # 前扩行只参与昨收，不落窗
            if end and day > end:
                continue
            bars.append({
                "date": day,
                "code": code,
                "open": row["open"], "high": row["high"], "low": row["low"], "close": close,
                "volume": row["volume"] * TENCENT_VOLUME_MULTIPLIER,  # 手 → 股
                "amount": row["amount"] * TENCENT_AMOUNT_MULTIPLIER,  # 万元 → 元
                "change_percent": round(change, 4) if change is not None else None,
                "turnover": row["turnover"],
                "prev_close": last_close,
            })
        return bars
