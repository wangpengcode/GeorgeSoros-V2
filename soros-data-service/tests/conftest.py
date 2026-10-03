"""pytest 配置：sys.path 引导 + TestClient 工厂 fixture（stub adapter 见 helpers.py）。

- 服务根 soros-data-service/ 加入 sys.path（适配器/路由可 import）
- make_client：给定 DataRouter，构建双挂（/api/v1 + 根路径）FastAPI app，返回 TestClient
- client：三源全 ok stub 的默认 client（专项测试请自行用 make_client 构造自定义 router）
"""

from __future__ import annotations

import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

TESTS_DIR = Path(__file__).resolve().parent
if str(TESTS_DIR) not in sys.path:
    sys.path.insert(0, str(TESTS_DIR))

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from adapters.base import DataRouter
from handlers import register_exception_handlers
from helpers import StubAdapter, make_bar
from router import create_router


@pytest.fixture
def make_router():
    """DataRouter 工厂：三源 adapter 列表 → DataRouter（缺源会抛 ValueError，天然防漏源）。"""

    def _make(adapters):
        return DataRouter(adapters)

    return _make


@pytest.fixture
def make_client():
    """TestClient 工厂：给定 DataRouter，构建双挂（/api/v1 + 根路径）FastAPI app。

    双挂目的：验证 /health 与 /api/v1/health 同形（PLAN §5.1 冒烟兼容）。
    """

    def _make(router: DataRouter) -> TestClient:
        app = FastAPI(title="soros-data-service-test")
        # 与生产 main.py 同一注册入口：422/404 信封 handler 是 app 级（APIRouter 无法携带）
        register_exception_handlers(app)
        app.include_router(create_router(router), prefix="/api/v1")
        app.include_router(create_router(router))
        return TestClient(app)

    return _make


@pytest.fixture
def client(make_client):
    """默认 client：三源全 ok stub，可直接打端点（专项测试请用 make_client 覆写 router）。"""

    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar("600000")], delisted=set()),
        StubAdapter(
            "akshare",
            bars=lambda *a: [make_bar("600000")],
            stock_list=[{
                "code": "600000", "name": "浦发银行", "market": "SH",
                "board": "MAIN", "is_st": False, "delisted": False,
            }],
            calendar=["2026-09-28", "2026-09-29", "2026-09-30"],
        ),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])
    return make_client(router)
