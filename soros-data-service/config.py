"""服务配置（PLAN §11.1 韧性参数可配置，端口 8000）。

熔断 / 限流参数对齐 §11.1 默认值：
- CircuitBreaker 每源独立：连续 5 次失败 → open 60s → half-open 单探测
- TokenBucket 每源独立：baostock 5 rps / AKShare 2 rps + 抖动 / mootdx 3 rps
- Router 顺序 baostock → akshare → mootdx
所有值可通过环境变量覆盖（SOROS_* 前缀），便于测试/部署注入。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Dict


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw.strip())
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw.strip())
    except ValueError:
        return default


@dataclass
class Settings:
    # 服务端口（PLAN §5.1：FastAPI + uvicorn，端口 8000）
    port: int = field(default_factory=lambda: _env_int("SOROS_PORT", 8000))
    host: str = os.getenv("SOROS_HOST", "0.0.0.0")

    # ── CircuitBreaker（PLAN §11.1）──
    cb_failure_threshold: int = field(default_factory=lambda: _env_int("SOROS_CB_FAILURE_THRESHOLD", 5))
    cb_open_timeout_seconds: int = field(default_factory=lambda: _env_int("SOROS_CB_OPEN_TIMEOUT", 60))
    cb_recovery_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_CB_RECOVERY_JITTER", 0.0))

    # ── TokenBucket（PLAN §11.1）──
    rate_baostock: float = field(default_factory=lambda: _env_float("SOROS_RATE_BAOSTOCK", 5.0))
    rate_akshare: float = field(default_factory=lambda: _env_float("SOROS_RATE_AKSHARE", 2.0))
    rate_mootdx: float = field(default_factory=lambda: _env_float("SOROS_RATE_MOOTDX", 3.0))
    # AKShare 抖动（PLAN §11.1 "2 rps + 抖动"）——随机 sleep [0, jitter) 打散请求
    akshare_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_AKSHARE_JITTER", 0.2))
    rate_acquire_timeout_seconds: float = field(default_factory=lambda: _env_float("SOROS_RATE_ACQUIRE_TIMEOUT", 30.0))

    # ── Router（PLAN §11.1：baostock → akshare → mootdx）──
    router_order: tuple = field(
        default_factory=lambda: tuple(
            os.getenv("SOROS_ROUTER_ORDER", "baostock,akshare,mootdx").replace(" ", "").split(",")
        )
    )

    # 请求校验
    batch_max_codes: int = field(default_factory=lambda: _env_int("SOROS_BATCH_MAX_CODES", 1000))

    def __post_init__(self) -> None:
        if not self.router_order:
            self.router_order = ("baostock", "akshare", "mootdx")
        if len(self.router_order) != len(set(self.router_order)):
            raise ValueError(f"SOROS_ROUTER_ORDER 不能包含重复源: {self.router_order}")


settings = Settings()
