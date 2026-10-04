"""三 adapter 纯转换逻辑单测（禁真实网络：monkeypatch 源 SDK 调用）。

覆盖（任务清单）：
- baostock：tradestatus(row[11])!='1' 停牌行被滤、prev_close=row[10]、裸数字 code 回填
- akshare：成交量×100、昨收→prev_close、日期去横线
- mootdx：change_percent=(close-last_close)/last_close×100 四舍五入 4 位、
  不输出 prev_close 键、vol×100（M3 接入时实测校准注释见 constants.py）
"""

from __future__ import annotations

import pandas as pd
import pytest

import adapters.akshare_adapter as akshare_module
import adapters.baostock_adapter as baostock_module
import adapters.mootdx_adapter as mootdx_module

from adapters.akshare_adapter import AkshareAdapter
from adapters.baostock_adapter import BaostockAdapter
from adapters.mootdx_adapter import MootdxAdapter
from adapters.base import SourceError
from circuit_breaker import HEALTH_DOWN, STATE_OPEN, CircuitBreaker
from constants import (
    AKSHARE_VOLUME_MULTIPLIER,
    BAOSTOCK_VOLUME_MULTIPLIER,
    MOOTDX_VOLUME_MULTIPLIER,
)
from helpers import FakeCircuitBreaker, FakeRateLimiter, raise_error


class FakeRs:
    """模拟 baostock ResultData（error_code + next/get_row_data 迭代）。"""

    def __init__(self, rows):
        self.error_code = "0"
        self.error_msg = ""
        self._rows = list(rows)
        self._i = 0

    def next(self):
        if self._i < len(self._rows):
            self._i += 1
            return True
        return False

    def get_row_data(self):
        return self._rows[self._i - 1]


# ──────────────────────────────────────────────────────────────────────────────
# baostock
# ──────────────────────────────────────────────────────────────────────────────

def test_baostock_filters_suspended_and_maps_prev_close(monkeypatch):
    """tradestatus(row[11]) != '1' 停牌行被滤；prev_close=row[10]；code 用输入的裸数字回填。"""
    monkeypatch.setattr(baostock_module.bs, "login", lambda: FakeRs([]))
    calls = {}

    def fake_query(code, fields, start_date, end_date, frequency, adjustflag):
        calls["code"] = code
        calls["start_date"] = start_date
        calls["adjustflag"] = adjustflag
        return FakeRs([
            ["2026-09-29", "sh.600000", "9.50", "9.90", "9.40", "9.84",
             "100000", "984000", "0.51", "0.30", "9.79", "1"],
            # 停牌行：tradestatus(row[11])='0'，OHLC=昨收、量额=0，官方说明必须用 tradestatus 滤
            ["2026-09-28", "sh.600000", "", "", "", "9.79", "", "", "", "", "9.80", "0"],
        ])

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)
    adapter = BaostockAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-28", "2026-09-29", "qfq")

    assert len(bars) == 1, "tradestatus != '1' 的停牌行必须被滤除"
    bar = bars[0]
    assert bar["code"] == "600000", "裸数字 code 回填（不用 row[1] 的 sh.600000）"
    assert bar["date"] == "2026-09-29"
    assert bar["prev_close"] == 9.79, "prev_close=row[10] preclose"
    assert bar["volume"] == 100000.0 * BAOSTOCK_VOLUME_MULTIPLIER, "volume 原生=股"
    assert bar["change_percent"] == 0.51, "change_percent=pctChg（不复权）"
    assert calls["code"] == "sh.600000", "入参须转 baostock 内部格式 sh.600000"
    assert calls["adjustflag"] == "2", "qfq → adjustflag=2"


def test_baostock_delisted_from_query_stock_basic(monkeypatch):
    monkeypatch.setattr(baostock_module.bs, "login", lambda: FakeRs([]))

    def fake_query_basic():
        return FakeRs([
            ["sh.600000", "浦发银行", "1999-11-10", "", "1", "1"],          # 上市中
            ["sh.600001", "退市股", "1999-11-10", "2020-01-01", "1", "0"],  # 退市
            ["sz.000001", "平安银行", "1991-04-03", "", "1", "1"],          # 上市中
            ["sh.000001", "上证指数", "", "", "2", "1"],                    # 指数，非股票
        ])

    monkeypatch.setattr(baostock_module.bs, "query_stock_basic", fake_query_basic)
    adapter = BaostockAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    delisted = adapter._sync_fetch_delisted_codes()
    assert "600001" in delisted, "type==1 && status!=1 → 退市"
    assert "600000" not in delisted
    assert "000001" not in delisted, "指数（type!=1）不混入退市集"


def test_baostock_login_failure_raises_source_error(monkeypatch):
    def fake_login():
        rs = FakeRs([])
        rs.error_code = "1"
        rs.error_msg = "网络错误"
        return rs

    monkeypatch.setattr(baostock_module.bs, "login", fake_login)
    adapter = BaostockAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    with pytest.raises(SourceError):
        adapter._sync_fetch_daily_bars("600000", "2026-09-28", "2026-09-29", "qfq")


# ──────────────────────────────────────────────────────────────────────────────
# akshare
# ──────────────────────────────────────────────────────────────────────────────

def test_akshare_volume_multiplied_and_date_dash_stripped(monkeypatch):
    """成交量手×100→股（探针实证）；昨收→prev_close；start/end 去横线传源。"""
    df = pd.DataFrame([{
        "日期": "2026-09-30", "开盘": 9.85, "最高": 10.20, "最低": 9.80, "收盘": 10.05,
        "成交量": 1474848, "成交额": 147484820, "涨跌幅": 2.04, "换手率": 0.85, "昨收": 9.84,
    }])
    calls = {}

    def fake_hist(symbol, period, start_date, end_date, adjust):
        calls["symbol"] = symbol
        calls["start_date"] = start_date
        calls["end_date"] = end_date
        calls["adjust"] = adjust
        return df

    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist", fake_hist)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert len(bars) == 1
    bar = bars[0]
    assert bar["volume"] == 1474848 * AKSHARE_VOLUME_MULTIPLIER, "成交量手×100→股"
    assert bar["amount"] == 147484820, "成交额=元"
    assert bar["prev_close"] == 9.84, "昨收列→prev_close"
    assert bar["change_percent"] == 2.04, "涨跌幅（不复权原始值）"
    assert bar["date"] == "2026-09-30"
    assert calls["start_date"] == "20260925", "日期去横线传给源"
    assert calls["end_date"] == "20260930"
    assert calls["symbol"] == "600000", "裸数字直传"
    assert calls["adjust"] == "qfq"


def test_akshare_without_prev_close_column_yields_none(monkeypatch):
    df = pd.DataFrame([{
        "日期": "2026-09-30", "开盘": 9.85, "最高": 10.20, "最低": 9.80, "收盘": 10.05,
        "成交量": 1000, "成交额": 100500, "涨跌幅": 2.04, "换手率": 0.5,
    }])
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist", lambda **kw: df)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")
    assert bars[0]["prev_close"] is None, "无昨收列 → prev_close=None"
    assert bars[0]["turnover"] == 0.5


def test_akshare_empty_df_returns_empty_list(monkeypatch):
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist", lambda **kw: pd.DataFrame())
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    assert adapter._sync_fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq") == []


def test_akshare_stock_list_filters_north_and_derives_board(monkeypatch):
    """北交所前缀 83/87/43/920 滤除；board 推导 688→STAR、300/301→GEM；is_st 合并标记。"""
    df = pd.DataFrame([
        {"code": "600000", "name": "浦发银行"},
        {"code": "000001", "name": "平安银行"},
        {"code": "300750", "name": "宁德时代"},
        {"code": "688111", "name": "金山办公"},
        {"code": "301269", "name": "华大九天"},
        {"code": "830799", "name": "北交所83"},
        {"code": "920019", "name": "北交所920"},
        {"code": "870000", "name": "北交所87"},
        {"code": "430000", "name": "北交所43"},
    ])
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    adapter._stock_list_df = lambda: df
    adapter._fetch_st_codes = lambda: {"000001"}
    stocks = adapter._sync_fetch_stock_list()

    codes = [s["code"] for s in stocks]
    assert "830799" not in codes and "920019" not in codes, "北交所 83/920 前缀滤除"
    assert "870000" not in codes and "430000" not in codes, "北交所 87/43 前缀滤除"

    by_code = {s["code"]: s for s in stocks}
    assert by_code["688111"]["board"] == "STAR", "688→STAR"
    assert by_code["300750"]["board"] == "GEM", "300→GEM"
    assert by_code["301269"]["board"] == "GEM", "301→GEM"
    assert by_code["600000"]["board"] == "MAIN"
    assert by_code["600000"]["market"] == "SH"
    assert by_code["000001"]["market"] == "SZ"
    assert by_code["000001"]["is_st"] is True, "st_em∪stop_em 合并标记"
    assert by_code["600000"]["is_st"] is False


def test_akshare_stock_list_primary_fallback_to_spot_em(monkeypatch):
    """主源 stock_info_a_code_name 失败（返回空）→ 备源 stock_zh_a_spot_em（列名代码/名称）。"""
    monkeypatch.setattr(akshare_module.ak, "stock_info_a_code_name", lambda: None)
    df = pd.DataFrame([{"代码": "600000", "名称": "浦发银行"}])
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_spot_em", lambda: df)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    out = adapter._stock_list_df()
    assert list(out.columns) == ["code", "name"], "备源须归一为 code/name 两列"
    assert out.iloc[0]["code"] == "600000"


def test_akshare_stock_list_all_sources_fail_raises_source_error(monkeypatch):
    monkeypatch.setattr(akshare_module.ak, "stock_info_a_code_name", raise_error(RuntimeError("主源故障")))
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_spot_em", raise_error(RuntimeError("备源故障")))
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    with pytest.raises(SourceError):
        adapter._stock_list_df()


def test_akshare_st_codes_resolves_new_api_names(monkeypatch):
    """akshare ≥1.17 把 st_em/stop_em 改名 stock_zh_a_st_em/stock_zh_a_stop_em：
    新名优先命中；旧名仍兼容；两名皆缺降级空集（子集降级不炸整批语义）。"""
    st_df = pd.DataFrame({"代码": ["600000", "000001"], "名称": ["ST测试", "*ST退"]})
    stop_df = pd.DataFrame({"代码": ["300750"]})
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_st_em", lambda: st_df)
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_stop_em", lambda: stop_df)
    monkeypatch.delattr(akshare_module.ak, "st_em", raising=False)
    monkeypatch.delattr(akshare_module.ak, "stop_em", raising=False)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    assert adapter._fetch_st_codes() == {"600000", "000001", "300750"}

    # 旧名兼容（若装的是老版本 akshare）
    monkeypatch.setattr(akshare_module.ak, "st_em", lambda: st_df, raising=False)
    monkeypatch.setattr(akshare_module.ak, "stop_em", lambda: stop_df, raising=False)
    assert adapter._fetch_st_codes() == {"600000", "000001", "300750"}

    # 两名皆缺 → 降级空集，不抛
    monkeypatch.delattr(akshare_module.ak, "st_em", raising=False)
    monkeypatch.delattr(akshare_module.ak, "stop_em", raising=False)
    monkeypatch.delattr(akshare_module.ak, "stock_zh_a_st_em", raising=False)
    monkeypatch.delattr(akshare_module.ak, "stock_zh_a_stop_em", raising=False)
    assert adapter._fetch_st_codes() == set()


def test_akshare_trading_calendar(monkeypatch):
    df = pd.DataFrame({"trade_date": ["2021-10-01", "2021-10-08", "2021-10-11"]})
    monkeypatch.setattr(akshare_module.ak, "tool_trade_date_hist_sina", lambda: df)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    assert adapter._sync_fetch_trading_calendar() == ["2021-10-01", "2021-10-08", "2021-10-11"]


# ──────────────────────────────────────────────────────────────────────────────
# mootdx
# ──────────────────────────────────────────────────────────────────────────────

class FakeQuotesClient:
    """模拟 mootdx Quotes client：get_security_bars 返回注入 bars，首次返回后清空（模拟分页耗尽）。"""

    def __init__(self, bars):
        self.calls = []
        self._bars = list(bars)

    def get_security_bars(self, category, market, code, start, count):
        self.calls.append((category, market, code, start, count))
        out = self._bars
        self._bars = []
        return out


class FakeClient:
    """模拟 MootdxAdapter._get_client 返回值：含 .client 属性（_safe_call 调用 .client.get_security_bars）。"""

    def __init__(self, bars):
        self.client = FakeQuotesClient(bars)


def test_mootdx_change_percent_and_vol_multiplied(monkeypatch):
    """change_percent=(close-last_close)/last_close×100 四舍五入 4 位；不输出 prev_close 键；vol 手×100。"""
    bars_in = [
        {"datetime": "2026-09-29 15:00:00", "open": 9.0, "high": 9.5, "low": 8.9,
         "close": 9.4, "vol": 100, "amount": 9400, "last_close": 9.0},
        {"datetime": "2026-09-30 15:00:00", "open": 9.4, "high": 10.0, "low": 9.3,
         "close": 9.98, "vol": 120, "amount": 11900, "last_close": 9.4},
    ]
    adapter = MootdxAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    fake_client = FakeClient(bars_in)
    monkeypatch.setattr(adapter, "_get_client", lambda: fake_client)

    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert len(bars) == 2
    assert bars[0]["date"] == "2026-09-29", "分页结果按日期升序"
    assert bars[0]["change_percent"] == round((9.4 - 9.0) / 9.0 * 100, 4), (
        "change_percent=(close-last_close)/last_close×100 四舍五入 4 位"
    )
    assert bars[1]["change_percent"] == round((9.98 - 9.4) / 9.4 * 100, 4)
    assert bars[0]["volume"] == 100 * MOOTDX_VOLUME_MULTIPLIER, (
        "vol 手×100→股（M3 接入时实测校准，见 constants.py MOOTDX_VOLUME_MULTIPLIER）"
    )
    assert bars[0]["amount"] == 9400
    assert "prev_close" not in bars[0], "mootdx 不输出 prev_close 键（不复权口径与库内 qfq 不一致，防 §4.6 误报）"
    assert "prev_close" not in bars[1]
    assert bars[0]["turnover"] == 0, "mootdx 不提供换手率 → 0"
    assert fake_client.client.calls[0][0] == 9, "category=9（日K）"
    assert fake_client.client.calls[0][1] == 1, "600000 沪市 market=1"


def test_mootdx_sz_market_is_zero(monkeypatch):
    bars_in = [{"datetime": "2026-09-30 15:00:00", "open": 9.0, "high": 9.5, "low": 8.9,
                "close": 9.0, "vol": 100, "amount": 900, "last_close": 9.0}]
    adapter = MootdxAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    fake_client = FakeClient(bars_in)
    monkeypatch.setattr(adapter, "_get_client", lambda: fake_client)
    adapter._sync_fetch_daily_bars("000001", "2026-09-25", "2026-09-30", "qfq")
    assert fake_client.client.calls[0][1] == 0, "000001 深市 market=0"


def test_mootdx_filters_outside_date_range(monkeypatch):
    bars_in = [
        {"datetime": "2026-09-25 15:00:00", "open": 9.0, "high": 9.5, "low": 8.9,
         "close": 9.0, "vol": 100, "amount": 900, "last_close": 9.0},
        {"datetime": "2026-10-01 15:00:00", "open": 9.0, "high": 9.5, "low": 8.9,
         "close": 9.0, "vol": 100, "amount": 900, "last_close": 9.0},
    ]
    adapter = MootdxAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    fake_client = FakeClient(bars_in)
    monkeypatch.setattr(adapter, "_get_client", lambda: fake_client)
    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-26", "2026-09-30", "qfq")
    assert len(bars) == 0, "日期区间 [start,end] 外被滤除"


class FailingQuotesClient:
    """get_security_bars 持续抛错（模拟 mootdx 源故障，MEDIUM-1 契约）。"""

    def get_security_bars(self, category, market, code, start, count):
        raise RuntimeError("mootdx 节点不可用")


class FailingClient:
    """模拟 _get_client 返回：含 .client 属性（_safe_call 调用 .client.get_security_bars）。"""

    def __init__(self):
        self.client = FailingQuotesClient()


def test_mootdx_sdk_error_counts_toward_circuit_breaker(monkeypatch):
    """mootdx SDK 持续抛错 → 异常向上抛（不再吞）→ CB 计失败并达阈值 open（MEDIUM-1 新契约）。

    修复前：_safe_call 吞异常返回 []，_call_guarded 误 record_success，熔断永不 open、
    /health 恒 "ok"。修复后：SDK 抛错经 base.py 分类为 SourceError 并 record_failure。
    """
    cb = CircuitBreaker("mootdx", failure_threshold=2, open_timeout_seconds=60)
    adapter = MootdxAdapter(cb, FakeRateLimiter())
    monkeypatch.setattr(adapter, "_get_client", lambda: FailingClient())

    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")
    assert cb.consecutive_failures == 1, "SDK 抛错计入熔断失败（不再误 record_success）"

    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")
    assert cb.state == STATE_OPEN, "连续失败达阈值 → open"
    assert cb.health() == HEALTH_DOWN, "/health 不再恒 ok"
