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
from fastapi.middleware.cors import CORSMiddleware

from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter
from adapters.baostock_adapter import BaostockAdapter
from adapters.mootdx_adapter import MootdxAdapter
from adapters.yahoo_adapter import YahooAdapter
from adapters.tencent_adapter import TencentAdapter
from adapters.sse_adapter import SseAdapter
from channels import get_channels
from circuit_breaker import CircuitBreaker
from config import settings
from handlers import register_exception_handlers
from ipguard import guard as ipguard, start_guard_thread
from models import ChannelsResponse
from netfix import disable_ipv4_stack_forcer
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
            TokenBucket(settings.rate_baostock, jitter=0.0, name="baostock",
                        acquire_timeout_seconds=settings.rate_acquire_timeout_seconds),
        ),
        AkshareAdapter(
            CircuitBreaker("akshare", **cb_kwargs),
            TokenBucket(
                settings.rate_akshare,
                jitter=settings.akshare_jitter_seconds,  # PLAN §11.1: "AKShare 2 rps + 抖动"
                name="akshare",
                acquire_timeout_seconds=settings.rate_acquire_timeout_seconds,
            ),
        ),
        MootdxAdapter(
            CircuitBreaker("mootdx", **cb_kwargs),
            TokenBucket(settings.rate_mootdx, jitter=0.0, name="mootdx",
                        acquire_timeout_seconds=settings.rate_acquire_timeout_seconds),
        ),
        # 第四源（可选）：分片默认池成员 + failover 序尾；0.5 rps + 抖动（海外源，间歇性获取）
        YahooAdapter(
            CircuitBreaker("yahoo", **cb_kwargs),
            TokenBucket(settings.rate_yahoo, jitter=settings.yahoo_jitter_seconds, name="yahoo",
                        acquire_timeout_seconds=settings.rate_acquire_timeout_seconds),
        ),
        # 第五源（可选）：国内独立转发商，qfq/hfq 服务端自算；1 rps + 抖动
        TencentAdapter(
            CircuitBreaker("tencent", **cb_kwargs),
            TokenBucket(settings.rate_tencent, jitter=settings.tencent_jitter_seconds, name="tencent",
                        acquire_timeout_seconds=settings.rate_acquire_timeout_seconds),
        ),
        # 第六源（可选）：上交所行情云（源头级校准腿 + 首次建仓灌历史）；0.5 rps + 抖动
        SseAdapter(
            CircuitBreaker("sse", **cb_kwargs),
            TokenBucket(settings.rate_sse, jitter=settings.sse_jitter_seconds, name="sse",
                        acquire_timeout_seconds=settings.rate_acquire_timeout_seconds),
        ),
    ]
    return DataRouter(adapters)


# 启动自检：构建 app 即完成 import 链 + Adapter 实例化（akshare/baostock/mootdx 全部导入）
data_router = build_router()


def apply_cors(app: FastAPI) -> None:
    """CORS 中间件（file:// 页面 Origin=null 场景，Payment-X 渠道管理台类比）。

    设计依据：本地 HTML（file:// 协议）跨域请求 Origin=null——allow_origins=["*"] 且
    allow_credentials=False 时 Starlette 返回 Access-Control-Allow-Origin:*，对任何 Origin
    （含 null）放行；台账观测无 cookie，Credentials 无需放行（且 * 与 credentials 同开
    浏览器会拒绝）。放行方法/头全开，预检 OPTIONS 由中间件自动应答。
    """
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 进程内 IPv4 强制（根因修复：家宽原生 v6 出口被东财拒，requests v6 优先必踩）
    if settings.disable_ipv6:
        disable_ipv4_stack_forcer()
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
# 本地 HTML 页面跨域（file:// Origin=null）：allow_origins=* + credentials=False，见 apply_cors
apply_cors(app)

# PLAN §11.1 统一前缀 /api/v1（Kotlin 侧 PythonDataServiceClient 调用入口）
app.include_router(create_router(data_router), prefix="/api/v1")
# 根路径兼容（冒烟 curl /health；与 /api/v1 路由并存，路径无冲突）
app.include_router(create_router(data_router))


# IPGuard 观测端点（独立于 /health：Kotlin strict fail-on-unknown 对健康 JSON 新增字段会炸解析，
# 挂独立路径做到 Python 侧零破坏部署；换IP状态/封禁源/出口IP 变化在此观测）
@app.get("/api/v1/ipguard")
@app.get("/ipguard")
async def ipguard_status():
    from netfix import snapshot as netfix_snapshot
    body = {"status": "ok", "ipguard": ipguard.snapshot()}
    body.update(netfix_snapshot())
    return body


# 渠道运营台账（CORS 页面展示：每源 最近成功/失败时间 + 封禁 + 熔断）
@app.get("/api/v1/channels", response_model=ChannelsResponse)
async def channels_status():
    return get_channels(data_router, ipguard)


if __name__ == "__main__":
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
