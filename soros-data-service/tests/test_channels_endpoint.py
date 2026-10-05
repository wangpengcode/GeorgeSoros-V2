"""渠道台账端点测试（GET /api/v1/channels，CORS 页面展示）。

- 直接单测 channels.get_channels：6 源 StubAdapter router + 隔离 IPGuard → 契约键集合、
  health/breaker 值域、banned null
- 熔断透出：baostock 熔断 open + health down → 该源 health=="down"、breaker=="open"
- IPGuard 封禁透出：封禁达阈值后 → banned {at, egress_ip}
- 真实 app 端到端：TestClient GET /api/v1/channels → 200 形状正确（response_model 校验透出）

时间戳格式 "%Y-%m-%d %H:%M:%S"，与 /ipguard 现状一致（本地墙钟，非 ISO）。
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from adapters.base import DataRouter
from channels import get_channels
from helpers import StubAdapter, make_bar
from ipguard import IPGuard
from models import ChannelsResponse

# PLAN §11.1 channels 每项契约键集合（逐字段，防漂移；2026-10-05 均分流量定稿新增调用计数）
CHANNEL_ITEM_KEYS = {
    "source", "health", "last_success_at", "last_failure_at", "banned", "breaker",
    "total_calls", "empty_results", "failures",
}
HEALTH_VALUES = ("ok", "degraded", "down")
BREAKER_VALUES = ("closed", "open", "half_open")
SOURCE_NAMES = {"baostock", "akshare", "mootdx", "yahoo", "tencent", "sse"}


def _six_source_router() -> DataRouter:
    """6 源均正常（与 main.build_router 同构注册序：baostock/akshare/mootdx/yahoo/tencent/sse）。"""
    return DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("akshare", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
        StubAdapter("yahoo", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("tencent", bars=lambda *a: [make_bar("600000")]),
        StubAdapter("sse", bars=lambda *a: [make_bar("600000")]),
    ])


def _new_guard(**kwargs) -> IPGuard:
    """隔离 IPGuard（不碰全局单例），默认注入假探针/假出口IP，并先采 egress 基线。"""
    defaults = dict(probe_fn=lambda: True, egress_ip_fn=lambda: "1.2.3.4")
    defaults.update(kwargs)
    g = IPGuard(**defaults)
    g.poll_egress()  # egress_ip 基线 "1.2.3.4"（封禁透出 egress_ip 断言用）
    return g


def _by_source(body: dict) -> dict:
    return {c["source"]: c for c in body["channels"]}


# ──────────────────────────────────────────────────────────────────────────────
# 直接单测 channels.get_channels
# ──────────────────────────────────────────────────────────────────────────────

def test_get_channels_contract_shape():
    """契约：6 源正常 → {status:"ok", channels:[...]}，每项键集/值域正确，banned null。"""
    body = get_channels(_six_source_router(), _new_guard())
    assert body["status"] == "ok"
    assert len(body["channels"]) == 6
    assert {c["source"] for c in body["channels"]} == SOURCE_NAMES
    for c in body["channels"]:
        assert set(c.keys()) == CHANNEL_ITEM_KEYS, (
            "每项键集必须 == {source,health,last_success_at,last_failure_at,banned,breaker}"
        )
        assert c["health"] in HEALTH_VALUES, "health 值域 ok|degraded|down"
        assert c["breaker"] in BREAKER_VALUES, "breaker 值域 closed|open|half_open"
        assert c["banned"] is None, "无封禁 → banned null"
        assert c["last_success_at"] is None and c["last_failure_at"] is None, "新 router 台账为空"
        assert c["total_calls"] == 0 and c["empty_results"] == 0 and c["failures"] == 0, "新 router 计数为零"


def test_channel_counters_passthrough():
    """调用计数透出：一次成功取数 → 分配源 total_calls=1；未分配源保持零。"""
    router = _six_source_router()
    result, _ = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None
    body = get_channels(router, _new_guard())
    by = _by_source(body)
    assert by["baostock"]["total_calls"] == 1
    assert by["baostock"]["empty_results"] == 0 and by["baostock"]["failures"] == 0
    for name in ("akshare", "mootdx", "yahoo", "tencent", "sse"):
        assert by[name]["total_calls"] == 0, f"{name} 未被分配，计数为零"


def test_breaker_open_passthrough():
    """熔断透出：baostock 熔断 open + health down → 该源 health=="down"、breaker=="open"。"""
    router = _six_source_router()
    breaker = router.adapters["baostock"].circuit_breaker
    breaker._state = "open"
    breaker._health = "down"
    body = get_channels(router, _new_guard())
    by = _by_source(body)
    assert by["baostock"]["health"] == "down", "熔断 open → health down"
    assert by["baostock"]["breaker"] == "open", "熔断 state 透出 open"
    assert by["akshare"]["health"] == "ok", "其他源不受影响"
    assert by["akshare"]["breaker"] == "closed"


def test_guard_banned_passthrough():
    """IPGuard 封禁透出：baostock 达阈值封禁 → banned == {at, egress_ip}，其他源 null。"""
    router = _six_source_router()
    guard = _new_guard()
    for _ in range(5):
        guard.report_failure("baostock", ConnectionError("RemoteDisconnected"))
    assert guard.is_banned("baostock"), "前置条件：达到阈值已封禁"
    body = get_channels(router, guard)
    by = _by_source(body)
    banned = by["baostock"]["banned"]
    assert banned is not None
    assert set(banned.keys()) == {"at", "egress_ip"}, "banned 键集 == {at, egress_ip}"
    assert banned["egress_ip"] == "1.2.3.4", "egress_ip 透出 poll_egress 基线"
    assert by["akshare"]["banned"] is None, "未封禁源 banned null"


# ──────────────────────────────────────────────────────────────────────────────
# 真实 app 端到端（镜像生产 main.py 的 channels_status 定义）
# ──────────────────────────────────────────────────────────────────────────────

def _channels_client(router: DataRouter, guard: IPGuard) -> TestClient:
    """与生产 main.py 同构：response_model=ChannelsResponse + get_channels(router, guard)。"""
    app = FastAPI(title="soros-data-service-channels-test")

    @app.get("/api/v1/channels", response_model=ChannelsResponse)
    async def channels_status():
        return get_channels(router, guard)

    return TestClient(app)


def test_channels_endpoint_e2e_shape():
    """真实 app 端到端：GET /api/v1/channels → 200 且形状正确（response_model 校验透出）。"""
    client = _channels_client(_six_source_router(), _new_guard())
    resp = client.get("/api/v1/channels")
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "channels"}
    assert body["status"] == "ok"
    assert len(body["channels"]) == 6
    assert set(body["channels"][0].keys()) == CHANNEL_ITEM_KEYS
