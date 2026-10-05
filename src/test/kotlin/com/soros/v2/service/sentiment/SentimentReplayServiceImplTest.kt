package com.soros.v2.service.sentiment

import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.TradingCalendar
import com.soros.v2.exception.BusinessException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager

/**
 * §19.12 SentimentReplayServiceImpl 契约测试（Fake ComputeService + mock Repository/Notifier/TransactionManager）。
 *
 * 契约（类 KDoc / §19.12 / C2/M5）：
 * - 阵亡周期（含 cycle_type 定性）也必须落库（C2 静默数据丢失修复；按天事务逐日 saveAll(dragonUpdates)）；
 * - force=false 增量补缺：待算清单 = 交易日历 ∩ [from,to] − sentiment_cycle 已有行日期，只落缺日行；
 *   已有行跳过（断点续跑）；空洞日内存推进状态但不落库（skippedDays 累计）；
 * - 数据守卫：缺日柱子覆盖率 <90%（非 ST 有效股）→ 不硬算 + 前缀截止（该日及之后不再落库，
 *   已落库行保留），Summary.deferredDates 记录（§19.12 决策 4，阈值 BAR_COVERAGE_THRESHOLD=0.9）；
 * - force=true 全量重建：事务0 预清理区间两表（防 uq_dragon_active 冲突）后逐日重建（§19.12 决策 3）；
 * - force=false 二次调用（续跑）→ filledDays=0、不重复 compute/写（§19.12 决策 4）；
 * - 断点热启动：prevCycle=findByTradeDate(D0 前一交易日)；activeDragon=findAllByEndDateIsNull()；
 *   calendar 前缀扩展到 min(from, 全部进行中龙头 brokenDate)（§19.12 决策 5）；
 * - 按天分事务：每日 tx = delete 当日两表行 + insert（无整区间 delete、无显式 flush）；
 * - 批量投影：findReplayBars(start,end) 8 字段瘦身加载（§19.12 决策 1）；
 * - 空区间守卫：无交易日 → 摘要 0 行、不 compute；
 * - 非法区间 from>to → BusinessException；
 * - digest 数字与实际保存行数一致。
 */
class SentimentReplayServiceImplTest {

    private val day1 = LocalDate.of(2026, 9, 28)
    private val day2 = LocalDate.of(2026, 9, 29)

    private class FakeComputeService : SentimentComputeService {
        val results = ArrayDeque<SentimentComputeResult>()
        val contexts = mutableListOf<SentimentComputeContext>()
        var computeCalls = 0

        override fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult {
            computeCalls++
            contexts.add(ctx)
            return results.removeFirst()
        }
    }

    private class FakeNotifier : DingTalkNotifier {
        val notified = mutableListOf<Pair<DingTalkEvent, String>>()
        val digests = mutableListOf<String>()

        override fun notify(event: DingTalkEvent, title: String, content: String) {
            notified.add(event to content)
        }

        override fun notifyDailyDigest(digest: String) {
            digests.add(digest)
        }
    }

    /** §19.12 决策 1 瘦身投影假实现（findReplayBars 8 字段：code/tradeDate/close/changePct/isLimitUp/isLimitDown/limitUpStreak/limitDownStreak） */
    private class FakeReplayBar(
        override val code: String,
        override val tradeDate: LocalDate,
        override val close: BigDecimal? = BigDecimal.ZERO,
        override val changePct: BigDecimal? = BigDecimal.ZERO,
        override val isLimitUp: Boolean = false,
        override val isLimitDown: Boolean = false,
        override val limitUpStreak: Short = 0,
        override val limitDownStreak: Short = 0,
    ) : StockHistoryRepository.ReplayBarProjection

    private lateinit var compute: FakeComputeService
    private lateinit var sentimentRepo: SentimentCycleRepository
    private lateinit var dragonRepo: DragonCycleRepository
    private lateinit var stockHistoryRepo: StockHistoryRepository
    private lateinit var stockInfoRepo: StockInfoRepository
    private lateinit var calendarRepo: TradingCalendarRepository
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var notifier: FakeNotifier
    private lateinit var replay: SentimentReplayServiceImpl

    @BeforeEach
    fun setUp() {
        compute = FakeComputeService()
        sentimentRepo = Mockito.mock(SentimentCycleRepository::class.java)
        dragonRepo = Mockito.mock(DragonCycleRepository::class.java)
        stockHistoryRepo = Mockito.mock(StockHistoryRepository::class.java)
        stockInfoRepo = Mockito.mock(StockInfoRepository::class.java)
        calendarRepo = Mockito.mock(TradingCalendarRepository::class.java)
        transactionManager = Mockito.mock(PlatformTransactionManager::class.java)
        notifier = FakeNotifier()
        replay = SentimentReplayServiceImpl(
            computeService = compute,
            sentimentCycleRepository = sentimentRepo,
            dragonCycleRepository = dragonRepo,
            stockHistoryRepository = stockHistoryRepo,
            stockInfoRepository = stockInfoRepo,
            tradingCalendarRepository = calendarRepo,
            notifier = notifier,
            transactionManager = transactionManager,
        )
        // §19.12 默认依赖：无 ST（隔离铁律 is_st 仅用于排除）、无历史行、无进行中龙头、批量投影空
        // 守卫默认 countByIsStFalseAndDelistedFalse=0 → 阈值 0 → 空柱不触发守卫（0<0 为 false）
        Mockito.`when`(stockInfoRepo.findByIsStTrue()).thenReturn(emptyList())
        Mockito.`when`(stockHistoryRepo.findReplayBars(anyDate(), anyDate())).thenReturn(emptyList())
        Mockito.`when`(stockInfoRepo.countByIsStFalseAndDelistedFalse()).thenReturn(0L)
        Mockito.`when`(dragonRepo.findAllByEndDateIsNull()).thenReturn(emptyList())
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(anyDate(), anyDate())).thenReturn(emptyList())
    }

    /**
     * Mockito.any() 的 Kotlin 非空参数安全 matcher：
     * 注册 `any(LocalDate)` matcher，但返回非空占位值（避免 Kotlin 非空检查对 null 抛 NPE）。
     */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return day1
    }

    private fun calendar(vararg days: LocalDate): List<TradingCalendar> = days.map { TradingCalendar(it) }

    private fun sentimentRow(date: LocalDate) = SentimentCycle().apply { tradeDate = date }

    private fun dragon(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate?,
        status: CycleStatus = CycleStatus.RISING,
        cycleType: CycleType? = null,
        brokenDate: LocalDate? = null,
    ) = DragonCycle().apply {
        this.code = code
        this.startDate = startDate
        this.endDate = endDate
        this.status = status
        this.cycleType = cycleType
        this.brokenDate = brokenDate
        this.maxStreak = 5
        this.rebreakCount = 0
        this.suspendedDays = 0
    }

    // ==================== 阵亡周期也落库（C2，按天事务逐日 saveAll） ====================

    @Test
    fun `testReplay persistsDeadDragonWithCycleType`() {
        // given: 2 个交易日；d1 选龙头（RISING），d2 同对象阵亡（DEAD + cycle_type=SMALL）
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        val leader = dragon("600000", day1, null)
        compute.results.add(SentimentComputeResult(sentimentRow(day1), listOf(leader), emptyList()))
        leader.endDate = day2
        leader.status = CycleStatus.DEAD
        leader.cycleType = CycleType.SMALL
        compute.results.add(SentimentComputeResult(sentimentRow(day2), listOf(leader), listOf("600000")))

        // when
        val summary = replay.replay(day1, day2)

        // then: 阵亡周期落库（end_date + cycle_type 定性；C2 静默数据丢失修复；§19.12 按天 saveAll(dragonUpdates)）
        assertEquals(2, summary.sentimentRows, "sentiment 2 行")
        assertEquals(1, summary.dragonRows, "dragonRows=去重后实际保存周期数 1（含阵亡行）")
        Mockito.verify(dragonRepo, Mockito.atLeastOnce()).saveAll(
            Mockito.argThat { saved: List<DragonCycle> ->
                saved.any { it.endDate == day2 && it.cycleType == CycleType.SMALL }
            },
        )
    }

    // ==================== 删段重建（按天 delete，无整区间 delete、无显式 flush） ====================

    @Test
    fun `testReplay deletesRangeBeforeRebuild`() {
        // given: 2 个交易日，无龙头变更
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        compute.results.add(SentimentComputeResult(sentimentRow(day1), emptyList(), emptyList()))
        compute.results.add(SentimentComputeResult(sentimentRow(day2), emptyList(), emptyList()))

        // when
        replay.replay(day1, day2)

        // then: §19.12 决策 3 按天分事务——每日 tx = delete 当日两表行 + insert（无整区间 delete、无显式 flush）
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(day1, day1)
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(day2, day2)
        Mockito.verify(sentimentRepo, Mockito.never()).deleteByTradeDateBetween(day1, day2)
        Mockito.verify(sentimentRepo, Mockito.never()).flush()
        Mockito.verify(dragonRepo).deleteByStartDateBetween(day1, day1)
        Mockito.verify(dragonRepo).deleteByStartDateBetween(day2, day2)
        Mockito.verify(dragonRepo, Mockito.never()).deleteByStartDateBetween(day1, day2)
        assertEquals(2, compute.computeCalls, "逐日 computeFor 2 次")
        Mockito.verify(sentimentRepo, Mockito.times(2)).save(Mockito.any())
    }

    // ==================== 增量补缺（force=false）：只算缺日 ====================

    @Test
    fun `testReplay incrementalFillOnlyMissingDaysSkippedExisting`() {
        // given: 区间 5 交易日已有 2 日行（d1,d2）→ 待算清单只含缺日 d3,d4,d5（§19.12 决策 4 增量补缺）
        val d1 = day1
        val d2 = day2
        val d3 = day2.plusDays(1)
        val d4 = day2.plusDays(2)
        val d5 = day2.plusDays(3)
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(d1, d5)).thenReturn(calendar(d1, d2, d3, d4, d5))
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(d1, d5))
            .thenReturn(listOf(sentimentRow(d1), sentimentRow(d2)))
        // D0=d3 前一交易日=d2 → 断点热启动 prevCycle（MEDIUM-1 修复：查交易日历严格前位，非日历数组）
        Mockito.`when`(calendarRepo.findFirstByTradeDateBeforeOrderByTradeDateDesc(d3)).thenReturn(TradingCalendar(d2))
        Mockito.`when`(sentimentRepo.findByTradeDate(d2)).thenReturn(sentimentRow(d2))
        repeat(3) { compute.results.add(SentimentComputeResult(sentimentRow(d3), emptyList(), emptyList())) }

        // when
        val summary = replay.replay(d1, d5)

        // then: 只算 3 日；已有 2 日跳过（断点续跑天然成立）
        assertEquals(3, summary.filledDays, "缺日补算落库 filledDays=3（§19.12 决策 4）")
        assertEquals(2, summary.skippedDays, "已有 2 日行跳过 skippedDays=2")
        assertEquals(3, compute.computeCalls, "只 compute 缺日 3 次（已有行不重复算）")
        Mockito.verify(sentimentRepo, Mockito.times(3)).save(Mockito.any())
    }

    // ==================== 空洞日：内存推进状态但不落库 ====================

    @Test
    fun `testReplay holeDayAdvancesStateWithoutPersist`() {
        // given: 5 交易日；d1 与 d3 已有行，缺日集合不连续 → d3 为"空洞日"（§19.12 决策 4）
        val d1 = day1
        val d2 = day2
        val d3 = day2.plusDays(1)
        val d4 = day2.plusDays(2)
        val d5 = day2.plusDays(3)
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(d1, d5)).thenReturn(calendar(d1, d2, d3, d4, d5))
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(d1, d5))
            .thenReturn(listOf(sentimentRow(d1), sentimentRow(d3)))
        Mockito.`when`(sentimentRepo.findByTradeDate(d2)).thenReturn(sentimentRow(d2))
        repeat(4) { compute.results.add(SentimentComputeResult(sentimentRow(d2), emptyList(), emptyList())) }

        // when
        val summary = replay.replay(d1, d5)

        // then: d2/d4/d5 落库；空洞日 d3 compute 但不落库（状态连续性）；filledDays=3、skippedDays=2
        assertEquals(3, summary.filledDays, "落库缺日 filledDays=3（d2,d4,d5）")
        assertEquals(2, summary.skippedDays, "跳过日=已有行 d1 + 空洞日 d3 → skippedDays=2")
        assertEquals(4, compute.computeCalls, "自 D0 起每日均 compute（含空洞日 d3，状态推进）")
        Mockito.verify(sentimentRepo, Mockito.times(3)).save(Mockito.any())
    }

    // ==================== 数据守卫：覆盖率 <90% → 前缀截止 + deferredDates + 已算日保留 ====================

    @Test
    fun `testReplay guardLowCoveragePrefixCutoffDeferredAndRetainsPrior`() {
        // given: 3 交易日；非 ST 有效股=100（阈值=100×0.9=90）；d1 覆盖 100 柱、d2 覆盖 1 柱、d3 覆盖 100 柱
        val d3 = day2.plusDays(1)
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, d3)).thenReturn(calendar(day1, day2, d3))
        Mockito.`when`(stockInfoRepo.countByIsStFalseAndDelistedFalse()).thenReturn(100L)
        val bars = (1..100).map { FakeReplayBar("000$it", day1) } +
            FakeReplayBar("000001", day2) +
            (1..100).map { FakeReplayBar("100$it", d3) }
        Mockito.`when`(stockHistoryRepo.findReplayBars(anyDate(), anyDate())).thenReturn(bars)
        compute.results.add(SentimentComputeResult(sentimentRow(day1), emptyList(), emptyList()))

        // when
        val summary = replay.replay(day1, d3)

        // then: d1 覆盖率≥90% 落库（已算日保留）；d2 覆盖率<90% → 守卫"不硬算"+ 前缀截止（d2 及之后不再落库）
        assertEquals(1, summary.filledDays, "d1 已算落库 filledDays=1（已算日保留，§19.12 决策 4）")
        assertEquals(listOf(day2, d3), summary.deferredDates, "守卫拦下 d2/d3 留待下次（deferredDates 非空）")
        assertEquals(1, compute.computeCalls, "d2/d3 不硬算（守卫'不硬算'语义）")
        Mockito.verify(sentimentRepo, Mockito.times(1)).save(Mockito.any())
    }

    // ==================== force=false 续跑幂等 ====================

    @Test
    fun `testReplay rerunForceFalseFilledZeroNoDuplicateWrites`() {
        // given: 区间 2 交易日已全部存在（断点续跑完成态，§19.12 决策 4）
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2))
            .thenReturn(listOf(sentimentRow(day1), sentimentRow(day2)))

        // when: force=false 二次调用（续跑）→ 无缺日
        val summary = replay.replay(day1, day2)

        // then: filledDays=0、不重复 compute、不重复写（重跑同一接口即从缺日继续）
        assertEquals(0, summary.filledDays, "续跑无缺日 filledDays=0")
        assertEquals(2, summary.skippedDays, "全部已有行跳过 skippedDays=2")
        assertEquals(0, compute.computeCalls, "无缺日不 compute")
        Mockito.verify(sentimentRepo, Mockito.never()).save(Mockito.any())
        Mockito.verify(sentimentRepo, Mockito.never()).deleteByTradeDateBetween(anyDate(), anyDate())
    }

    // ==================== force=true：事务0 预清理区间两表后全量重建 ====================

    @Test
    fun `testReplay forceTruePreclearsRangeThenFullRebuild`() {
        // given: force=true 全量重建（§19.12 决策 3/4：防 uq_dragon_active 冲突，BackfillJob §13.5 钩子传 true）
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        compute.results.add(SentimentComputeResult(sentimentRow(day1), emptyList(), emptyList()))
        compute.results.add(SentimentComputeResult(sentimentRow(day2), emptyList(), emptyList()))

        // when
        val summary = replay.replay(day1, day2, force = true)

        // then: 事务0 整区间预清理两表 + flush，再逐日重建（现状重放语义）
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(day1, day2)
        Mockito.verify(dragonRepo).deleteByStartDateBetween(day1, day2)
        Mockito.verify(sentimentRepo).flush()
        assertEquals(true, summary.force, "summary 标记 force=true")
        assertEquals(2, summary.filledDays, "全量重建 filledDays=2")
        assertEquals(0, summary.skippedDays, "force=true 无跳过日")
        assertTrue(summary.deferredDates.isEmpty(), "无守卫 defer")
        Mockito.verify(sentimentRepo, Mockito.times(2)).save(Mockito.any())
    }

    // ==================== MEDIUM-2：force=true 区间前进行中龙头兜底 WARN（防 uq_dragon_active 撞键） ====================

    @Test
    fun `testReplay forceTruePreIntervalActiveDragonWarnsButCompletes`() {
        // given: force=true；区间 [day1,day2] 前存在进行中龙头（startDate<from, endDate=null），同 code 再当选将撞 uq_dragon_active
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        Mockito.`when`(dragonRepo.findAllByEndDateIsNull()).thenReturn(listOf(dragon("600001", day1.minusDays(3), null)))
        compute.results.add(SentimentComputeResult(sentimentRow(day1), emptyList(), emptyList()))
        compute.results.add(SentimentComputeResult(sentimentRow(day2), emptyList(), emptyList()))

        // when & then: WARN 防误用，不自动终结、不改数据（logger.warn 只告警，建议 from 取历史真起点或先手工终结）；重建完成不抛异常
        val summary = replay.replay(day1, day2, force = true)

        assertEquals(true, summary.force, "summary force=true")
        assertEquals(2, summary.filledDays, "重建完成 filledDays=2")
        assertEquals(0, summary.skippedDays, "force=true 无跳过日")
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(day1, day2)
        Mockito.verify(dragonRepo).deleteByStartDateBetween(day1, day2)
        Mockito.verify(dragonRepo).findAllByEndDateIsNull()
        // 残留龙头未被终结/未改数据：compute 返回空 dragonUpdates，saveAll 只落空列表，600001 残留行不动
    }

    // ==================== 断点热启动：prevCycle + 进行中龙头 + calendar 前缀 ====================

    @Test
    fun `testReplay hotStartBridgesPrevCycleAndActiveDragon`() {
        // given: from=d3,to=d5；区间内无已有行；存在进行中龙头 brokenDate=d1(<from)
        val d1 = day1
        val d2 = day2
        val d3 = day2.plusDays(1)
        val d4 = day2.plusDays(2)
        val d5 = day2.plusDays(3)
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(anyDate(), anyDate()))
            .thenReturn(calendar(d1, d2, d3, d4, d5))
        Mockito.`when`(sentimentRepo.findByTradeDate(d2)).thenReturn(sentimentRow(d2))
        val active = dragon("600000", d1, null, status = CycleStatus.BROKEN, brokenDate = d1)
        Mockito.`when`(dragonRepo.findAllByEndDateIsNull()).thenReturn(listOf(active))
        // MEDIUM-1 修复：D0=d3 前一交易日=d2 查交易日历严格前位（非日历数组前位）
        Mockito.`when`(calendarRepo.findFirstByTradeDateBeforeOrderByTradeDateDesc(d3)).thenReturn(TradingCalendar(d2))
        repeat(3) { compute.results.add(SentimentComputeResult(sentimentRow(d3), emptyList(), emptyList())) }

        // when
        replay.replay(d3, d5)

        // then: D0=d3 前一交易日=d2 热启动 prevCycle；activeDragon=进行中龙头；calendar 前缀扩展到 brokenDate=d1（§19.12 决策 5）
        Mockito.verify(sentimentRepo).findByTradeDate(d2)
        val firstCtx = compute.contexts.first()
        assertTrue(firstCtx.calendar.contains(d1), "calendar 含 brokenDate 前缀 d1（观察期从 brokenDate 起算）")
        assertEquals(d2, firstCtx.prevCycle?.tradeDate, "prevCycle=D0 前一交易日行")
        assertTrue(firstCtx.activeDragonCycles.contains(active), "activeDragon 热启动衔接（内存就地 mutate）")
    }

    // ==================== MEDIUM-1：d0==from 热启动 prevCycle 断链修复（查交易日历严格前位） ====================

    @Test
    fun `testReplay hotStartPrevCycleAtFromResumePoint`() {
        // given: 情绪已填到 d2（from 前一交易日），从 from=d3 续填；无 brokenDate<from 的进行中龙头 → calendarStart==from
        val d2 = day2
        val d3 = day2.plusDays(1)
        val d4 = day2.plusDays(2)
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(anyDate(), anyDate())).thenReturn(calendar(d3, d4))
        // D0=d3==from：日历数组首位前位越界 → 须查交易日历严格前位（MEDIUM-1 修复，非数组前位）
        Mockito.`when`(calendarRepo.findFirstByTradeDateBeforeOrderByTradeDateDesc(d3)).thenReturn(TradingCalendar(d2))
        Mockito.`when`(sentimentRepo.findByTradeDate(d2)).thenReturn(sentimentRow(d2))
        repeat(2) { compute.results.add(SentimentComputeResult(sentimentRow(d3), emptyList(), emptyList())) }

        // when
        val summary = replay.replay(d3, d4)

        // then: 前一交易日 d2 行作为 prevCycle 参与 D0 递推（followup 不断链；findByTradeDate(d2) 被调用）
        assertEquals(2, summary.filledDays, "d3/d4 补算落库 filledDays=2")
        Mockito.verify(sentimentRepo).findByTradeDate(d2)
        val firstCtx = compute.contexts.first()
        assertEquals(d2, firstCtx.prevCycle?.tradeDate, "D0=from 时 prevCycle=from 前一交易日行（热启动不断链）")
    }

    // ==================== 空区间守卫 ====================

    @Test
    fun `testReplay emptyCalendarReturnsZeroSummary`() {
        // given: 区间内无交易日
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(emptyList())

        // when
        val summary = replay.replay(day1, day2)

        // then: 摘要 0 行，不 compute、不落库
        assertEquals(0, summary.sentimentRows, "无交易日 sentiment 0 行")
        assertEquals(0, summary.dragonRows, "无龙头")
        assertEquals(0, compute.computeCalls, "空区间不 compute")
        assertEquals(false, summary.force, "summary force 默认 false")
        assertEquals(0, summary.filledDays, "空区间 filledDays=0")
        assertEquals(0, summary.skippedDays, "空区间 skippedDays=0")
        assertTrue(summary.deferredDates.isEmpty(), "空区间 deferredDates 空")
        Mockito.verify(dragonRepo, Mockito.never()).saveAll(Mockito.anyList())
    }

    // ==================== 非法区间守卫 ====================

    @Test
    fun `testReplay invalidRangeThrowsBusinessException`() {
        // when & then: from>to 非法区间 → BusinessException
        assertThrows(BusinessException::class.java) {
            replay.replay(day2, day1)
        }
        assertEquals(0, compute.computeCalls, "非法区间不 compute")
    }

    // ==================== digest 数字与保存数一致 ====================

    @Test
    fun `testReplay digestNumbersMatchSavedRows`() {
        // given: 2 个交易日 + 1 条阵亡龙头（同前面景）
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        val leader = dragon("600000", day1, null)
        compute.results.add(SentimentComputeResult(sentimentRow(day1), listOf(leader), emptyList()))
        leader.endDate = day2
        leader.status = CycleStatus.DEAD
        leader.cycleType = CycleType.SMALL
        compute.results.add(SentimentComputeResult(sentimentRow(day2), listOf(leader), listOf("600000")))

        // when
        replay.replay(day1, day2)

        // then: digest 数字与 summary（实际保存）一致
        assertTrue(notifier.digests.isNotEmpty(), "回放完成应发 digest")
        val digest = notifier.digests.single()
        assertTrue(digest.contains("sentiment 2 行"), "digest sentiment 行数=2")
        assertTrue(digest.contains("龙头 1 条"), "digest 龙头条数=1（实际保存列表）")
    }
}
