"""新浪 failover 测试（2026-10-04 东财 push2his 整族拒连 → akshare 内部 EM→新浪 failover）。

设计要点：
- failover 在 akshare 适配器内部（EM 失败不炸到 Router，不浪费 baostock 接管配额；
  也不计入 IPGuard 封禁窗口——否则 akshare 被ban会跳过新浪直接让 baostock 接管，
  新浪链路永远不会被用到）
- 归因：serving_source 线程本地标注（akshare / akshare-sina），Router 结果如实透传，
  data_source 对拍口径可区分（varchar(20) 放得下，Kotlin 字符串透传零改动）
- 新浪缺口补齐：无 change_percent 列 → 双拉（不复权窗口前扩 10 自然日）算真实涨跌幅；
  turnover 源是小数（0.003561）→ ×100 对齐东财百分比口径
- 限流：新浪两次 HTTP 各自过 TokenBucket（内层调用也要限流，防绕过）
"""

from __future__ import annotations

import pandas as pd
import pytest

import adapters.akshare_adapter as akshare_module
from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter, SourceError
from helpers import FakeCircuitBreaker, StubAdapter, make_bar


def _sina_raw_df(code_price=11.0):
    """新浪不复权 df：窗口前扩一天（9-29）供首行涨跌幅计算；turnover 为小数口径。"""
    return pd.DataFrame([
        {"date": "2026-09-28", "open": 10.90, "high": 11.10, "low": 10.80,
         "close": code_price, "volume": 90_000_000.0, "amount": 9.8e8, "turnover": 0.0030},
        {"date": "2026-09-29", "open": 11.30, "high": 11.41, "low": 11.28,
         "close": 11.35, "volume": 69_097_909.0, "amount": 7.8463e8, "turnover": 0.003561},
        {"date": "2026-09-30", "open": 11.36, "high": 11.65, "low": 11.33,
         "close": 11.57, "volume": 104_535_745.0, "amount": 1.2058e9, "turnover": 0.005387},
    ])


def _adapter_with(monkeypatch, em_raises=None, em_df=None, sina_raises=None, qfq_df=None, raw_df=None):
    """构造受控 AkshareAdapter：EM/sina 行为注入式覆盖。"""
    adapter = AkshareAdapter(FakeCircuitBreaker(), CountingRateLimiter())
    if em_raises is not None:
        def em_fail(*a, **k):
            raise em_raises
        monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist", em_fail)
    elif em_df is not None:
        monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist", lambda *a, **k: em_df)
    else:
        monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist",
                            lambda *a, **k: pd.DataFrame())
    if sina_raises is not None:
        def sina_fail(*a, **k):
            raise sina_raises
        monkeypatch.setattr(akshare_module.ak, "stock_zh_a_daily", sina_fail)
    else:
        monkeypatch.setattr(
            akshare_module.ak, "stock_zh_a_daily",
            lambda symbol, start_date, end_date, adjust:
                (raw_df if raw_df is not None else _sina_raw_df()) if adjust in ("", None)
                else (qfq_df if qfq_df is not None else _sina_raw_df()),
        )
    return adapter


class CountingRateLimiter:
    """计数限流桩：统计内层调用次数（新浪双拉必须各自过限流）。"""

    def __init__(self):
        self.count = 0

    def acquire(self, timeout=30.0):
        self.count += 1
        return True


# ──────────────────────────────────────────────────────────────────────────────
# failover 行为与归因
# ──────────────────────────────────────────────────────────────────────────────

def test_em_success_serves_with_label_akshare(monkeypatch):
    """EM 正常 → 不触新浪，归因 akshare。"""
    em_df = pd.DataFrame([{
        "日期": "2026-09-30", "开盘": 10.0, "最高": 10.2, "最低": 9.8, "收盘": 10.05,
        "成交量": 100.0, "成交额": 10000.0, "涨跌幅": 1.0, "换手率": 0.5, "昨收": 9.95,
    }])
    adapter = _adapter_with(monkeypatch, em_df=em_df)
    adapter._sync_fetch_stock_list = lambda: []  # 防误触
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert adapter.serving_source == "akshare"
    assert len(bars) == 1


def test_em_banned_fails_over_to_sina_with_label(monkeypatch):
    """EM 被拒（RemoteDisconnected）→ 新浪接管，归因 akshare-sina。"""
    adapter = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"))
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert adapter.serving_source == "akshare-sina"
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"], "qfq 窗口内两行"


def test_both_fail_raises_source_error_with_both_reasons(monkeypatch):
    """双链全失败 → SourceError 带两侧原因（排障不静默）。"""
    adapter = _adapter_with(
        monkeypatch,
        em_raises=ConnectionError("RemoteDisconnected"),
        sina_raises=ConnectionError("sina reset"),
    )
    with pytest.raises(SourceError) as ei:
        adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert "em(" in str(ei.value) and "sina(" in str(ei.value)


def test_router_result_carries_sina_label(monkeypatch):
    """Router 结果 source=akshare-sina 如实透传（data_source 归因对拍口径）。"""
    akshare = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"))
    router = DataRouter([StubAdapter("baostock"), akshare, StubAdapter("mootdx")])
    result, errors = router.fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert result["source"] == "akshare-sina"


# ──────────────────────────────────────────────────────────────────────────────
# 新浪口径补齐
# ──────────────────────────────────────────────────────────────────────────────

def test_sina_change_percent_computed_from_raw_with_prev_day(monkeypatch):
    """真实涨跌幅从【不复权】收盘计算，首行也要有（raw 窗口前扩供昨收）。"""
    adapter = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"),
                            raw_df=_sina_raw_df())
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    by_date = {b["date"]: b for b in bars}
    # 9-29: (11.35/11.00-1)*100 = 3.1818...%
    assert abs(by_date["2026-09-29"]["change_percent"] - 3.1818) < 0.01, "前扩昨收参与计算"
    # 9-30: (11.57/11.35-1)*100 = 1.9383...%
    assert abs(by_date["2026-09-30"]["change_percent"] - 1.9383) < 0.01


def test_sina_turnover_fraction_converted_to_percent(monkeypatch):
    """新浪 turnover 小数（0.003561）→ ×100 百分比对齐东财口径。"""
    adapter = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"))
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert abs(bars[-1]["turnover"] - 0.5387) < 1e-6


def test_sina_volume_already_in_shares(monkeypatch):
    """新浪 volume 已是股（东财是手×100）→ 不再乘系数。"""
    adapter = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"))
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert bars[-1]["volume"] == 104_535_745.0


def test_sina_symbol_prefix_mapping(monkeypatch):
    """新浪 symbol 前缀：6/9 开头 sh，其余 sz（北交所上游已隔离不落入）。"""
    seen = {}

    def spy(symbol, start_date, end_date, adjust):
        seen.setdefault(adjust, []).append(symbol)
        return _sina_raw_df()

    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_hist",
                        lambda *a, **k: (_ for _ in ()).throw(ConnectionError("x")))
    monkeypatch.setattr(akshare_module.ak, "stock_zh_a_daily", spy)
    adapter = AkshareAdapter(FakeCircuitBreaker(), CountingRateLimiter())
    adapter._sync_fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert seen["qfq"] == ["sh600000", "sz000001"], "沪 6/9→sh，深→sz"


def test_sina_inner_calls_rate_limited(monkeypatch):
    """新浪双拉两次 HTTP 各自过限流（内层调用不允许绕过 TokenBucket）。"""
    adapter = _adapter_with(monkeypatch, em_raises=ConnectionError("RemoteDisconnected"))
    adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert adapter.rate_limiter.count >= 2, "raw + qfq 两次调用都要限流"
