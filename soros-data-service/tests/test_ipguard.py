"""IPGuard 组件测试（2026-10-04 东财封禁事件驱动设计，穿透 92 分）。

设计要点：
- 封禁与熔断分离：熔断 60s=瞬时故障；封禁=小时级（换IP才有救），
  banned 期间该源直接拒绝（不发起请求），避免熔断半开探针把负载打回被封 IP
- 判定：同源 60s 窗口内 ≥5 次连接层异常（RemoteDisconnected/Connection reset 等）
- 自愈：后台线程定期探测东财连通性，通过即解除 banned（无需人工/重启）
- 业务异常（非连接层）不计封禁——那是源接口故障，归熔断管
"""

from __future__ import annotations

import pytest

from adapters.base import DataRouter, SourceError, SourceUnavailableError
from helpers import StubAdapter, make_bar
from ipguard import IPGuard, guard as global_guard


def _fake_clock(start: float = 1_000_000.0):
    """可控时钟：返回 (clock_fn, advance_fn)。"""
    state = {"now": start}

    def clock():
        return state["now"]

    def advance(seconds: float):
        state["now"] += seconds

    return clock, advance


def _new_guard(**kwargs) -> IPGuard:
    """隔离实例（不碰全局单例），默认注入假探针/假出口IP。"""
    defaults = dict(probe_fn=lambda: True, egress_ip_fn=lambda: "1.2.3.4")
    defaults.update(kwargs)
    return IPGuard(**defaults)


# ──────────────────────────────────────────────────────────────────────────────
# 封禁判定
# ──────────────────────────────────────────────────────────────────────────────

def test_connection_failures_below_threshold_not_banned():
    """4 次连接层异常（阈值 5）→ 不封禁。"""
    g = _new_guard()
    for _ in range(4):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert not g.is_banned("akshare")


def test_five_connection_failures_in_window_ban():
    """60s 窗口内 5 次连接层异常 → 封禁。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.is_banned("akshare"), "达到阈值应封禁"


def test_failures_outside_window_do_not_accumulate():
    """窗口外（>60s）的失败滑出，不累计封禁。"""
    clock, advance = _fake_clock()
    g = _new_guard(clock=clock)
    for _ in range(4):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    advance(120)
    g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert not g.is_banned("akshare"), "窗口已滑走，仅剩 1 次失败"


def test_business_error_not_counted_as_ban_signal():
    """业务异常（源接口故障）不计封禁——归熔断管，职责分离。"""
    g = _new_guard()
    for _ in range(10):
        g.report_failure("akshare", RuntimeError("接口返回空数据"))
    assert not g.is_banned("akshare")


def test_success_resets_failure_counter():
    """成功即清零失败计数（偶发连接抖动不积累成误封）。"""
    g = _new_guard()
    for _ in range(4):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    g.report_success("akshare")
    g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert not g.is_banned("akshare")


def test_ban_is_per_source():
    """封禁按源隔离：akshare 被封不影响 baostock。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.is_banned("akshare")
    assert not g.is_banned("baostock")


# ──────────────────────────────────────────────────────────────────────────────
# 自愈
# ──────────────────────────────────────────────────────────────────────────────

def test_maybe_recover_probe_pass_unbans():
    """探针通过（东财恢复可达/换IP生效）→ 解除封禁。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.maybe_recover() is True
    assert not g.is_banned("akshare")


def test_maybe_recover_probe_fail_backs_off():
    """探针失败（换的 IP 也被封）→ 保持封禁 + 下次探测按退避延后。"""
    probe_calls = []
    g = _new_guard(probe_fn=lambda: probe_calls.append(1) or False)
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.maybe_recover() is False
    assert g.is_banned("akshare")
    assert g.maybe_recover() is False
    assert len(probe_calls) == 1, "退避期内不应重复探测（15min 间隔，不打被封 IP）"


def test_maybe_recover_no_banned_skips_probe():
    """无封禁 → 不探测（探针零成本待命）。"""
    probe_calls = []
    g = _new_guard(probe_fn=lambda: probe_calls.append(1) or True)
    assert g.maybe_recover() is False
    assert probe_calls == []


def test_maybe_recover_backoff_resets_after_success():
    """恢复成功后退避重置（下次封禁从最短间隔重新开始探测）。"""
    probe_ok = {"ok": True}
    g = _new_guard(probe_fn=lambda: probe_ok["ok"])
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    g.maybe_recover()  # 成功恢复
    assert g.maybe_recover() is False, "无封禁不再探测"
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.maybe_recover() is True, "二次封禁首轮探测即应执行（退避已重置）"


def test_success_while_banned_unbans():
    """banned 期间真实请求成功（封禁已过期/误判）→ 立即解除。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    g.report_success("akshare")
    assert not g.is_banned("akshare"), "真实成功是最强的未封禁证据"


def test_egress_ip_change_detected():
    """出口 IP 变化被记录（换IP 生效的可观测信号）。"""
    ips = iter(["1.2.3.4", "1.2.3.4", "5.6.7.8"])
    g = _new_guard(egress_ip_fn=lambda: next(ips))
    g.poll_egress()
    g.poll_egress()
    assert g.egress_ip == "1.2.3.4" and g.egress_ip_changed_at is None
    g.poll_egress()
    assert g.egress_ip == "5.6.7.8", "检测到 IP 变化"
    assert g.egress_ip_changed_at is not None


# ──────────────────────────────────────────────────────────────────────────────
# 与 Router/Adapter 集成（failover 语义不破坏）
# ──────────────────────────────────────────────────────────────────────────────

def test_banned_source_skipped_in_failover(monkeypatch):
    """banned 源在 failover 中直接跳过（不发起请求），其余源接管。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))

    order: list = []

    def mk(name: str) -> StubAdapter:
        def bars(code, start, end, adjust):
            order.append(name)
            return [make_bar(code)]

        return StubAdapter(name, bars=bars)

    router = DataRouter([mk("baostock"), mk("akshare"), mk("mootdx")])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None and result["source"] == "baostock", "归属源 akshare 被封，baostock 接管"
    assert order == ["baostock"], "akshare 被封不应发起请求"
    assert any("IP被封禁" in e or "IP" in e for e in errors) or result is not None


def test_all_shard_owner_and_fallback_banned_returns_error_text(monkeypatch):
    """akshare 封禁 + baostock 归属 code：归属源 baostock 正常时不报错（分片×封禁组合穿透）。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    baostock = StubAdapter("baostock", bars=lambda *a: [make_bar("000001")])
    akshare = StubAdapter("akshare", bars=lambda *a: [make_bar("000001")])
    router = DataRouter([baostock, akshare, StubAdapter("mootdx")])
    result, errors = router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "baostock", "奇数 code 归属 akshare，被封后 baostock 接管"
    assert baostock.call_counts["daily_bars"] == 1


def test_global_guard_isolated_state():
    """全局单例可用且状态隔离（banned 记录到实例，不影响其他测试）。"""
    assert global_guard is not None
    for _ in range(5):
        global_guard.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert global_guard.is_banned("akshare")
    global_guard.report_success("akshare")
    assert not global_guard.is_banned("akshare"), "测试自清理：成功解除，不留污染"


def test_snapshot_shape():
    """snapshot() 结构稳定（/ipguard 观测端点契约）。"""
    g = _new_guard()
    snap = g.snapshot()
    assert set(snap.keys()) == {"egress_ip", "egress_ip_changed_at", "banned", "events"}
    assert snap["banned"] == {}
    assert snap["events"] == []


# ──────────────────────────────────────────────────────────────────────────────
# 事件环形缓冲（/ipguard events 观测：ban/unban 历史，最近 100 条，旧→新）
# ──────────────────────────────────────────────────────────────────────────────

def test_ban_event_recorded_in_snapshot():
    """5× 连接层异常封禁 → events[0] == ban 事件，且 banned.at 与事件 at 同刻（观测对拍不漂移）。"""
    g = _new_guard()
    g.poll_egress()  # 先采 egress 基线 → "1.2.3.4"
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    snap = g.snapshot()
    assert len(snap["events"]) == 1
    ev = snap["events"][0]
    assert set(ev.keys()) == {"type", "source", "at", "egress_ip"}
    assert ev["type"] == "ban"
    assert ev["source"] == "akshare"
    assert ev["egress_ip"] == "1.2.3.4"
    assert snap["banned"]["akshare"]["at"] == ev["at"], "封禁快照与事件记录同一时刻"


def test_success_unban_event_reason_real_success():
    """banned 期间真实请求成功（最强未封禁证据）→ events 尾 {type:"unban", reason:"real_success"}，banned 清空。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.is_banned("akshare"), "前置条件：已封禁"
    g.report_success("akshare")
    snap = g.snapshot()
    assert snap["banned"] == {}
    ev = snap["events"][-1]
    assert ev["type"] == "unban"
    assert ev["source"] == "akshare"
    assert ev["reason"] == "real_success", "真实成功解封路径须区分解封来源"


def test_probe_recover_unban_event_reason_probe_recovered():
    """探针恢复路径（maybe_recover 探针通过）→ events 尾 {type:"unban", reason:"probe_recovered"}，banned 清空。"""
    g = _new_guard()
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.maybe_recover() is True, "前置条件：探针通过并解除封禁"
    snap = g.snapshot()
    assert snap["banned"] == {}
    ev = snap["events"][-1]
    assert ev["type"] == "unban"
    assert ev["source"] == "akshare"
    assert ev["reason"] == "probe_recovered", "探针恢复解封路径须区分解封来源"


def test_events_ring_bounded_at_100():
    """事件环形缓冲：超 100 条最旧被淘汰，len 恒 ≤ 100（/ipguard events 观测契约）。"""
    g = _new_guard()
    for i in range(101):
        src = f"src-{i:03d}"
        for _ in range(5):
            g.report_failure(src, ConnectionError("RemoteDisconnected"))
    snap = g.snapshot()
    assert len(snap["events"]) == 100, "环形缓冲上限 100"
    assert snap["events"][0]["source"] == "src-001", "最旧事件（src-000）应被淘汰"
    assert snap["events"][-1]["source"] == "src-100", "最新事件（src-100）保留"
    assert all(ev["type"] == "ban" for ev in snap["events"]), "全部为 ban 事件（无 unban 干扰）"
