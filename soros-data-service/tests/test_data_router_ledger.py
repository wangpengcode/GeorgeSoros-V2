"""DataRouter 渠道台账测试（/channels 观测语义，见 adapters/base.py _mark_success/_mark_failure）。

台账语义（契约）：
- last_success_at：该源最近一次「成功取到数据」（日K=非空结果）
- last_failure_at：该源最近一次「真实请求已发出但源故障」（SourceError）
- banned/open（SourceUnavailableError，未发请求）与 ParameterError（调用方参数错误）
  不计入台账——前者归封禁/熔断状态展示（/channels banned/breaker 字段），后者非源故障。
时间戳格式 "%Y-%m-%d %H:%M:%S"。

所有用例都 monkeypatch adapters.base.guard 为隔离实例，避免污染全局单例。
"""

from __future__ import annotations

from adapters.base import DataRouter, ParameterError, SourceError
from helpers import StubAdapter, make_bar, raise_error
from ipguard import IPGuard


class _OpenCircuitBreaker:
    """allow_request() 恒 False 的开放熔断（模拟熔断 open 路径，未发请求）。"""

    state = "open"

    def health(self):
        return "down"

    def allow_request(self):
        return False

    def record_success(self):
        pass

    def record_failure(self):
        pass


def _new_guard(**kwargs) -> IPGuard:
    """隔离 IPGuard（不碰全局单例），默认注入假探针/假出口IP。"""
    defaults = dict(probe_fn=lambda: True, egress_ip_fn=lambda: "1.2.3.4")
    defaults.update(kwargs)
    return IPGuard(**defaults)


def _router() -> DataRouter:
    """三源均正常（核心三源）。"""
    return DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("akshare", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])


def _failure_router() -> DataRouter:
    """baostock 抛 SourceError → akshare 接管。"""
    return DataRouter([
        StubAdapter("baostock", bars=raise_error(SourceError("baostock 源故障"))),
        StubAdapter("akshare", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])


# ──────────────────────────────────────────────────────────────────────────────
# 正常流程：成功记 last_success_at
# ──────────────────────────────────────────────────────────────────────────────

def test_success_records_last_success_at(monkeypatch):
    """baostock 成功返回 bar → ledger["baostock"]["last_success_at"] 非空、last_failure_at null。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    router = _router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "baostock"
    ledger = router.channel_ledger()["baostock"]
    assert ledger["last_success_at"] is not None, "成功取到数据应记 last_success_at"
    assert ledger["last_failure_at"] is None, "无失败，last_failure_at 保持 null"
    assert len(ledger["last_success_at"]) == 19, "时间戳格式 %Y-%m-%d %H:%M:%S（19 字符）"


# ──────────────────────────────────────────────────────────────────────────────
# 异常路径：SourceError 记 last_failure_at + failover 接管
# ──────────────────────────────────────────────────────────────────────────────

def test_failure_records_last_failure_and_failover(monkeypatch):
    """baostock 抛 SourceError → akshare 接管 → baostock 记失败、akshare 记成功。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    router = _failure_router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "akshare", "baostock 故障 → akshare 接管"
    ledger = router.channel_ledger()
    assert ledger["baostock"]["last_failure_at"] is not None, "SourceError（真实请求已发出）应记 last_failure_at"
    assert ledger["akshare"]["last_success_at"] is not None, "接管成功应记 last_success_at"
    assert ledger["baostock"]["last_success_at"] is None, "baostock 本次未成功"


# ──────────────────────────────────────────────────────────────────────────────
# 边界：SourceUnavailableError（banned/熔断 open，未发请求）不记台账
# ──────────────────────────────────────────────────────────────────────────────

def test_banned_source_unavailable_not_recorded(monkeypatch):
    """banned（SourceUnavailableError，未发请求）不计 last_failure_at——归封禁状态展示。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for _ in range(5):
        g.report_failure("baostock", ConnectionError("RemoteDisconnected"))
    assert g.is_banned("baostock"), "前置条件：baostock 已达阈值封禁"
    router = _router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "akshare", "baostock 被封 → akshare 接管"
    ledger = router.channel_ledger()
    assert "baostock" not in ledger or ledger["baostock"]["last_failure_at"] is None, \
        "banned 未发请求，不记 last_failure_at"
    assert ledger["akshare"]["last_success_at"] is not None, "接管成功照记"


def test_open_breaker_source_unavailable_not_recorded(monkeypatch):
    """熔断 open（SourceUnavailableError，未发请求）不计 last_failure_at——归熔断状态展示。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    router = _router()
    router.adapters["baostock"].circuit_breaker = _OpenCircuitBreaker()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "akshare", "baostock 熔断 open → akshare 接管"
    ledger = router.channel_ledger()
    assert "baostock" not in ledger or ledger["baostock"]["last_failure_at"] is None, \
        "熔断 open 未发请求，不记 last_failure_at"
    assert ledger["akshare"]["last_success_at"] is not None


def test_parameter_error_not_recorded(monkeypatch):
    """ParameterError（调用方参数错误，跨源一致）不计台账——ledger 全空。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    router = DataRouter([
        StubAdapter("baostock", bars=raise_error(ParameterError("非法代码"))),
        StubAdapter("akshare", bars=raise_error(ParameterError("非法代码"))),
        StubAdapter("mootdx", bars=raise_error(ParameterError("非法代码"))),
    ])
    result, errors = router.fetch_daily_bars("abc", "2026-09-30", "2026-09-30", "qfq")
    assert result is None
    assert router.channel_ledger() == {}, "ParameterError 不计台账，ledger 全空"
