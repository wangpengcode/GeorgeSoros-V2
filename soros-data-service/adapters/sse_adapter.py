"""上交所行情云 Adapter（2026-10-04 第六源：源头级校准腿 + 首次建仓灌历史）。

定位（PLAN §19.3 ② 实测通过 A 级，源头级）：
- yunhq.sse.com.cn:32042 源头级接口：单次返回单股 IPO 首日至今全历史
  （600000 实测 6400 条/377KB，1999-11-10 起）——覆盖深度优于 baostock。
- 可选源：Router 缺席不报错；注册后排 failover 序尾（router_order 默认不含，
  _resolve_order 按「已注册」追加）。定位=第五校准源（权威基准）+ 首次建仓灌历史，
  非日常批量（日更分片池按 §19.2 决策，不默认纳入）。

端点（2026-10-04 双路实测）：
- https://yunhq.sse.com.cn:32042/v1/{sh1|sz1}/dayk/{code}?select=date,open,high,low,close,volume,amount&begin={YYYYMMDD|int}&end={YYYYMMDD|int}
    * 6/9 开头 → sh1，其余 → sz1（to_sse_market）；begin=0&end=-1=全历史
    * 返回：{"code","total","begin","end","kline":[[20260930,11.36,11.65,11.33,11.57,104535745,1205814858],...]}
      字段序=[date(int YYYYMMDD), open, high, low, close, volume(股,已实锤与 baostock 一字不差), amount(元)]
    * Header：Referer http://www.sse.com.cn/
    * 端口 32041 SSL 失败用 32042（§19.3 ②）

口径：
- raw 不复权：_supports_adjust_qfq/hfq 全 False → qfq/hfq 请求一律 CapabilityError
  （跨源一致，不计熔断失败）。官网源口径「raw 真值 + 复权因子推 qfq」归 AdjustCheckStep
  §17.2 B6 咬合，不在本 adapter 内做复权。
- change_percent 由相邻 raw close 链式自算，首行无昨收时 null（照 yahoo prev_close chain 先例）。
- 限流：走 BaseAdapter._call_guarded（rate_sse 默认 0.5 rps + 抖动；
  外部源间歇性获取铁律，禁连续猛打）。
"""

from __future__ import annotations

import logging
from typing import List

import requests

from adapters.base import BaseAdapter, CapabilityError, SourceError
from constants import (
    SOURCE_SSE,
    SSE_VOLUME_MULTIPLIER,
    SSE_AMOUNT_MULTIPLIER,
    to_sse_market,
    ADJUST_QFQ,
    ADJUST_HFQ,
)

logger = logging.getLogger(__name__)

_DAYK_URL = "https://yunhq.sse.com.cn:32042/v1/{market}/dayk/{code}"
# 源头 raw 真值 + 复权因子推 qfq 归 AdjustCheckStep（§17.2 B6），本 adapter 永不做复权
_HEADERS = {
    "Referer": "http://www.sse.com.cn/",
    "User-Agent": ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"),
}
_TIMEOUT_SECONDS = 15


def _begin_end_params(start: str, end: str) -> tuple:
    """begin/end 参数（纯函数可测）：空 → 0/-1 全历史；否则 YYYYMMDD 整型（YYYY-MM-DD 去横线）。"""
    if not start and not end:
        return 0, -1
    begin = int(start.replace("-", "")) if start else 0
    end = int(end.replace("-", "")) if end else -1
    return begin, end


class SseAdapter(BaseAdapter):
    source_name = SOURCE_SSE

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        # 只做日K腿：不参与 stock-list/退市/指数/业绩/板块
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        self._supports_index = False
        self._supports_fundamentals = False
        self._supports_board_members = False
        # raw 不复权：qfq/hfq 均不支持（源头 raw 真值 + 复权因子推 qfq 归 AdjustCheckStep）
        self._supports_adjust_qfq = False
        self._supports_adjust_hfq = False

    # ---- 日 K 线 ----
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        # 复权能力守卫：raw 仅支持 none；qfq/hfq 一律 CapabilityError（能力型，不计熔断）
        if adjust in (ADJUST_QFQ, ADJUST_HFQ) and not getattr(self, f"_supports_adjust_{adjust}", False):
            raise CapabilityError(
                f"{self.source_name}: 不支持 {adjust} 复权（raw 真值 + 复权因子推 qfq 归 AdjustCheckStep）"
            )
        market = to_sse_market(code)
        begin, end_param = _begin_end_params(start, end)
        dayk = self._fetch_dayk_json(market, code, begin, end_param)
        rows = self._parse_dayk(dayk)
        if not rows:
            return []
        return self._to_bars(rows, code, start, end)

    # ---- dayk API 拉取（独立方法：测试注入缝；requests 异常统一 SourceError）----
    def _fetch_dayk_json(self, market: str, code: str, begin: int, end: int) -> dict:
        url = _DAYK_URL.format(market=market, code=code)
        params = {
            "select": "date,open,high,low,close,volume,amount",
            "begin": str(begin),
            "end": str(end),
        }
        try:
            resp = requests.get(url, params=params, headers=_HEADERS, timeout=_TIMEOUT_SECONDS)
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:  # noqa: BLE001 - 网络层异常统一 SourceError 分类
            raise SourceError(f"{self.source_name} 拉取失败: {exc}") from exc

    # ---- dayk JSON → 行序列 ----
    def _parse_dayk(self, dayk: dict) -> List[dict]:
        """解析 dayk["kline"] → 行序列。

        行字段序=[date(int YYYYMMDD), open, high, low, close, volume(股), amount(元)]；
        date int → "YYYY-MM-DD" 字符串。
        坏结构/空 kline → []（Router「空结果」语义 failover，不炸）；None/缺字段行跳过（对齐 yahoo 防御风格）。
        """
        try:
            kline = dayk["kline"]
        except (KeyError, TypeError):
            return []
        if not isinstance(kline, list):
            return []
        rows: List[dict] = []
        for item in kline:
            if not isinstance(item, (list, tuple)) or len(item) < 7:
                continue
            if item[0] is None:
                continue
            try:
                date_int = int(item[0])
                date = (f"{date_int // 10000:04d}-{date_int // 100 % 100:02d}-{date_int % 100:02d}")
                open_ = float(item[1])
                high = float(item[2])
                low = float(item[3])
                close = float(item[4])
                volume = float(item[5])      # 股（实测与 baostock 一字不差，不乘系数）
                amount = float(item[6])      # 元
            except (TypeError, ValueError):
                continue  # None/缺字段行跳过（对齐 yahoo 防御风格）
            rows.append({
                "date": date,
                "open": open_, "high": high, "low": low, "close": close,
                "volume": volume, "amount": amount,
            })
        return rows

    # ---- 行口径转换 ----
    def _to_bars(self, rows: List[dict], code: str, start: str, end: str) -> List[dict]:
        """rows → PLAN §5.6 11 键 bar 列表。

        口径：volume 已是股（SSE_VOLUME_MULTIPLIER=1，实测与 baostock 一字不差）、amount=元；
        change_percent 由相邻 raw close 链式自算、首行 null；窗口 [start, end] 过滤
        （前扩行只参与昨收；begin/end 已在服务端切片，此处为本地防御）。
        SSE 无换手率列 → turnover 如实置 0（对齐 yahoo 无源数据置 0 口径，不硬凑）。
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
                "volume": row["volume"] * SSE_VOLUME_MULTIPLIER,  # 原生=股，不乘系数
                "amount": row["amount"] * SSE_AMOUNT_MULTIPLIER,  # 原生=元
                "change_percent": round(change, 4) if change is not None else None,
                "turnover": 0.0,  # SSE 无换手率列，如实置 0
                "prev_close": last_close,
            })
        return bars
