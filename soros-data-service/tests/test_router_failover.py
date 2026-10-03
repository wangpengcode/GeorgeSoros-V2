"""DataRouter failover 单测（PLAN §11.1：baostock → akshare → mootdx，首源成功即返回）。

- 主源异常切次源；全源失败进 failed[]（reason 含 "All sources failed"）
- mootdx 永不参与 stock-list；source 字段记录实际成功源
- 参数错误（ParameterError）跨源一致 → break 不再尝试次源
"""

from __future__ import annotations

from adapters.base import DataRouter, ParameterError, SourceError
from helpers import StubAdapter, make_bar, raise_error


def _router(baostock_bars=None, akshare_bars=None, mootdx_bars=None,
            baostock_delisted=None, akshare_stock_list=None):
    return DataRouter([
        StubAdapter("baostock", bars=baostock_bars, delisted=baostock_delisted),
        StubAdapter("akshare", bars=akshare_bars, stock_list=akshare_stock_list),
        StubAdapter("mootdx", bars=mootdx_bars),
    ])


def test_main_source_failure_fallthrough_to_secondary():
    router = _router(
        baostock_bars=raise_error(SourceError("baostock 网络故障")),
        akshare_bars=lambda *a: [make_bar("600000")],
        mootdx_bars=lambda *a: [make_bar("600000", include_prev_close=False)],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None
    assert result["source"] == "akshare", "主源 baostock 异常应切次源 akshare"
    assert errors == []
    assert router.adapters["akshare"].call_counts["daily_bars"] == 1
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0, "首源成功即返回，不再试 mootdx"


def test_all_sources_failed_reason_contains_marker():
    router = _router(
        baostock_bars=raise_error(SourceError("baostock down")),
        akshare_bars=raise_error(SourceError("akshare down")),
        mootdx_bars=raise_error(SourceError("mootdx down")),
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is None
    assert len(errors) == 1
    assert "All sources failed" in errors[0], "全源失败 reason 必须含 'All sources failed'"
    assert "baostock" in errors[0] and "akshare" in errors[0] and "mootdx" in errors[0]


def test_source_field_records_actual_success_source():
    router = _router(
        baostock_bars=raise_error(SourceError("down")),
        akshare_bars=raise_error(SourceError("down")),
        mootdx_bars=lambda *a: [make_bar("000001", include_prev_close=False)],
    )
    result, errors = router.fetch_daily_bars("000001", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None
    assert result["source"] == "mootdx", "baostock/akshare 均败 → mootdx 兜底，source 须记录 mootdx"


def test_parameter_error_breaks_without_trying_next_source():
    router = _router(
        baostock_bars=raise_error(ParameterError("非法代码")),
        akshare_bars=lambda *a: [make_bar("600000")],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is None
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, (
        "参数错误跨源一致，不再尝试次源"
    )
    assert "参数错误" in errors[0]


def test_empty_result_treated_as_failure_and_continues():
    router = _router(
        baostock_bars=lambda *a: [],
        akshare_bars=lambda *a: [make_bar("600000")],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None
    assert result["source"] == "akshare", "主源空结果视为失败继续切次源"


def test_mootdx_never_participates_in_stock_list():
    router = _router(
        baostock_delisted={"600001"},
        akshare_stock_list=[
            {"code": "600000", "name": "浦发银行", "market": "SH", "board": "MAIN",
             "is_st": False, "delisted": False},
            {"code": "600001", "name": "退市股", "market": "SH", "board": "MAIN",
             "is_st": False, "delisted": False},
        ],
    )
    stocks = router.fetch_stock_list()

    assert router.adapters["mootdx"].call_counts["stock_list"] == 0, "mootdx 永不参与 stock-list"
    by_code = {s["code"]: s for s in stocks}
    assert by_code["600001"]["delisted"] is True, "退市标记由 baostock query_stock_basic 合并"
    assert by_code["600000"]["delisted"] is False
