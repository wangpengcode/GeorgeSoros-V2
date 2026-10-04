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

from adapters.base import DataRouter, SourceError
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
    assert "All sources failed" in body["failed"][0]["reason"]


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
