"""soros-data-service 入口（PLAN §5.1：FastAPI + uvicorn，端口 8000）。

- 构建三源 Adapter + 每源独立 CircuitBreaker/TokenBucket + failover DataRouter
- 启动自检：import 链完整（构建 app 时即实例化各 adapter，导入 akshare/baostock/mootdx）
- 端点挂载：根路径（冒烟 curl /health 兼容）+ /api/v1（PLAN §11.1 统一前缀）
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter
from adapters.baostock_adapter import BaostockAdapter
from adapters.mootdx_adapter import MootdxAdapter
from circuit_breaker import CircuitBreaker
from config import settings
from handlers import register_exception_handlers
from ipguard import guard as ipguard, start_guard_thread
from rate_limiter import TokenBucket
from router import create_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("soros-data-service")


def build_router() -> DataRouter:
    """按 §11.1 韧性参数实例化三源 Adapter + 每源独立熔断/令牌桶。"""
    cb_kwargs = dict(
        failure_threshold=settings.cb_failure_threshold,
        open_timeout_seconds=settings.cb_open_timeout_seconds,
    )
    adapters = [
        BaostockAdapter(
            CircuitBreaker("baostock", **cb_kwargs),
            TokenBucket(settings.rate_baostock, jitter=0.0, name="baostock"),
        ),
        AkshareAdapter(
            CircuitBreaker("akshare", **cb_kwargs),
            TokenBucket(
                settings.rate_akshare,
                jitter=settings.akshare_jitter_seconds,  # PLAN §11.1: "AKShare 2 rps + 抖动"
                name="akshare",
            ),
        ),
        MootdxAdapter(
            CircuitBreaker("mootdx", **cb_kwargs),
            TokenBucket(settings.rate_mootdx, jitter=0.0, name="mootdx"),
        ),
    ]
    return DataRouter(adapters)


# 启动自检：构建 app 即完成 import 链 + Adapter 实例化（akshare/baostock/mootdx 全部导入）
data_router = build_router()


@asynccontextmanager
async def lifespan(app: FastAPI):
    start_guard_thread(ipguard)  # IPGuard 守护线程：出口IP轮询 + 封禁探针自愈（daemon）
    ipguard.poll_egress()  # 启动即采基线（首次观测是基线不是"变化"）
    logger.info(
        "soros-data-service 启动自检通过：sources=%s, router_order=%s, shard_sources=%s, "
        "egress_ip=%s, port=%s",
        [a.source_name for a in data_router.adapters.values()],
        list(settings.router_order),
        list(settings.shard_sources),
        ipguard.egress_ip,
        settings.port,
    )
    yield
    logger.info("soros-data-service 关闭")


app = FastAPI(title="soros-data-service", version="0.1.0", lifespan=lifespan)
# PLAN §11.1 错误信封：422/404 全局 handler（覆盖下方根路径与 /api/v1 双挂）
register_exception_handlers(app)

# PLAN §11.1 统一前缀 /api/v1（Kotlin 侧 PythonDataServiceClient 调用入口）
app.include_router(create_router(data_router), prefix="/api/v1")
# 根路径兼容（冒烟 curl /health；与 /api/v1 路由并存，路径无冲突）
app.include_router(create_router(data_router))


# IPGuard 观测端点（独立于 /health：Kotlin strict fail-on-unknown 对健康 JSON 新增字段会炸解析，
# 挂独立路径做到 Python 侧零破坏部署；换IP状态/封禁源/出口IP 变化在此观测）
@app.get("/api/v1/ipguard")
@app.get("/ipguard")
async def ipguard_status():
    return {"status": "ok", "ipguard": ipguard.snapshot()}


if __name__ == "__main__":
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
