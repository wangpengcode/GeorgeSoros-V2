"""腾讯行情 Adapter 测试（2026-10-04 第五源落地：分压路由可选成员 + 校准/对拍腿）。

设计要点：
- 腾讯是「可选源」：Router 缺席不报错（missing-check 只硬查 baostock/akshare/mootdx），
  注册后进入 failover 序尾（默认 router_order 不含 tencent，按追加语义排最后、yahoo 之后）
- ticker 映射：6/9 开头 → sh（沪），其余 → sz（深）——与 baostock/sina 前缀规则一致
- qfq 口径与 yahoo 相反（重点！）：腾讯 qfq 是服务端算好的，close/high/low/open 一律
  原样取、**不得再乘复权因子**；Yahoo 因源数据只给 raw+adjclose 才需 self-serve 因子缩放
- 单位换算（§19.3 ① 实测字段序）：volume 手 → ×100 转股（TENCENT_VOLUME_MULTIPLIER）；
  amount 万元 → ×1e4 转元（TENCENT_AMOUNT_MULTIPLIER）；turnover 已是 % 值直接用
- change_percent 无现成列 → 相邻行 close 链式自算（前扩行供首行昨收）；首行无昨收时 null
- hfq 服务端支持（_supports_adjust_hfq=True）→ 改用 hfqday 键，值同样原样取
- 注入缝 _fetch_kline_json；requests 异常统一 SourceError（计入熔断失败分类）
- 坏结构/空结果 → []（Router「空结果」语义 failover，不炸，对齐 yahoo 空结果约定）
"""

from __future__ import annotations

import requests
import pytest

import adapters.tencent_adapter as tencent_module
from adapters.base import DataRouter, SourceError
from constants import to_tencent_symbol
from helpers import FakeCircuitBreaker, FakeRateLimiter, StubAdapter, make_bar, raise_error


def _kline_json(adjust="qfq"):
    """3 行 kline JSON（9-28 窗外供首行昨收；9-29/9-30 请求窗内）。

    结构 data.{symbol}.{qfqday|hfqday|day}；字段序=[date, open, close, high, low,
    volume(手), 分红dict, turnover%, amount(万元), 占位]（close 第 3 位、high/low 4/5 位）。
    造数易算（期望值手算可验证）：
    - volume 1000 手 → 100000 股；1200 手 → 120000 股
    - amount 10000 万元 → 1e8 元；12000 万元 → 1.2e8 元
    - 相邻 close：9-28→9-29 10.00/10.00 = 0%；9-29→9-30 10.30/10.00 - 1 = 3%
    """
    key = {"qfq": "qfqday", "hfq": "hfqday", "none": "day"}[adjust]
    rows = [
        ["2026-09-28", "10.00", "10.00", "10.50", "9.50", "500", {}, "1.0", "5000", None],
        ["2026-09-29", "10.00", "10.00", "10.60", "9.60", "1000", {}, "1.5", "10000", None],
        ["2026-09-30", "10.20", "10.30", "10.80", "9.90", "1200", {}, "2.0", "12000", None],
    ]
    return {"data": {"sh600000": {key: rows}}}


def _adapter_with(monkeypatch, kline=None, raises=None):
    """构造受控 TencentAdapter：_fetch_kline_json 注入式覆盖（记录 symbol/start/end/adjust）。

    默认按 adjust 自动选择 qfqday/hfqday/day 键；也可显式传入 kline 或 raises 触发异常。
    """
    adapter = tencent_module.TencentAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    calls = []

    def fake_fetch(symbol, start, end, adjust):
        calls.append((symbol, start, end, adjust))
        if raises is not None:
            raise raises
        return kline if kline is not None else _kline_json(adjust)

    monkeypatch.setattr(adapter, "_fetch_kline_json", fake_fetch)
    adapter.calls = calls
    return adapter


# ──────────────────────────────────────────────────────────────────────────────
# ticker 映射与调用参数（纯函数，当前即可通过）
# ──────────────────────────────────────────────────────────────────────────────

def test_ticker_mapping_sh_sz():
    """6/9 开头 → sh 前缀，其余 → sz（与 baostock/sina 前缀规则一致）。"""
    assert to_tencent_symbol("600000") == "sh600000"
    assert to_tencent_symbol("000001") == "sz000001"
    assert to_tencent_symbol("300750") == "sz300750"


def test_count_for_window():
    """count 前扩窗口：(end-start).days+10；上限 800；缺失日期退化为最小前扩。"""
    adapter = tencent_module.TencentAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    assert adapter._count_for_window("2026-09-01", "2026-09-30") == 39   # 29 + 10
    assert adapter._count_for_window("2020-01-01", "2026-09-30") == 800  # 跨年超窗截断
    assert adapter._count_for_window("", "") == 10                       # 缺失日期防御


def test_build_param_adjust_suffix():
    """param 末段：qfq/hfq 直传服务端；none/空 → 空串（raw 不复权）。"""
    adapter = tencent_module.TencentAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    assert adapter._build_param("sh600000", "2026-09-29", "2026-09-30", "qfq") == (
        "sh600000,day,2026-09-29,2026-09-30,11,qfq"
    )
    assert adapter._build_param("sh600000", "2026-09-29", "2026-09-30", "hfq") == (
        "sh600000,day,2026-09-29,2026-09-30,11,hfq"
    )
    assert adapter._build_param("sh600000", "2026-09-29", "2026-09-30", "none") == (
        "sh600000,day,2026-09-29,2026-09-30,11,"
    )
    assert adapter._build_param("sh600000", "2026-09-29", "2026-09-30", "") == (
        "sh600000,day,2026-09-29,2026-09-30,11,"
    )


# ──────────────────────────────────────────────────────────────────────────────
# qfq 口径（服务端已复权，原样取）
# ──────────────────────────────────────────────────────────────────────────────

def test_qfq_ohlc_untouched(monkeypatch):
    """qfq：腾讯服务端已复权，close/high/low/open 一律原样取——【不得再乘因子】，与 yahoo 相反。"""
    adapter = _adapter_with(monkeypatch)
    bars = adapter._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    assert adapter.calls == [("sh600000", "2026-09-29", "2026-09-30", "qfq")]
    by_date = {b["date"]: b for b in bars}
    b = by_date["2026-09-30"]
    # 若按 yahoo 思路误乘 factor 会偏离；腾讯 qfq 必须原样
    assert abs(b["open"] - 10.20) < 1e-9
    assert abs(b["high"] - 10.80) < 1e-9
    assert abs(b["low"] - 9.90) < 1e-9
    assert abs(b["close"] - 10.30) < 1e-9
    assert b["code"] == "600000"


def test_volume_amount_turnover_conversion(monkeypatch):
    """单位换算（§19.3 ① 实测字段序）：volume 手×100→股；amount 万元×1e4→元；turnover 已是 % 直接用。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    by_date = {b["date"]: b for b in bars}
    b = by_date["2026-09-29"]
    assert b["volume"] == 100000          # 1000 手 × 100
    assert b["amount"] == 100000000.0     # 10000 万元 × 1e4
    assert b["turnover"] == 1.5           # 已是 % 值，不乘
    b30 = by_date["2026-09-30"]
    assert b30["volume"] == 120000
    assert b30["amount"] == 120000000.0
    assert b30["turnover"] == 2.0


# ──────────────────────────────────────────────────────────────────────────────
# change_percent 链式自算
# ──────────────────────────────────────────────────────────────────────────────

def test_change_percent_chain_from_adjacent_closes(monkeypatch):
    """涨跌幅无现成列：相邻行 close 链式自算（前扩行 9-28 供首行昨收，只参与昨收不落窗）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    by_date = {b["date"]: b for b in bars}
    assert abs(by_date["2026-09-29"]["change_percent"]) < 1e-9          # 10.00/10.00 - 1 = 0%
    assert abs(by_date["2026-09-30"]["change_percent"] - 3.0) < 1e-9    # 10.30/10.00 - 1 = 3%


def test_change_percent_first_row_null_without_prev(monkeypatch):
    """首行无昨收 → change_percent null（非 0；区别于 yahoo 的 0.0 兜底，tencent 口径=null）。"""
    rows = [
        ["2026-09-29", "10.00", "10.00", "10.60", "9.60", "1000", {}, "1.5", "10000", None],
        ["2026-09-30", "10.20", "10.30", "10.80", "9.90", "1200", {}, "2.0", "12000", None],
    ]
    kline = {"data": {"sh600000": {"qfqday": rows}}}
    bars = _adapter_with(monkeypatch, kline=kline)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    assert bars[0]["change_percent"] is None
    assert abs(bars[1]["change_percent"] - 3.0) < 1e-9


# ──────────────────────────────────────────────────────────────────────────────
# 窗口过滤 + raw/hfq 路径
# ──────────────────────────────────────────────────────────────────────────────

def test_window_filter_excludes_pre_start_rows(monkeypatch):
    """窗外行（9-28 前扩）只参与昨收，不落窗。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq")
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]


def test_raw_adjust_serves_day_key(monkeypatch):
    """adjust=none：腾讯返回 day 键（raw），OHLC 原样，change_percent 仍自算。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "none")
    by_date = {b["date"]: b for b in bars}
    assert abs(by_date["2026-09-30"]["close"] - 10.30) < 1e-9
    assert abs(by_date["2026-09-29"]["change_percent"]) < 1e-9


def test_hfq_serves_hfqday_key(monkeypatch):
    """hfq 服务端支持（_supports_adjust_hfq=True）：改用 hfqday 键，值同样原样取（不复乘因子）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "hfq")
    by_date = {b["date"]: b for b in bars}
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]
    assert abs(by_date["2026-09-30"]["close"] - 10.30) < 1e-9


# ──────────────────────────────────────────────────────────────────────────────
# 空结果防御 + 异常分类
# ──────────────────────────────────────────────────────────────────────────────

def test_empty_kline_returns_empty_list(monkeypatch):
    """坏结构/空结果 → []（Router「空结果」语义 failover，不炸，对齐 yahoo 空结果约定）。"""
    for bad in ({"data": {"sh600000": {}}}, {"data": {}}, {}):
        adapter = _adapter_with(monkeypatch, kline=bad)
        assert adapter._sync_fetch_daily_bars("600000", "2026-09-29", "2026-09-30", "qfq") == []


def test_fetch_kline_json_network_failure_raises_source_error(monkeypatch):
    """网络故障（requests 层）→ SourceError（计入熔断失败分类，非裸异常穿透）。"""
    adapter = tencent_module.TencentAdapter(FakeCircuitBreaker(), FakeRateLimiter())
    monkeypatch.setattr(requests, "get", raise_error(ConnectionError("RemoteDisconnected")))
    with pytest.raises(SourceError):
        adapter._fetch_kline_json("sh600000", "2026-09-29", "2026-09-30", "qfq")


# ──────────────────────────────────────────────────────────────────────────────
# Router 集成：可选源 + failover 序尾
# ──────────────────────────────────────────────────────────────────────────────

def test_router_without_tencent_still_builds():
    """Tencent 是可选源：三源 Router 构建不报错（missing-check 不硬查 tencent）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=[make_bar("600000")]),
        StubAdapter("akshare"),
        StubAdapter("mootdx"),
    ])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None, "三源 Router 行为回归不变"
    assert result["source"] == "baostock"


def test_router_with_tencent_failover_tail():
    """注册 tencent → 进入 failover 序尾（yahoo 之后）；全前源空结果时 tencent 接管并如实归因。"""
    order = []

    def mk(name, bars=None):
        def f(code, start, end, adjust):
            order.append(name)
            return bars or []
        return StubAdapter(name, bars=f)

    router = DataRouter([
        mk("baostock"), mk("akshare"), mk("mootdx"),
        mk("yahoo"), mk("tencent", bars=[make_bar("600000")]),
    ])
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "tencent"
    assert order == ["baostock", "akshare", "mootdx", "yahoo", "tencent"], (
        "可选源按「已注册」追加语义排 failover 序尾（yahoo 之后）"
    )
