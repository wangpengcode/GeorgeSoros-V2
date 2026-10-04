"""CORS 中间件测试（file:// Origin=null 场景，Payment-X 渠道管理台类比）。

设计依据（PLAN §11.1 + main.apply_cors docstring）：本地 HTML（file:// 协议）跨域请求
Origin=null——allow_origins=["*"] 且 allow_credentials=False 时 Starlette 返回
Access-Control-Allow-Origin:*，对任何 Origin（含 null）放行；放行方法/头全开，
预检 OPTIONS 由中间件自动应答。

import main 安全：build_router() 构造各 adapter 不触发网络（登录/拉取均在首次 fetch 时），
纯内存构建。
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from main import apply_cors


def _cors_client() -> TestClient:
    """自建 app + apply_cors（镜像生产 main.py 的 CORS 配置 + 一个最小 channels 路由）。"""
    app = FastAPI(title="cors-test")
    apply_cors(app)

    @app.get("/api/v1/channels")
    def channels():
        return {"status": "ok", "channels": []}

    return TestClient(app)


def test_preflight_options_returns_cors_headers():
    """预检 OPTIONS /api/v1/channels（Origin=null + Access-Control-Request-Method=GET）→ 200 且 CORS 头正确。"""
    client = _cors_client()
    resp = client.options(
        "/api/v1/channels",
        headers={
            "Origin": "null",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert resp.status_code == 200
    assert resp.headers.get("access-control-allow-origin") == "*", "allow_all_origins 时预检回显 *"
    assert "access-control-allow-methods" in resp.headers, "预检必须带 access-control-allow-methods"
    assert "GET" in resp.headers["access-control-allow-methods"], "放行方法全开（ALL_METHODS）需含 GET"


def test_get_with_origin_null_returns_allow_origin():
    """GET 带 Origin=null → 响应头 access-control-allow-origin == "*"（file:// 页面跨域放行）。"""
    client = _cors_client()
    resp = client.get("/api/v1/channels", headers={"Origin": "null"})
    assert resp.status_code == 200
    assert resp.headers.get("access-control-allow-origin") == "*", "allow_origins=* 对任何 Origin（含 null）放行"
