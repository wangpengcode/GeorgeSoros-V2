package com.soros.v2.service.signal

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * §12.4.1 / §19.11.1 筹码分布递推纯函数契约测试（ChipDistributionCalculator）。
 *
 * 本测试是实现的唯一规格：手算黄金用例 + 边界纪律。实现方（Implementer）以本测试全绿为准。
 *
 * 算法口径（§19.11.1 定稿，公式逐条对应）：
 * - 递推：D_t = D_{t-1}×(1−tr) + tr×triangular(qfq_high, qfq_low, qfq_close)，180 桶、qfq 坐标全程
 * - 分位端点：c90=p5/p95、c70=p15/p85（决策 2）；集中度 conc=(high−low)/(high+low)×100（东财口径）
 * - cost_dev = (close−avg_cost)/avg_cost×100（PLAN 版符号，决策 1）；avg_cost=分布均值（不落库）
 * - 获利盘 profit_ratio = CDF(现价)（现价下方筹码权重占比）
 * - warm-up：前 60 交易日 8 列全 NULL，第 61 日起有值（§17.1 B7）
 * - 边界：一字板 ±0.5% 扁平兜底、换手率 clamp ≤1、停牌分布冻结、上市首日全量换手
 *
 * 黄金用例手算基准（连续解析三角分布值；180 桶离散化引入微差，断言容差覆盖）：
 *   triang(11,9,10)  ：avg=10.0000  cost_dev=0.0000  p5=9.3162  p15=9.5477  p85=10.4523  p95=10.6838
 *   triang(11,9,10.5)：avg=10.1667  cost_dev=3.2787  p5=9.3873  p15=9.6708  p85=10.6127  p95=10.7764
 *   0.8×triang(11,9,10.5)+0.2×triang(11,9,10)：
 *                      avg=10.1333  cost_dev=−1.3158  p5=9.3693  p15=9.6396  p85=10.5918  p95=10.7643
 *
 * ⚠️ 实现侧为 TODO 空壳，本测试当前红；Implementer 填充 [ChipDistributionCalculatorImpl] 后应全绿。
 */
class ChipDistributionCalculatorTest {

    private val start = LocalDate.of(2025, 1, 2)

    /** 组装真实实现（纯函数无 Mock） */
    private fun calc() = ChipDistributionCalculatorImpl()

    private fun bar(
        offset: Int,
        high: String,
        low: String,
        close: String,
        turnoverRate: String,
    ) = ChipBarInput(
        tradeDate = start.plusDays(offset.toLong()),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        turnoverRate = BigDecimal(turnoverRate),
    )

    private fun warmUpBars(count: Int = 60): List<ChipBarInput> =
        (0 until count).map { i -> bar(i, "11.0000", "9.0000", "10.0000", "0.10") }

    // ==================== 黄金用例 ====================

    /**
     * 黄金用例：10 日递推（warm-up 60 日 + 4 个断言日，§19.11.1 递推公式手算）。
     *
     * 构造：60 日 warm-up（常量 bar）→ 第 61 日 tr=1.0 全量换手重置为 triang(11,9,10) →
     * 第 62 日 tr=1.0 重置为 triang(11,9,10.5) → 第 63 日 tr=0.2 混合 → 第 64 日 tr=0.0 冻结。
     * 换手率=1.0 使 D_t 恒等于当日三角（重置语义），混合日 D_t = 0.8×D_{t-1} + 0.2×triang(11,9,10)。
     */
    @Test
    fun `testGolden 10DayRecursion hand computed chip columns`() {
        // given: 60 日 warm-up + 4 个断言日
        val bars = warmUpBars() + listOf(
            bar(60, "11.0000", "9.0000", "10.0000", "1.00"),   // day61 全量换手重置
            bar(61, "11.0000", "9.0000", "10.5000", "1.00"),   // day62 全量换手重置（右偏三角）
            bar(62, "11.0000", "9.0000", "10.0000", "0.20"),   // day63 混合
            bar(63, "11.0000", "9.0000", "10.0000", "0.00"),   // day64 换手=0 冻结
        )

        // when
        val results = calc().computeForHistory(bars)

        // then: 结果与 bars 一一对应
        assertEquals(64, results.size, "结果与 bars 一一对应")

        // then ①: warm-up 前 60 日 8 列全 NULL（§17.1 B7）
        for (i in 0 until 60) {
            val r = results[i]
            assertNull(r.profitRatio, "day${i + 1} profit_ratio warm-up NULL")
            assertNull(r.costDev, "day${i + 1} cost_dev warm-up NULL")
            assertNull(r.c90Low, "day${i + 1} c90_low warm-up NULL")
            assertNull(r.c90High, "day${i + 1} c90_high warm-up NULL")
            assertNull(r.c90Conc, "day${i + 1} c90_conc warm-up NULL")
            assertNull(r.c70Low, "day${i + 1} c70_low warm-up NULL")
            assertNull(r.c70High, "day${i + 1} c70_high warm-up NULL")
            assertNull(r.c70Conc, "day${i + 1} c70_conc warm-up NULL")
        }

        // then ②: 第 61 日 = triang(11,9,10)（解析值，§19.11.1 三角 CDF 逆函数）
        assertChip(
            results[60],
            expectedProfit = 50.0,
            expectedCostDev = 0.0,
            expectedC90Low = 9.3162,
            expectedC90High = 10.6838,
            expectedC90Conc = 6.8377,
            expectedC70Low = 9.5477,
            expectedC70High = 10.4523,
            expectedC70Conc = 4.5228,
            label = "day61 triang(11,9,10)",
        )

        // then ③: 第 62 日 = triang(11,9,10.5)（右偏，mode=10.5，对称点 p=(c−a)/(b−a)=0.75）
        assertChip(
            results[61],
            expectedProfit = 75.0,          // CDF(10.5)=(10.5−9)/(11−9)=0.75
            expectedCostDev = 3.2787,       // (10.5−10.1667)/10.1667×100
            expectedC90Low = 9.3873,        // 9+sqrt(0.05×2×1.5)
            expectedC90High = 10.7764,      // 11−sqrt(0.05×2×0.5)
            expectedC90Conc = 6.8890,
            expectedC70Low = 9.6708,        // 9+sqrt(0.15×3)
            expectedC70High = 10.6127,      // 11−sqrt(0.15×2×0.5)
            expectedC70Conc = 4.6437,
            label = "day62 triang(11,9,10.5)",
        )

        // then ④: 第 63 日 = 0.8×triang(11,9,10.5) + 0.2×triang(11,9,10)（递推混合，D_t=D_{t-1}×(1−tr)+tr×D_new）
        //   CDF_63(x) = 0.8×CDF_A(x) + 0.2×CDF_B(x)；x<9.5 段 CDF_B=0
        assertChip(
            results[62],
            expectedProfit = 36.67,         // 0.8×CDF_A(10)+0.2×CDF_B(10)=0.8×(1/3)+0.2×0.5=0.3667
            expectedCostDev = -1.3158,      // (10−10.1333)/10.1333×100
            expectedC90Low = 9.3693,        // 解 0.3667(x−9)²=0.05
            expectedC90High = 10.7643,      // 解 1−0.9(11−x)²=0.95
            expectedC90Conc = 6.9292,
            expectedC70Low = 9.6396,        // 解 0.3667(x−9)²=0.15
            expectedC70High = 10.5918,      // 解 1−0.9(11−x)²=0.85
            expectedC70Conc = 4.7058,
            label = "day63 blend 0.8×triang(11,9,10.5)+0.2×triang(11,9,10)",
        )

        // then ⑤: 第 64 日 tr=0.0 → 分布冻结 = 第 63 日（换手=0 不注入新筹码）
        assertSameChip(results[63], results[62], "day64 冻结 = day63")
    }

    // ==================== 边界条件 ====================

    /** 一字板（high=low）→ ±0.5% 扁平兜底（§19.11.1 边界）；全量换手下分布=均匀带 [close×0.995, close×1.005] */
    @Test
    fun `testYiZiBan flat band plusMinus halfPercent`() {
        // given: 60 日 warm-up + 一字板全量换手日（high=low=close=10）
        val bars = warmUpBars() + listOf(bar(60, "10.0000", "10.0000", "10.0000", "1.00"))

        // when
        val result = calc().computeForHistory(bars)[60]

        // then: 均匀带 [9.95,10.05] 的解析值（一字板 ±0.5% 扁平兜底）
        assertChip(
            result,
            expectedProfit = 50.0,
            expectedCostDev = 0.0,
            expectedC90Low = 9.955,    // 9.95 + 0.1×0.05
            expectedC90High = 10.045,  // 9.95 + 0.1×0.95
            expectedC90Conc = 0.45,    // (10.045−9.955)/(10.045+9.955)×100
            expectedC70Low = 9.965,    // 9.95 + 0.1×0.15
            expectedC70High = 10.035,  // 9.95 + 0.1×0.85
            expectedC70Conc = 0.35,    // (10.035−9.965)/(10.035+9.965)×100
            label = "yiziban flat band ±0.5%",
        )
    }

    /** 换手率 >1 → clamp 到 1（全量换手重置语义，§19.11.1 边界） */
    @Test
    fun `testTurnoverClamp gt1 acts as full reset`() {
        // given: 两串 bar 只有 tr>1 日的换手率不同（2.5 vs 1.0）
        val runA = warmUpBars() + listOf(
            bar(60, "11.0000", "9.0000", "10.0000", "1.00"),
            bar(61, "11.0000", "9.0000", "10.0000", "2.50"),   // clamp→1.0 重置
            bar(62, "11.0000", "9.0000", "10.0000", "0.50"),
        )
        val runB = warmUpBars() + listOf(
            bar(60, "11.0000", "9.0000", "10.0000", "1.00"),
            bar(61, "11.0000", "9.0000", "10.0000", "1.00"),   // 基准 1.0 重置
            bar(62, "11.0000", "9.0000", "10.0000", "0.50"),
        )

        // when
        val resA = calc().computeForHistory(runA)
        val resB = calc().computeForHistory(runB)

        // then: tr=2.5 与 tr=1.0 等价（clamp≤1）；后续日状态一致
        assertSameChip(resA[61], resB[61], "tr=2.5 clamp→1.0 = tr=1.0 全量重置")
        assertSameChip(resA[62], resB[62], "clamp 后混合状态一致")
    }

    /** 停牌（无 bar 输入）→ 分布冻结不变（§19.11.1 边界；递推只在 bar 日推进，缺日不衰减） */
    @Test
    fun `testSuspension noBar keeps distribution frozen`() {
        // given: runB 在 day62 前插入 5 日停牌（tradeDate 缺口），bar 值完全一致
        val runA = warmUpBars() + listOf(
            bar(60, "11.0000", "9.0000", "10.0000", "1.00"),
            bar(61, "11.0000", "9.0000", "10.5000", "1.00"),
            bar(62, "11.0000", "9.0000", "10.0000", "0.20"),
        )
        val runB = warmUpBars() + listOf(
            bar(60, "11.0000", "9.0000", "10.0000", "1.00"),
            bar(66, "11.0000", "9.0000", "10.5000", "1.00"),   // 停牌 5 日后复牌
            bar(67, "11.0000", "9.0000", "10.0000", "0.20"),
        )

        // when
        val resA = calc().computeForHistory(runA)
        val resB = calc().computeForHistory(runB)

        // then: 相同 bar 值 → 相同分布（停牌日无 bar 不衰减）
        assertSameChip(resA[60], resB[60], "停牌前分布一致")
        assertSameChip(resA[61], resB[61], "复牌日分布一致（停牌期间冻结无衰减）")
        assertSameChip(resA[62], resB[62], "复牌后次日分布一致")
    }

    /** warm-up：第 61 日起 8 列有值（§17.1 B7：宁可标空不用不准的数） */
    @Test
    fun `testWarmUp nullsUntil61st day then nonNull`() {
        // given: 恰好 61 根 bar
        val bars = warmUpBars() + listOf(bar(60, "11.0000", "9.0000", "10.0000", "1.00"))

        // when
        val results = calc().computeForHistory(bars)

        // then: 前 60 日 null、第 61 日非 null
        for (i in 0 until 60) {
            assertNull(results[i].profitRatio, "warm-up day${i + 1}")
        }
        val day61 = results[60]
        assertNotNull(day61.profitRatio, "第 61 日起 profit_ratio 有值")
        assertNotNull(day61.costDev, "第 61 日起 cost_dev 有值")
        assertNotNull(day61.c90Low, "第 61 日起 c90_low 有值")
        assertNotNull(day61.c90High, "第 61 日起 c90_high 有值")
        assertNotNull(day61.c90Conc, "第 61 日起 c90_conc 有值")
        assertNotNull(day61.c70Low, "第 61 日起 c70_low 有值")
        assertNotNull(day61.c70High, "第 61 日起 c70_high 有值")
        assertNotNull(day61.c70Conc, "第 61 日起 c70_conc 有值")
    }

    /** advanceOneDay 增量推进：prev=null（warm-up 起点 D_0 单峰近似）→ 第 1 日仍 NULL */
    @Test
    fun `testAdvanceOneDay nullPrev returns warmUp null`() {
        // when: prev=null（第 1 日）
        val result = calc().advanceOneDay(null, bar(0, "11.0000", "9.0000", "10.0000", "0.10"))

        // then: warm-up 首日全 NULL
        assertNull(result.profitRatio)
        assertNull(result.costDev)
        assertNull(result.c90Low)
        assertNull(result.c90High)
        assertNull(result.c90Conc)
        assertNull(result.c70Low)
        assertNull(result.c70High)
        assertNull(result.c70Conc)
    }

    /** advanceOneDay 增量推进：tr=1.0 全量换手 → 无视 prev 状态直接重置为当日三角（对基无关） */
    @Test
    fun `testAdvanceOneDay fullTurnover resets to today triangular`() {
        // given: prev = 均匀 180 桶分布（任意 prev；tr=1.0 应完全重置）
        val prev = ChipDistributionState(
            buckets = DoubleArray(180) { 1.0 / 180.0 },
            totalWeight = 1.0,
        )

        // when
        val result = calc().advanceOneDay(prev, bar(0, "11.0000", "9.0000", "10.0000", "1.00"))

        // then: 重置为 triang(11,9,10)（与黄金用例 day61 同值）
        assertChip(
            result,
            expectedProfit = 50.0,
            expectedCostDev = 0.0,
            expectedC90Low = 9.3162,
            expectedC90High = 10.6838,
            expectedC90Conc = 6.8377,
            expectedC70Low = 9.5477,
            expectedC70High = 10.4523,
            expectedC70Conc = 4.5228,
            label = "advanceOneDay tr=1.0 reset to triang(11,9,10)",
        )
    }

    /**
     * 除权检测 TODO：隐含昨收 ≠ 前日 close → 该票全日期重算重写。
     *
     * 按任务纪律，检测逻辑位于 SignalReplayService 层（§19.11.1「除权检测内置」），
     * ChipDistributionCalculator 为纯函数接口、无 prev_close 输入、无检测方法——不硬造接口方法。
     * 该用例在 SignalReplayService 集成测试中覆盖（补算重写语义）。
     */
    @Disabled("TODO 除权检测位于 SignalReplayService 层；ChipDistributionCalculator 无此接口方法（§19.11.1）")
    @Test
    fun `testExRightsDetection TODO located in replay layer`() {
        // 本测试仅作契约占位：除权重算触发点在回放/增量服务，非本纯函数。
    }

    // ==================== 断言辅助 ====================

    private fun assertChip(
        r: ChipDayResult,
        expectedProfit: Double,
        expectedCostDev: Double,
        expectedC90Low: Double,
        expectedC90High: Double,
        expectedC90Conc: Double,
        expectedC70Low: Double,
        expectedC70High: Double,
        expectedC70Conc: Double,
        label: String,
    ) {
        // warm-up 后各项必须非 null（断言前解包；null 直接红）
        val profit = r.profitRatio?.toDouble() ?: error("$label profit_ratio 非 null（warm-up 后）")
        val costDev = r.costDev?.toDouble() ?: error("$label cost_dev 非 null（warm-up 后）")
        val c90Low = r.c90Low?.toDouble() ?: error("$label c90_low 非 null（warm-up 后）")
        val c90High = r.c90High?.toDouble() ?: error("$label c90_high 非 null（warm-up 后）")
        val c90Conc = r.c90Conc?.toDouble() ?: error("$label c90_conc 非 null（warm-up 后）")
        val c70Low = r.c70Low?.toDouble() ?: error("$label c70_low 非 null（warm-up 后）")
        val c70High = r.c70High?.toDouble() ?: error("$label c70_high 非 null（warm-up 后）")
        val c70Conc = r.c70Conc?.toDouble() ?: error("$label c70_conc 非 null（warm-up 后）")
        // 价格列容差 0.05（180 桶宽 0.0111，覆盖插值误差）；百分比列容差 1.0
        assertEquals(expectedProfit, profit, 1.0, "$label profit_ratio")
        assertEquals(expectedCostDev, costDev, 1.0, "$label cost_dev")
        assertEquals(expectedC90Low, c90Low, 0.05, "$label c90_low")
        assertEquals(expectedC90High, c90High, 0.05, "$label c90_high")
        assertEquals(expectedC90Conc, c90Conc, 1.0, "$label c90_conc")
        assertEquals(expectedC70Low, c70Low, 0.05, "$label c70_low")
        assertEquals(expectedC70High, c70High, 0.05, "$label c70_high")
        assertEquals(expectedC70Conc, c70Conc, 1.0, "$label c70_conc")
    }

    private fun assertSameChip(a: ChipDayResult, b: ChipDayResult, label: String) {
        assertEquals(a.profitRatio, b.profitRatio, "$label profit_ratio")
        assertEquals(a.costDev, b.costDev, "$label cost_dev")
        assertEquals(a.c90Low, b.c90Low, "$label c90_low")
        assertEquals(a.c90High, b.c90High, "$label c90_high")
        assertEquals(a.c90Conc, b.c90Conc, "$label c90_conc")
        assertEquals(a.c70Low, b.c70Low, "$label c70_low")
        assertEquals(a.c70High, b.c70High, "$label c70_high")
        assertEquals(a.c70Conc, b.c70Conc, "$label c70_conc")
    }
}
