"""/daily-bars/batch 均分流量分组并行测试（2026-10-05 均分流量定稿）。

设计依据（router.py daily_bars_batch）：
- 并行只在源之间：每票先由 _assign_source 轮转分配一个健康源，按分配源分组并行
  （worker 数 = 非空组数），组内逐只串行（防封禁铁律——组内节奏不变）。
- worker 回传 source=钉死分配源（单点分配：分组与执行用同一次 _assign_source 结果，
  worker 内不再轮转、不再换源）。
- 无健康源/指数 code 的处理：指数归串行尾批（走指数路径）；无健康源直接 failed
  （零外部请求），不进 worker。
- failed 语义：分配源真实故障 → failed[{code, reason}]（reason 归因被分配源）；
  空结果 = 占位 count=0（带 empty_sources）进 results（非 failed）。

确定性说明：线程测试不用 sleep 竞速——「真并行」用 threading.Barrier 让各源组 worker
进入临界区后同时放行（组数不足则 barrier 超时立即红）；「组内串行」用并发深度计数器。
"""

from __future__ import annotations

import threading
import time

from adapters.base import DataRouter, SourceError
from helpers import FakeCircuitBreaker, StubAdapter, make_bar, raise_error
from ipguard import IPGuard


class ConcurrencyTracker:
    """线程安全的并发深度跟踪：enter/exit 事件 + 最大并发深度。"""

    def __init__(self):
        self._lock = threading.Lock()
        self.active = 0
        self.max_active = 0
        self.events = []

    def enter(self):
        with self._lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.events.append("enter")

    def exit(self):
        with self._lock:
            self.active -= 1
            self.events.append("exit")


def _assert_no_loss_no_dup(body, codes):
    """无丢无重：results + failed 的 code 集 == 输入 codes，且各自无重复。"""
    result_codes = list(body["results"].keys())
    failed_codes = [f["code"] for f in body["failed"]]
    assert len(result_codes) == len(set(result_codes)), f"results 内重复 code: {result_codes}"
    assert len(failed_codes) == len(set(failed_codes)), f"failed 内重复 code: {failed_codes}"
    assert len(result_codes) + len(failed_codes) == len(codes), (
        f"无丢：results({len(result_codes)}) + failed({len(failed_codes)}) != 输入({len(codes)})"
    )
    got = set(result_codes) | set(failed_codes)
    assert got == set(codes), f"code 集合不一致: {got} != {set(codes)}"


def _new_guard(**kwargs) -> IPGuard:
    defaults = dict(probe_fn=lambda: True, egress_ip_fn=lambda: "1.2.3.4")
    defaults.update(kwargs)
    return IPGuard(**defaults)


# ──────────────────────────────────────────────────────────────────────────────
# 多组并行：3 源轮转分组真并行 + 合并完整
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_multi_group_merges_complete(make_client):
    """3 票 3 源全健康 → 轮转必分 3 组，3 worker 并行（屏障证明并发深度 3），合并无丢无重。"""
    tracker = ConcurrencyTracker()
    barrier = threading.Barrier(3, timeout=5)  # 3 组 worker 都进入后同时放行 → 证明真并行

    def mk(name):
        def bars(code, start_date, end_date, adjust):
            tracker.enter()
            barrier.wait()
            tracker.exit()
            return [make_bar(code)]

        return StubAdapter(name, bars=bars)

    router = DataRouter([mk("baostock"), mk("akshare"), mk("mootdx")])
    client = make_client(router)
    codes = ["600000", "000001", "600002"]  # 轮转 cursor 0/1/2 → 恰好每源一票一组
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    _assert_no_loss_no_dup(body, codes)
    assert tracker.max_active == 3, "三个源组必须真正并行（屏障证明并发深度 3）"
    counts = {n: router.adapters[n].call_counts["daily_bars"] for n in ("baostock", "akshare", "mootdx")}
    assert counts == {"baostock": 1, "akshare": 1, "mootdx": 1}, "均分流量：每源恰一票"


def test_batch_parallel_failed_semantics_no_failover(make_client):
    """failed 语义：分配源故障 → 该票 failed（reason 归因分配源），不切源；成功组不受拖累。"""

    def baostock_bars(code, start_date, end_date, adjust):
        return [make_bar(code)]

    def akshare_bars(code, start_date, end_date, adjust):
        raise SourceError("akshare 故障")

    def mootdx_bars(code, start_date, end_date, adjust):
        raise SourceError("mootdx 故障")

    router = DataRouter([
        StubAdapter("baostock", bars=baostock_bars),
        StubAdapter("akshare", bars=akshare_bars),
        StubAdapter("mootdx", bars=mootdx_bars),
    ])
    client = make_client(router)
    codes = ["600000", "000001", "600002", "000003"]  # 轮转 bs/ak/mx/bs → bs 组 2 票成功
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200, "单股失败不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert set(body["results"].keys()) == {"600000", "000003"}, "baostock 组成功"
    failed_by = {f["code"]: f["reason"] for f in body["failed"]}
    assert set(failed_by) == {"000001", "600002"}
    assert "akshare" in failed_by["000001"], "reason 归因被分配源"
    assert "mootdx" in failed_by["600002"]
    assert "All sources failed" not in failed_by["000001"], "不再有 failover 聚合文案"
    _assert_no_loss_no_dup(body, codes)


# ──────────────────────────────────────────────────────────────────────────────
# 单组等价：健康候选只剩一个源 → 全票同组，组内逐只串行
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_single_group_is_serial(make_client):
    """唯一健康源 → 全票同组 worker=1，组内逐只串行（并发深度 1），顺序保持输入序。"""
    tracker = ConcurrencyTracker()
    call_order = []

    def baostock_bars(code, start_date, end_date, adjust):
        tracker.enter()
        call_order.append(code)
        time.sleep(0.01)  # 拉长临界区，若组内并发会暴露 max_active>1
        tracker.exit()
        return [make_bar(code)]

    router = DataRouter([
        StubAdapter("baostock", bars=baostock_bars),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    router.adapters["akshare"].circuit_breaker = FakeCircuitBreaker(state="open")
    router.adapters["mootdx"].circuit_breaker = FakeCircuitBreaker(state="open")
    client = make_client(router)
    codes = ["600000", "000001", "600002", "000003"]
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert set(body["results"].keys()) == set(codes)
    assert body["failed"] == []
    assert tracker.max_active == 1, "单组 worker 内必须逐只串行（防封禁铁律）"
    assert call_order == codes, "组内保持输入顺序逐只串行"


# ──────────────────────────────────────────────────────────────────────────────
# 指数尾批 + 无健康源零请求
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_index_tail_batch(make_client):
    """指数尾批：codes 混入非纯数字（sh000001）→ 归串行尾批走指数路径（仅 akshare），结果完整。"""
    akshare = StubAdapter(
        "akshare",
        bars=lambda *a: [make_bar(a[0])],
        index_bars=lambda *a: [make_bar(a[0])],
    )
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        akshare,
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    client = make_client(router)
    codes = ["600000", "sh000001", "000001"]
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200, "指数 code 混入不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    _assert_no_loss_no_dup(body, codes)
    assert akshare.call_counts["index_daily"] == 1, "sh000001 走指数路径（仅 akshare）"


def test_batch_parallel_no_healthy_source_all_failed_zero_calls(monkeypatch, make_client):
    """全源封禁 → 全部 code 直接 failed（零外部请求），reason 说明本轮放弃。"""
    g = _new_guard()
    monkeypatch.setattr("adapters.base.guard", g)
    for name in ("baostock", "akshare", "mootdx"):
        for _ in range(5):
            g.report_failure(name, ConnectionError("RemoteDisconnected"))
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    client = make_client(router)
    codes = ["600000", "000001", "600002"]
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["results"] == {}
    assert {f["code"] for f in body["failed"]} == set(codes)
    assert all("无健康源" in f["reason"] for f in body["failed"]), "无健康源 → 本轮不发起外部调用"
    counts = {n: router.adapters[n].call_counts["daily_bars"] for n in ("baostock", "akshare", "mootdx")}
    assert counts == {"baostock": 0, "akshare": 0, "mootdx": 0}, "零外部请求"


# ──────────────────────────────────────────────────────────────────────────────
# items 模式：分配源分组 + 空占位 empty_sources 跨请求累计（K=2 配套）
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_items_placeholder_accumulates_empty_sources(make_client):
    """items 全源空：第 1 请求占位 empty_sources=[bs]；第 2 请求换源（空票排除）票累计两源。"""

    def bars(code, start_date, end_date, adjust):
        return []

    router = DataRouter([
        StubAdapter("baostock", bars=bars),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])
    client = make_client(router)
    payload = {"items": [{"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-30"}]}
    resp1 = client.post("/api/v1/daily-bars/batch", json=payload)
    assert resp1.status_code == 200
    body1 = resp1.json()
    assert body1["failed"] == []
    assert body1["results"]["600000"]["count"] == 0
    assert body1["results"]["600000"]["empty_sources"] == ["baostock"], "第 1 轮空票归因轮转首位"

    resp2 = client.post("/api/v1/daily-bars/batch", json=payload)
    body2 = resp2.json()
    assert body2["results"]["600000"]["count"] == 0
    es2 = body2["results"]["600000"]["empty_sources"]
    assert len(es2) == 2 and "baostock" in es2, "第 2 轮换源（空票排除），票跨轮累计（K=2 判据）"
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1, "第 2 轮不再撞已投票源"


def test_batch_items_failure_goes_failed(make_client):
    """items 分配源故障 → failed[{code, reason}]，reason 归因分配源。"""

    def bars(code, start_date, end_date, adjust):
        raise SourceError("源故障")

    router = DataRouter([
        StubAdapter("baostock", bars=bars),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-30"}],
    })
    assert resp.status_code == 200, "单股失败不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert "600000" not in body["results"]
    assert len(body["failed"]) == 1
    assert body["failed"][0]["code"] == "600000"
    assert "源故障" in body["failed"][0]["reason"]
