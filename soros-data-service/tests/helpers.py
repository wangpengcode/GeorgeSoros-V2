"""测试共享工具（Python 3.9 语法，无 3.10+ 特性）。

- StubAdapter：可控桩适配器，行为由构造参数注入（bars/stock_list/delisted/calendar/health）
- FakeCircuitBreaker / FakeRateLimiter：无状态假依赖，隔离 adapter 层不触网络
- make_bar / raise_error：bar 与异常工厂
"""

from __future__ import annotations

from adapters.base import BaseAdapter
from adapters.base import SOURCE_AKSHARE, SOURCE_BAOSTOCK, SOURCE_MOOTDX


class FakeCircuitBreaker:
    def __init__(self, health="ok"):
        self._health = health

    def health(self):
        return self._health

    def allow_request(self):
        return True

    def record_success(self):
        pass

    def record_failure(self):
        pass


class FakeRateLimiter:
    def acquire(self, timeout=30.0):
        return True


class StubAdapter(BaseAdapter):
    """可控桩适配器：三源行为一致（bars/stock_list/delisted/calendar/index_bars/health 注入式覆盖）。"""

    def __init__(self, source_name, bars=None, stock_list=None, delisted=None,
                 calendar=None, stock_basic_rows=None, index_bars=None, health="ok"):
        super().__init__(FakeCircuitBreaker(health), FakeRateLimiter())
        self.source_name = source_name
        # 能力标记与真实 adapter 对齐：stock-list/is_st/退市永不走 mootdx；指数仅 akshare
        self._supports_stock_list = source_name in (SOURCE_BAOSTOCK, SOURCE_AKSHARE)
        self._supports_delisted = source_name == SOURCE_BAOSTOCK
        self._supports_is_st = source_name == SOURCE_AKSHARE
        self._supports_index = source_name == SOURCE_AKSHARE
        self._bars = bars
        self._stock_list = stock_list
        self._delisted = delisted
        self._calendar = calendar
        self._stock_basic_rows = stock_basic_rows
        self._index_bars = index_bars
        self.call_counts = {
            "daily_bars": 0, "stock_list": 0, "delisted": 0,
            "calendar": 0, "stock_basic": 0, "index_daily": 0,
        }

    def _resolve(self, value, *args):
        if callable(value):
            return value(*args)
        if isinstance(value, Exception):
            raise value
        return value

    # ---- 日 K ----
    def _sync_fetch_daily_bars(self, code, start, end, adjust):
        self.call_counts["daily_bars"] += 1
        return self._resolve(self._bars, code, start, end, adjust)

    # ---- 指数日 K（仅 akshare；mootdx 永不走）----
    def _sync_fetch_index_daily(self, code, start, end, adjust):
        self.call_counts["index_daily"] += 1
        return self._resolve(self._index_bars, code, start, end, adjust)

    # ---- 股票列表 ----
    def _sync_fetch_stock_list(self):
        self.call_counts["stock_list"] += 1
        return self._resolve(self._stock_list)

    # ---- 退市 ----
    def _sync_fetch_delisted_codes(self):
        self.call_counts["delisted"] += 1
        return self._resolve(self._delisted) or set()

    def fetch_stock_basic_rows(self):
        """baostock query_stock_basic 兜底列表（DataRouter._baostock_stock_list 调用）。"""
        self.call_counts["stock_basic"] += 1
        return self._resolve(self._stock_basic_rows) or []

    # ---- 交易日历 ----
    def _sync_fetch_trading_calendar(self):
        self.call_counts["calendar"] += 1
        return self._resolve(self._calendar) or []


def raise_error(exc):
    """返回一个无论收到什么参数都抛出 exc 的函数（模拟源故障/参数错误）。"""

    def _f(*args):
        raise exc

    return _f


def make_bar(code, date="2026-09-30", open_=9.85, high=10.20, low=9.80, close=10.05,
             volume=147484820.0, amount=1474848200.0, change_percent=2.04, turnover=0.85,
             prev_close=9.84, include_prev_close=True):
    """构造一条符合 PLAN §5.6 字段的 bar dict。

    include_prev_close=False 模拟 mootdx（不输出 prev_close 键，API 层由 Bar 模型补 null）。
    """
    bar = {
        "date": date,
        "code": code,
        "open": open_,
        "high": high,
        "low": low,
        "close": close,
        "volume": volume,            # 股
        "amount": amount,            # 元
        "change_percent": change_percent,  # 不复权
        "turnover": turnover,        # 换手率%
    }
    if include_prev_close:
        bar["prev_close"] = prev_close
    return bar
