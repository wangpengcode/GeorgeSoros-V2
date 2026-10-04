"""Yahoo Finance 适配器测试（2026-10-04 第四源落地：分压路由成员 + 校准/对拍腿）。

设计要点：
- 直连 v8/finance/chart（穿透实验：yfinance 1.2.0 强制 curl_cffi chrome 指纹被
  Yahoo 边缘持续 429；普通 requests + 浏览器 UA 稳定 200）→ 注入缝为 _fetch_chart_json
- Yahoo 是「可选源」：Router 缺席不报错（missing-check 只硬查 baostock/akshare/mootdx），
  注册后进入 failover 序尾（默认 router_order 不含 yahoo，按追加语义排最后）
- ticker 映射：6/9 开头 → .SS（沪），其余 → .SZ（深）；与 baostock/sina 同规则
- 单次拉取含原始 close + adjclose 双列：
  * qfq：factor = adjclose/close，OHLC×factor、close=adjclose（qfq 对齐最新口径）
  * change_percent 从【原始】收盘算（与 sina 同：复权价算涨跌幅是错的）
  * start 前扩 10 自然日 → 请求窗首行也有真实涨跌幅（raw 单拉内含昨收）
- 口径缺口如实标注：turnover 无源数据 → 0；amount = 典型价×volume（估算）
- hfq 不支持 → ParameterError（跨源一致，不计熔断）
- 限流走 _call_guarded（0.5 rps + 抖动，外部源间歇性获取铁律）
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

import pytest

import adapters.yahoo_adapter as yahoo_module
from adapters.base import DataRouter, ParameterError, SourceError
from helpers import StubAdapter, make_bar

TZ = ZoneInfo("Asia/Shanghai")


def _epoch(d: date) -> str:
    return str(int(datetime(d.year, d.month, d.day, 9, 30, tzinfo=TZ).timestamp()))


def _chart_json():
    """3 行原始+复权 chart JSON（9-28 窗外前扩行供首行涨跌幅；9-29/9-30 请求窗内）。

    9-30 除权示例：raw close 11.35 vs adj 10.80 → factor≈0.95154。
    """
    return {
        "chart": {
            "result": [{
                "meta": {"exchangeTimezoneName": "Asia/Shanghai"},
                "timestamp": [
                    int(datetime(2026, 9, 28, 9, 30, tzinfo=TZ).timestamp()),
                    int(datetime(2026, 9, 29, 9, 30, tzinfo=TZ).timestamp()),
                    int(datetime(2026, 9, 30, 9, 30, tzinfo=TZ).timestamp()),
                ],
                "indicators": {
                    "quote": [{
                        "open": [10.90, 10.90, 11.30],
                        "high": [11.10, 11.10, 11.65],
                        "low": [10.80, 10.80, 11.33],
                        "close": [11.00, 11.00, 11.35],
                        "volume": [90_000_000, 69_097_909, 104_535_745],
                    }],
                    "adjclose": [{"adjclose": [10.46, 10.46, 10.80]}],
                },
            }],
            "error": None,
        }
    }


def _adapter_with(monkeypatch, chart=None, raises=None):
    """构造受控 YahooAdapter：_fetch_chart_json 注入式覆盖（记录 ticker/params）。"""
    adapter = yahoo_module.YahooAdapter(FakeCB(), FakeRL())
    calls = []

    def fake_fetch(ticker, start, end):
        calls.append((ticker, start, end))
        if raises is not None:
            raise raises
        return chart if chart is not None else _chart_json()

    monkeypatch.setattr(adapter, "_fetch_chart_json", fake_fetch)
    adapter.calls = calls
    return adapter


class FakeCB:
    def health(self):
        return "ok"

    def allow_request(self):
        return True

    def record_success(self):
        pass

    def record_failure(self):
        pass


class FakeRL:
    def acquire(self, timeout=30.0):
        return True


# ──────────────────────────────────────────────────────────────────────────────
# ticker 映射与调用参数
# ──────────────────────────────────────────────────────────────────────────────

def test_ticker_mapping_sh_sz(monkeypatch):
    """6/9 开头 → .SS，其余 → .SZ（与 baostock/sina 前缀规则一致）。"""
    adapter = _adapter_with(monkeypatch)
    adapter._sync_fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert [c[0] for c in adapter.calls] == ["600000.SS", "000001.SZ"]


def test_start_extended_and_end_exclusive(monkeypatch):
    """窗口前扩 10 自然日供昨收；Yahoo period2 互斥 → 内部 +1 天（epoch 参数纯函数）。"""
    params = yahoo_module._period_params("2026-09-29", "2026-09-30")
    p1 = int(datetime(2026, 9, 19, tzinfo=timezone.utc).timestamp())
    p2 = int(datetime(2026, 10, 1, tzinfo=timezone.utc).timestamp())
    assert params == {"period1": str(p1), "period2": str(p2)}


# ──────────────────────────────────────────────────────────────────────────────
# qfq 口径
# ──────────────────────────────────────────────────────────────────────────────

def test_qfq_ohlc_scaled_by_adj_factor(monkeypatch):
    """qfq：factor=adjclose/close，OHLC×factor，close=adjclose。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    by_date = {b["date"]: b for b in bars}
    b = by_date["2026-09-30"]
    factor = 10.80 / 11.35
    assert abs(b["close"] - 10.80) < 1e-9
    assert abs(b["open"] - 11.30 * factor) < 1e-9
    assert abs(b["high"] - 11.65 * factor) < 1e-9
    assert abs(b["low"] - 11.33 * factor) < 1e-9


def test_change_percent_from_raw_closes(monkeypatch):
    """涨跌幅从【原始】收盘算（复权价算涨幅是错的）；首行靠前扩行得昨收。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    by_date = {b["date"]: b for b in bars}
    # 9-29: (11.00/11.00-1)=0%；9-30: (11.35/11.00-1)*100=3.1818%
    assert abs(by_date["2026-09-29"]["change_percent"]) < 1e-9
    assert abs(by_date["2026-09-30"]["change_percent"] - 3.1818) < 0.01


def test_window_filter_excludes_pre_start_rows(monkeypatch):
    """前扩行只用于算涨跌幅，不落窗（9-28 不在结果里）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-29", "2026-09-30", "qfq")
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]


def test_none_adjust_serves_raw_close(monkeypatch):
    """adjust=none：不乘 factor，close=原始收盘。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "none")
    assert abs(bars[0]["close"] - 11.35) < 1e-9
    assert abs(bars[0]["open"] - 11.30) < 1e-9


def test_hfq_unsupported_is_parameter_error(monkeypatch):
    """hfq 无源数据 → ParameterError（跨源一致，不计熔断失败）。"""
    adapter = _adapter_with(monkeypatch)
    with pytest.raises(ParameterError):
        adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "hfq")


# ──────────────────────────────────────────────────────────────────────────────
# 口径缺口如实标注 + 异常分类 + 坏数据防御
# ──────────────────────────────────────────────────────────────────────────────

def test_turnover_zero_and_amount_estimated(monkeypatch):
    """turnover 无源数据=0；amount=典型价((H+L+C)/3)×volume 估算（raw 口径）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    b = bars[0]
    assert b["turnover"] == 0
    typical = (11.65 + 11.33 + 11.35) / 3
    assert abs(b["amount"] - typical * 104_535_745) < 1.0


def test_volume_in_shares_not_scaled(monkeypatch):
    """Yahoo volume 已是股 → 不再乘系数（与 baostock 原生=股同口径）。"""
    bars = _adapter_with(monkeypatch)._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert bars[0]["volume"] == 104_535_745


def test_empty_chart_returns_empty_list(monkeypatch):
    """空 chart / 坏结构 → 返回 []（Router「空结果」语义 → failover 下一源，不炸）。"""
    for bad in ({}, {"chart": {"result": []}}, {"chart": {"result": [{"meta": {}}]}}):
        adapter = _adapter_with(monkeypatch, chart=bad)
        assert adapter._sync_fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq") == []


def test_null_quote_row_skipped(monkeypatch):
    """停牌/缺数行（quote 含 None）剔除，不影响前后行涨跌幅链。"""
    chart = _chart_json()
    result = chart["chart"]["result"][0]
    result["timestamp"].append(int(datetime(2026, 10, 9, 9, 30, tzinfo=TZ).timestamp()))
    for key in ("open", "high", "low", "close", "volume"):
        result["indicators"]["quote"][0][key].append(None)
    result["indicators"]["adjclose"][0]["adjclose"].append(None)
    adapter = _adapter_with(monkeypatch, chart=chart)
    bars = adapter._sync_fetch_daily_bars("000001", "2026-09-29", "2026-10-09", "qfq")
    assert [b["date"] for b in bars] == ["2026-09-29", "2026-09-30"]


def test_network_failure_raises_source_error(monkeypatch):
    """网络故障 → SourceError（计入熔断失败分类，非裸异常穿透）。"""
    adapter = _adapter_with(monkeypatch, raises=ConnectionError("RemoteDisconnected"))
    with pytest.raises(SourceError):
        adapter.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")


# ──────────────────────────────────────────────────────────────────────────────
# Router 集成：可选源 + 分片成员
# ──────────────────────────────────────────────────────────────────────────────

def test_router_without_yahoo_still_builds():
    """Yahoo 是可选源：三源 Router 构建不报错（missing-check 不硬查 yahoo）。"""
    router = DataRouter([
        StubAdapter("baostock", bars=[make_bar("000001")]),
        StubAdapter("akshare"),
        StubAdapter("mootdx"),
    ])
    result, errors = router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None, "三源 Router 行为回归不变"
    assert result["source"] == "baostock"


def test_router_with_yahoo_failover_tail():
    """注册 yahoo → 进入 failover 序尾；全前源空结果时 yahoo 接管并如实归因。"""
    def bars(code, start, end, adjust):
        return []  # baostock/akshare/mootdx 全空
    router = DataRouter([
        StubAdapter("baostock", bars=bars),
        StubAdapter("akshare", bars=bars),
        StubAdapter("mootdx", bars=bars),
        StubAdapter("yahoo", bars=[make_bar("000001")]),
    ])
    result, errors = router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "yahoo"


def test_shard_owner_yahoo_first(monkeypatch):
    """分片三源默认池（baostock,akshare,yahoo）：000002 % 3 == 2 → yahoo 归属优先。"""
    from config import settings
    monkeypatch.setattr(settings, "shard_sources", ("baostock", "akshare", "yahoo"))
    order: list = []

    def mk(name):
        def bars(code, start, end, adjust):
            order.append(name)
            if name == "yahoo":
                return [make_bar(code)]
            return []
        return StubAdapter(name, bars=bars)

    router = DataRouter([mk("baostock"), mk("akshare"), mk("mootdx"), mk("yahoo")])
    result, _ = router.fetch_daily_bars("000002", "2026-09-30", "2026-09-30", "qfq")
    assert order == ["yahoo"], "yahoo 归属 code 应最先尝试且命中即停"
    assert result["source"] == "yahoo"
