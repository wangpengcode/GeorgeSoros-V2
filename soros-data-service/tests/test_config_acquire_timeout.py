"""rate_acquire_timeout 默认值单测（2026-10-04 修 B：30s→120s）。

背景：baostock 熔断期分片 failover 堆积下游源，15s/次节奏下排 2-3 个就超 30s，
"限流等待超时"把等 35s 能成功的请求变成失败。120s 数学上安全：
最坏单源扛 2 分片 ≈ 100 票 × 16.7s ≈ 1670s < Spring 批预算 1800s。
"""

from __future__ import annotations

from config import Settings


def test_default_acquire_timeout_120s(monkeypatch):
    """默认 120s（env 未设时）——覆盖 failover 堆积场景。"""
    monkeypatch.delenv("SOROS_RATE_ACQUIRE_TIMEOUT", raising=False)
    settings = Settings()
    assert settings.rate_acquire_timeout_seconds == 120.0


def test_env_override_acquire_timeout(monkeypatch):
    """env 显式设置仍优先生效（可调性不回退）。"""
    monkeypatch.setenv("SOROS_RATE_ACQUIRE_TIMEOUT", "45")
    settings = Settings()
    assert settings.rate_acquire_timeout_seconds == 45.0
