"""TokenBucket 单测（PLAN §11.1：baostock 5 rps / AKShare 2 rps + 抖动 / mootdx 3 rps）。

- 超速率拒绝（桶空）
- 抖动 sleep 只拉长请求间隔，不突破平均速率上限
- 每源独立配额互不干扰
- 阻塞 acquire 超时返回 False
"""

from __future__ import annotations

import rate_limiter as rl_module
from rate_limiter import TokenBucket


def _freeze_time(monkeypatch, start=0.0):
    """可控单调时钟替换 time.monotonic。"""
    now = {"t": start}
    monkeypatch.setattr(rl_module.time, "monotonic", lambda: now["t"])
    return now


def test_over_rate_rejection(monkeypatch):
    now = _freeze_time(monkeypatch)
    bucket = TokenBucket(rate=1.0, capacity=1.0)
    assert bucket.try_acquire() is True
    assert bucket.try_acquire() is False, "桶空立即拒绝（超速率）"
    now["t"] += 1.0
    assert bucket.try_acquire() is True, "1 秒后回填 1 令牌恢复"


def test_burst_capped_by_capacity(monkeypatch):
    now = _freeze_time(monkeypatch)
    bucket = TokenBucket(rate=2.0, capacity=2.0)
    assert bucket.try_acquire() is True
    assert bucket.try_acquire() is True
    assert bucket.try_acquire() is False, "容量=rate，初始突发上限=capacity"
    now["t"] += 0.5
    assert bucket.try_acquire() is True, "0.5s 回填 1 令牌（0.5×2）"


def test_average_rate_bounded_with_jitter(monkeypatch):
    """带抖动时平均速率仍不超上限（令牌桶不变量：窗口内发放令牌 ≤ capacity + rate×elapsed）。

    模拟：sleep 推进时钟、random.uniform 取最大值（最坏抖动），跑 200 次 acquire。
    初始有 capacity 个突发令牌，故预算 = capacity + rate×elapsed；抖动 sleep 只会让
    elapsed 更大、发放更稀，绝不突破预算 → 平均速率趋近 ≤ rate。
    """
    now = {"t": 0.0}
    monkeypatch.setattr(rl_module.time, "monotonic", lambda: now["t"])

    def fake_sleep(d):
        now["t"] += d

    def fake_uniform(a, b):
        return b  # 取最大抖动

    monkeypatch.setattr(rl_module.time, "sleep", fake_sleep)
    monkeypatch.setattr(rl_module.random, "uniform", fake_uniform)

    rate = 5.0
    bucket = TokenBucket(rate=rate, capacity=rate, jitter=0.05, name="akshare")
    n = 200
    for _ in range(n):
        assert bucket.acquire(timeout=60.0), f"第 {_} 次 acquire 应在模拟时钟下成功"

    elapsed = now["t"]
    budget = bucket.capacity + rate * elapsed + 1e-9
    assert n <= budget, (
        f"带抖动发放 {n} 个令牌超过令牌桶预算 {budget:.4f}"
        f"（capacity + rate×elapsed = {bucket.capacity} + {rate}×{elapsed:.4f}），"
        f"平均速率 {n / elapsed:.4f} rps 突破上限 {rate} rps"
    )


def test_independent_buckets_no_interference(monkeypatch):
    now = _freeze_time(monkeypatch)
    a = TokenBucket(rate=1.0, capacity=1.0, name="baostock")
    b = TokenBucket(rate=1.0, capacity=1.0, name="mootdx")
    assert a.try_acquire() is True
    assert b.try_acquire() is True, "每源独立配额：A 耗尽不影响 B"
    assert a.try_acquire() is False
    assert b.try_acquire() is False
    now["t"] += 1.0
    assert a.try_acquire() is True
    assert b.try_acquire() is True


def test_acquire_timeout_returns_false(monkeypatch):
    _freeze_time(monkeypatch)
    bucket = TokenBucket(rate=0.0, capacity=0.0)
    assert bucket.acquire(timeout=0.0) is False, "永远无令牌 → 超时返回 False"
