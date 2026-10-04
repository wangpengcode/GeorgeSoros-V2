"""API 契约测试（PLAN §11.1 四端点 + §5.6 响应 JSON 逐字段断言，核心防四端映射漂移）。

- 全部 adapter 用 StubAdapter / 真 adapter + 注入，无真实网络（trading-calendar 也 mock adapter 层）
- daily-bars data[] 每条 key 集合 == PLAN §5.6 11 字段（防漂移核心断言）
- 422 非法参数 / 404 未知路径错误体信封形 {status:"error", error:{code,message}}
  （handlers.py app 级全局 handler 统一注册，生产 main.py 与测试 conftest 同一入口）
"""

from __future__ import annotations

import pandas as pd
import pytest

from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter, SourceError
from helpers import FakeCircuitBreaker, FakeRateLimiter, StubAdapter, make_bar, raise_error

# PLAN §5.6 单根 bar 契约键集合（逐字段，防四端映射漂移核心断言）
BAR_CONTRACT_KEYS = {
    "date", "code", "open", "high", "low", "close",
    "volume", "amount", "change_percent", "turnover", "prev_close",
}
# PLAN §11.1 stock-list 元素契约键集合（Step 5a 增补 ipo_date：BaoStock query_stock_basic 回填）
STOCK_ITEM_KEYS = {"code", "name", "market", "board", "is_st", "delisted", "ipo_date"}
HEALTH_SOURCES = ("baostock", "akshare", "mootdx", "yahoo", "tencent", "sse")


def _default_router():
    """四源均正常的 router（与现网 main.build_router 同构）：baostock 主源返回含 prev_close 的 bar。"""
    baostock = StubAdapter("baostock", bars=lambda *a: [make_bar("600000")], delisted=set())
    akshare = StubAdapter("akshare", bars=lambda *a: [make_bar("600000")])
    mootdx = StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)])
    yahoo = StubAdapter("yahoo", bars=lambda *a: [make_bar("600000")])
    return DataRouter([baostock, akshare, mootdx, yahoo])


# ──────────────────────────────────────────────────────────────────────────────
# /health
# ──────────────────────────────────────────────────────────────────────────────

def test_health_double_mount_same_shape(make_client):
    """/api/v1/health 与 /health 双挂同形（PLAN §5.1 冒烟兼容）。"""
    router = DataRouter([
        StubAdapter("baostock", health="ok"),
        StubAdapter("akshare", health="ok"),
        StubAdapter("mootdx", health="ok"),
        StubAdapter("yahoo", health="ok"),
        StubAdapter("tencent", health="ok"),
        StubAdapter("sse", health="ok"),
    ])
    client = make_client(router)
    r1 = client.get("/api/v1/health")
    r2 = client.get("/health")
    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json() == r2.json(), "双挂同形：/health 与 /api/v1/health 完全一致"
    body = r1.json()
    assert set(body.keys()) == {"status", "sources"}
    assert set(body["sources"].keys()) == set(HEALTH_SOURCES)
    assert body["status"] == "ok"
    assert all(v in ("ok", "degraded", "down") for v in body["sources"].values())


def test_health_degraded_when_any_source_degraded(make_client):
    router = DataRouter([
        StubAdapter("baostock", health="ok"),
        StubAdapter("akshare", health="degraded"),
        StubAdapter("mootdx", health="ok"),
    ])
    body = make_client(router).get("/api/v1/health").json()
    assert body["status"] == "degraded", "任一源 degraded → 整体 degraded"
    assert body["sources"]["akshare"] == "degraded"


def test_health_down_when_any_source_down(make_client):
    router = DataRouter([
        StubAdapter("baostock", health="down"),
        StubAdapter("akshare", health="degraded"),
        StubAdapter("mootdx", health="ok"),
    ])
    body = make_client(router).get("/api/v1/health").json()
    assert body["status"] == "down", "任一源 down → 整体 down"


# ──────────────────────────────────────────────────────────────────────────────
# /daily-bars/batch
# ──────────────────────────────────────────────────────────────────────────────

def test_daily_bars_batch_contract_field_keys(make_client):
    """核心防漂移断言：data[] 每条 JSON key 集合 == PLAN §5.6 11 字段。"""
    client = make_client(_default_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "results", "failed"}
    assert body["status"] == "ok"
    assert body["failed"] == []

    item = body["results"]["600000"]
    assert set(item.keys()) == {"source", "count", "data"}
    assert item["source"] == "baostock", "source 记录实际成功源"
    assert item["count"] == 1
    assert len(item["data"]) == 1

    bar = item["data"][0]
    assert set(bar.keys()) == BAR_CONTRACT_KEYS, (
        "data[] 每条 JSON key 集合必须 == PLAN §5.6 "
        "{date,code,open,high,low,close,volume,amount,change_percent,turnover,prev_close}"
    )
    # 核心字段数值口径抽查（单位契约 §2.4）
    assert bar["code"] == "600000", "裸数字 code"
    assert bar["volume"] == 147484820.0, "volume=股"
    assert bar["amount"] == 1474848200.0, "amount=元"
    assert bar["prev_close"] == 9.84


def test_daily_bars_mootdx_source_still_keeps_prev_close_key(make_client):
    """mootdx 不输出 prev_close → API 层 Bar 模型默认 None 补齐，键集合不变（防漂移核心断言）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=raise_error(SourceError("down"))),
        StubAdapter("akshare", bars=raise_error(SourceError("down"))),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["results"]["600000"]["source"] == "mootdx"
    bar = body["results"]["600000"]["data"][0]
    assert set(bar.keys()) == BAR_CONTRACT_KEYS
    assert bar["prev_close"] is None, "mootdx 不输出 prev_close → API 层输出 null（键集合不变）"


def test_daily_bars_batch_mixed_success_failure(make_client):
    """单股失败不炸整批：1 成 1 败混合，失败进 failed[]，成功仍正常返回。"""
    def bars_fn(code, start, end, adjust):
        if code == "000001":
            raise SourceError("源故障")
        return [make_bar(code)]

    router = DataRouter([
        StubAdapter("baostock", bars=bars_fn, delisted=set()),
        StubAdapter("akshare", bars=bars_fn),
        StubAdapter("mootdx", bars=bars_fn),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000", "000001"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200, "单股失败不炸整批"
    body = resp.json()
    assert set(body["results"].keys()) == {"600000"}
    assert len(body["failed"]) == 1
    assert set(body["failed"][0].keys()) == {"code", "reason"}
    assert body["failed"][0]["code"] == "000001"
    assert "All sources failed" in body["failed"][0]["reason"]


def test_daily_bars_invalid_code_422_envelope(make_client):
    """契约：非法 code → 422 且错误体信封形 {status:"error", error:{code,message}}。

    实现：handlers.py 注册 app 级 RequestValidationError handler，422 信封形
    PARAM_INVALID（生产 main.py 与测试 conftest 同一注册入口，非实现 gap）。
    """
    client = make_client(_default_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["abc"], "start_date": "2026-09-25", "end_date": "2026-09-30",
    })
    assert resp.status_code == 422, "非法 code 必须 422"
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "422 错误体必须信封形 {status,error:{code,message}}"
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}


def test_daily_bars_invalid_date_422_envelope(make_client):
    client = make_client(_default_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000"], "start_date": "not-a-date", "end_date": "2026-09-30",
    })
    assert resp.status_code == 422, "非法日期必须 422"
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "422 错误体必须信封形"
    assert body["status"] == "error"


def test_daily_bars_batch_codes_over_batch_max_422_envelope(monkeypatch, make_client):
    """契约：codes 超过 settings.batch_max_codes → 422 信封形 PARAM_INVALID。

    注入小上限（5）验证 batch_max_codes 配置生效（修复死配置：config.py 有值但路由不校验）。
    错误码走 constants.py ERROR_PARAM_INVALID 单点，非硬编码。
    """
    from config import settings

    monkeypatch.setattr(settings, "batch_max_codes", 5)
    client = make_client(_default_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000", "600001", "600002", "600003", "600004", "600005"],
        "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 422, "codes 超 batch_max_codes 必须 422"
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "422 错误体必须信封形 {status,error:{code,message}}"
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}
    assert body["error"]["code"] == "PARAM_INVALID", "错误码走 constants.py 单点"


# ──────────────────────────────────────────────────────────────────────────────
# /stock-list
# ──────────────────────────────────────────────────────────────────────────────

def _akshare_stock_list_router(df, st_codes, delisted=set()):
    """构造 stock-list router：baostock 侧由 delisted 集合转为 query_stock_basic 行（含 ipo_date）。

    DataRouter.fetch_stock_list 现在用 fetch_stock_basic_rows() 一次取退市+ipo_date（Step 5a），
    StubAdapter 的 delisted 参数已被 stock_basic_rows 取代。
    """
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    akshare._stock_list_df = lambda: df
    akshare._fetch_st_codes = lambda: st_codes
    basic_rows = []
    for code in delisted:
        basic_rows.append({
            "code": f"sh.{code}", "code_name": code, "ipo_date": "2000-01-01",
            "out_date": "", "type": "1", "status": "0",
        })
    return DataRouter([
        StubAdapter("baostock", stock_basic_rows=basic_rows),
        akshare,
        StubAdapter("mootdx"),
    ])


def test_stock_list_contract_filters_and_board_derivation(make_client):
    """stocks[] 元素 key == {code,name,market,board,is_st,delisted}；北交所滤除；board 推导。"""
    df = pd.DataFrame([
        {"code": "600000", "name": "浦发银行"},
        {"code": "688111", "name": "金山办公"},
        {"code": "300750", "name": "宁德时代"},
        {"code": "830799", "name": "北交所83"},
        {"code": "920019", "name": "北交所920"},
    ])
    router = _akshare_stock_list_router(df, st_codes={"300750"})
    client = make_client(router)

    resp = client.get("/api/v1/stock-list")
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "stocks"}

    stocks = body["stocks"]
    codes = [s["code"] for s in stocks]
    assert "830799" not in codes, "北交所 83 前缀滤除"
    assert "920019" not in codes, "北交所 920 新号段滤除"
    assert all(set(s.keys()) == STOCK_ITEM_KEYS for s in stocks), (
        "stocks[] 元素 key 集合必须 == {code,name,market,board,is_st,delisted}"
    )

    by_code = {s["code"]: s for s in stocks}
    assert by_code["688111"]["board"] == "STAR", "688→STAR"
    assert by_code["300750"]["board"] == "GEM", "300→GEM"
    assert by_code["600000"]["board"] == "MAIN"
    assert by_code["600000"]["market"] == "SH"
    assert by_code["300750"]["is_st"] is True, "st_em∪stop_em ST 标记透传"
    assert by_code["600000"]["is_st"] is False
    assert router.adapters["mootdx"].call_counts["stock_list"] == 0, "mootdx 永不参与 stock-list"


def test_stock_list_market_board_query_filter(make_client):
    df = pd.DataFrame([
        {"code": "600000", "name": "浦发银行"},
        {"code": "300750", "name": "宁德时代"},
        {"code": "688111", "name": "金山办公"},
    ])
    router = _akshare_stock_list_router(df, st_codes=set())
    client = make_client(router)

    resp = client.get("/api/v1/stock-list", params={"market": "SH", "board": "MAIN"})
    codes = [s["code"] for s in resp.json()["stocks"]]
    assert "600000" in codes
    assert "300750" not in codes, "创业板 GEM 应被 board=MAIN 过滤"
    assert "688111" not in codes, "科创板 STAR 应被 board=MAIN 过滤"


def test_stock_list_all_fail_503_envelope(make_client):
    router = DataRouter([
        StubAdapter("baostock", stock_basic_rows=raise_error(SourceError("baostock query_stock_basic 失败"))),
        StubAdapter("akshare", stock_list=raise_error(SourceError("akshare 列表失败"))),
        StubAdapter("mootdx"),
    ])
    client = make_client(router)

    resp = client.get("/api/v1/stock-list")
    assert resp.status_code == 503
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}
    assert body["error"]["code"] == "STOCK_LIST_FAILED"


# ──────────────────────────────────────────────────────────────────────────────
# /trading-calendar
# ──────────────────────────────────────────────────────────────────────────────

def test_trading_calendar_contract_and_monotonic(make_client):
    dates = ["2026-09-25", "2026-09-28", "2026-09-29", "2026-09-30"]
    router = DataRouter([
        StubAdapter("baostock"),
        StubAdapter("akshare", calendar=dates),
        StubAdapter("mootdx"),
    ])
    client = make_client(router)

    resp = client.get("/api/v1/trading-calendar")
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"dates"}
    assert body["dates"] == dates, "dates 原样返回"
    assert body["dates"] == sorted(body["dates"]), "dates 单调递增"


def test_trading_calendar_failure_503_envelope(make_client):
    router = DataRouter([
        StubAdapter("baostock"),
        StubAdapter("akshare", calendar=raise_error(SourceError("akshare 交易日历失败"))),
        StubAdapter("mootdx"),
    ])
    client = make_client(router)

    resp = client.get("/api/v1/trading-calendar")
    assert resp.status_code == 503
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}
    assert body["status"] == "error"
    assert body["error"]["code"] == "TRADING_CALENDAR_FAILED"


# ──────────────────────────────────────────────────────────────────────────────
# 未知路径
# ──────────────────────────────────────────────────────────────────────────────

def test_unknown_path_returns_envelope(make_client):
    """契约：未知路径 → 信封形错误 {status:"error", error:{code,message}}。

    实现：handlers.py 注册 app 级 StarletteHTTPException handler，404 信封形
    NOT_FOUND（生产 main.py 与测试 conftest 同一注册入口，非实现 gap）。
    """
    client = make_client(_default_router())
    resp = client.get("/api/v1/nonexistent-path")
    assert resp.status_code == 404
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "未知路径错误体必须信封形"
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}
