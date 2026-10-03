package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.9 强势池/崩塌池判定纯函数契约测试（SentimentPoolEvaluator）。
 *
 * 对象池定义（阈值全部配置化，本测试用 SentimentProperties 默认值 + 显式改值验证 yml 读取）：
 *   P1. 近 3 个交易日内最高 limit_up_streak >= pool.min-streak（默认 3）
 *   P2. 近 5 个交易日内涨停次数 >= pool.min-limit-ups（默认 2）
 *   P3. 近 5 个交易日累计涨幅 >= pool.min-5d-gain（默认 50%，qfq 收盘价窗口：close5-close1)/close1*100）
 *   C1. limit_down_streak >= collapse.min-streak（默认 2，连续跌停）
 *   C2. 近 5 个交易日累计跌幅 <= -collapse.max-5d-drop（默认 30%，qfq 收盘价窗口）
 *
 * 铁律：涨停判定用不复权 change_pct，池累计用 qfq 收盘价窗口——坐标永不混用（schema.sql 列口径）。
 * 入参 5 根 bar 窗口（含当日，升序）；判定为纯函数（同输入 → 同输出）。
 *
 * ⚠️ 实现侧为 TODO 骨架，本测试当前红；Implementer 按本契约填充 isInStrongPool/isInCollapsePool。
 */
class SentimentPoolEvaluatorTest {

    private fun evaluator(properties: SentimentProperties = SentimentProperties()) =
        SentimentPoolEvaluator(properties)

    /** 构造一根日线 bar（口径：close=qfq，change_pct=不复权） */
    private fun bar(
        date: LocalDate = LocalDate.of(2026, 9, 30),
        code: String = "600000",
        close: BigDecimal = BigDecimal("10.0000"),
        changePct: BigDecimal = BigDecimal("0.00"),
        isLimitUp: Boolean = false,
        limitUpStreak: Short = 0,
        isLimitDown: Boolean = false,
        limitDownStreak: Short = 0,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.close = close
        this.changePct = changePct
        this.isLimitUp = isLimitUp
        this.limitUpStreak = limitUpStreak
        this.isLimitDown = isLimitDown
        this.limitDownStreak = limitDownStreak
    }

    /** 5 个连续交易日（升序窗口） */
    private val d1 = LocalDate.of(2026, 9, 24)
    private val d2 = LocalDate.of(2026, 9, 25)
    private val d3 = LocalDate.of(2026, 9, 28)
    private val d4 = LocalDate.of(2026, 9, 29)
    private val d5 = LocalDate.of(2026, 9, 30)

    // ==================== P1：近3日最高 limit_up_streak >= min-streak(3) ====================

    @Test
    fun `testIsInStrongPool P1 - 近3日最高streak恰好3板入池`() {
        // given: 近3日（d3/d4/d5）中 d4 曾达 3 板（limit_up_streak=3），d5 断板回落
        val bars = listOf(
            bar(d1), bar(d2), bar(d3),
            bar(d4, limitUpStreak = 3),
            bar(d5),
        )

        // when & then: P1 命中（阈值恰等）
        assertTrue(evaluator().isInStrongPool(bars), "P1 近3日最高=3 ≥ min-streak=3 → 入池")
    }

    @Test
    fun `testIsInStrongPool P1 - 近3日最高streak差一点不入池`() {
        // given: 近3日最高 limit_up_streak=2（< min-streak=3）
        val bars = listOf(
            bar(d1), bar(d2),
            bar(d3, limitUpStreak = 2),
            bar(d4), bar(d5),
        )

        // when & then: P1 未命中（且无其他条件命中）
        assertFalse(evaluator().isInStrongPool(bars), "P1 近3日最高=2 < 3 → 不入池")
    }

    @Test
    fun `testIsInStrongPool P1 - 老板不计入近3日窗口`() {
        // given: 3 板在 d1（窗口外），近3日最高=0
        val bars = listOf(
            bar(d1, limitUpStreak = 3),
            bar(d2), bar(d3), bar(d4), bar(d5),
        )

        // when & then: P1 窗口是近3日（d3-d5），d1 的 3 板不在窗口内
        assertFalse(evaluator().isInStrongPool(bars), "P1 窗口近3日，d1 老板不计")
    }

    // ==================== P2：近5日涨停次数 >= min-limit-ups(2) ====================

    @Test
    fun `testIsInStrongPool P2 - 近5日恰好2次涨停入池`() {
        // given: d1/d3 涨停，近5日涨停次数=2（阈值恰等）
        val bars = listOf(
            bar(d1, isLimitUp = true, limitUpStreak = 1),
            bar(d2),
            bar(d3, isLimitUp = true, limitUpStreak = 2),
            bar(d4), bar(d5),
        )

        // when & then
        assertTrue(evaluator().isInStrongPool(bars), "P2 涨停次数=2 ≥ min-limit-ups=2 → 入池")
    }

    @Test
    fun `testIsInStrongPool P2 - 近5日仅1次涨停不入池`() {
        // given: 仅 d1 涨停
        val bars = listOf(
            bar(d1, isLimitUp = true, limitUpStreak = 1),
            bar(d2), bar(d3), bar(d4), bar(d5),
        )

        // when & then
        assertFalse(evaluator().isInStrongPool(bars), "P2 涨停次数=1 < 2 → 不入池")
    }

    // ==================== P3：近5日 qfq 收盘累计涨幅 >= min-5d-gain(50%) ====================

    @Test
    fun `testIsInStrongPool P3 - 累计涨幅恰好50pct入池`() {
        // given: close 从 10.0 → 15.0，累计涨幅 +50%（阈值恰等，qfq 收盘价窗口）
        val bars = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("11.0000")),
            bar(d3, close = BigDecimal("12.0000")),
            bar(d4, close = BigDecimal("13.0000")),
            bar(d5, close = BigDecimal("15.0000")),
        )

        // when & then
        assertTrue(evaluator().isInStrongPool(bars), "P3 累计+50% ≥ min-5d-gain=50 → 入池")
    }

    @Test
    fun `testIsInStrongPool P3 - 累计涨幅差一点不入池`() {
        // given: close 从 10.0 → 14.9，累计涨幅 +49%
        val bars = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("11.0000")),
            bar(d3, close = BigDecimal("12.0000")),
            bar(d4, close = BigDecimal("13.5000")),
            bar(d5, close = BigDecimal("14.9000")),
        )

        // when & then
        assertFalse(evaluator().isInStrongPool(bars), "P3 累计+49% < 50 → 不入池")
    }

    // ==================== 组合：任一命中即入池 ====================

    @Test
    fun `testIsInStrongPool anyConditionHitEntersPool`() {
        // given: P1/P2/P3 全部不命中，但 change_pct=5.5（大肉阈值无关池判定）——确保纯池子判定不被其他列污染
        val bars = listOf(
            bar(d1), bar(d2), bar(d3), bar(d4),
            bar(d5, changePct = BigDecimal("5.50")),
        )

        // when & then
        assertFalse(evaluator().isInStrongPool(bars), "无 P1/P2/P3 命中 → 不入池")
    }

    // ==================== C1：limit_down_streak >= min-streak(2) ====================

    @Test
    fun `testIsInCollapsePool C1 - 连续跌停恰好2板入池`() {
        // given: 当日 limit_down_streak=2（阈值恰等）
        val bars = listOf(
            bar(d1), bar(d2), bar(d3), bar(d4),
            bar(d5, isLimitDown = true, limitDownStreak = 2),
        )

        // when & then
        assertTrue(evaluator().isInCollapsePool(bars), "C1 跌停连板=2 ≥ min-streak=2 → 入崩塌池")
    }

    @Test
    fun `testIsInCollapsePool C1 - 连续跌停1板不入池`() {
        // given: 当日 limit_down_streak=1（< min-streak=2）
        val bars = listOf(
            bar(d1), bar(d2), bar(d3), bar(d4),
            bar(d5, isLimitDown = true, limitDownStreak = 1),
        )

        // when & then
        assertFalse(evaluator().isInCollapsePool(bars), "C1 跌停连板=1 < 2 → 不入崩塌池")
    }

    // ==================== C2：近5日累计跌幅 <= -max-5d-drop(-30%) ====================

    @Test
    fun `testIsInCollapsePool C2 - 累计跌幅恰好-30pct入池`() {
        // given: close 从 10.0 → 7.0，累计跌幅 -30%（阈值恰等）
        val bars = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("9.5000")),
            bar(d3, close = BigDecimal("9.0000")),
            bar(d4, close = BigDecimal("8.0000")),
            bar(d5, close = BigDecimal("7.0000")),
        )

        // when & then
        assertTrue(evaluator().isInCollapsePool(bars), "C2 累计-30% ≤ -30 → 入崩塌池")
    }

    @Test
    fun `testIsInCollapsePool C2 - 累计跌幅差一点不入池`() {
        // given: close 从 10.0 → 7.05，累计跌幅 -29.5%（> -30）
        val bars = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("9.5000")),
            bar(d3, close = BigDecimal("9.0000")),
            bar(d4, close = BigDecimal("8.0000")),
            bar(d5, close = BigDecimal("7.0500")),
        )

        // when & then
        assertFalse(evaluator().isInCollapsePool(bars), "C2 累计-29.5% > -30 → 不入崩塌池")
    }

    // ==================== 阈值从 yml 读（显式改 SentimentProperties） ====================

    @Test
    fun `testIsInStrongPool thresholdsFromConfiguredProperties`() {
        // given: 自定义阈值（yml 覆盖场景：min-streak=4, min-limit-ups=3, min-5d-gain=80）
        val props = SentimentProperties().apply {
            pool.minStreak = 4
            pool.minLimitUps = 3
            pool.min5dGain = BigDecimal("80")
        }
        // 近3日最高 3 板 + 近5日涨停 2 次 + 累计涨幅 50% —— 默认阈值下全命中，自定义阈值下全不命中
        val bars = listOf(
            bar(d1, isLimitUp = true, limitUpStreak = 1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("11.0000")),
            bar(d3, isLimitUp = true, limitUpStreak = 3, close = BigDecimal("12.0000")),
            bar(d4, close = BigDecimal("13.0000")),
            bar(d5, close = BigDecimal("15.0000")),
        )

        // when & then: 阈值读配置而非硬编码
        assertFalse(evaluator(props).isInStrongPool(bars), "自定义 min-streak=4/min-limit-ups=3/min-5d-gain=80 全不命中")
        assertTrue(evaluator(SentimentProperties()).isInStrongPool(bars), "默认阈值命中（对照）")
    }

    @Test
    fun `testIsInCollapsePool thresholdsFromConfiguredProperties`() {
        // given: 自定义阈值（collapse.min-streak=3, max-5d-drop=20）
        val props = SentimentProperties().apply {
            collapse.minStreak = 3
            collapse.max5dDrop = BigDecimal("20")
        }
        val bars = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("9.0000")),
            bar(d3, close = BigDecimal("8.5000")),
            bar(d4, close = BigDecimal("8.0000")),
            bar(d5, isLimitDown = true, limitDownStreak = 2, close = BigDecimal("7.0000")),
        )

        // when & then: 默认阈值下 C1=2 命中；自定义 C1=3 且 C2=-30% 阈值下也不命中（C2 需 ≤-20，这里 -30 命中…用-19 场景看）——
        // 用 close=8.2（-18%）验证 C2 自定义 20 不命中
        val noHit = listOf(
            bar(d1, close = BigDecimal("10.0000")),
            bar(d2, close = BigDecimal("9.0000")),
            bar(d3, close = BigDecimal("8.8000")),
            bar(d4, close = BigDecimal("8.5000")),
            bar(d5, isLimitDown = true, limitDownStreak = 2, close = BigDecimal("8.2000")),
        )
        assertFalse(evaluator(props).isInCollapsePool(noHit), "自定义 min-streak=3/max-5d-drop=20 不命中（跌停2板<3 且跌幅-18%>-20）")
        assertTrue(evaluator(SentimentProperties()).isInCollapsePool(bars), "默认阈值 C1=2 命中（对照）")
        assertEquals(BigDecimal("30"), SentimentProperties().collapse.max5dDrop, "默认 max-5d-drop=30")
    }
}
