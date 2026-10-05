"""盘中端点契约测试（§19.13.2 盘中监控页 P1-P3 数据面，TDD 先红后绿）。

覆盖：
- IntradaySource 单元：akshare 函数 monkeypatch 注入假 DataFrame（无真实网络），
  校验归一化键契约 + 每个底层 ak 调用一次限流 acquire（禁止连续猛打外部源红线）
- 路由三端点：/intraday/pools（涨停/跌停/炸板池）、/intraday/spot（全市场快照）、
  /intraday/bid-ask（单票五档）——200 契约键 / 422 PARAM_INVALID / 503 错误信封
- 注入式：router 经 create_router(data_router, intraday=stub) 注入桩源，零网络
"""

from __future__ import annotations

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from adapters.base import DataRouter
from handlers import register_exception_handlers
from helpers import StubAdapter
from router import create_router


# ──────────────────────────────────────────────────────────────────────────────
# 契约键集合（防漂移核心断言）
# ──────────────────────────────────────────────────────────────────────────────

POOL_CORE_KEYS = {"code", "name", "change_pct", "latest_price", "amount", "turnover_rate"}
POOL_ITEM_KEYS = POOL_CORE_KEYS | {"lianban", "first_time", "broken_count", "industry"}
SPOT_ITEM_KEYS = {"code", "name", "latest_price", "change_pct", "volume", "amount", "turnover_rate"}
BID_ASK_KEYS = {"code", "bids", "asks"}


# ──────────────────────────────────────────────────────────────────────────────
# 桩：限流记录器 + akshare 假数据
# ──────────────────────────────────────────────────────────────────────────────

class RecordingLimiter:
    """记录 acquire 次数的假限流器（校验「每次底层 ak 调用恰一次 acquire」）。"""

    def __init__(self):
        self.calls = 0

    def acquire(self, timeout=30.0):
        self.calls += 1
        return True


def _zt_pool_df():
    """ak.stock_zt_pool_em 返回列的子集（真实列名，归一化输入样本）。"""
    return pd.DataFrame([
        {
            "代码": "600000", "名称": "浦发银行", "涨跌幅": 10.01, "最新价": 12.5,
            "成交额": 123456789.0, "换手率": 5.2, "连板数": 2, "首次封板时间": "093100",
            "炸板次数": 0, "所属行业": "银行",
        },
        {
            "代码": "000001", "名称": "平安银行", "涨跌幅": 9.98, "最新价": 8.8,
            "成交额": 98765432.0, "换手率": 3.1, "连板数": 1, "首次封板时间": "144500",
            "炸板次数": 1, "所属行业": "银行",
        },
    ])


def _spot_df():
    """ak.stock_zh_a_spot_em 返回列的子集。"""
    return pd.DataFrame([
        {
            "代码": "600519", "名称": "贵州茅台", "最新价": 1500.0, "涨跌幅": 1.25,
            "成交量": 20000, "成交额": 3000000000.0, "换手率": 0.31,
        },
    ])


def _bid_ask_df():
    """ak.stock_bid_ask_em 返回 item/value 两列（真实结构）。"""
    rows = []
    for i in range(5, 0, -1):
        rows.append({"item": f"卖{i}价", "value": 10.0 + i * 0.01})
        rows.append({"item": f"卖{i}量", "value": 100 * i})
    for i in range(1, 6):
        rows.append({"item": f"买{i}价", "value": 10.0 - i * 0.01})
        rows.append({"item": f"买{i}量", "value": 200 * i})
    return pd.DataFrame(rows)


class FakeIntraday:
    """注入 router 的桩盘中源（路由测试用，零 akshare 依赖）。"""

    source_name = "intraday-stub"

    def __init__(self, pools=None, spot=None, bid_ask=None):
        self._pools = pools or {"limit_up": [], "limit_down": [], "broken": []}
        self._spot = spot or []
        self._bid_ask = bid_ask or {"code": "600519", "bids": [], "asks": []}

    def fetch_pools(self, date_yyyymmdd):
        return self._pools

    def fetch_spot(self):
        return self._spot

    def fetch_bid_ask(self, code):
        if code == "BAD":
            raise RuntimeError("外部源故障")
        return self._bid_ask


def _client(intraday) -> TestClient:
    router = DataRouter([
        StubAdapter("baostock"), StubAdapter("akshare"), StubAdapter("mootdx"),
        StubAdapter("yahoo"), StubAdapter("tencent"), StubAdapter("sse"),
    ])
    app = _make_app(router, intraday)
    return TestClient(app)


def _make_app(router, intraday):
    from fastapi import FastAPI
    app = FastAPI(title="test-intraday")
    register_exception_handlers(app)
    app.include_router(create_router(router, intraday=intraday), prefix="/api/v1")
    app.include_router(create_router(router, intraday=intraday))
    return app


# ──────────────────────────────────────────────────────────────────────────────
# IntradaySource 单元（monkeypatch akshare，无真实网络）
# ──────────────────────────────────────────────────────────────────────────────

def test_fetch_pools_normalizes_and_throttles(monkeypatch):
    """涨停/跌停/炸板三池归一化 + 每个 ak 调用恰一次 acquire（3 次）。"""
    import adapters.intraday_source as mod

    limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=limiter)

    def _fail(*a, **kw):  # pragma: no cover - 跌停/炸板池样本同涨停池形状
        return _zt_pool_df()

    monkeypatch.setattr(mod.ak, "stock_zt_pool_em", _fail)
    monkeypatch.setattr(mod.ak, "stock_zt_pool_dtgc_em", _fail)
    monkeypatch.setattr(mod.ak, "stock_zt_pool_zbgc_em", _fail)

    payload = src.fetch_pools("20260930")

    assert limiter.calls == 3, "三个池各一次底层 ak 调用 → 恰 3 次 acquire"
    assert set(payload) == {"limit_up", "limit_down", "broken"}
    for pool_name, rows in payload.items():
        assert len(rows) == 2, f"{pool_name} 归一化 2 行"
        for item in rows:
            assert POOL_CORE_KEYS <= set(item), f"{pool_name} 核心键齐"
            assert item["code"] in ("600000", "000001"), "代码归一化"


def test_fetch_spot_normalizes_and_throttles(monkeypatch):
    """spot 快照归一化 + 恰一次 acquire。"""
    import adapters.intraday_source as mod

    limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=limiter)
    monkeypatch.setattr(mod.ak, "stock_zh_a_spot_em", lambda: _spot_df())

    stocks = src.fetch_spot()

    assert limiter.calls == 1, "spot 一次底层 ak 调用 → 恰 1 次 acquire"
    assert len(stocks) == 1
    assert set(stocks[0]) == SPOT_ITEM_KEYS, "spot 键契约逐字段"
    assert stocks[0]["code"] == "600519"
    assert stocks[0]["volume"] == 20000 * 100, "成交量 手×100→股（与日K口径一致）"


def test_fetch_bid_ask_parses_five_levels(monkeypatch):
    """五档解析：item/value 行 → bids/asks 各 5 档 [价,量]。"""
    import adapters.intraday_source as mod

    limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=limiter)
    monkeypatch.setattr(mod.ak, "stock_bid_ask_em", lambda symbol: _bid_ask_df())

    payload = src.fetch_bid_ask("600519")

    assert limiter.calls == 1, "bid-ask 一次底层 ak 调用 → 恰 1 次 acquire"
    assert BID_ASK_KEYS <= set(payload)
    assert len(payload["bids"]) == 5 and len(payload["asks"]) == 5
    assert payload["bids"][0] == [9.99, 200], "买一 [价,量]"
    assert payload["asks"][0] == [10.01, 100], "卖一 [价,量]"


def test_rate_limiter_exhaustion_raises(monkeypatch):
    """限流 acquire 失败 → 不发起外部调用（零容忍空转红线）。"""
    import adapters.intraday_source as mod

    class FullLimiter:
        def acquire(self, timeout=30.0):
            return False

    src = mod.IntradaySource(rate_limiter=FullLimiter())
    monkeypatch.setattr(mod.ak, "stock_zh_a_spot_em", lambda: pytest.fail("限流失败不得发起外部调用"))

    with pytest.raises(Exception):
        src.fetch_spot()


# ──────────────────────────────────────────────────────────────────────────────
# 新浪备用源（2026-10-05 转发商出口 IP 被东财 push2 拒连事故：spot/bid-ask 单源必须降级备源）
# ──────────────────────────────────────────────────────────────────────────────

class _FakeSinaResponse:
    def __init__(self, payload: bytes, status=200):
        self._payload = payload
        self.status_code = status

    @property
    def content(self):
        return self._payload

    @property
    def text(self):
        return self._payload.decode("gbk")


def test_fetch_spot_falls_back_to_sina(monkeypatch):
    """东财 spot 故障 → 新浪 Market_Center 分页兜底；每页恰一次新浪桶 acquire。"""
    import adapters.intraday_source as mod

    em_limiter = RecordingLimiter()
    sina_limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=em_limiter, sina_rate_limiter=sina_limiter)

    def _em_fail():
        raise RuntimeError("em down")

    page1 = [
        {"code": "600519", "name": "贵州茅台", "trade": "1500.000", "changepercent": 1.25,
         "volume": 2000000, "amount": 3000000000, "turnoverratio": 0.31},
    ]
    page2 = []  # 空页终止
    calls = {"n": 0}

    def _fake_get(url, timeout=0, headers=None):
        calls["n"] += 1
        return _FakeSinaResponse(__import__("json").dumps(page1 if calls["n"] == 1 else page2).encode("utf-8"))

    monkeypatch.setattr(mod.ak, "stock_zh_a_spot_em", _em_fail)
    monkeypatch.setattr(mod.requests, "get", _fake_get)

    stocks = src.fetch_spot()

    assert em_limiter.calls == 1, "先取东财桶一次（失败后走备源）"
    assert sina_limiter.calls == 2, "新浪两页（含终止空页）各一次 acquire"
    assert len(stocks) == 1
    assert set(stocks[0]) == SPOT_ITEM_KEYS, "备源输出与主源同键契约"
    assert stocks[0]["code"] == "600519" and stocks[0]["latest_price"] == 1500.0
    assert stocks[0]["volume"] == 2000000, "新浪成交量已是股口径，不乘乘子"


def test_fetch_bid_ask_falls_back_to_sina(monkeypatch):
    """东财五档故障 → 新浪 hq.sinajs.cn 单票兜底（GBK 解码，买1→买5/卖1→卖5）。"""
    import adapters.intraday_source as mod

    em_limiter = RecordingLimiter()
    sina_limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=em_limiter, sina_rate_limiter=sina_limiter)

    # 真实字段序：0名称..8成交量,9成交额, 10买一量,11买一价,...,20卖一量,21卖一价,...
    fields = ["贵州茅台", "1500.000", "1490.000", "1495.000", "1510.000", "1480.000",
              "1494.990", "1495.010", "3833098", "4797246636.000"]
    for i in range(1, 6):
        fields += [str(200 * i), f"{1495.0 - i * 0.01:.3f}"]   # 买i量,买i价
    for i in range(1, 6):
        fields += [str(100 * i), f"{1495.0 + i * 0.01:.3f}"]   # 卖i量,卖i价
    fields += ["2026-09-30", "15:00:00", "00"]
    payload = ('var hq_str_sh600519="' + ",".join(fields) + '";').encode("gbk")

    def _em_fail(symbol):
        raise RuntimeError("em down")

    def _fake_get(url, timeout=0, headers=None):
        assert "Referer" in headers, "新浪必须带 Referer（否则 401）"
        assert "sh600519" in url, "代码→sh/sz 前缀映射"
        return _FakeSinaResponse(payload)

    monkeypatch.setattr(mod.ak, "stock_bid_ask_em", _em_fail)
    monkeypatch.setattr(mod.requests, "get", _fake_get)

    out = src.fetch_bid_ask("600519")

    assert em_limiter.calls == 1 and sina_limiter.calls == 1, "东财一attempt+新浪一请求各一次 acquire"
    assert BID_ASK_KEYS <= set(out)
    assert out["bids"][0] == [1494.99, 200], "买一 [价,量]"
    assert out["asks"][0] == [1495.01, 100], "卖一 [价,量]"
    assert len(out["bids"]) == 5 and len(out["asks"]) == 5


def test_sina_spot_page_cap_safety(monkeypatch):
    """新浪分页死循环防线：页数超上限强制终止（空页判定失效也不猛打）。"""
    import adapters.intraday_source as mod

    sina_limiter = RecordingLimiter()
    src = mod.IntradaySource(rate_limiter=RecordingLimiter(), sina_rate_limiter=sina_limiter)

    def _em_fail():
        raise RuntimeError("em down")

    monkeypatch.setattr(mod.ak, "stock_zh_a_spot_em", _em_fail)
    monkeypatch.setattr(
        mod.requests, "get",
        lambda url, timeout=0, headers=None: _FakeSinaResponse('[{"code":"600519","name":"x","trade":"1","changepercent":0,"volume":1,"amount":1,"turnoverratio":0}]'.encode("utf-8")),
    )

    stocks = src.fetch_spot()
    assert sina_limiter.calls <= mod.MAX_SINA_SPOT_PAGES, "页数硬上限内终止"
    assert len(stocks) == mod.MAX_SINA_SPOT_PAGES


# ──────────────────────────────────────────────────────────────────────────────
# 路由端点（注入桩源，零网络）
# ──────────────────────────────────────────────────────────────────────────────

def test_pools_endpoint_contract():
    """/intraday/pools 200 契约 + 双挂同形。"""
    intraday = FakeIntraday(pools={
        "limit_up": [{"code": "600000", "name": "浦发银行", "change_pct": 10.01,
                      "latest_price": 12.5, "amount": 1.0, "turnover_rate": 5.2}],
        "limit_down": [], "broken": [],
    })
    client = _client(intraday)
    for path in ("/api/v1/intraday/pools", "/intraday/pools"):
        resp = client.get(path, params={"date": "2026-09-30"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok" and body["date"] == "20260930", "日期回显归一化 YYYYMMDD"
        assert body["limit_up"][0]["code"] == "600000"


def test_pools_endpoint_rejects_bad_date():
    """非法日期格式 → 422 PARAM_INVALID 信封。"""
    client = _client(FakeIntraday())
    resp = client.get("/api/v1/intraday/pools", params={"date": "not-a-date"})
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "PARAM_INVALID"


def test_spot_endpoint_contract():
    """/intraday/spot 200 契约。"""
    intraday = FakeIntraday(spot=[{"code": "600519", "name": "贵州茅台", "latest_price": 1500.0,
                                   "change_pct": 1.25, "volume": 2000000, "amount": 3e9,
                                   "turnover_rate": 0.31}])
    client = _client(intraday)
    resp = client.get("/api/v1/intraday/spot")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok" and body["count"] == 1
    assert set(body["stocks"][0]) == SPOT_ITEM_KEYS


def test_bid_ask_endpoint_contract():
    """/intraday/bid-ask 200 契约。"""
    intraday = FakeIntraday(bid_ask={"code": "600519", "bids": [[1.0, 2]], "asks": [[3.0, 4]]})
    client = _client(intraday)
    resp = client.get("/api/v1/intraday/bid-ask", params={"code": "600519"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok" and body["code"] == "600519"
    assert body["bids"] == [[1.0, 2]]


def test_bid_ask_endpoint_requires_code():
    """缺 code → 422。"""
    client = _client(FakeIntraday())
    resp = client.get("/api/v1/intraday/bid-ask")
    assert resp.status_code == 422


def test_bid_ask_endpoint_error_envelope():
    """源故障 → 503 INTRADAY_FAILED 错误信封（不裸 500）。"""
    client = _client(FakeIntraday(bid_ask=None) if False else _BadIntraday())
    resp = client.get("/api/v1/intraday/bid-ask", params={"code": "BAD"})
    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "INTRADAY_FAILED"


class _BadIntraday(FakeIntraday):
    def fetch_bid_ask(self, code):
        raise RuntimeError("外部源故障")
