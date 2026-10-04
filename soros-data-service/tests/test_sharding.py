"""多源分片分压测试（2026-10-04 设计穿透 92 分落地方案）。

设计要点（PLAN §11.1 failover 之上的顺序层）：
- 按 code 稳态分片：int(code) % len(shard_sources) 决定归属源，归属源优先尝试
- 分片只调整顺序、不改能力：归属源故障自然 failover 到其余源（数据完整性优先于分压）
- 禁用 hash()（PYTHONHASHSEED 随机 → 重启换归属 → 滚动自愈互相覆盖），用 int(code) 稳定映射
- 指数代码（sh000001）走指数路径不受分片影响；单分片配置退化为原 router_order

探序手法：stub 空结果 → Router「空结果」继续下一源 → order 记录完整尝试序列。
"""

from __future__ import annotations

import pytest

from adapters.base import DataRouter, SourceError
from config import settings
from helpers import StubAdapter, make_bar


def _probe_router(order: list, fail_source: str | None = None, success_source: str = "mootdx") -> DataRouter:
    """记录各源被尝试的顺序。

    fail_source 抛 SourceError；success_source 返回 1 根 bar（命中即返回，终止尝试）；
    其余返回空列表（「空结果」→ 继续下一源）。由此 order 即完整尝试序列。
    """

    def mk(name: str) -> StubAdapter:
        def bars(code, start, end, adjust):
            order.append(name)
            if name == fail_source:
                raise SourceError(f"{name} 故障")
            if name == success_source:
                return [make_bar(code)]
            return []

        return StubAdapter(name, bars=bars)

    return DataRouter([mk("baostock"), mk("akshare"), mk("mootdx")])


# ──────────────────────────────────────────────────────────────────────────────
# 稳态映射
# ──────────────────────────────────────────────────────────────────────────────

def test_shard_even_code_baostock_first():
    """偶数尾 code（600000 % 2 == 0）→ 归属 baostock，最先尝试。"""
    order: list = []
    router = _probe_router(order)
    router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert order == ["baostock", "akshare", "mootdx"], "归属源 baostock 应最先尝试"


def test_shard_odd_code_akshare_first():
    """奇数尾 code（000001 % 2 == 1）→ 归属 akshare，最先尝试。"""
    order: list = []
    router = _probe_router(order)
    router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert order == ["akshare", "baostock", "mootdx"], "归属源 akshare 应最先尝试"


def test_shard_mapping_is_deterministic_across_instances():
    """同一 code 在不同 Router 实例上归属一致（禁 hash()，int(code) 稳态映射）。"""
    order_a: list = []
    order_b: list = []
    _probe_router(order_a).fetch_daily_bars("300750", "", "", "qfq")
    _probe_router(order_b).fetch_daily_bars("300750", "", "", "qfq")
    assert order_a[0] == order_b[0], "归属映射必须跨实例稳定（滚动自愈不互相覆盖）"


# ──────────────────────────────────────────────────────────────────────────────
# failover 语义不变
# ──────────────────────────────────────────────────────────────────────────────

def test_shard_owner_failure_fails_over_to_other_source():
    """归属源故障 → failover 其余源，数据完整性优先于分压。"""
    order: list = []
    router = _probe_router(order, fail_source="baostock", success_source="akshare")
    result, errors = router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert result is not None and result["source"] == "akshare", "baostock 故障应由 akshare 接管"
    assert order == ["baostock", "akshare"], "mootdx 不应被触发（akshare 已成功）"


def test_shard_result_still_reports_serving_source():
    """结果 source 字段如实反映实际服务源（A/B 对拍口径：data_source 归因不变）。"""
    order: list = []
    router = _probe_router(order, fail_source="akshare", success_source="baostock")
    result, _ = router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert result["source"] == "baostock"


# ──────────────────────────────────────────────────────────────────────────────
# 边界
# ──────────────────────────────────────────────────────────────────────────────

def test_shard_index_code_unaffected():
    """指数 code（sh000001）走指数路径（仅 akshare），分片不介入。"""
    akshare = StubAdapter("akshare", index_bars=[make_bar("sh000001")])
    baostock = StubAdapter("baostock", index_bars=[make_bar("sh000001")])
    mootdx = StubAdapter("mootdx", index_bars=[make_bar("sh000001")])
    router = DataRouter([baostock, akshare, mootdx])
    router.fetch_daily_bars("sh000001", "2026-09-30", "2026-09-30", "qfq")
    assert akshare.call_counts["index_daily"] == 1, "指数只走 akshare（分片不影响指数路径）"
    assert baostock.call_counts["index_daily"] == 0
    assert mootdx.call_counts["index_daily"] == 0
    assert mootdx.call_counts["daily_bars"] == 0, "指数 code 不落入股票分片路径"


def test_shard_single_source_config_falls_back_to_router_order(monkeypatch):
    """shard_sources 配置不足 2 个 → 退化为原 router_order（分压关闭，零风险兜底）。"""
    monkeypatch.setattr(settings, "shard_sources", ("baostock",))
    order: list = []
    router = _probe_router(order)
    router.fetch_daily_bars("000001", "2026-09-30", "2026-09-30", "qfq")
    assert order == ["baostock", "akshare", "mootdx"], "单分片应退化为默认顺序"


def test_shard_sources_env_override_flips_mapping(monkeypatch):
    """SOROS_SHARD_SOURCES 覆盖分片映射（如 "akshare,baostock" → 奇偶对调）。"""
    monkeypatch.setattr(settings, "shard_sources", ("akshare", "baostock"))
    order: list = []
    router = _probe_router(order)
    router.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert order[0] == "akshare", "覆盖后 600000（偶）应归属 akshare"
