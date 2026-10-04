"""Step 5a 新端点契约测试（PLAN §11.1 /fundamentals、§4.8 /board-members、§11.2 /cross-validate）。

- 全部 stub adapter + monkeypatch，无真实网络
- /fundamentals：report_date 校验（YYYYMMDD）、单位亿元×1e8→元、复合列名探底
- /board-members：board_type 校验、{板块名:[codes]} 结构、单板块失败降级
- /daily-bars/cross-validate：baostock+akshare 双源独立抓取、mootdx 永不参与、
  源无数据也占位 {source,count:0,data:[]}
- /stock-list 增补 ipo_date（BaoStock query_stock_basic 一次取退市+ipo_date）
"""

from __future__ import annotations

import pandas as pd
import pytest

import adapters.akshare_adapter as akshare_module

from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter, SourceError
from helpers import FakeCircuitBreaker, FakeRateLimiter, StubAdapter, make_bar, raise_error

FUNDAMENTALS_KEYS = {"code", "revenue", "net_profit"}
CROSS_RESULT_KEYS = {"source", "count", "data", "error"}


# ──────────────────────────────────────────────────────────────────────────────
# /fundamentals（PLAN §11.1）
# ──────────────────────────────────────────────────────────────────────────────

def _fundamentals_router():
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    akshare._sync_fetch_fundamentals = lambda report_date: [
        {"code": "600000", "revenue": 128.5e8, "net_profit": 45.2e8},
        {"code": "300750", "revenue": 3000.0e8, "net_profit": 88.0e8},
    ]
    return DataRouter([
        StubAdapter("baostock"),
        akshare,
        StubAdapter("mootdx"),
    ])


def test_fundamentals_contract_keys(make_client):
    """fundamentals 响应 {status, stocks:[{code,revenue,net_profit}]}，单位元。"""
    client = make_client(_fundamentals_router())
    resp = client.post("/api/v1/fundamentals", json={"report_date": "20241231"})
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "stocks"}
    assert body["status"] == "ok"
    assert len(body["stocks"]) == 2
    assert all(set(s.keys()) == FUNDAMENTALS_KEYS for s in body["stocks"])
    # 单位=元：源亿元 ×1e8 已在 adapter 层换算
    assert body["stocks"][0]["revenue"] == 128.5e8
    assert body["stocks"][1]["net_profit"] == 88.0e8


def test_fundamentals_report_date_validation(make_client):
    """report_date 非 YYYYMMDD → 422 信封 PARAM_INVALID。"""
    client = make_client(_fundamentals_router())
    resp = client.post("/api/v1/fundamentals", json={"report_date": "2024-12-31"})
    assert resp.status_code == 422
    body = resp.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "PARAM_INVALID"


def test_fundamentals_empty_stocks_ok(make_client):
    """报告期无数据（未披露）→ 200 空 stocks（Kotlin 侧跳过不报错，PLAN §11.1 防御）。"""
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    akshare._sync_fetch_fundamentals = lambda report_date: []
    client = make_client(DataRouter([
        StubAdapter("baostock"), akshare, StubAdapter("mootdx"),
    ]))
    resp = client.post("/api/v1/fundamentals", json={"report_date": "20250331"})
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "stocks": []}


def test_fundamentals_adapter_composite_columns(monkeypatch):
    """akshare stock_yjbb_em 复合列名探底：营业总收入-营业总收入 / 净利润-净利润。"""
    df = pd.DataFrame([
        {"股票代码": "600000", "股票简称": "浦发", "营业总收入-营业总收入": 128.5, "净利润-净利润": 45.2},
    ])
    monkeypatch.setattr(akshare_module.ak, "stock_yjbb_em", lambda date: df)
    adapter = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    stocks = adapter._sync_fetch_fundamentals("20241231")
    assert stocks[0]["code"] == "600000"
    assert stocks[0]["revenue"] == 128.5e8, "亿元×1e8=元"
    assert stocks[0]["net_profit"] == 45.2e8


# ──────────────────────────────────────────────────────────────────────────────
# /board-members（PLAN §4.8）
# ──────────────────────────────────────────────────────────────────────────────

def _board_router(boards=None, boards_empty=None):
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    if boards is not None:
        akshare._sync_fetch_board_members = lambda board_type: boards
    if boards_empty is not None:
        akshare._sync_fetch_board_members = boards_empty
    return DataRouter([
        StubAdapter("baostock"), akshare, StubAdapter("mootdx"),
    ])


def test_board_members_contract_keys(make_client):
    """board-members 响应 {status, boards:{板块名:[codes]}, degraded}。"""
    boards = {"小金属": ["600000", "300750"], "半导体": ["688111"]}
    client = make_client(_board_router(boards=boards))
    resp = client.post("/api/v1/board-members", json={"board_type": "industry"})
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "boards", "degraded"}
    assert body["status"] == "ok"
    assert body["boards"] == boards
    assert body["degraded"] is False, "全板块正常 → degraded=false（完整快照）"


def test_board_members_board_type_validation(make_client):
    """board_type 非 industry/concept → 422 信封 PARAM_INVALID。"""
    client = make_client(_board_router(boards={}))
    resp = client.post("/api/v1/board-members", json={"board_type": "sector"})
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "PARAM_INVALID"


def test_board_members_single_board_failure_degrades(monkeypatch, make_client):
    """单板块成分失败 → 跳过该板块不炸整批（§4.8 防御）。"""
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())

    def fake_cons_fn(symbol):
        if symbol == "坏板块":
            raise RuntimeError("东财接口故障")
        return pd.DataFrame([{"代码": "600000"}])

    monkeypatch.setattr(akshare_module.ak, "stock_board_industry_name_em",
                        lambda: pd.DataFrame([{"板块名称": "好板块"}, {"板块名称": "坏板块"}]))
    monkeypatch.setattr(akshare_module.ak, "stock_board_industry_cons_em", fake_cons_fn)
    client = make_client(DataRouter([
        StubAdapter("baostock"), akshare, StubAdapter("mootdx"),
    ]))
    resp = client.post("/api/v1/board-members", json={"board_type": "industry"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["boards"] == {"好板块": ["600000"]}, "坏板块降级跳过，好板块保留"
    assert body["degraded"] is True, "任一板块拉取失败 → degraded=true（消费侧跳过清空防误清全库）"


# ──────────────────────────────────────────────────────────────────────────────
# /daily-bars/cross-validate（PLAN §11.2）
# ──────────────────────────────────────────────────────────────────────────────

def test_cross_validate_baostock_akshare_mootdx_never(make_client):
    """双源交叉验证：baostock+akshare 各抓一次，mootdx 永不参与；results 双源键齐全。"""
    baostock = StubAdapter("baostock", bars=lambda *a: [make_bar("600000", close=10.05)])
    akshare = StubAdapter("akshare", bars=lambda *a: [make_bar("600000", close=10.05)])
    mootdx = StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", close=9.99)])
    client = make_client(DataRouter([baostock, akshare, mootdx]))

    resp = client.post("/api/v1/daily-bars/cross-validate", json={
        "codes": ["600000"], "start_date": "2026-09-30", "end_date": "2026-09-30",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) == {"status", "results", "failed"}
    assert body["status"] == "ok"
    assert body["failed"] == []
    assert set(body["results"].keys()) == {"600000"}

    per_source = body["results"]["600000"]
    assert set(per_source.keys()) == {"baostock", "akshare"}, "双源键必须齐全"
    for s in per_source.values():
        assert set(s.keys()) == CROSS_RESULT_KEYS
        assert s["count"] == 1
    assert mootdx.call_counts["daily_bars"] == 0, "mootdx 永不参与交叉验证"


def test_cross_validate_source_failure_still_keeps_placeholder(make_client):
    """akshare 失败 → results 仍含 akshare 占位 {count:0,data:[]}（Kotlin 按共同日期对齐）。"""
    baostock = StubAdapter("baostock", bars=lambda *a: [make_bar("600000")])
    akshare = StubAdapter("akshare", bars=raise_error(SourceError("akshare 故障")))
    client = make_client(DataRouter([baostock, akshare, StubAdapter("mootdx")]))

    resp = client.post("/api/v1/daily-bars/cross-validate", json={
        "codes": ["600000"], "start_date": "2026-09-30", "end_date": "2026-09-30",
    })
    assert resp.status_code == 200
    per_source = resp.json()["results"]["600000"]
    assert per_source["baostock"]["count"] == 1
    assert per_source["akshare"]["count"] == 0
    assert per_source["akshare"]["data"] == []
    # 部署穿透发现：源失败时占位必须有 error 文案（否则静默 0 行无法区分「无数据」与「故障」，
    # 2026-10-04 东财封禁 + baostock 限流等待超时双双表现为 count=0，排查无门）
    assert "akshare 故障" in per_source["akshare"]["error"], "失败源必须带 error 文案"
    assert per_source["baostock"]["error"] is None, "成功源 error=null"


def test_cross_validate_invalid_code_422(make_client):
    """非股票 code（指数/带前缀/字母）→ 422 信封 PARAM_INVALID。"""
    baostock = StubAdapter("baostock", bars=lambda *a: [make_bar("600000")])
    akshare = StubAdapter("akshare", bars=lambda *a: [make_bar("600000")])
    client = make_client(DataRouter([baostock, akshare, StubAdapter("mootdx")]))
    resp = client.post("/api/v1/daily-bars/cross-validate", json={
        "codes": ["sh000001"], "start_date": "2026-09-30", "end_date": "2026-09-30",
    })
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "PARAM_INVALID"


# ──────────────────────────────────────────────────────────────────────────────
# /stock-list 增补 ipo_date（BaoStock query_stock_basic 一次取退市+ipo_date）
# ──────────────────────────────────────────────────────────────────────────────

def _stock_list_router_with_ipo():
    akshare = AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    akshare._stock_list_df = lambda: pd.DataFrame([
        {"code": "600000", "name": "浦发银行"},
        {"code": "300750", "name": "宁德时代"},
    ])
    akshare._fetch_st_codes = lambda: set()
    basic_rows = [
        {"code": "sh.600000", "code_name": "浦发银行", "ipo_date": "1999-11-10",
         "out_date": "", "type": "1", "status": "1"},
        {"code": "sz.300750", "code_name": "宁德时代", "ipo_date": "2018-06-11",
         "out_date": "", "type": "1", "status": "1"},
    ]
    return DataRouter([
        StubAdapter("baostock", stock_basic_rows=basic_rows),
        akshare,
        StubAdapter("mootdx"),
    ])


def test_stock_list_includes_ipo_date(make_client):
    """stock-list 元素含 ipo_date（YYYY-MM-DD），未匹配到 → null。"""
    client = make_client(_stock_list_router_with_ipo())
    resp = client.get("/api/v1/stock-list")
    assert resp.status_code == 200
    stocks = resp.json()["stocks"]
    by_code = {s["code"]: s for s in stocks}
    assert by_code["600000"]["ipo_date"] == "1999-11-10"
    assert by_code["300750"]["ipo_date"] == "2018-06-11"
    assert all("ipo_date" in s for s in stocks), "ipo_date 键必须恒存在"
