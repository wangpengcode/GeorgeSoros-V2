package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.9 龙头生命周期状态机契约测试（DragonCycleStateMachine，逐日推进纯函数）。
 *
 * 状态流转（§4.9）：RISING → BROKEN（观察期）→ [反包→RISING | 阵亡→DEAD | 停牌→SUSPENDED]。
 * 用户口径：小周期=龙头 5-7 板断板且不反包；大周期=断板后反包再涨停创新高。
 * 停牌不结束周期：复牌后再走断板/反包判定。
 *
 * 观察期定义（本测试契约）：broken_date 之后的 observeDays 个交易日，第 observeDays 日仍未反包 → DEAD；
 * 观察期内任一交易日涨停 → 反包（rebreak_count+1，status 回 RISING；若连板超断板前 max_streak → cycle_type=BIG）。
 *
 * 上位判定（electLeader，§4.9 step1）：前一龙头 DEAD 后，当日最高板股票接棒开新周期（并列取先到者）；
 * 回放首日无前文 → 取区间开始时最高板（BOOT 首日）。
 *
 * ⚠️ 实现侧为 TODO 骨架，本测试当前红；Implementer 按本契约填充 advance/electLeader。
 */
class DragonCycleStateMachineTest {

    private val observeDays = 3
    private val smallMaxStreak = 7

    private fun machine(observe: Int = observeDays, smallMax: Int = smallMaxStreak) =
        DragonCycleStateMachine(
            SentimentProperties().apply {
                dragon.observeDays = observe
                dragon.smallMaxLimitUpStreak = smallMax
            },
        )

    private val day = LocalDate.of(2026, 9, 30)

    /** 构造龙头周期（进行中默认 RISING） */
    private fun cycle(
        code: String = "600000",
        startDate: LocalDate = day.minusDays(10),
        maxStreak: Short = 5,
        rebreakCount: Short = 0,
        suspendedDays: Short = 0,
        cycleType: CycleType? = null,
        status: CycleStatus = CycleStatus.RISING,
        brokenDate: LocalDate? = null,
    ) = DragonCycle().apply {
        this.code = code
        this.startDate = startDate
        this.endDate = null
        this.maxStreak = maxStreak
        this.rebreakCount = rebreakCount
        this.suspendedDays = suspendedDays
        this.suspendJson = null
        this.cycleType = cycleType
        this.status = status
        this.brokenDate = brokenDate
        this.note = null
    }

    private fun bar(
        code: String = "600000",
        date: LocalDate = day,
        changePct: BigDecimal = BigDecimal("0.00"),
        isLimitUp: Boolean = false,
        limitUpStreak: Short = 0,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.close = BigDecimal("10.0000")
        this.changePct = changePct
        this.isLimitUp = isLimitUp
        this.limitUpStreak = limitUpStreak
    }

    /** 交易日历：断板日 D0 起共 n 个交易日（含当日） */
    private fun calendar(day0: LocalDate, n: Int): List<LocalDate> =
        (0 until n).map { day0.plusDays(it.toLong()) }

    // ==================== RISING：晋级（streak+1） ====================

    @Test
    fun `testAdvance rising limitUp refreshes maxStreak stays rising`() {
        // given: RISING 周期 max_streak=5，今日涨停 streak=6（晋级）
        val c = cycle(maxStreak = 5)
        val today = bar(isLimitUp = true, limitUpStreak = 6)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: max_streak 刷新、状态停留 RISING、不结束
        assertEquals(6, t.cycle.maxStreak, "max_streak 刷新=6")
        assertEquals(CycleStatus.RISING, t.cycle.status, "停留 RISING")
        assertFalse(t.dead, "晋级不结束周期")
        assertNull(t.cycle.endDate, "end_date 仍 null")
    }

    // ==================== RISING：断板 → BROKEN ====================

    @Test
    fun `testAdvance rising noLimitUp goesBroken`() {
        // given: RISING 周期今日未涨停 → 断板
        val c = cycle(maxStreak = 5)
        val today = bar(isLimitUp = false)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 状态 BROKEN + broken_date=今日 + 观察期起点
        assertEquals(CycleStatus.BROKEN, t.cycle.status, "断板 → BROKEN")
        assertEquals(day, t.cycle.brokenDate, "broken_date=今日")
        assertFalse(t.dead, "断板日不立即阵亡")
        assertEquals(5, t.cycle.maxStreak, "max_streak 不变（断板日未晋级）")
    }

    // ==================== BROKEN：观察期内反包 → RISING ====================

    @Test
    fun `testAdvance broken rebreak within window backToRising`() {
        // given: BROKEN（broken_date=day-2），观察期内今日涨停（streak=1，未超 max_streak=5）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val today = bar(isLimitUp = true, limitUpStreak = 1)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 反包 → RISING，rebreak_count+1，cycle_type 未定性（未创新高）
        assertEquals(CycleStatus.RISING, t.cycle.status, "反包 → RISING")
        assertEquals(1, t.cycle.rebreakCount, "rebreak_count+1")
        assertNull(t.cycle.cycleType, "连板未超断板前最高板，cycle_type 仍 null")
        assertFalse(t.dead, "反包不结束")
        assertNull(t.cycle.endDate, "end_date 仍 null")
    }

    @Test
    fun `testAdvance broken rebreak newHigh qualifiesBigCycle`() {
        // given: BROKEN（broken_date=day-2），今日涨停 streak=6 超断板前 max_streak=5（创新高）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val today = bar(isLimitUp = true, limitUpStreak = 6)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 反包创新高 → cycle_type=BIG（小周期升级大周期，§4.9）
        assertEquals(CycleStatus.RISING, t.cycle.status, "反包 → RISING")
        assertEquals(1, t.cycle.rebreakCount, "rebreak_count+1")
        assertEquals(CycleType.BIG, t.cycle.cycleType, "创新高 → 定性 BIG")
    }

    // ==================== 断板定性（D10：大周期=反包再涨停并创新高；反包未创新高 → SMALL） ====================

    @Test
    fun `testAdvance rebreakNewHigh thenDiesQualifiesBig`() {
        // given: BROKEN（broken_date=day-2, max_streak=5）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val m = machine()

        // when: 反包创新高（streak 6 > 5）→ 回 RISING + cycle_type=BIG
        m.advance(day, c, bar(isLimitUp = true, limitUpStreak = 6), calendar(day.minusDays(2), 3))
        assertEquals(CycleType.BIG, c.cycleType, "反包创新高 → cycle_type 定格 BIG")
        assertEquals(CycleStatus.RISING, c.status, "反包回 RISING")

        // 再断板 → 观察期过阵亡
        m.advance(day.plusDays(1), c, bar(date = day.plusDays(1), isLimitUp = false), calendar(day.minusDays(2), 4))
        val t = m.advance(day.plusDays(4), c, bar(date = day.plusDays(4), isLimitUp = false), calendar(day.minusDays(2), 7))

        // then: 阵亡定性 BIG（反包创新高记忆保留）
        assertTrue(t.dead, "阵亡")
        assertEquals(CycleType.BIG, t.cycle.cycleType, "反包创新高后阵亡 → 定性 BIG")
    }

    @Test
    fun `testAdvance rebreakNoNewHigh thenDiesQualifiesSmallWithNote`() {
        // given: BROKEN（broken_date=day-2, max_streak=5）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val m = machine()

        // when: 反包未创新高（streak 1 < 5）→ 回 RISING，cycle_type 不定格
        m.advance(day, c, bar(isLimitUp = true, limitUpStreak = 1), calendar(day.minusDays(2), 3))
        assertNull(c.cycleType, "反包未创新高 → cycle_type 仍 null（不定格 BIG）")
        assertEquals(1, c.rebreakCount, "rebreak_count+1")
        assertEquals(CycleStatus.RISING, c.status, "反包回 RISING")

        // 再断板 → 观察期过阵亡
        m.advance(day.plusDays(1), c, bar(date = day.plusDays(1), isLimitUp = false), calendar(day.minusDays(2), 4))
        val t = m.advance(day.plusDays(4), c, bar(date = day.plusDays(4), isLimitUp = false), calendar(day.minusDays(2), 7))

        // then: 阵亡定性 SMALL + note 留痕（大周期需反包创新高，§4.9/D10）
        assertTrue(t.dead, "阵亡")
        assertEquals(CycleType.SMALL, t.cycle.cycleType, "反包未创新高后阵亡 → 定性 SMALL")
        assertNotNull(t.cycle.note, "SMALL 留痕 note")
        assertTrue(t.cycle.note!!.contains("未创新高"), "note 说明反包未创新高")
    }

    // ==================== BROKEN：观察期过 → DEAD ====================

    @Test
    fun `testAdvance broken observeWindowExhaustedDies`() {
        // given: BROKEN，broken_date=day-3，观察期第 3 日（observeDays=3）今日未反包 → 阵亡
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(3), maxStreak = 5)
        val today = bar(isLimitUp = false)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(3), 4))

        // then: DEAD + end_date=今日 + 小周期定性（max_streak=5 ≤ small-max 7 且无反包）
        assertEquals(CycleStatus.DEAD, t.cycle.status, "观察期无反包 → DEAD")
        assertEquals(day, t.cycle.endDate, "end_date=今日（阵亡日）")
        assertTrue(t.dead, "dead=true（供日报今日阵亡汇总）")
        assertEquals(CycleType.SMALL, t.cycle.cycleType, "5-7 板断板且不反包 → SMALL")
    }

    @Test
    fun `testAdvance broken highStreakDiesQualifiesBig`() {
        // given: max_streak=8 的 BROKEN 断板且不反包 → 大周期（max_streak ≥ 8）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(3), maxStreak = 8)
        val today = bar(isLimitUp = false)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(3), 4))

        // then: 8 板断板不反包 → 定性 BIG（§4.9 max_streak ≥ 8）
        assertEquals(CycleStatus.DEAD, t.cycle.status, "DEAD")
        assertTrue(t.dead, "dead=true")
        assertEquals(CycleType.BIG, t.cycle.cycleType, "max_streak≥8 断板不反包 → BIG")
    }

    @Test
    fun `testAdvance broken stillInWindowNoDeath`() {
        // given: BROKEN，broken_date=day-2，观察期第 2 日今日未涨停 → 仍观察中（未到第 3 日）
        val c = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val today = bar(isLimitUp = false)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 仍在 BROKEN（观察窗口未过），不 DEAD
        assertEquals(CycleStatus.BROKEN, t.cycle.status, "观察期第 2 日仍在窗口内")
        assertFalse(t.dead, "未阵亡")
        assertNull(t.cycle.endDate, "end_date 仍 null")
    }

    // ==================== 停牌 SUSPENDED → 复牌再判定 ====================

    @Test
    fun `testAdvance noBarOnTradingDaySuspends`() {
        // given: 交易日历含当日但龙头无 bar（今日停牌）
        val c = cycle(maxStreak = 5)
        val cal = calendar(day.minusDays(2), 3)

        // when
        val t = machine().advance(day, c, null, cal)

        // then: SUSPENDED + suspended_days+1 + suspend_json 记录区间
        assertEquals(CycleStatus.SUSPENDED, t.cycle.status, "无 bar → SUSPENDED")
        assertEquals(1, t.cycle.suspendedDays, "suspended_days+1")
        assertNotNull(t.cycle.suspendJson, "suspend_json 记录停牌区间")
        assertFalse(t.dead, "停牌不结束周期（周期延续，§4.9）")
        assertNull(t.cycle.endDate, "end_date 仍 null")
        assertNull(t.cycle.brokenDate, "停牌不改 broken_date")
    }

    @Test
    fun `testAdvance suspendedResumeOnBarRejudge`() {
        // given: SUSPENDED（停牌 2 天）复牌有 bar 且涨停
        val c = cycle(status = CycleStatus.SUSPENDED, suspendedDays = 2, maxStreak = 5)
        val today = bar(isLimitUp = true, limitUpStreak = 6)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 复牌后回到 RISING（涨停延续），suspended_days 保持历史值
        assertEquals(CycleStatus.RISING, t.cycle.status, "复牌涨停 → RISING")
        assertEquals(6, t.cycle.maxStreak, "max_streak 刷新")
        assertFalse(t.dead, "复牌晋级不结束")
    }

    @Test
    fun `testAdvance suspendedResumeBrokenWhenNoLimitUp`() {
        // given: SUSPENDED 复牌无涨停 → 走断板判定
        val c = cycle(status = CycleStatus.SUSPENDED, suspendedDays = 2, maxStreak = 5)
        val today = bar(isLimitUp = false)

        // when
        val t = machine().advance(day, c, today, calendar(day.minusDays(2), 3))

        // then: 复牌后未涨停 → BROKEN（走断板/反包判定）
        assertEquals(CycleStatus.BROKEN, t.cycle.status, "复牌未涨停 → BROKEN")
        assertEquals(day, t.cycle.brokenDate, "broken_date=复牌日")
    }

    // ==================== 纯函数性：相同输入序列 → 相同结果 ====================

    @Test
    fun `testAdvance isPureFunction sameInputSameResult`() {
        // given: 两份完全相同的周期实例（规避就地修改污染第二次调用）
        val c1 = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val c2 = cycle(status = CycleStatus.BROKEN, brokenDate = day.minusDays(2), maxStreak = 5)
        val today = bar(isLimitUp = true, limitUpStreak = 6)
        val cal = calendar(day.minusDays(2), 3)

        // when: 同一输入跑两次
        val t1 = machine().advance(day, c1, today, cal)
        val t2 = machine().advance(day, c2, today, cal)

        // then: 结果完全一致（纯函数契约，回放与增量同路径 §13.5 的根基）
        assertEquals(t1.cycle.status, t2.cycle.status, "status 一致")
        assertEquals(t1.cycle.maxStreak, t2.cycle.maxStreak, "max_streak 一致")
        assertEquals(t1.cycle.rebreakCount, t2.cycle.rebreakCount, "rebreak_count 一致")
        assertEquals(t1.cycle.cycleType, t2.cycle.cycleType, "cycle_type 一致")
        assertEquals(t1.dead, t2.dead, "dead 一致")
        assertEquals(t1.message, t2.message, "message 一致")
    }

    // ==================== 上位判定 electLeader ====================

    @Test
    fun `testElectLeader picksHighestBoardWhenNoActiveDragon`() {
        // given: 无进行中龙头，当日最高板 600036（streak=7）> 600000（streak=6）
        val topBars = listOf(
            bar("600000", limitUpStreak = 6),
            bar("600036", limitUpStreak = 7),
        )

        // when
        val elected = machine().electLeader(day, topBars, emptyList())

        // then: 开新周期，龙头=600036，start_date=今日，max_streak=7
        assertEquals(1, elected.size, "无前龙头时开 1 条新周期")
        val newCycle = elected.first()
        assertEquals("600036", newCycle.code, "最高板接棒")
        assertEquals(day, newCycle.startDate, "start_date=上位日")
        assertEquals(7, newCycle.maxStreak, "max_streak=接棒日连板")
        assertEquals(CycleStatus.RISING, newCycle.status, "新周期状态 RISING（BOOT 首日，§13.5 回放语义）")
        assertNull(newCycle.endDate, "end_date null 进行中")
    }

    @Test
    fun `testElectLeader tieBreaksByFirstOccurrence`() {
        // given: 600000 与 600036 并列最高板 streak=5（并列取先到者 = topBars 顺序）
        val topBars = listOf(
            bar("600000", limitUpStreak = 5),
            bar("600036", limitUpStreak = 5),
        )

        // when
        val elected = machine().electLeader(day, topBars, emptyList())

        // then: 先到者 600000 接棒
        assertEquals("600000", elected.first().code, "并列取先到者（§4.9 step1）")
    }

    @Test
    fun `testElectLeader noElectionWhenActiveDragonExists`() {
        // given: 有进行中龙头（end_date IS NULL），不重复开新周期
        val active = listOf(cycle(code = "600000", maxStreak = 6))
        val topBars = listOf(bar("600036", limitUpStreak = 7))

        // when
        val elected = machine().electLeader(day, topBars, active)

        // then: 无新周期（龙头更替只在前龙头 DEAD 后）
        assertTrue(elected.isEmpty(), "有进行中龙头不开新周期")
    }

    @Test
    fun `testElectLeader emptyTopBarsNoElection`() {
        // when: 无候选（全市场无涨停/无最高板）
        val elected = machine().electLeader(day, emptyList(), emptyList())

        // then: 空
        assertTrue(elected.isEmpty(), "无候选不开新周期")
    }
}
