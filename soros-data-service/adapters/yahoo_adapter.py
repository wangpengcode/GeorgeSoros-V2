"""Yahoo Finance Adapter（2026-10-04 第四源：分压路由成员 + 校准/对拍第三腿）。

定位（用户 2026-10-04 拍板「Yahoo 也加入拉取队列」）：
- 可选源：Router 缺席不报错；注册后排 failover 序尾（router_order 默认不含，
  _resolve_order 按「已注册」追加），分片默认池 baostock,akshare,yahoo
- 实现选型（穿透实验 2026-10-04）：绕开 yfinance 库——其强制 curl_cffi chrome
  指纹被 Yahoo 边缘持续 429（对照：chart API + 普通 requests + 浏览器 UA 稳定 200）。
  直连 v8/finance/chart，单次调用含原始 quote.close + adjclose 双列，限流自控
- 口径（与设计穿透一致）：
  * qfq：factor = adjclose/close → OHLC×factor、close=adjclose（对齐最新复权口径）
  * change_percent 从【原始】收盘算——复权价算涨跌幅是错的（与新浪链同铁律）
  * start 前扩 10 自然日 → 请求窗首行也有真实昨收（与 sina RAW_LOOKBACK 同思路）
- 口径缺口如实标注（不硬凑）：turnover 无源数据 → 0；
  amount = 典型价((H+L+C)/3)×volume 估算（raw 口径，落库可辨识为估算值）
- hfq 不支持 → ParameterError（跨源一致语义，不计熔断失败）
- 限流：走 BaseAdapter._call_guarded（rate_yahoo 默认 0.5 rps + 抖动；
  外部源间歇性获取铁律，禁连续猛打）
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import List, Optional
from zoneinfo import ZoneInfo

import requests

from adapters.base import BaseAdapter, ParameterError, SourceError
from constants import SOURCE_YAHOO

logger = logging.getLogger(__name__)

# 涨跌幅昨收前扩窗口（自然日；与 sina RAW_LOOKBACK_DAYS 同思路）
RAW_LOOKBACK_DAYS = 10
_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{ticker}"
_HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"),
}
_TIMEOUT_SECONDS = 15

# 6/9 开头沪市（含 60x/68x/9xx B股），其余深市——与 to_baostock_code / sina 前缀同规则
_SH_PREFIXES = ("6", "9")


def to_yahoo_ticker(code: str) -> str:
    """裸数字代码 → Yahoo ticker（600000 → 600000.SS，000001 → 000001.SZ）。"""
    suffix = ".SS" if code.startswith(_SH_PREFIXES) else ".SZ"
    return code + suffix


def _period_params(start: str, end: str) -> dict:
    """epoch 参数（纯函数可测）：period1 前扩 10 自然日（昨收）；period2 互斥 → +1 天。"""
    params: dict = {}
    if start:
        start_dt = datetime.strptime(start, "%Y-%m-%d") - timedelta(days=RAW_LOOKBACK_DAYS)
        params["period1"] = str(int(start_dt.replace(tzinfo=timezone.utc).timestamp()))
    if end:
        end_dt = datetime.strptime(end, "%Y-%m-%d") + timedelta(days=1)
        params["period2"] = str(int(end_dt.replace(tzinfo=timezone.utc).timestamp()))
    return params


class YahooAdapter(BaseAdapter):
    source_name = SOURCE_YAHOO

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        # Yahoo 只做日K腿：不参与 stock-list/退市/指数/业绩/板块
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        self._supports_index = False
        self._supports_fundamentals = False
        self._supports_board_members = False

    # ---- 日 K 线 ----
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        if adjust == "hfq":
            raise ParameterError("yahoo: 不支持 hfq（无后复权源数据）")
        chart = self._fetch_chart_json(to_yahoo_ticker(code), start, end)
        rows = self._parse_chart(chart)
        if not rows:
            return []
        return self._to_bars(rows, code, start, end, adjust)

    # ---- chart API 拉取（独立方法：测试注入缝；requests 异常统一 SourceError）----
    def _fetch_chart_json(self, ticker: str, start: str, end: str) -> dict:
        params = {"interval": "1d", "includeAdjustedClose": "true", "events": "div,splits"}
        params.update(_period_params(start, end))
        try:
            resp = requests.get(_CHART_URL.format(ticker=ticker), params=params,
                                headers=_HEADERS, timeout=_TIMEOUT_SECONDS)
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:  # noqa: BLE001 - 网络层异常统一 SourceError 分类
            raise SourceError(f"yahoo 拉取失败: {exc}") from exc

    # ---- chart JSON → 行序列（raw OHLCV + adjclose；停牌/缺数行剔除）----
    def _parse_chart(self, chart: dict) -> List[dict]:
        try:
            result = chart["chart"]["result"][0]
            timestamps = result["timestamp"]
            quote = result["indicators"]["quote"][0]
            adjcloses = result["indicators"].get("adjclose", [{}])[0].get("adjclose")
            tz = ZoneInfo(result["meta"].get("exchangeTimezoneName", "Asia/Shanghai"))
        except (KeyError, IndexError, TypeError):
            return []  # 空结果/坏结构 → Router「空结果」语义 failover，不炸
        rows: List[dict] = []
        for i, ts in enumerate(timestamps):
            raw_o, raw_h = quote["open"][i], quote["high"][i]
            raw_l, raw_c = quote["low"][i], quote["close"][i]
            volume = quote["volume"][i]
            if None in (raw_o, raw_h, raw_l, raw_c, volume):
                continue  # 停牌/缺数行
            rows.append({
                "date": datetime.fromtimestamp(ts, tz).strftime("%Y-%m-%d"),
                "open": float(raw_o), "high": float(raw_h), "low": float(raw_l),
                "close": float(raw_c), "volume": float(volume),
                "adjclose": float(adjcloses[i]) if adjcloses else float(raw_c),
            })
        return rows

    # ---- 行口径转换 ----
    def _to_bars(self, rows: List[dict], code: str, start: str, end: str, adjust: str) -> List[dict]:
        scaled = adjust == "qfq"
        bars: List[dict] = []
        prev_raw_close: Optional[float] = None
        for row in rows:
            d = row["date"]
            if start and d < start:
                continue  # 前扩行只参与昨收，不落窗
            if end and d > end:
                continue
            raw_close = row["close"]
            factor = (row["adjclose"] / raw_close) if scaled and raw_close else 1.0
            change = ((raw_close / prev_raw_close - 1) * 100) if prev_raw_close else 0.0
            high, low, volume = row["high"], row["low"], row["volume"]
            bars.append({
                "date": d,
                "code": code,
                "open": row["open"] * factor,
                "high": high * factor,
                "low": low * factor,
                "close": row["adjclose"] if scaled else raw_close,
                "volume": volume,                       # 已是股，不乘系数
                "amount": ((high + low + raw_close) / 3) * volume,  # 典型价×量（估算）
                "change_percent": round(change, 4),     # 原始收盘口径
                "turnover": 0,                          # 无源数据，如实置 0
                "prev_close": prev_raw_close,
            })
            prev_raw_close = raw_close
        return bars
