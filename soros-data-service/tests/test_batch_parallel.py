"""/daily-bars/batch 多源分组并行测试（2026-10-04 改动 1 设计定稿）。

设计依据（router.py daily_bars_batch）：
- 并行只在源之间：按 _shard_owner 分片组并行（worker 数 = 非空组数），源内逐只串行走
  failover Router（防封禁铁律——组内节奏不变）。
- 分组归属与串行 failover 共用 DataRouter._shard_owner（单点判断，两处归属一致）。
- 指数/非纯数字 code 无分片归属 → 归串行尾批（主线程在所有 worker 完成后逐只拉取）。
- failed 语义与串行一致：单股全源失败才进 failed[]（成功组不受失败组拖累）。

确定性说明：线程测试不用 sleep 竞速猜结果——「真并行」用 threading.Barrier(2) 让两个
分片组 worker 各自进入临界区后同时放行（若只有单组则 barrier 超时 BrokenBarrierError，
测试立即红）；「组内串行」用并发深度计数器断言 max_active==1。
"""

from __future__ import annotations

import threading
import time

from adapters.base import DataRouter, SourceError
from config import settings
from helpers import StubAdapter, make_bar


class ConcurrencyTracker:
    """线程安全的并发深度跟踪：enter/exit 事件 + 最大并发深度。

    enter 在进入临界区时调用、exit 在离开时调用；events 记录交替序列供断言。
    """

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


# ──────────────────────────────────────────────────────────────────────────────
# 多组并行：≥2 个分片组真并行 + 合并完整
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_multi_group_merges_complete(monkeypatch, make_client):
    """多组并行：≥2 分片组并行执行，results/failed 合并完整、每 code 恰好一次（无丢无重）。

    屏障证明真并行：两个分片组 worker 各自进入临界区后同时放行，并发深度必须到 2。
    """
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare"))
    tracker = ConcurrencyTracker()
    barrier = threading.Barrier(2, timeout=5)  # 两分片组 worker 都进入后同时放行 → 证明真并行

    def mk(name):
        def bars(code, start_date, end_date, adjust):
            tracker.enter()
            barrier.wait()
            tracker.exit()
            return [make_bar(code)]

        return StubAdapter(name, bars=bars)

    router = DataRouter([mk("baostock"), mk("akshare"), mk("mootdx")])
    client = make_client(router)
    codes = ["600000", "000001", "600002", "000003"]  # 偶数→baostock 组，奇数→akshare 组
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    _assert_no_loss_no_dup(body, codes)
    assert tracker.max_active == 2, "两个分片组必须真正并行（屏障证明并发深度 2）"


def test_batch_parallel_failed_semantics_all_sources_fail(monkeypatch, make_client):
    """failed 语义与串行一致：组内全源失败才进 failed[]，成功组不受失败组拖累。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare"))

    def baostock_bars(code, start_date, end_date, adjust):
        # 归属 baostock 的 code 成功；000001 归属 akshare 但 failover 到 baostock 也败
        if code in ("600000", "600002"):
            return [make_bar(code)]
        raise SourceError("baostock 也失败")

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
    codes = ["600000", "000001", "600002"]
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200, "单股失败不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert set(body["results"].keys()) == {"600000", "600002"}, "baostock 组成功"
    assert [f["code"] for f in body["failed"]] == ["000001"], "akshare 组全源失败 → failed[]"
    assert "All sources failed" in body["failed"][0]["reason"]
    _assert_no_loss_no_dup(body, codes)


# ──────────────────────────────────────────────────────────────────────────────
# 单组等价：全部 code 同归属 → 行为与串行一致（源内逐只串行）
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_single_group_is_serial(monkeypatch, make_client):
    """单组等价：全部 code 同归属 → worker=1，源内逐只串行（并发深度 1），顺序保持输入序。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare"))
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
    client = make_client(router)
    codes = ["600000", "600002", "600004", "600006"]  # 全偶数 → 全归属 baostock（单组）
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
# 指数尾批：非纯数字 code 无分片归属 → 串行尾批
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_index_tail_batch(monkeypatch, make_client):
    """指数尾批：codes 混入非纯数字（sh000001）→ 结果完整不炸；sh000001 走指数路径（仅 akshare）。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare"))
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


def test_batch_parallel_no_owner_all_tail_serial(monkeypatch, make_client):
    """无归属全尾批：shard_sources 配置 <2 源 → 全部 code 归串行尾批（主线程逐只），顺序保持。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock",))
    call_order = []

    def bars(code, start_date, end_date, adjust):
        call_order.append(code)
        return [make_bar(code)]

    router = DataRouter([
        StubAdapter("baostock", bars=bars),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])
    client = make_client(router)
    codes = ["600000", "000001", "600004"]
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": codes, "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    _assert_no_loss_no_dup(body, codes)
    assert call_order == codes, "全尾批须保持输入顺序串行（主线程逐只）"


# ──────────────────────────────────────────────────────────────────────────────
# 归属单点一致性：batch 分组与串行 failover 共用 _shard_owner
# ──────────────────────────────────────────────────────────────────────────────

def test_batch_parallel_owner_consistency(monkeypatch):
    """归属单点一致性：_shard_owner(code) 的归属源 == _ordered_for_stock(code) 首位（owner）。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare"))
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    # 纯数字有归属：偶数→baostock、奇数→akshare，failover 首位必须是归属源
    for code in ["600000", "000001", "600002", "000003", "300750"]:
        owner = router._shard_owner(code)
        ordered = router._ordered_for_stock(code)
        assert owner is not None, f"{code} 应有两分片归属"
        assert ordered[0] is owner, f"{code} 分片归属 {owner.source_name} 必须是 failover 首位"
        assert ordered[0].source_name == owner.source_name

    # 无归属（非纯数字指数 code）→ _shard_owner None，failover 退化 router_order 首位
    owner = router._shard_owner("sh000001")
    assert owner is None, "指数 code 无分片归属"
    ordered = router._ordered_for_stock("sh000001")
    assert ordered[0].source_name == "baostock", "无归属 → 退化 router_order 首位"
