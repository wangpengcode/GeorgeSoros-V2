"""baostock 会话过期自愈单测（2026-10-04 修 A，禁真实网络：monkeypatch bs SDK）。

背景（回填实测）：baostock 服务端会话过期后 `_logged_in` 粘死为 True，
每票必败"用户未登录"（16 连败）→ 熔断器跳闸 → 该分片 failover 全部堆积到
下游源 → 下游 30s 限流等待超时 → 6 源全败。修 A = 捕获"用户未登录"
→ 登录态重置 → 重登录 → 重试一次；重试仍败才抛错，绝不无限循环。
"""

from __future__ import annotations

import pytest

import adapters.baostock_adapter as baostock_module
from adapters.baostock_adapter import BaostockAdapter
from adapters.base import SourceError
from helpers import FakeCircuitBreaker, FakeRateLimiter


class FakeRs:
    """模拟 baostock ResultData（可注入 error_code/error_msg）。"""

    def __init__(self, rows, error_code="0", error_msg=""):
        self.error_code = error_code
        self.error_msg = error_msg
        self._rows = list(rows)
        self._i = 0

    def next(self):
        if self.error_code != "0":
            return False
        if self._i < len(self._rows):
            self._i += 1
            return True
        return False

    def get_row_data(self):
        return self._rows[self._i - 1]


ROW = ["2026-01-05", "sz.000001", "10.0", "10.5", "9.9", "10.2", "100000", "1020000", "1.5", "0.8", "10.1", "1"]


def make_adapter() -> BaostockAdapter:
    return BaostockAdapter(FakeCircuitBreaker(), FakeRateLimiter())


def test_session_expiry_relogins_and_retries(monkeypatch):
    """首查'用户未登录' → 登录态重置 + 恰好重登录一次 + 重试成功。"""
    adapter = make_adapter()
    adapter._logged_in = True  # 模拟粘死的已登录态（服务端会话实际已过期）
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    query_calls = []
    responses = [
        FakeRs([], error_code="10001", error_msg="用户未登录"),
        FakeRs([ROW]),
    ]

    def fake_query(*args, **kwargs):
        query_calls.append(kwargs)
        return responses[len(query_calls) - 1]

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)

    bars = adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(bars) == 1
    assert bars[0]["close"] == 10.2
    assert len(query_calls) == 2            # 首查 + 重试，仅一次
    assert len(login_calls) == 1            # 恰好重登录一次
    assert adapter._logged_in is True


def test_relogin_failure_raises_source_error(monkeypatch):
    """重登录失败（登录接口报错）→ SourceError，不再重试查询。"""
    adapter = make_adapter()
    adapter._logged_in = True
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: FakeRs([], error_code="network", error_msg="登录失败"),
    )
    query_calls = []

    def fake_query(*args, **kwargs):
        query_calls.append(kwargs)
        return FakeRs([], error_code="10001", error_msg="用户未登录")

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)

    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(query_calls) == 1            # 重登录失败后不再重试


def test_retry_still_expired_raises_no_loop(monkeypatch):
    """重试后仍'用户未登录' → SourceError 抛出，查询恰好 2 次不无限循环。"""
    adapter = make_adapter()
    adapter._logged_in = True
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    query_calls = []

    def fake_query(*args, **kwargs):
        query_calls.append(kwargs)
        return FakeRs([], error_code="10001", error_msg="用户未登录")

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)

    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(query_calls) == 2            # 首查 + 一次重试，到此为止
    assert len(login_calls) == 1


def test_normal_path_no_extra_login(monkeypatch):
    """正常路径（会话有效）→ 不触发重登录，行为与修复前完全一致。"""
    adapter = make_adapter()
    adapter._logged_in = True
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    monkeypatch.setattr(
        baostock_module.bs, "query_history_k_data_plus",
        lambda *args, **kwargs: FakeRs([ROW]),
    )

    bars = adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(bars) == 1
    assert login_calls == []                # 零额外登录


def test_network_error_relogins_and_retries(monkeypatch):
    """首查'网络接收错误'（baostock lib send_msg 断连 → history.py BSERR_RECVSOCK_FAIL）
    → 与会话过期同路径自愈：登录态重置 + 重登录 + 重试一次（2026-10-05 生产事故①根因）。"""
    adapter = make_adapter()
    adapter._logged_in = True
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    query_calls = []
    responses = [
        FakeRs([], error_code="network", error_msg="网络接收错误。"),
        FakeRs([ROW]),
    ]

    def fake_query(*args, **kwargs):
        query_calls.append(kwargs)
        return responses[len(query_calls) - 1]

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)

    bars = adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(bars) == 1
    assert len(query_calls) == 2            # 首查 + 重试一次
    assert len(login_calls) == 1            # 恰好重登录一次（进程内 socket 已死，必须重连）
    assert adapter._logged_in is True


def test_network_error_retry_still_fails_raises_no_loop(monkeypatch):
    """'网络接收错误'重试仍败 → SourceError，查询恰 2 次不无限循环。"""
    adapter = make_adapter()
    adapter._logged_in = True
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    query_calls = []

    def fake_query(*args, **kwargs):
        query_calls.append(kwargs)
        return FakeRs([], error_code="network", error_msg="网络接收错误。")

    monkeypatch.setattr(baostock_module.bs, "query_history_k_data_plus", fake_query)

    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("000001", "2026-01-01", "2026-01-31", "qfq")

    assert len(query_calls) == 2
    assert len(login_calls) == 1


def test_session_expiry_relogin_for_stock_basic(monkeypatch):
    """query_stock_basic 路径同样自愈（退市集合/列表兜底共用）。"""
    adapter = make_adapter()
    adapter._logged_in = True
    login_calls = []
    monkeypatch.setattr(
        baostock_module.bs, "login",
        lambda: (login_calls.append(1), FakeRs([]))[1],
    )
    basic_calls = []
    responses = [
        FakeRs([], error_code="10001", error_msg="用户未登录"),
        FakeRs([["sh.600000", "浦发银行", "1999-11-10", "", "1", "1"]]),
    ]

    def fake_basic():
        basic_calls.append(1)
        return responses[len(basic_calls) - 1]

    monkeypatch.setattr(baostock_module.bs, "query_stock_basic", fake_basic)

    rows = adapter.fetch_stock_basic_rows()

    assert rows[0]["code"] == "sh.600000"
    assert len(basic_calls) == 2
    assert len(login_calls) == 1
