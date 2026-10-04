"""BaoStock Adapter（PLAN §5.3 V2 版 + §5.2 代码格式转换 + 探针实测结论）。

实现要点（PLAN §5.3）：
- 输入裸数字 "600000" → BaoStock 内部 "sh.600000"（6/9 → sh，否则 sz）
- adjustflag 映射 qfq=2 / hfq=1 / none=3
- 停牌过滤必须用 tradestatus（row[11]）：官方说明停牌日返回行且 close≠空、=昨收，
  用 "close 为空" 判断会把停牌假 bar（OHLC=昨收、量额=0）放进库
- volume 原生=股（探针实测），amount=元，change_percent=pctChg（不复权）
- prev_close=preclose（除权后昨收，传输字段不落库）
- 退市状态：query_stock_basic status != '1'（type=='1' 股票），供 /stock-list delisted
"""

from __future__ import annotations

import logging
import threading
from typing import List

import baostock as bs

from adapters.base import BaseAdapter, SourceError
from constants import BAOSTOCK_ADJUST_MAP, BAOSTOCK_VOLUME_MULTIPLIER, BAOSTOCK_AMOUNT_MULTIPLIER, to_baostock_code

logger = logging.getLogger(__name__)

# BaoStock query_history_k_data_plus 字段序（PLAN §5.3）
# date,code,open,high,low,close,volume,amount,pctChg,turn,preclose,tradestatus
_IDX_DATE = 0
_IDX_CODE = 1
_IDX_OPEN = 2
_IDX_HIGH = 3
_IDX_LOW = 4
_IDX_CLOSE = 5
_IDX_VOLUME = 6
_IDX_AMOUNT = 7
_IDX_PCT_CHG = 8
_IDX_TURN = 9
_IDX_PRECLOSE = 10
_IDX_TRADESTATUS = 11


class BaostockAdapter(BaseAdapter):
    source_name = "baostock"

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        self._supports_delisted = True      # query_stock_basic 退市状态
        # stock-list 列表主源是 AKShare；baostock 仅作兜底（见 DataRouter._baostock_stock_list）
        self._supports_stock_list = False
        self._supports_is_st = False        # query_stock_basic 无 isST 字段，is_st 走 akshare st_em
        self._logged_in = False
        self._login_lock = threading.Lock()

    # ---- 登录 ----
    def _ensure_login(self) -> bool:
        if self._logged_in:
            return True
        with self._login_lock:
            if self._logged_in:
                return True
            lg = bs.login()
            if lg.error_code != "0":
                raise SourceError(f"baostock 登录失败: {lg.error_msg}")
            self._logged_in = True
            logger.info("baostock 登录成功")
        return True

    # ---- 会话过期自愈（2026-10-04 修 A）----
    def _query_with_session_retry(self, query_fn):
        """首查返回"用户未登录"→ 登录态重置 + 重登录 + 重试一次；仍败才抛错。

        背景：服务端会话过期后 _logged_in 粘死为 True，每票必败（回填实测
        16 连败）→ 熔断 → 分片 failover 全堆下游源 → 下游限流等待超时。
        重试恰好一次，绝不无限循环。
        """
        self._ensure_login()
        rs = query_fn()
        if rs.error_code != "0" and "用户未登录" in (rs.error_msg or ""):
            logger.warning("baostock 会话过期，重登录后重试一次")
            with self._login_lock:
                self._logged_in = False
            self._ensure_login()
            rs = query_fn()
        return rs

    # ---- 日 K 线（PLAN §5.3）----
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        bs_code = to_baostock_code(code)
        adjust_flag = BAOSTOCK_ADJUST_MAP.get(adjust, "2")
        rs = self._query_with_session_retry(lambda: bs.query_history_k_data_plus(
            bs_code,
            "date,code,open,high,low,close,volume,amount,pctChg,turn,preclose,tradestatus",
            start_date=start,
            end_date=end,
            frequency="d",
            adjustflag=adjust_flag,
        ))
        if rs.error_code != "0":
            raise SourceError(f"baostock 查询失败: {rs.error_msg}")

        bars = []
        while rs.error_code == "0" and rs.next():
            row = rs.get_row_data()
            # 停牌过滤必须用 tradestatus（row[11]），不能用 close 为空判断
            if row[_IDX_TRADESTATUS] != "1":
                continue
            bars.append({
                "date": row[_IDX_DATE],
                "code": code,                                       # 直接用输入的裸数字
                "open": float(row[_IDX_OPEN]) if row[_IDX_OPEN] else 0,
                "high": float(row[_IDX_HIGH]) if row[_IDX_HIGH] else 0,
                "low": float(row[_IDX_LOW]) if row[_IDX_LOW] else 0,
                "close": float(row[_IDX_CLOSE]) if row[_IDX_CLOSE] else 0,
                "volume": (float(row[_IDX_VOLUME]) if row[_IDX_VOLUME] else 0) * BAOSTOCK_VOLUME_MULTIPLIER,  # 原生=股
                "amount": (float(row[_IDX_AMOUNT]) if row[_IDX_AMOUNT] else 0) * BAOSTOCK_AMOUNT_MULTIPLIER,  # 元
                "change_percent": float(row[_IDX_PCT_CHG]) if row[_IDX_PCT_CHG] else 0,  # pctChg 不复权
                "turnover": float(row[_IDX_TURN]) if row[_IDX_TURN] else 0,
                "prev_close": float(row[_IDX_PRECLOSE]) if row[_IDX_PRECLOSE] else None,  # 除权后昨收
            })
        return bars

    # ---- query_stock_basic（退市 + 兜底列表共用）----
    def fetch_stock_basic_rows(self) -> List[dict]:
        """query_stock_basic 全量行（含退市状态），供 /stock-list 退市集合与列表兜底。"""
        return self._call_guarded(self._sync_fetch_stock_basic_rows)

    def _sync_fetch_stock_basic_rows(self) -> List[dict]:
        rs = self._query_with_session_retry(bs.query_stock_basic)
        if rs.error_code != "0":
            raise SourceError(f"baostock query_stock_basic 失败: {rs.error_msg}")
        rows = []
        while rs.error_code == "0" and rs.next():
            row = rs.get_row_data()
            rows.append({
                "code": row[0],          # sh.600000
                "code_name": row[1],
                "ipo_date": row[2],
                "out_date": row[3],
                "type": row[4],          # '1' 股票 / '2' 指数 / '3' 其它 / '4' 可转债 / '5' 基金
                "status": row[5],        # '1' 上市中 / '0' 退市或中止上市
            })
        return rows

    # ---- 退市状态（PLAN §11.1：delisted 只认 baostock/akshare，永不走 mootdx）----
    def _sync_fetch_delisted_codes(self) -> set:
        rows = self._sync_fetch_stock_basic_rows()
        return {
            r["code"].split(".")[-1]
            for r in rows
            if r["type"] == "1" and r["status"] != "1"
        }
