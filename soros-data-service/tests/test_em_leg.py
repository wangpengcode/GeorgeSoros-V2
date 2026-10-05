"""akshare EM 腿级熔断测试（2026-10-05 均分流量定稿）。

背景（2026-10-05 生产实测）：EM(push2his) 整族被 WAF 拒连，EM 失败被内部 failover
到新浪掩盖——每票都先白撞一次 EM（超时级联拖慢整批），且 EM 故障不外抛，源级熔断
永远看不到它。腿级熔断：EM 连续 3 次失败 → 腿开（直走新浪），每 300s 放行一次探针；
探针成功腿闭合，探针失败刷新 300s 窗口。EM 失败仍不外抛（不烧源级熔断/IPGuard）。

时钟注入：adapter._clock（默认 time.monotonic），测试替换实例属性。
"""

from __future__ import annotations

from helpers import FakeCircuitBreaker, FakeRateLimiter, make_bar
from adapters.akshare_adapter import AkshareAdapter


def make_adapter() -> AkshareAdapter:
    return AkshareAdapter(FakeCircuitBreaker(), FakeRateLimiter())


def _wire(adapter, em_fail: bool, em_calls: dict, sina_calls: dict) -> None:
    """钉住 EM/新浪两腿：EM 按 em_fail 抛错，两腿都记录调用次数。"""
    def em(code, start, end, adjust):
        em_calls["n"] += 1
        if em_fail:
            raise ConnectionError("EM push2his 拒连")
        return [make_bar(code)]

    def sina(code, start, end, adjust):
        sina_calls["n"] += 1
        return [make_bar(code)]

    adapter._fetch_em_daily = em
    adapter._fetch_sina_daily = sina


def test_em_leg_opens_after_three_consecutive_failures():
    """EM 连续 3 次失败 → 腿开：第 4 次起直走新浪，不再撞 EM。"""
    adapter = make_adapter()
    adapter._clock = lambda: 1000.0
    em_calls, sina_calls = {"n": 0}, {"n": 0}
    _wire(adapter, em_fail=True, em_calls=em_calls, sina_calls=sina_calls)
    for _ in range(4):
        result = adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
        assert len(result) == 1, "新浪腿接住，不外抛"
    assert em_calls["n"] == 3, "恰 3 次真实 EM 尝试后腿开"
    assert sina_calls["n"] == 4
    assert adapter.serving_source == "akshare-sina", "腿开期间归因新浪"


def test_em_success_resets_streak():
    """失败 2 次 → EM 成功清零计数 → 再失败 2 次不腿开（非累计阈值）。"""
    adapter = make_adapter()
    adapter._clock = lambda: 1000.0
    em_calls, sina_calls = {"n": 0}, {"n": 0}
    flip = {"fail": True}
    def em(code, start, end, adjust):
        em_calls["n"] += 1
        if flip["fail"]:
            raise ConnectionError("EM down")
        return [make_bar(code)]
    adapter._fetch_em_daily = em
    adapter._fetch_sina_daily = lambda *a: (sina_calls.__setitem__("n", sina_calls["n"] + 1), [make_bar(a[0])])[1]
    for _ in range(2):
        adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    flip["fail"] = False
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")   # EM 成功，清零
    assert adapter.serving_source == "akshare", "EM 恢复归因 akshare"
    flip["fail"] = True
    for _ in range(2):
        adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 5, "2+1+2 全部真实尝试 EM（成功打断连续计数）"
    flip["fail"] = True
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 6, "第 3 次连续失败腿开，本票仍尝试过 EM"


def test_em_probe_after_300s_success_closes_leg():
    """腿开 300s 后放行一次探针：探针成功 → 腿闭合，后续票恢复走 EM。"""
    adapter = make_adapter()
    now = [1000.0]
    adapter._clock = lambda: now[0]
    em_calls, sina_calls = {"n": 0}, {"n": 0}
    flip = {"fail": True}
    def em(code, start, end, adjust):
        em_calls["n"] += 1
        if flip["fail"]:
            raise ConnectionError("EM down")
        return [make_bar(code)]
    adapter._fetch_em_daily = em
    adapter._fetch_sina_daily = lambda *a: (sina_calls.__setitem__("n", sina_calls["n"] + 1), [make_bar(a[0])])[1]
    for _ in range(3):
        adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 3
    now[0] += 299.0
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 3, "299s 未到探针窗口，EM 不放行"
    now[0] += 1.0
    flip["fail"] = False
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 4, "300s 探针窗口到，放行一次 EM"
    assert adapter.serving_source == "akshare", "探针成功归因 akshare"
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 5, "探针成功腿闭合，后续票正常走 EM"


def test_em_probe_failure_refreshes_window():
    """探针失败 → 刷新 300s 窗口（期间继续直走新浪），到期才放行下一次探针。"""
    adapter = make_adapter()
    now = [1000.0]
    adapter._clock = lambda: now[0]
    em_calls, sina_calls = {"n": 0}, {"n": 0}
    _wire(adapter, em_fail=True, em_calls=em_calls, sina_calls=sina_calls)
    for _ in range(3):
        adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 3
    now[0] += 300.0
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 4, "探针放行一次（失败）"
    now[0] += 100.0
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 4, "探针失败刷新窗口，100s 内不再撞 EM"
    now[0] += 201.0
    adapter.fetch_daily_bars("600000", "2026-09-30", "2026-09-30", "qfq")
    assert em_calls["n"] == 5, "距上次探针 300s 到，放行下一次探针"
