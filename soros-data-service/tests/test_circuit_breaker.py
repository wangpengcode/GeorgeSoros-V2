"""CircuitBreaker 状态机单测（PLAN §11.1：closed → 连续 5 败 → open 60s → half-open 单探测）。

时间用注入 fake monotonic（monkeypatch），不真等 60s。
健康语义：closed→ok，half_open 或有失败痕迹→degraded，open→down。
"""

from __future__ import annotations

import circuit_breaker as cb_module
from circuit_breaker import (
    HEALTH_DEGRADED,
    HEALTH_DOWN,
    HEALTH_OK,
    STATE_CLOSED,
    STATE_HALF_OPEN,
    STATE_OPEN,
    CircuitBreaker,
)


def _freeze_time(monkeypatch, start=0.0):
    """用可控单调时钟替换 time.monotonic，返回 now 字典（测试内推进时钟用）。"""
    now = {"t": start}

    def fake_monotonic():
        return now["t"]

    monkeypatch.setattr(cb_module.time, "monotonic", fake_monotonic)
    return now


def test_closed_to_open_after_five_failures(monkeypatch):
    _freeze_time(monkeypatch)
    cb = CircuitBreaker("t", failure_threshold=5, open_timeout_seconds=60)
    assert cb.state == STATE_CLOSED
    for i in range(5):
        assert cb.allow_request() is True, f"closed 第 {i + 1} 次请求仍放行"
        cb.record_failure()
    assert cb.state == STATE_OPEN, "连续 5 次失败 → open"
    assert cb.allow_request() is False, "open 第 6 次调用直接拒绝（不触源）"
    assert cb.consecutive_failures == 5


def test_open_rejects_until_timeout_then_half_open_probe_success_resets(monkeypatch):
    now = _freeze_time(monkeypatch)
    cb = CircuitBreaker("t", failure_threshold=5, open_timeout_seconds=60)
    for _ in range(5):
        cb.allow_request()
        cb.record_failure()
    assert cb.state == STATE_OPEN

    now["t"] += 30
    assert cb.allow_request() is False, "open 未到 60s 仍拒绝"

    now["t"] += 30
    assert cb.state == STATE_OPEN, "状态在 allow_request 中惰性迁移"
    assert cb.allow_request() is True, "60s 到期 → half_open 放行单探测"
    assert cb.state == STATE_HALF_OPEN

    cb.record_success()
    assert cb.state == STATE_CLOSED, "单探测成功 → 复位 closed"
    assert cb.consecutive_failures == 0


def test_half_open_probe_failure_reopens_and_restarts_timer(monkeypatch):
    now = _freeze_time(monkeypatch)
    cb = CircuitBreaker("t", failure_threshold=5, open_timeout_seconds=60)
    for _ in range(5):
        cb.allow_request()
        cb.record_failure()

    now["t"] += 60
    assert cb.allow_request() is True
    assert cb.state == STATE_HALF_OPEN

    cb.record_failure()  # 单探测失败 → 重回 open 并重启计时
    assert cb.state == STATE_OPEN

    now["t"] += 30
    assert cb.allow_request() is False, "重回 open 后新计时未满 60s 拒绝"
    now["t"] += 30
    assert cb.allow_request() is True, "累计 60s → 再次 half_open"


def test_health_state_mapping(monkeypatch):
    now = _freeze_time(monkeypatch)
    cb = CircuitBreaker("t", failure_threshold=5, open_timeout_seconds=60)
    assert cb.health() == HEALTH_OK

    cb.record_failure()
    assert cb.health() == HEALTH_DEGRADED, "有失败痕迹 → degraded"

    for _ in range(4):
        cb.record_failure()
    assert cb.health() == HEALTH_DOWN, "open → down"

    # half_open（探测期）→ degraded
    now["t"] += 60
    cb.allow_request()
    assert cb.state == STATE_HALF_OPEN
    assert cb.health() == HEALTH_DEGRADED, "half_open → degraded"


def test_record_success_in_closed_resets_failure_count(monkeypatch):
    _freeze_time(monkeypatch)
    cb = CircuitBreaker("t", failure_threshold=5, open_timeout_seconds=60)
    cb.record_failure()
    cb.record_failure()
    cb.record_success()
    assert cb.state == STATE_CLOSED
    assert cb.consecutive_failures == 0, "closed 期间成功即清零失败计数"
