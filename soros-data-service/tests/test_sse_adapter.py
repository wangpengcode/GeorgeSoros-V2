"""上交所行情云 Adapter 测试（2026-10-04 第六源落地：源头级校准腿 + 首次建仓灌历史）。

设计要点：
- SSE 是「可选源」：Router 缺席不报错（missing-check 只硬查 baostock/akshare/mootdx），
  注册后进入 failover 序尾（yahoo/tencent 之后）
- 市场映射：6/9 开头 → sh1，其余 → sz1（与 baostock/sina 前缀规则一致）
- begin/end 参数：空 → 0/-1 全历史；'YYYY-MM-DD' → int YYYYMMDD（去横线）
- 字段序=[date(int YYYYMMDD), open, high, low, close, volume(股), amount(元)]
- volume 是【股】（实测与 baostock 一字不差）→ 不乘任何系数（与腾讯「手×100」最易混，
  SSE_VOLUME_MULTIPLIER=1，注释写明实测依据）；amount 已是【元】原样取
- raw 不复权：qfq/hfq 请求一律 ParameterError（_supports_adjust_qfq/hfq=False；
  复权因子推 qfq 归 AdjustCheckStep §17.2 B6，本 adapter 内不做复权）
- change_percent 无现成列 → 相邻 raw close 链式自算（前扩行供首行昨收）；首行无昨收时 null
- Referer 头（http://www.sse.com.cn/）随请求发出（缺失会被源头拒）
- 注入缝 _fetch_dayk_json；requests 异常统一 SourceError（计入熔断失败分类）
- 空结果（total=0/kline=[]）→ []（Router「空结果」语义 failover，不炸，对齐 yahoo 空结果约定）
"""

from __future__ import annotations

import requests
import pytest

import adapters.sse_adapter as sse_module
from adapters.base import DataRouter, ParameterError, SourceError
from constants import to_sse_market
from helpers import FakeCircuitBreaker, FakeRateLimiter, StubAdapter, make_bar, raise_error


def _dayk_json():
    """3 行 dayk JSON（9-28 窗外供首行昨收；9-29/9-30 请求窗内）。

    结构 {"code","total","begin","end","kline":[...]}；字段序=[date(int YYYYMMDD),
    open, high, low, close, volume(股), amount(元)]。
    造数易算（期望值手算可验证）：
    - volume 已是股 → 不乘系数（1000000/1200000 原样）
    - amount 已是元 → 原样（10000000.0/12360000.0）
    - 相邻 close：9-28→9-29 10.00/10.00 = 0%；9-29→9-30 10.30/10.00 - 1 = 3%
    """
    return {
        "code": "600000",
        "total": 3,
        "begin": 20260928,
        "end": 20260930,
        "kline": [
            [20260928, 10.00, 10.50, 9.50, 10.00, 500000, 5000000],
            [20260929, 10.00, 10.60, 9.60, 10.00, 1000000, 10000000],
            [20260930, 10.20, 10.80, 9.90, 10.30, 1200000, 12360000],
        ],
    }


class _FakeResp:
    """requests.get 的假响应：raise_for_status 无操作 + json 返回载荷（仅测 header 传递用）。"""

    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        pass

    def json(self):
        return self._payload


def _adapter_with(monkeypatch, dayk=None, raises=None):
    """构造受控 SseAdapter：_fetch_dayk_json 注入式覆盖（记录 market/code/begin/end）。"""
    adapter = sse_module.SseAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    calls = []

    def fake_fetch(market, code, begin, end):
        calls.append((market, code, begin, end))
        if raises is not None:
            raise raises
        return dayk if dayk is not None else _dayk_json()

    monkeypatch.setattr(adapter, "_fetch_dayk_json", fake_fetch)
    adapter.calls = calls
    return adapter


# ──────────────────────────────────────────────────────────────────────────────
# 市场映射与 begin/end 参数（纯函数，当前即可通过）
# ──────────────────────────────────────────────────────────────────────────────

def test_market_mapping_sh_sz():
    """6/9 开头 → sh1，其余 → sz1（与 baostock/sina 前缀规则一致）。"""
    assert to_sse_market("600000") == "sh1"
    assert to_sse_market("000001") == "sz1"
    assert to_sse_market("300750") == "sz1"


def test_begin_end_params():
    """begin/end：空 → 0/-1 全历史；否则 YYYYMMDD 整型（YYYY-MM-DD 去横线）。"""
    assert sse_module._begin_end_params("", "") == (0, -1)
    assert sse_module._begin_end_params("2026-09-30", "2026-09-30") == (20260930, 20260930)
    assert sse_module._begin_end_params("2026-09-01", "2026-09-30") == (20260901, 20260930)


# ──────────────────────────────────────────────────────────────────────────────
# date 转换 + 单位口径（volume=股 不乘系数，与腾讯最易混）
# ──────────────────────────────────────────────────────────────────────────────

def test_date_int_to_iso_string(monkeypatch):
    """date int YYYYMMDD → 输出 "YYYY-MM-DD" 字符串。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]


def test_volume_in_shares_not_scaled(monkeypatch):
    """SSE volume 已是【股】（实测与 baostock 一字不差，SSE_VOLUME_MULTIPLIER=1）→ 不乘任何系数。

    这是与腾讯「手×100」最易混的断言点：腾讯字段序 volume(手) 需 ×100，SSE 字段序 volume(股) 原样。
    """
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    by_date = {b["date"]: b for b in bars}
    assert by_date["2026-09-29"]["volume"] == 1000000
    assert by_date["2026-09-30"]["volume"] == 1200000


def test_amount_unchanged_yuan(monkeypatch):
    """SSE amount 已是【元】→ 原样取（SSE_AMOUNT_MULTIPLIER=1，不乘系数）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    by_date = {b["date"]: b for b in bars}
    assert by_date["2026-09-29"]["amount"] == 10000000.0
    assert by_date["2026-09-30"]["amount"] == 12360000.0


# ──────────────────────────────────────────────────────────────────────────────
# change_percent 链式自算
# ──────────────────────────────────────────────────────────────────────────────

def test_change_percent_chain_from_adjacent_closes(monkeypatch):
    """涨跌幅无现成列：相邻 raw close 链式自算（前扩行 9-28 供首行昨收，只参与昨收不落窗）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    by_date = {b["date"]: b for b in bars}
    assert abs(by_date["2026-09-29"]["change_percent"]) < 1e-9          # 10.00/10.00 - 1 = 0%
    assert abs(by_date["2026-09-30"]["change_percent"] - 3.0) < 1e-9    # 10.30/10.00 - 1 = 3%


def test_change_percent_first_row_null_without_prev(monkeypatch):
    """首行无昨收 → change_percent null（非 0；区别于 yahoo 的 0.0 兜底，SSE 口径=null）。"""
    dayk = {
        "code": "600000", "total": 2, "begin": 20260929, "end": 20260930,
        "kline": [
            [20260929, 10.00, 10.60, 9.60, 10.00, 1000000, 10000000],
            [20260930, 10.20, 10.80, 9.90, 10.30, 1200000, 12360000],
        ],
    }
    bars = _adapter_with(monkeypatch, dayk=dayk)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    assert bars[0]["change_percent"] is None
    assert abs(bars[1]["change_percent"] - 3.0) < 1e-9


# ──────────────────────────────────────────────────────────────────────────────
# raw-only 契约 + Referer 头 + 异常分类
# ──────────────────────────────────────────────────────────────────────────────

def test_qfq_hfq_raises_parameter_error(monkeypatch):
    """raw-only 契约：qfq/hfq 请求 → ParameterError（跨源一致，不计熔断失败，照 yahoo hfq 先例）。

    复权因子推 qfq 归 AdjustCheckStep（§17.2 B6），本 adapter 内不做复权。
    """
    adapter = _adapter_with(monkeypatch)
    with pytest.raises(ParameterError):
        adapter._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    with pytest.raises(ParameterError):
        adapter._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "hfq")


def test_raw_adjust_none_serves(monkeypatch):
    """raw（adjust none / 空）正常出数。"""
    for adjust in ("none", ""):
        bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", adjust)
        assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]


def test_referer_header_sent(monkeypatch):
    """SSE 源头要求 Referer 头（http://www.sse.com.cn/）——缺失会被源头拒。"""
    captured = {}

    def fake_get(url, **kwargs):
        captured["url"] = url
        captured["headers"] = kwargs.get("headers", {})
        return _FakeResp(_dayk_json())

    monkeypatch.setattr(requests, "get", fake_get)
    adapter = sse_module.SseAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    adapter._fetch_dayk_json("sh1", "600000", 20260929, 20260930)
    assert captured["headers"].get("Referer") == "http://www.sse.com.cn/"
    assert "User-Agent" in captured["headers"]


def test_fetch_dayk_network_failure_raises_source_error(monkeypatch):
    """网络故障（requests 层）→ SourceError（计入熔断失败分类，非裸异常穿透）。"""
    adapter = sse_module.SseAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    monkeypatch.setattr(requests, "get", raise_error(ConnectionError("RemoteDisconnected")))
    with pytest.raises(SourceError):
        adapter._fetch_dayk_json("sh1", "600000", 20260929, 20260930)


def test_empty_result_returns_empty_list(monkeypatch):
    """空结果（total=0/kline=[]）/坏结构 → []（Router「空结果」语义 failover，不炸，对齐 yahoo）。"""
    for bad in ({"code": "600000", "total": 0, "kline": []}, {}, {"kline": []}):
        adapter = _adapter_with(monkeypatch, dayk=bad)
        assert adapter._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none") == []


# ──────────────────────────────────────────────────────────────────────────────
# Router 集成：可选源 + failover 序尾
# ──────────────────────────────────────────────────────────────────────────────

def test_router_without_sse_still_builds():
    """SSE 是可选源：三源 Router 构建不报错（missing-check 不硬查 sse）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=[make_bar("600000")]),
        StubAdapter("akshare"),
        StubAdapter("mootdx"),
    ])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None, "三源 Router 行为回归不变"
    assert result["source"] == "baostock"


def test_router_with_sse_failover_tail():
    """注册 sse → 进入 failover 序尾（yahoo/tencent 之后）；全前源空结果时 sse 接管并如实归因。"""
    order = []

    def mk(name, bars=None):
        def f(code, start, end, adjust):
            order.append(name)
            return bars or []
        return StubAdapter(name, bars=f)

    router = DataRouter([
        mk("baostock"), mk("akshare"), mk("mootdx"),
        mk("yahoo"), mk("tencent"), mk("sse", bars=[make_bar("600000")]),
    ])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "sse"
    assert order == ["baostock", "akshare", "mootdx", "yahoo", "tencent", "sse"], (
        "可选源按「已注册」追加语义排 failover 序尾（yahoo/tencent 之后）"
    )
