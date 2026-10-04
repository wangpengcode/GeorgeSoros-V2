"""rate_acquire_timeout 接线单测（2026-10-04 修 B 完全体）。

背景：base.py:114 调 acquire() 不传参 → 永远走 TokenBucket.acquire 的硬编码
默认 30s，config.rate_acquire_timeout_seconds 是从未被消费的死配置。
修 B 完全体 = TokenBucket 构造时接收实例超时 + main.py 六处装配全部接线。
"""

from __future__ import annotations

import time

import pytest

from rate_limiter import TokenBucket


def test_acquire_uses_instance_timeout():
    """不传参时用实例超时：空桶 + 0.2s 实例超时 → ~0.2s 返回 False。"""
    tb = TokenBucket(0.0001, 0.0, name="wiring-test", acquire_timeout_seconds=0.2)
    start = time.monotonic()
    assert tb.acquire() is False
    elapsed = time.monotonic() - start
    assert 0.15 < elapsed < 2.0


def test_acquire_explicit_timeout_wins():
    """显式传参仍优先于实例超时（调用方可覆盖）。"""
    tb = TokenBucket(0.0001, 0.0, name="wiring-test", acquire_timeout_seconds=5.0)
    start = time.monotonic()
    assert tb.acquire(timeout=0.05) is False
    elapsed = time.monotonic() - start
    assert elapsed < 3.0


def test_default_constructor_keeps_30s():
    """未指定实例超时时保持 30.0（向后兼容）。"""
    tb = TokenBucket(0.06, 0.0, name="legacy")
    assert tb._acquire_timeout == 30.0


def test_main_wires_settings_timeout_into_all_buckets():
    """main.py 装配：每个 adapter 的令牌桶实例超时 == settings.rate_acquire_timeout_seconds。"""
    import main
    from config import Settings

    settings = Settings()
    router = main.build_router()
    for adapter in router.adapters.values():
        assert adapter.rate_limiter._acquire_timeout == settings.rate_acquire_timeout_seconds, (
            f"{adapter.source_name} 未接线 rate_acquire_timeout"
        )
