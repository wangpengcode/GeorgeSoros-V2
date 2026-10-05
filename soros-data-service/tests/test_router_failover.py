"""DataRouter 单票单源语义测试（2026-10-05 均分流量定稿，替代旧 failover 语义）。

- 每票由健康源轮转分配一个源；分配源故障 → 本轮放弃（result None），不切源
- 空结果 = 正面证据：返回 count=0 占位（带 empty_sources），非 failed、不切源
- mootdx 永不参与 stock-list；source 字段记录实际服务源
"""

from __future__ import annotations

from adapters.base import DataRouter, ParameterError, SourceError
from helpers import StubAdapter, make_bar, raise_error


def _router(baostock_bars=None, akshare_bars=None, mootdx_bars=None,
            baostock_delisted=None, akshare_stock_list=None):
    """baostock_delisted 兼容旧签名：DataRouter.fetch_stock_list 现在用 fetch_stock_basic_rows()
    （Step 5a 一次取退市+ipo_date），把 delisted 集合转为 query_stock_basic 行。"""
    basic_rows = []
    for code in (baostock_delisted or set()):
        basic_rows.append({
            "code": f"sh.{code}", "code_name": code, "ipo_date": "2000-01-01",
            "out_date": "", "type": "1", "status": "0",
        })
    return DataRouter([
        StubAdapter("baostock", bars=baostock_bars, stock_basic_rows=basic_rows),
        StubAdapter("akshare", bars=akshare_bars, stock_list=akshare_stock_list),
        StubAdapter("mootdx", bars=mootdx_bars),
    ])


def test_source_field_records_actual_success_source():
    """首票轮转到候选首位：source 字段如实记录实际服务源。"""
    router = _router(
        baostock_bars=lambda *a: [make_bar("600000")],
        akshare_bars=raise_error(SourceError("down")),
        mootdx_bars=raise_error(SourceError("down")),
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None
    assert result["source"] == "baostock", "首票轮转候选首位，source 如实归因"
    assert errors == []


def test_assigned_source_failure_abandons_round():
    """分配源故障 → 本轮放弃（None + 单源 reason），绝不切源（均分铁律：失败票等下轮重扫）。"""
    router = _router(
        baostock_bars=raise_error(SourceError("baostock 网络故障")),
        akshare_bars=lambda *a: [make_bar("600000")],
        mootdx_bars=lambda *a: [make_bar("600000", include_prev_close=False)],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is None, "不 inline failover"
    assert len(errors) == 1 and "baostock" in errors[0], "只归因被分配源"
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, "故障票零切换"
    assert router.adapters["mootdx"].call_counts["daily_bars"] == 0


def test_parameter_error_abandons_round_without_next_source():
    """参数错误跨源一致：分配源报错即止，不再尝试其他源。"""
    router = _router(
        baostock_bars=raise_error(ParameterError("非法代码")),
        akshare_bars=lambda *a: [make_bar("600000")],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is None
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, (
        "参数错误跨源一致，不换源重试"
    )
    assert "参数错误" in errors[0]


def test_empty_result_returns_placeholder_not_failed():
    """分配源空结果 = 正面证据（源确认无数据）：count=0 占位带 empty_sources，非 failed、不切源。"""
    router = _router(
        baostock_bars=lambda *a: [],
        akshare_bars=lambda *a: [make_bar("600000")],
    )
    result, errors = router.fetch_daily_bars("600000", "2026-09-25", "2026-09-30", "qfq")

    assert result is not None, "空结果返回占位，非 None"
    assert result["count"] == 0 and result["data"] == []
    assert result["empty_sources"] == ["baostock"], "空票归因被分配源（K=2 verified-empty 判据）"
    assert errors == []
    assert router.adapters["akshare"].call_counts["daily_bars"] == 0, "空结果不切源"


def test_empty_votes_rotate_to_other_source_next_round():
    """同 code 下轮扫描：空票源优先排除，换源验证（K=2 两源空才可判 verified-empty）。"""
    flip = {"ak_empty": True}
    router = _router(
        baostock_bars=lambda *a: [],
        akshare_bars=lambda *a: [] if flip["ak_empty"] else [make_bar("000001")],
        mootdx_bars=lambda *a: [],
    )
    r1, _ = router.fetch_daily_bars("000001", "2026-09-25", "2026-09-30", "qfq")
    assert r1["count"] == 0 and r1["empty_sources"] == ["baostock"]
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1
    r2, _ = router.fetch_daily_bars("000001", "2026-09-25", "2026-09-30", "qfq")
    assert r2["count"] == 0
    assert "baostock" not in r2["empty_sources"] or len(r2["empty_sources"]) == 2, (
        "第二轮换源，票累计"
    )
    assert router.adapters["baostock"].call_counts["daily_bars"] == 1, "有空票时不再撞 baostock"
    flip["ak_empty"] = False
    # 回退轮转终会命中 akshare（全候选投过票则回退全候选）
    for _ in range(4):
        r3, _ = router.fetch_daily_bars("000001", "2026-09-25", "2026-09-30", "qfq")
        if r3["count"] == 1:
            break
    assert r3["count"] == 1 and r3["source"] == "akshare", "换源后取到数据"


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
    by_code = {s["code"] for s in stocks}
    stocks_by_code = {s["code"]: s for s in stocks}
    assert "600001" in by_code
    assert stocks_by_code["600001"]["delisted"] is True, "退市标记由 baostock query_stock_basic 合并"
    assert stocks_by_code["600000"]["delisted"] is False
