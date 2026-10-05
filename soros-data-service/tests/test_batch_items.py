"""/daily-bars/batch items 逐段窗口模式 pytest（Architect 设计 §6/§7.4 用例 34-40）。

items 模式（重跑计划）：每 item = {code, start_date, end_date} 独立拉取窗口；
响应契约不变（results 按 code 键、failed[]）。铁律：
- items 与 codes+dates 二选一（XOR），混用 422 PARAM_INVALID；
- 请求批内 code 不得重复（响应按 code 键，同批重复会造成归属歧义）→ 422；
- 0 行且非 failed → results[code] count=0 data=[]（停牌语义，非 failed）；
- len(items) > batch_max_codes → 422。

红线：当前 models.DailyBarsBatchRequest 无 items 字段（extra 忽略）→ items 单发请求缺 codes
被 pydantic 拒 422，多段 0 行/失败路径未实现 → 用例 34/36/37/38 预期红；40 向后兼容保持绿。
"""

from __future__ import annotations

from datetime import date, timedelta

from adapters.base import CapabilityError, DataRouter, SourceError
from config import settings
from helpers import StubAdapter, make_bar

PARAM_INVALID = "PARAM_INVALID"


def _window_router():
    """四源均正常：adapter 按请求窗口逐自然日返回 bar（验证 items 逐段窗口生效）。"""

    def bars(code, start_date, end_date, adjust):
        days = []
        d = date.fromisoformat(start_date)
        stop = date.fromisoformat(end_date)
        while d <= stop:
            days.append(make_bar(code, date=d.isoformat()))
            d += timedelta(days=1)
        return days

    return DataRouter([
        StubAdapter("baostock", bars=bars, delisted=set()),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])


def _assert_422_envelope(resp, code=PARAM_INVALID):
    """422 信封形 {status:"error", error:{code,message}}（§11.1）。"""
    assert resp.status_code == 422
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "422 错误体必须信封形 {status,error:{code,message}}"
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}
    assert body["error"]["code"] == code, "错误码走 constants.py PARAM_INVALID 单点"


# ──────────────────────────────────────────────────────────────────────────────
# 34. items 与 codes+dates 混用 → 422（XOR 二选一铁律）
# ──────────────────────────────────────────────────────────────────────────────

def test_items_and_codes_mixed_422(make_client):
    client = make_client(_window_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-30"}],
        "codes": ["600000"],
        "start_date": "2026-09-25",
        "end_date": "2026-09-30",
    })
    _assert_422_envelope(resp)


# ──────────────────────────────────────────────────────────────────────────────
# 35. items 中 code 重复 → 422（同批 code 唯一铁律）
# ──────────────────────────────────────────────────────────────────────────────

def test_items_duplicate_code_422(make_client):
    client = make_client(_window_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [
            {"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-26"},
            {"code": "600000", "start_date": "2026-09-28", "end_date": "2026-09-30"},
        ],
    })
    _assert_422_envelope(resp)


# ──────────────────────────────────────────────────────────────────────────────
# 36. items 多段（不同 code 不同窗口）→ results 按 code 键，data 范围各自正确
# ──────────────────────────────────────────────────────────────────────────────

def test_items_multi_segment_different_windows(make_client):
    client = make_client(_window_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [
            {"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-26"},
            {"code": "000001", "start_date": "2026-09-28", "end_date": "2026-09-30"},
        ],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    assert set(body["results"].keys()) == {"600000", "000001"}, "results 按 code 键"

    item1 = body["results"]["600000"]
    assert item1["count"] == 2
    assert [b["date"] for b in item1["data"]] == ["2026-09-25", "2026-09-26"], "600000 数据范围=自身窗口"

    item2 = body["results"]["000001"]
    assert item2["count"] == 3
    assert [b["date"] for b in item2["data"]] == ["2026-09-28", "2026-09-29", "2026-09-30"], "000001 数据范围=自身窗口"


# ──────────────────────────────────────────────────────────────────────────────
# 37. items 单段 0 行且非 failed → results[code] count=0 data=[]（停牌占位）
# ──────────────────────────────────────────────────────────────────────────────

def test_items_single_zero_rows_not_failed(make_client):
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [], delisted=set()),
        StubAdapter("akshare", bars=lambda *a: []),
        StubAdapter("mootdx", bars=lambda *a: []),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-30"}],
    })
    assert resp.status_code == 200, "0 行不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == [], "0 行非 failed（停牌语义，供 Kotlin 侧 verified-empty 记录）"
    assert "600000" in body["results"], "0 行仍进 results 占位（count=0 data=[]）"
    assert body["results"]["600000"]["count"] == 0
    assert body["results"]["600000"]["data"] == []


# ──────────────────────────────────────────────────────────────────────────────
# 38. items 单码失败 → failed[{code,reason}]，results 无该 code
# ──────────────────────────────────────────────────────────────────────────────

def test_items_single_failure_goes_failed(make_client):
    def bars(code, start_date, end_date, adjust):
        raise SourceError("源故障")

    router = DataRouter([
        StubAdapter("baostock", bars=bars, delisted=set()),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-30"}],
    })
    assert resp.status_code == 200, "单股失败不炸整批"
    body = resp.json()
    assert body["status"] == "ok"
    assert "600000" not in body["results"]
    assert len(body["failed"]) == 1
    assert body["failed"][0]["code"] == "600000"
    assert "源故障" in body["failed"][0]["reason"], "reason 归因被分配源（单票单源语义）"


# ──────────────────────────────────────────────────────────────────────────────
# 39. len(items) > batch_max_codes → 422 PARAM_INVALID
# ──────────────────────────────────────────────────────────────────────────────

def test_items_over_batch_max_codes_422(monkeypatch, make_client):
    monkeypatch.setattr(settings, "batch_max_codes", 2)
    client = make_client(_window_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [
            {"code": "600000", "start_date": "2026-09-25", "end_date": "2026-09-26"},
            {"code": "600001", "start_date": "2026-09-25", "end_date": "2026-09-26"},
            {"code": "600002", "start_date": "2026-09-25", "end_date": "2026-09-26"},
        ],
    })
    _assert_422_envelope(resp)


# ──────────────────────────────────────────────────────────────────────────────
# 40. items 空 + codes+dates 路径 → 向后兼容（原契约测试不回归）
# ──────────────────────────────────────────────────────────────────────────────

# ──────────────────────────────────────────────────────────────────────────────
# 41-44. 全源空结果判定（2026-10-04 生产事故③）：证据分类而非裸字符串比对
#
# 生产链（000007 停牌洞段）：akshare/baostock/tencent 空结果（正面证据）+ mootdx/sse
# 不支持该复权（结构性无证据）+ yahoo 429（限流无证据）→ 原判定要求所有 part 以
# "空结果" 结尾 → 误归 failed[] → 停牌段永远无法记 verified-empty，每次重跑空拉。
# 证据分类语义：
# - 空结果 = 源成功查询且确认无数据（正面证据）；
# - 不支持该复权 = 结构性无证据（该源无法服务此复权，不构成反证）；
# - 429/Too Many Requests = 限流无证据（源未下判断）；
# - 其余（超时/网络/异常）= 真实故障 → 整体归 failed（绝不误判停牌）。
# 判停牌充要：≥1 个空结果正面证据 且 0 个真实故障。
# ──────────────────────────────────────────────────────────────────────────────

def _suspend_like_router(akshare_bars=None):
    return DataRouter([
        StubAdapter("baostock", bars=lambda *a: [], delisted=set()),
        StubAdapter("akshare", bars=akshare_bars if akshare_bars is not None else (lambda *a: [])),
        StubAdapter("mootdx", bars=CapabilityError("mootdx: 不支持 qfq 复权（mootdx/TDX 仅提供不复权日K）")),
    ])


def test_items_suspend_mixed_capability_and_empty_placeholder(make_client):
    # 41. 生产链复现：空结果 ×2 + 不支持该复权 → 占位非 failed（原判定误归 failed）
    client = make_client(_suspend_like_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "000007", "start_date": "2022-06-20", "end_date": "2022-06-30"}],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["failed"] == [], "空结果+能力缺失混合必须判停牌占位，不得归 failed"
    assert body["results"]["000007"]["count"] == 0
    assert body["results"]["000007"]["data"] == []


def test_items_suspend_with_rate_limit_429_placeholder(make_client):
    # 42. 混入 429 限流（源未下判断、无证据）→ 仍有 ≥1 空结果正面证据 → 占位
    def rate_limited(*a):
        raise SourceError("yahoo 拉取失败: 429 Client Error: Too Many Requests")

    client = make_client(_suspend_like_router(akshare_bars=rate_limited))
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "000007", "start_date": "2023-04-24", "end_date": "2023-05-05"}],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["failed"] == [], "429 限流非反证，空结果正面证据在即判停牌占位"
    assert body["results"]["000007"]["count"] == 0


def test_items_suspend_with_circuit_breaker_open_placeholder(make_client):
    # 45. 混入「熔断 open」（SourceUnavailableError：未发起请求、源未下判断，base.py 错误分类学）
    # → 无证据类，与 429 同理；≥1 空结果正面证据在场即判停牌占位
    # （2026-10-04 生产：000008/000016 段 tencent/baostock/akshare 空结果 ×3 + yahoo 熔断 open → 误归 failed）
    def cb_open(*a):
        raise SourceError("yahoo: 熔断 open")

    client = make_client(_suspend_like_router(akshare_bars=cb_open))
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "000008", "start_date": "2022-07-01", "end_date": "2022-07-10"}],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["failed"] == [], "熔断 open 非反证（源未发起请求），空结果正面证据在即判停牌占位"
    assert body["results"]["000008"]["count"] == 0


def test_items_real_failure_with_empty_goes_failed(make_client):
    # 43. 真实故障（网络超时）→ 保守归 failed，绝不占位（单票单源：分配源故障即 failed）。
    # 轮转语义下「别的源故障 + 本源空」不可同票同轮观测——K=2 空票判据由 Spring 侧
    # empty_sources≥2 把关，单源空票占位不会推水位（等价保守性）。
    def timeout(*a):
        raise SourceError("baostock 网络超时")

    client = make_client(DataRouter([
        StubAdapter("baostock", bars=timeout, delisted=set()),
        StubAdapter("akshare", bars=lambda *a: []),
        StubAdapter("mootdx", bars=lambda *a: []),
    ]))
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "000007", "start_date": "2022-06-20", "end_date": "2022-06-30"}],
    })
    body = resp.json()
    assert "000007" not in body["results"], "真实故障在场不得占位"
    assert len(body["failed"]) == 1 and body["failed"][0]["code"] == "000007"


def test_items_all_capability_no_positive_evidence_goes_failed(make_client):
    # 44. 全部「不支持该复权」（零正面证据）→ failed：没有任何源真正查过，不得判停牌
    def capability(*a):
        raise CapabilityError("不支持 qfq 复权")

    client = make_client(DataRouter([
        StubAdapter("baostock", bars=capability, delisted=set()),
        StubAdapter("akshare", bars=capability),
        StubAdapter("mootdx", bars=capability),
    ]))
    resp = client.post("/api/v1/daily-bars/batch", json={
        "items": [{"code": "000007", "start_date": "2022-06-20", "end_date": "2022-06-30"}],
    })
    body = resp.json()
    assert "000007" not in body["results"], "零正面证据不得判停牌"
    assert len(body["failed"]) == 1


def test_items_empty_codes_path_backward_compat(make_client):
    client = make_client(_window_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000"],
        "start_date": "2026-09-25",
        "end_date": "2026-09-26",
        "adjust": "qfq",
    })
    assert resp.status_code == 200, "codes+dates 路径向后兼容"
    body = resp.json()
    assert body["status"] == "ok"
    assert set(body["results"].keys()) == {"600000"}
    assert body["failed"] == []
    assert [b["date"] for b in body["results"]["600000"]["data"]] == ["2026-09-25", "2026-09-26"]
