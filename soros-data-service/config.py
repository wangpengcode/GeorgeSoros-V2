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
    rate_baostock: float = field(default_factory=lambda: _env_float("SOROS_RATE_BAOSTOCK", 0.06))
    rate_akshare: float = field(default_factory=lambda: _env_float("SOROS_RATE_AKSHARE", 0.06))
    rate_mootdx: float = field(default_factory=lambda: _env_float("SOROS_RATE_MOOTDX", 0.06))
    # AKShare 抖动（PLAN §11.1 "2 rps + 抖动"）——随机 sleep [0, jitter) 打散请求
    akshare_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_AKSHARE_JITTER", 0.2))
    # 限流排队等待上限（2026-10-04 修 B：30s→120s）：熔断期分片 failover 堆积下游源，
    # 15s/次节奏下排 2-3 个就超 30s，把等 35s 能成功的请求变成失败。
    # 120s 安全：最坏单源扛 2 分片 ≈ 100 票 × 16.7s ≈ 1670s < Spring 批预算 1800s
    rate_acquire_timeout_seconds: float = field(default_factory=lambda: _env_float("SOROS_RATE_ACQUIRE_TIMEOUT", 120.0))
    # Yahoo（第四源，海外）：0.5 rps + 抖动（外部源间歇性获取铁律）
    rate_yahoo: float = field(default_factory=lambda: _env_float("SOROS_RATE_YAHOO", 0.05))
    yahoo_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_YAHOO_JITTER", 0.2))
    # Tencent（第五源，国内独立转发商，qfq/hfq 服务端自算）：1 rps + 抖动
    rate_tencent: float = field(default_factory=lambda: _env_float("SOROS_RATE_TENCENT", 0.06))
    tencent_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_TENCENT_JITTER", 0.3))
    # SSE 上交所行情云（第六源，源头级校准腿 + 首次建仓灌历史，非日常批量）：0.5 rps + 抖动
    rate_sse: float = field(default_factory=lambda: _env_float("SOROS_RATE_SSE", 0.05))
    sse_jitter_seconds: float = field(default_factory=lambda: _env_float("SOROS_SSE_JITTER", 0.5))

    # ── Router（PLAN §11.1：baostock → akshare → mootdx）──
    router_order: tuple = field(
        default_factory=lambda: tuple(
            os.getenv("SOROS_ROUTER_ORDER", "baostock,akshare,mootdx").replace(" ", "").split(",")
        )
    )

    # ── 多源分片分压（2026-10-04 设计穿透 92 分；改动 4 扩 tencent）──
    # 股票日K按 code 稳态分片：int(code) % len(shard_sources) 决定归属源（归属源优先尝试，
    # 故障仍 failover）。只调顺序不改能力；<2 个源视为关闭分压。禁 hash()（PYTHONHASHSEED
    # 随机 → 重启换归属 → 滚动自愈互相覆盖），必须稳定映射。
    # 默认池含 tencent（qfq 服务端计算口径已验证）；sse 不进池——qfq 不支持，守校准腿本职；env 可覆盖。
    shard_sources: tuple = field(
        default_factory=lambda: tuple(
            os.getenv("SOROS_SHARD_SOURCES", "baostock,akshare,yahoo,tencent").replace(" ", "").split(",")
        )
    )

    # ── netfix（2026-10-04 封禁根因修复：家宽原生 v6 出口被东财拒）──
    # 进程内过滤 AF_INET6，强制数据请求走 IPv4；SOROS_DISABLE_IPV6=0 可关闭
    disable_ipv6: bool = field(
        default_factory=lambda: os.getenv("SOROS_DISABLE_IPV6", "1").strip() not in ("0", "false", "no")
    )

    # 请求校验
    batch_max_codes: int = field(default_factory=lambda: _env_int("SOROS_BATCH_MAX_CODES", 1000))

    def __post_init__(self) -> None:
        if not self.router_order:
            self.router_order = ("baostock", "akshare", "mootdx")
        if len(self.router_order) != len(set(self.router_order)):
            raise ValueError(f"SOROS_ROUTER_ORDER 不能包含重复源: {self.router_order}")
        if len(self.shard_sources) != len(set(self.shard_sources)):
            raise ValueError(f"SOROS_SHARD_SOURCES 不能包含重复源: {self.shard_sources}")


settings = Settings()
