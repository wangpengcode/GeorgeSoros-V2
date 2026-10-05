"""均分流量轮转测试（2026-10-05 均分流量定稿，替代旧 int(code)%N 取模分片）。

设计要点（用户定稿，PLAN 均分流量章节）：
- 多源 = 均分流量防封禁：每票由「健康源轮转」分配一个源，单票单源，绝不 inline failover
- 失败票本轮放弃，等 stock_info 下一轮扫描自然重试（届时可能换源执行）
- 健康过滤（_healthy_candidates）：封禁（IPGuard banned）/ 熔断 open / 驱逐冷却中
  （连续 2 次 SourceError → 300s 冷却）/ 复权能力缺失 → 移出候选；half_open 保留
  （allow_request 单探针放行，成功即闭合自愈）
- 空票偏好（K=2 verified-empty 配套）：同 code 已投过空票的源，在还有其他候选时优先排除；
  全部候选都投过票 → 回退全候选（总得有人再试）
- 时钟注入：router._clock（默认 time.monotonic），测试直接替换实例属性，不碰全局 time
"""

from __future__ import annotations

from adapters.base import DataRouter, SourceError
from helpers import FakeCircuitBreaker, StubAdapter, make_bar, raise_error
from ipguard import IPGuard


def _new_guard(**kwargs) -> IPGuard:
    """隔离 IPGuard（monkeypatch adapters.base.guard，不污染全局单例）。"""
    defaults = dict(probe_fn=lambda: True, egress_ip_fn=lambda: "1.2.3.4")
    defaults.update(kwargs)
    return IPGuard(**defaults)


def _ok_router() -> DataRouter:
    """三源全健康（bars 各返回 1 根）。"""
    return DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])


# ──────────────────────────────────────────────────────────────────────────────
# 轮转均分
# ──────────────────────────────────────────────────────────────────────────────

def test_rotation_even_distribution():
    """全健康 → 连续 6 票轮转，每源恰好 2 次（均分流量，非取模归属）。"""
    router = _ok_router()
    for i in range(6):
        result, errors = router.fetch_daily_bars(f"60000{i}", "2026-09-30", "2026-09-30", "qfq")
        assert result is not None and result["count"] == 1
    counts = {n: router.adapters[n].call_counts["daily_bars"] for n in ("baostock", "akshare", "mootdx")}
    assert counts == {"baostock": 2, "akshare": 2, "mootdx": 2}, f"均分流量要求每源 2 次，实际 {counts}"


def test_result_source_reports_assigned_source():
    """结果 source 如实反映被分配源（归因对拍口径不变）。"""
    router = _ok_router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "baostock", "新 router 首票轮转到候选序首位"


# ──────────────────────────────────────────────────────────────────────────────
# 健康过滤：封禁 / 熔断 open / half_open / 能力
# ──────────────────────────────────────────────────────────────────────────────

def test_banned_source_excluded_from_rotation(monkeypatch):
    """被封禁源移出候选：绝不向它发请求（封禁期间零流量）。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for _ in range(5):
        g.report_failure("akshare", ConnectionError("RemoteDisconnected"))
    assert g.is_banned("akshare")
    router = _ok_router()
    for i in range(4):
        result, _ = router.fetch_daily_bars(f"60000{i}", "2026-09-30", "2026-09-30", "qfq")
        assert result is not None
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, "封禁源零请求"


def test_open_breaker_source_excluded_from_rotation():
    """熔断 open 源移出候选（未发请求）；half_open 保留（单探针自愈入口）。"""
    router = _ok_router()
    router.adapters["baostock"].circuit_breaker = FakeCircuitBreaker(state="open")
    for i in range(4):
        result, _ = router.fetch_daily_bars(f"60000{i}", "2026-09-30", "2026-09-30", "qfq")
        assert result is not None
    assert router.adapters["baostock"].call_counts["daily_bars"] == 0, "熔断 open 零请求"
    assert router.adapters["akshare"].call_counts["daily_bars"] == 2
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 2


def test_half_open_source_still_eligible():
    """half_open 保留在候选：allow_request 单探针放行，成功即闭合——排除它反而堵死自愈。"""
    router = _ok_router()
    router.adapters["baostock"].circuit_breaker = FakeCircuitBreaker(state="half_open")
    result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1, "half_open 应可被分配（探针）"


def test_incapable_adjust_source_excluded_from_rotation():
    """复权能力缺失的源移出该复权口径的候选（qfq/hfq）；none 口径不设限。"""
    router = _ok_router()
    router.adapters["mootdx"]._supports_adjust_qfq = False
    for i in range(4):
        result, _ = router.fetch_daily_bars(f"60000{i}", "2026-09-30", "2026-09-30", "qfq")
        assert result is not None
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0, "不支持 qfq 的源不被分配 qfq"
    for i in range(3):  # none 口径：mootdx 恢复候选，轮转必然轮到它
        result, _ = router.fetch_daily_bars(f"60010{i}", "2026-09-30", "2026-09-30", "none")
        assert result is not None
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 1, "none 口径能力全通过"


# ──────────────────────────────────────────────────────────────────────────────
# 源级驱逐：连续 2 次 SourceError → 300s 冷却
# ──────────────────────────────────────────────────────────────────────────────

def _bs_only_guard(monkeypatch) -> None:
    """隔离 guard 并封禁 akshare/mootdx → 候选只剩 baostock（驱逐路径确定性强）。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for name in ("akshare", "mootdx"):
        for _ in range(5):
            g.report_failure(name, ConnectionError("RemoteDisconnected"))


def test_single_failure_keeps_source_in_rotation(monkeypatch):
    """单次 SourceError 不驱逐（未达阈值 2）：下次仍可被分配。"""
    _bs_only_guard(monkeypatch)
    router = _ok_router()
    router._clock = lambda: 1000.0
    router.adapters["baostock"]._bars = raise_error(SourceError("baostock 源故障"))
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is None and "baostock" in errors[0]
    result, _ = router.fetch_daily_bars("600001", "2026-09-30", "2026-09-30", "qfq")
    assert result is None
    assert router.adapters["baostock"].call_counts["daily_bars"] == 2, "未达阈值 2，继续参与轮换"


def test_two_consecutive_failures_evict_source_for_300s(monkeypatch):
    """连续 2 次 SourceError → 驱逐 300s：冷却期内不再被分配（零请求）。"""
    _bs_only_guard(monkeypatch)
    router = _ok_router()
    now = [1000.0]
    router._clock = lambda: now[0]
    router.adapters["baostock"]._bars = raise_error(SourceError("baostock 源故障"))
    assert router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")[0] is None
    assert router.fetch_daily_bars("600001", "2026-09-30", "2026-09-30", "qfq")[0] is None
    assert router.adapters["baostock"].call_counts["daily_bars"] == 2, "恰两次真实请求"
    # 冷却期内：无健康候选 → 零请求直接 failed
    result, errors = router.fetch_daily_bars("600002", "2026-09-30", "2026-09-30", "qfq")
    assert result is None
    assert "无健康源" in errors[0], "冷却期内无候选 → 本轮放弃，不撞死源"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 2, "驱逐期内零请求"


def test_eviction_recovered_after_cooldown(monkeypatch):
    """300s 冷却到期 → 源自动回归候选（自愈入口，无需重启）。"""
    _bs_only_guard(monkeypatch)
    router = _ok_router()
    now = [1000.0]
    router._clock = lambda: now[0]
    router.adapters["baostock"]._bars = raise_error(SourceError("baostock 源故障"))
    router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    router.fetch_daily_bars("600001", "2026-09-30", "2026-09-30", "qfq")
    now[0] += 301.0  # 冷却到期
    router.adapters["baostock"]._bars = lambda *a: [make_bar("600003")]
    result, _ = router.fetch_daily_bars("600003", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None and result["source"] == "baostock", "冷却到期应回归轮换"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 3


def test_success_resets_failure_streak(monkeypatch):
    """失败→成功→失败：成功清零连续计数（非累计 2 次即驱逐）。"""
    _bs_only_guard(monkeypatch)
    router = _ok_router()
    now = [1000.0]
    router._clock = lambda: now[0]
    flip = {"fail": True}
    def bars(*a):
        if flip["fail"]:
            raise SourceError("间歇故障")
        return [make_bar(a[0])]
    router.adapters["baostock"]._bars = bars
    assert router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")[0] is None  # 败1
    flip["fail"] = False
    assert router.fetch_daily_bars("600001", "2026-09-30", "2026-09-30", "qfq")[0] is not None  # 成功清零
    flip["fail"] = True
    assert router.fetch_daily_bars("600002", "2026-09-30", "2026-09-30", "qfq")[0] is None  # 败1（重新计数）
    assert router.fetch_daily_bars("600003", "2026-09-30", "2026-09-30", "qfq")[0] is None  # 败2 → 驱逐
    assert router.adapters["baostock"].call_counts["daily_bars"] == 4
    result, errors = router.fetch_daily_bars("600004", "2026-09-30", "2026-09-30", "qfq")
    assert result is None and "无健康源" in errors[0], "中间成功打断连续计数，第 4 次失败才驱逐"


# ──────────────────────────────────────────────────────────────────────────────
# 不 failover：单票单源
# ──────────────────────────────────────────────────────────────────────────────

def test_no_failover_on_source_error():
    """分配源故障 → 本轮放弃（None + 单源 reason），绝不把流量压到其他源（均分铁律）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=raise_error(SourceError("baostock 源故障"))),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is None
    assert len(errors) == 1 and "baostock" in errors[0], "失败只归因被分配源"
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, "故障票零 failover"
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0


def test_no_healthy_source_zero_external_calls(monkeypatch):
    """全源封禁 → 无健康候选 → 零外部请求，直接 failed（外部调用零空转铁律）。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for name in ("baostock", "akshare", "mootdx"):
        for _ in range(5):
            g.report_failure(name, ConnectionError("RemoteDisconnected"))
    router = _ok_router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is None and "无健康源" in errors[0]
    counts = {n: router.adapters[n].call_counts["daily_bars"] for n in ("baostock", "akshare", "mootdx")}
    assert counts == {"baostock": 0, "akshare": 0, "mootdx": 0}, "零外部请求"


def test_explicit_source_param_pins_assignment():
    """source= 显式钉源（batch 分组回填用）：只调该源，不走轮转。"""
    router = _ok_router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq", source="akshare")
    assert result is not None and result["source"] == "akshare"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 0
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq", source="nope")
    assert result is None and "nope" in errors[0] and "源未注册" in errors[0]


# ──────────────────────────────────────────────────────────────────────────────
# 空票（K=2 verified-empty 配套）：同 code 空票源优先排除，取到数据即清
# ──────────────────────────────────────────────────────────────────────────────

def test_empty_votes_prefer_unvoted_sources():
    """baostock 空票后，同 code 下轮优先排除它（不再撞它，换源验证空）。"""
    def empty_bars(*a):
        return []
    router = DataRouter([
        StubAdapter("baostock", bars=empty_bars),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["count"] == 0 and result["data"] == []
    assert result["empty_sources"] == ["baostock"], "空占位必须带 empty_sources（K=2 判据）"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1
    got = False
    for _ in range(3):
        result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
        if result["count"] == 1:
            got = True
            break
    assert got, "换源后取到数据（有空票时轮转排除 baostock，先撞其他源）"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1, "有空票且存在其他候选时不再撞 baostock"


def test_empty_votes_fallback_when_all_voted():
    """全候选都投过空票 → 回退全候选（总得有人再试）；votes 去重累计。"""
    def empty_bars(*a):
        return []
    router = DataRouter([
        StubAdapter("baostock", bars=empty_bars),
        StubAdapter("akshare", bars=empty_bars),
        StubAdapter("mootdx", bars=empty_bars),
    ])
    result = None
    for _ in range(6):
        result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
        assert result is not None and result["count"] == 0
    assert sorted(result["empty_sources"]) == ["akshare", "baostock", "mootdx"], "票跨轮按源累计"


def test_empty_votes_cleared_when_data_lands():
    """取到数据 → 该 code 空票清零（占位 empty_sources 重新从当前源计）。"""
    flip = {"empty": True}
    def bars(*a):
        return [] if flip["empty"] else [make_bar(a[0])]
    router = DataRouter([
        StubAdapter("baostock", bars=bars),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])
    for _ in range(6):
        router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    flip["empty"] = False
    result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["count"] == 1, "回退轮转终会命中非空源"
    flip["empty"] = True
    result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["count"] == 0
    assert len(result["empty_sources"]) == 1, "取到数据后空票已清，占位只含本轮源"


# ──────────────────────────────────────────────────────────────────────────────
# 渠道计数（/channels 可观测）
# ──────────────────────────────────────────────────────────────────────────────

def test_channel_counters_count_calls_empty_failures(monkeypatch):
    """total_calls/empty_results/failures 计数：真实请求才计数，封禁未发请求不计。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: []),           # 空结果
        StubAdapter("mootdx", bars=raise_error(SourceError("mootdx 源故障"))),
    ])
    router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")  # baostock 成功
    router.fetch_daily_bars("600001", "2026-09-30", "2026-09-30", "qfq")  # akshare 空
    router.fetch_daily_bars("600002", "2026-09-30", "2026-09-30", "qfq")  # mootdx 败1
    counters = router.channel_counters()
    assert counters["baostock"] == {"total_calls": 1, "empty_results": 0, "failures": 0}
    assert counters["akshare"] == {"total_calls": 1, "empty_results": 1, "failures": 0}
    assert counters["mootdx"]["failures"] == 1
    # 封禁 bs/ak → 候选只剩 mootdx → 败 2 次达阈值驱逐
    for name in ("baostock", "akshare"):
        for _ in range(5):
            g.report_failure(name, ConnectionError("RemoteDisconnected"))
    router.fetch_daily_bars("600003", "2026-09-30", "2026-09-30", "qfq")  # mootdx 败2 → 驱逐
    assert router.channel_counters()["mootdx"] == {"total_calls": 2, "empty_results": 0, "failures": 2}
    result, errors = router.fetch_daily_bars("600004", "2026-09-30", "2026-09-30", "qfq")
    assert result is None and "无健康源" in errors[0], "bs/ak 封禁 + mootdx 驱逐 → 无候选"
    assert router.channel_counters()["mootdx"]["total_calls"] == 2, "驱逐后零请求（未发请求不计 total_calls）"


def test_index_path_unaffected_by_rotation():
    """指数 code 走指数路径（仅 akshare），轮转不介入。"""
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])],
                    index_bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    result, _ = router.fetch_daily_bars("sh000001", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "akshare"
    assert router.adapters["akshare"].call_counts["index_daily"] == 1
    assert router.adapters["baostock"].call_counts["daily_bars"] == 0
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0
