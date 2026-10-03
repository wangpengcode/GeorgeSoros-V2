package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.9/§13.5 情绪周期计算服务纯函数契约测试（SentimentComputeService.computeFor）。
 *
 * computeFor 为纯函数：给定确定输入 → 确定输出；依赖数据全部显式经 [SentimentComputeContext] 注入，
 * 函数体内不访问 Repository —— Job 每日派生与冷启动回放共用同一实现（§13.5）。
 *
 * 派生口径（§4.9 step1-8，本测试定义的行为契约）：
 * - limit_up_count / lianban_count(limit_up_streak>=2) / max_streak 由当日 bars 派生
 * - big_meat_count = 强势池内今日 change_pct >= +5%（pool.big-meat-threshold）
 * - big_face_count = 强势池内今日 change_pct <= -5%
 * - 输出 sentiment.id 恒 0（未持久化），dragonUpdates/deadDragonCodes 为确定性列表
 *
 * ⚠️ 实现侧为 TODO 骨架，本测试当前红；Implementer 组装三个无状态组件（PoolEvaluator/Classifier/StateMachine）填充。
 */
class SentimentComputeServiceTest {

    private val date = LocalDate.of(2026, 9, 30)

    /** 组装真实无状态组件（三个纯函数组件 + 配置），断言侧不 Mock —— 契约即口径 */
    private fun service() = SentimentComputeServiceImpl(
        poolEvaluator = SentimentPoolEvaluator(SentimentProperties()),
        classifier = SentimentClassifier(SentimentProperties()),
        stateMachine = DragonCycleStateMachine(SentimentProperties()),
        properties = SentimentProperties(),
    )

    private fun bar(
        code: String,
        date: LocalDate,
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

    /** 5 日窗口（前 4 日普通 + 今日带属性）；供各股构造近 5 交易日行情 */
    private fun window(
        code: String,
        today: StockHistory,
    ): List<StockHistory> {
        val prev = (0 until 4).map { i -> bar(code, date.minusDays((4 - i).toLong())) }
        return prev + today
    }

    /**
     * 构造确定性子样例（期望派生结果）：
     * - A 600000：今日涨停 streak=3（P1 命中），change_pct=9.98 → 大肉（limit_up + lianban + big_meat）
     * - C 300750：d3 涨停 + 今日涨停 streak=2（P2 命中），change_pct=20.00 → 大肉（limit_up + lianban + big_meat）
     * - B 600036：d4 streak=3（P1 命中），今日未涨停 change_pct=-6.50 → 大面（big_face）
     * - D 600050：无 P1/P2/P3 命中，change_pct=1.00 → 不入池不计
     */
    private fun buildCtx(): SentimentComputeContext {
        val aToday = bar("600000", date, changePct = BigDecimal("9.98"), isLimitUp = true, limitUpStreak = 3)
        val cPrev = bar("300750", date.minusDays(2), isLimitUp = true, limitUpStreak = 1)
        val cToday = bar("300750", date, changePct = BigDecimal("20.00"), isLimitUp = true, limitUpStreak = 2)
        val bPrev = bar("600036", date.minusDays(1), isLimitUp = true, limitUpStreak = 3)
        val bToday = bar("600036", date, changePct = BigDecimal("-6.50"))
        val dToday = bar("600050", date, changePct = BigDecimal("1.00"))

        val barsByCode = mapOf(
            "600000" to window("600000", aToday),
            "300750" to listOf(bar("300750", date.minusDays(4)), bar("300750", date.minusDays(3)), cPrev, bar("300750", date.minusDays(1)), cToday),
            "600036" to listOf(bar("600036", date.minusDays(4)), bar("600036", date.minusDays(3)), bar("600036", date.minusDays(2)), bPrev, bToday),
            "600050" to window("600050", dToday),
        )
        return SentimentComputeContext(
            date = date,
            prevCycle = null,
            barsByCode = barsByCode,
            calendar = (0 until 5).map { i -> date.minusDays((4 - i).toLong()) },
            activeDragonCycles = emptyList(),
        )
    }

    // ==================== 纯函数性 ====================

    @Test
    fun `testComputeFor pureFunction sameContextSameResult`() {
        // given: 同一 ctx
        val ctx = buildCtx()
        val service = service()

        // when: 两次调用
        val r1 = service.computeFor(date, ctx)
        val r2 = service.computeFor(date, ctx)

        // then: 完全一致（回放与增量同路径的根基，§13.5）
        assertEquals(r1.sentiment.tradeDate, r2.sentiment.tradeDate, "trade_date 一致")
        assertEquals(r1.sentiment.limitUpCount, r2.sentiment.limitUpCount, "limit_up_count 一致")
        assertEquals(r1.sentiment.bigMeatCount, r2.sentiment.bigMeatCount, "big_meat_count 一致")
        assertEquals(r1.sentiment.bigCycleSug, r2.sentiment.bigCycleSug, "big_cycle_sug 一致")
        assertEquals(r1.dragonUpdates.size, r2.dragonUpdates.size, "dragonUpdates 一致")
        assertEquals(r1.deadDragonCodes, r2.deadDragonCodes, "deadDragonCodes 一致")
    }

    // ==================== 派生列口径 ====================

    @Test
    fun `testComputeFor derives aggregate columns from today bars`() {
        // when
        val r = service().computeFor(date, buildCtx())

        // then: §4.9 step1 全市场派生列
        assertEquals(date, r.sentiment.tradeDate, "trade_date=计算日")
        assertEquals(2, r.sentiment.limitUpCount, "limit_up_count=今日涨停 2 只（A/C）")
        assertEquals(2, r.sentiment.lianbanCount, "lianban_count=streak>=2 两只（A=3/C=2）")
        assertEquals(3, r.sentiment.maxStreak, "max_streak=当日最高板 3（A）")
    }

    @Test
    fun `testComputeFor derives pool meat face counts from strong pool`() {
        // when
        val r = service().computeFor(date, buildCtx())

        // then: §4.9 step2/3 大肉/大面 = 强势池内今日 ±5%
        assertEquals(3, r.sentiment.poolCount, "pool_count=强势池 3 只（A/B/C）")
        assertEquals(2, r.sentiment.bigMeatCount, "big_meat_count=A/C（池内 ≥+5%）")
        assertEquals(1, r.sentiment.bigFaceCount, "big_face_count=B（池内 ≤-5%）")
        assertEquals(0, r.sentiment.collapseCount, "collapse_count=0（样例无跌停连板）")
    }

    @Test
    fun `testComputeFor result sentiment not persisted and dragon lists deterministic`() {
        // when
        val r = service().computeFor(date, buildCtx())

        // then: 输出未持久化（id=0，由 Job/回放落库）+ 龙头变更列表可空但非 null
        assertEquals(0L, r.sentiment.id, "输出 sentiment.id=0（未持久化）")
        assertNotNull(r.dragonUpdates, "dragonUpdates 非 null（可为空列表）")
        assertNotNull(r.deadDragonCodes, "deadDragonCodes 非 null（可为空列表）")
        assertTrue(r.sentiment.bigMeatList == null || r.sentiment.bigMeatCount == 0, "大肉名单与 count 同源一致")
    }

    // ==================== 回放首日（无 prevCycle） ====================

    @Test
    fun `testComputeFor replayFirstDayNoPrevCycleComputes`() {
        // given: 回放首日 prevCycle=null（§13.5 冷启动）
        val ctx = buildCtx()

        // when: 不炸、正常产出
        val r = service().computeFor(date, ctx)

        // then: 基础列齐（followup 为空，因为无昨日名单）
        assertNotNull(r.sentiment, "sentiment 产出")
        assertTrue(r.sentiment.followupJson == null, "回放首日无昨日名单，followup 空")
    }
}
