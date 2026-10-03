"""PLAN Step 4 指数日K支持测试（禁真实网络：monkeypatch 源调用/requests）。

覆盖（协调者清单）：
1. 校验器放行/拒绝用例：裸数字股票 ∨ 带前缀指数（sh000001）两种形态混收；
   非法形态（5位、大写、超长、非数字后缀）422/ValidationError
2. 指数转换逻辑：sina→11 字段契约（change_percent 用 prev_close 现算、amount/turnover=0、
   code 带前缀）；区间前一行作首 bar prev_close；sina 失败→腾讯兜底（volume 手×100）；
   全子源失败→SourceError
3. 端点契约 11 字段：POST /api/v1/daily-bars/batch ["sh000001"] → data[] 键集合==PLAN §5.6
4. 指数与股票混批 failover：stock 走 baostock→akshare→mootdx，指数仅走 akshare（mootdx 永不参与）
"""

from __future__ import annotations

import pandas as pd
import pytest
from pydantic import ValidationError

import adapters.akshare_adapter as akshare_module
import requests

from adapters.akshare_adapter import AkshareAdapter
from adapters.base import DataRouter, SourceError
from constants import TENCENT_INDEX_VOLUME_MULTIPLIER
from helpers import FakeCircuitBreaker, FakeRateLimiter, StubAdapter, make_bar, raise_error
from models import DailyBarsBatchRequest

# PLAN §5.6 单根 bar 契约键集合（指数 bar 同样必须 11 字段）
BAR_CONTRACT_KEYS = {
    "date", "code", "open", "high", "low", "close",
    "volume", "amount", "change_percent", "turnover", "prev_close",
}


def make_index_bar(code="sh000001", date="2026-09-30", open_=3839.25, high=3851.22,
                   low=3833.09, close=3842.19, volume=41456024700.0,
                   change_percent=0.31, prev_close=3830.45):
    """构造一条指数 bar dict（11 字段契约，amount=0/turnover=0）。"""
    return {
        "date": date, "code": code,
        "open": open_, "high": high, "low": low, "close": close,
        "volume": volume, "amount": 0.0,
        "change_percent": change_percent, "turnover": 0.0,
        "prev_close": prev_close,
    }


# ──────────────────────────────────────────────────────────────────────────────
# 1. 校验器：放行 / 拒绝
# ──────────────────────────────────────────────────────────────────────────────

def _req(codes, start="2026-09-01", end="2026-09-30"):
    return DailyBarsBatchRequest(codes=codes, start_date=start, end_date=end)


def test_validator_accepts_index_code():
    """带前缀指数 code（sh000001 / sz399001）必须放行。"""
    assert _req(["sh000001"]).codes == ["sh000001"]
    assert _req(["sz399001"]).codes == ["sz399001"]
    assert _req(["sh000300", "sh000905", "sz399006"]).codes == ["sh000300", "sh000905", "sz399006"]


def test_validator_accepts_mixed_stock_and_index():
    """股票裸数字 + 指数带前缀混收。"""
    req = _req(["600000", "sh000001", "000001", "sz399006"])
    assert req.codes == ["600000", "sh000001", "000001", "sz399006"]


def test_validator_rejects_invalid_index_forms():
    """非法指数形态必须拒绝：5位后缀/大写/超长/非数字/裸6位带字符。"""
    invalid = ["sh1", "sh00000", "SH000001", "sh0000010", "sh00000x", "sh abcde"]
    for code in invalid:
        with pytest.raises(ValidationError):
            _req([code])
    # 裸数字 5 位（既非股票也非指数）
    with pytest.raises(ValidationError):
        _req(["60000"])
    # 字母
    with pytest.raises(ValidationError):
        _req(["abc"])


def test_validator_strips_whitespace():
    assert _req([" 600000 "]).codes == ["600000"]


def test_validator_rejects_empty():
    with pytest.raises(ValidationError):
        _req([])


# ──────────────────────────────────────────────────────────────────────────────
# 2. 指数转换逻辑
# ──────────────────────────────────────────────────────────────────────────────

class FakeResp:
    """模拟腾讯 fqkline 响应。"""

    def __init__(self, day_rows):
        self._day_rows = day_rows

    def raise_for_status(self):
        pass

    def json(self):
        return {"data": {"sh000001": {"day": self._day_rows}}}


def _adapter():
    return AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())


def test_sina_index_conversion_11_fields_and_change_pct(monkeypatch):
    """sina stock_zh_index_daily → 11 字段；change_percent 用 prev_close 现算；amount/turnover=0。"""
    df = pd.DataFrame([
        {"date": "2026-09-29", "open": 3816.15, "high": 3843.84, "low": 3810.81,
         "close": 3830.45, "volume": 39947339100},
        {"date": "2026-09-30", "open": 3839.25, "high": 3851.22, "low": 3833.09,
         "close": 3842.19, "volume": 41456024700},
    ])
    monkeypatch.setattr(akshare_module.ak, "stock_zh_index_daily", lambda symbol="sh000001": df)
    adapter = _adapter()

    bars = adapter._sync_fetch_index_daily("sh000001", "2026-09-29", "2026-09-30", "qfq")

    assert len(bars) == 2
    bar0, bar1 = bars[0], bars[1]
    assert bar0["date"] == "2026-09-29" and bar1["date"] == "2026-09-30"
    assert bar0["code"] == "sh000001", "指数 code 带前缀（显式特例）"
    assert bar0["prev_close"] is None, "首 bar 无区间前一行 → prev_close=None"
    assert bar0["change_percent"] == 0.0
    assert bar1["prev_close"] == 3830.45, "prev_close=上一交易日 close（现算）"
    assert bar1["change_percent"] == round((3842.19 - 3830.45) / 3830.45 * 100, 4)
    assert bar1["amount"] == 0.0, "指数无成交额单值 → 0"
    assert bar1["turnover"] == 0.0, "指数无换手率 → 0"
    assert set(bar1.keys()) == BAR_CONTRACT_KEYS, "指数 bar 同样 11 字段契约"


def test_build_index_bars_uses_out_of_range_prev_close():
    """区间前一行（越界）必须作为首 bar 的 prev_close。"""
    adapter = _adapter()
    bars = adapter._build_index_bars(
        "sh000001",
        ["2026-09-29", "2026-09-30"],
        [3816.15, 3839.25], [3843.84, 3851.22], [3810.81, 3833.09],
        [3830.45, 3842.19], [39947339100.0, 41456024700.0],
        "2026-09-30", "2026-09-30",
    )
    assert len(bars) == 1, "区间外 2026-09-29 不输出，仅作 prev_close"
    assert bars[0]["date"] == "2026-09-30"
    assert bars[0]["prev_close"] == 3830.45
    assert bars[0]["change_percent"] == round((3842.19 - 3830.45) / 3830.45 * 100, 4)


def test_index_chain_sina_failure_falls_back_to_tencent(monkeypatch):
    """sina 失败 → 腾讯 fqkline 兜底；腾讯 volume 手×100 对齐 sina 股口径。"""
    monkeypatch.setattr(akshare_module.ak, "stock_zh_index_daily", raise_error(RuntimeError("sina 网络失败")))
    fake = FakeResp([["2026-09-30", "3839.25", "3842.19", "3851.22", "3833.09", "414560247"]])
    monkeypatch.setattr(akshare_module.requests, "get", lambda *a, **k: fake)
    adapter = _adapter()

    bars = adapter._sync_fetch_index_daily("sh000001", "2026-09-30", "2026-09-30", "qfq")

    assert len(bars) == 1, "sina 失败自动切腾讯"
    assert bars[0]["date"] == "2026-09-30"
    assert bars[0]["volume"] == 414560247 * TENCENT_INDEX_VOLUME_MULTIPLIER, "腾讯 volume 手×100"
    assert bars[0]["change_percent"] == 0.0, "腾讯单根无 prev_close → 0"


def test_index_chain_all_sources_fail_raises_source_error(monkeypatch):
    """三子源全败 → SourceError（计入熔断，防恒 ok 假象）。"""
    monkeypatch.setattr(akshare_module.ak, "stock_zh_index_daily", raise_error(RuntimeError("sina down")))

    class FakeRespFail:
        def raise_for_status(self):
            raise requests.exceptions.HTTPError("tencent 500")

    monkeypatch.setattr(akshare_module.requests, "get", lambda *a, **k: FakeRespFail())
    monkeypatch.setattr(akshare_module.ak, "index_zh_a_hist", raise_error(RuntimeError("em down")))
    adapter = _adapter()

    with pytest.raises(SourceError):
        adapter._sync_fetch_index_daily("sh000001", "2026-09-30", "2026-09-30", "qfq")


# ──────────────────────────────────────────────────────────────────────────────
# 3. Router：指数走 akshare，mootdx 永不参与
# ──────────────────────────────────────────────────────────────────────────────

def _mixed_router(index_ok=True):
    return DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar("600000")], delisted=set()),
        StubAdapter(
            "akshare",
            bars=lambda *a: [make_bar("600000")],
            index_bars=(lambda *a: [make_index_bar("sh000001")]) if index_ok else raise_error(SourceError("指数源 down")),
        ),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])


def test_router_index_routes_only_to_akshare():
    """指数 code 只走 akshare 指数路径，mootdx/baostock 永不参与。"""
    router = _mixed_router()
    result, errors = router.fetch_daily_bars("sh000001", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None
    assert result["source"] == "akshare"
    assert result["data"][0]["code"] == "sh000001"
    assert router.adapters["akshare"].call_counts["index_daily"] == 1
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, "指数不走股票日K路径"
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0, "mootdx 永不参与指数"
    assert router.adapters["mootdx"].call_counts["index_daily"] == 0
    assert router.adapters["baostock"].call_counts["daily_bars"] == 0


def test_router_index_failure_reason_marker():
    router = _mixed_router(index_ok=False)
    result, errors = router.fetch_daily_bars("sh000001", "2026-09-25", "2026-09-30", "qfq")
    assert result is None
    assert "All index sources failed" in errors[0], "指数失败 reason 必须含 'All index sources failed'"


def test_router_stock_code_still_uses_normal_router():
    """裸数字股票 code 不受指数路由影响，仍走 baostock→akshare→mootdx。"""
    router = _mixed_router()
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")
    assert result is not None
    assert result["source"] == "baostock"
    assert router.adapters["akshare"].call_counts["index_daily"] == 0


# ──────────────────────────────────────────────────────────────────────────────
# 4. API 契约（11 字段）+ 指数与股票混批
# ──────────────────────────────────────────────────────────────────────────────

def test_api_index_batch_contract_11_fields(make_client):
    """POST /api/v1/daily-bars/batch ["sh000001"] → results.sh000001.data[] 11 字段契约。"""
    client = make_client(_mixed_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["sh000001"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["failed"] == []
    item = body["results"]["sh000001"]
    assert set(item.keys()) == {"source", "count", "data"}
    assert item["source"] == "akshare", "指数 source=akshare"
    assert item["count"] == 1
    bar = item["data"][0]
    assert set(bar.keys()) == BAR_CONTRACT_KEYS, "指数 bar 也必须 11 字段（防契约漂移）"
    assert bar["code"] == "sh000001"
    assert bar["turnover"] == 0.0 and bar["amount"] == 0.0


def test_api_mixed_stock_index_batch(make_client):
    """指数与股票混批：两个结果都在，无失败；指数走 akshare、股票走 baostock。"""
    router = _mixed_router()
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000", "sh000001"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert set(body["results"].keys()) == {"600000", "sh000001"}
    assert body["failed"] == []
    assert body["results"]["600000"]["source"] == "baostock", "股票走 baostock 主源"
    assert body["results"]["sh000001"]["source"] == "akshare", "指数走 akshare"
    assert set(body["results"]["sh000001"]["data"][0].keys()) == BAR_CONTRACT_KEYS


def test_api_index_invalid_422_envelope(make_client):
    """契约：非法指数 code → 422 信封形（与股票非法 code 同一校验通道）。"""
    client = make_client(_mixed_router())
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["sh00000"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 422, "非法指数 code 必须 422"
    body = resp.json()
    assert set(body.keys()) == {"status", "error"}, "422 错误体必须信封形 {status,error:{code,message}}"
    assert body["status"] == "error"
    assert set(body["error"].keys()) == {"code", "message"}


def test_api_index_failure_enters_failed_list(make_client):
    """指数全源失败 → 单只进 failed[]，不炸整批（可与其他股票混合）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=lambda *a: [make_bar("600000")], delisted=set()),
        StubAdapter(
            "akshare",
            bars=lambda *a: [make_bar("600000")],
            index_bars=raise_error(SourceError("指数源 down")),
        ),
        StubAdapter("mootdx", bars=lambda *a: [make_bar("600000", include_prev_close=False)]),
    ])
    client = make_client(router)
    resp = client.post("/api/v1/daily-bars/batch", json={
        "codes": ["600000", "sh000001"], "start_date": "2026-09-25", "end_date": "2026-09-30", "adjust": "qfq",
    })
    assert resp.status_code == 200, "指数失败不炸整批"
    body = resp.json()
    assert set(body["results"].keys()) == {"600000"}
    assert len(body["failed"]) == 1
    assert body["failed"][0]["code"] == "sh000001"
    assert "index" in body["failed"][0]["reason"].lower()
