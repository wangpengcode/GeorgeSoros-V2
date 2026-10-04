"""per-adapter 同源在途请求互斥锁测试（2026-10-04 改动 2 设计定稿）。

设计（adapters/base.py BaseAdapter._call_lock + DataRouter._adapter_locks）：
- 每实例一把 _call_lock，_call_guarded 锁住「实际调 adapter 方法」那一小段（含登录/查询）。
- DataRouter 构造时按 source_name 把各源锁登记进 _adapter_locks（key 与台账一致）。
- TokenBucket 限流等待在锁外（慢等不占锁）——难直接断言，改为断言 _lock_for(source)
  登记的锁对象与 adapter._call_lock 是同一把（旁证「限流等待在锁外」的锁隔离语义）。
- 锁=源级：同源互斥（防 baostock 模块级单例 socket 并发串包）；不同源互不阻塞（吞吐不受影响）。

确定性说明：线程测试不用 sleep 竞速猜结果——
- 同源串行化：两线程同时调同一 adapter，并发深度计数器断言 max_active==1（enter/exit 严格交替）。
- 不同源不互斥：两源各自线程进入临界区后用 Barrier(2) 同时放行，并发深度必须到 2。
"""

from __future__ import annotations

import threading
import time

from adapters.base import DataRouter
from helpers import StubAdapter, make_bar


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


def _assert_events_never_overlap(events):
    """enter/exit 事件严格交替（任一时刻临界区深度 ≤1）。"""
    depth = 0
    for ev in events:
        depth += 1 if ev == "enter" else -1
        assert 0 <= depth <= 1, f"临界区重叠：depth={depth}（events={events}）"
    assert depth == 0, f"enter/exit 失衡：depth={depth}"


# ──────────────────────────────────────────────────────────────────────────────
# 同源并发串行化
# ──────────────────────────────────────────────────────────────────────────────

def test_per_adapter_lock_serializes_concurrent_calls():
    """同源并发串行化：两线程同时调同一 adapter → 临界区不重叠（enter/exit 交替 + 并发深度 1）。"""
    tracker = ConcurrencyTracker()
    start = threading.Barrier(2)  # 两线程同时起跑，公平竞争锁

    def bars(code, start_date, end_date, adjust):
        tracker.enter()
        time.sleep(0.05)  # 拉长临界区，暴露任何重叠
        tracker.exit()
        return [make_bar(code)]

    adapter = StubAdapter("baostock", bars=bars)

    def call(code):
        start.wait()
        adapter.fetch_daily_bars(code, "2026-09-25", "2026-09-30", "none")

    t1 = threading.Thread(target=call, args=("600000",))
    t2 = threading.Thread(target=call, args=("600001",))
    t1.start()
    t2.start()
    t1.join(timeout=5)
    t2.join(timeout=5)
    assert not t1.is_alive() and not t2.is_alive(), "线程应已结束（无死锁）"
    assert tracker.max_active == 1, "同一源临界区必须串行（并发深度 1）"
    _assert_events_never_overlap(tracker.events)


# ──────────────────────────────────────────────────────────────────────────────
# 锁登记同一对象（旁证「限流等待在锁外」的锁隔离语义）
# ──────────────────────────────────────────────────────────────────────────────

def test_per_adapter_lock_registered_identity():
    """锁登记：DataRouter._lock_for(source) 返回的锁对象与 adapter._call_lock 是同一把。

    旁证语义：_call_guarded 用 adapter._call_lock 锁「实际请求」，_lock_for 只做登记/取用——
    两者必须是同一对象，外部协调（观测/限流隔离）拿到的锁与请求锁一致。
    """
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("akshare", bars=lambda *a: [make_bar(a[0])]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar(a[0], include_prev_close=False)]),
    ])
    for name, adapter in router.adapters.items():
        assert router._lock_for(name) is adapter._call_lock, (
            f"{name} 的 _lock_for 登记锁必须与 adapter._call_lock 同一对象（_call_guarded 用同一把锁）"
        )


# ──────────────────────────────────────────────────────────────────────────────
# 不同源不互斥（吞吐不受影响）
# ──────────────────────────────────────────────────────────────────────────────

def test_per_adapter_lock_different_sources_overlap():
    """不同源不互斥：两个不同 adapter 各自线程在临界区内 → 可重叠（并发深度 2，吞吐不受影响）。

    屏障证明：两源各自进入临界区后 Barrier(2) 同时放行——若锁被错误共享（同一把锁），
    第二个线程进不来、barrier 超时 BrokenBarrierError，max_active 无法到 2。
    """
    tracker = ConcurrencyTracker()
    barrier = threading.Barrier(2, timeout=5)

    def bars_baostock(code, start_date, end_date, adjust):
        tracker.enter()
        barrier.wait()
        tracker.exit()
        return [make_bar(code)]

    def bars_akshare(code, start_date, end_date, adjust):
        tracker.enter()
        barrier.wait()
        tracker.exit()
        return [make_bar(code)]

    baostock = StubAdapter("baostock", bars=bars_baostock)
    akshare = StubAdapter("akshare", bars=bars_akshare)

    def call(adapter, code):
        adapter.fetch_daily_bars(code, "2026-09-25", "2026-09-30", "none")

    t1 = threading.Thread(target=call, args=(baostock, "600000"))
    t2 = threading.Thread(target=call, args=(akshare, "000001"))
    t1.start()
    t2.start()
    t1.join(timeout=10)
    t2.join(timeout=10)
    assert not t1.is_alive() and not t2.is_alive(), "线程应已结束（无死锁）"
    assert tracker.max_active == 2, "不同源各自锁互不阻塞，临界区可重叠（并发深度 2）"
