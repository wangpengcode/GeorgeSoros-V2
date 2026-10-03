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
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §13.5 SentimentReplayServiceImpl 契约测试（Fake ComputeService + mock Repository/Notifier）。
 *
 * 契约（类 KDoc / §13.5 / C2/M5）：
 * - 阵亡周期（含 cycle_type 定性）也必须落库（静默数据丢失修复，对齐 Job 路径）；
 * - 回放前删区间行（sentiment + dragon），删后重建顺序；
 * - 空区间守卫：无交易日 → 摘要 0 行、不 compute；
 * - 非法区间 from>to → BusinessException；
 * - digest 数字与实际保存行数一致。
 */
class SentimentReplayServiceImplTest {

    private val day1 = LocalDate.of(2026, 9, 28)
    private val day2 = LocalDate.of(2026, 9, 29)

    private class FakeComputeService : SentimentComputeService {
        val results = ArrayDeque<SentimentComputeResult>()
        var computeCalls = 0

        override fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult {
            computeCalls++
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

    private lateinit var compute: FakeComputeService
    private lateinit var sentimentRepo: SentimentCycleRepository
    private lateinit var dragonRepo: DragonCycleRepository
    private lateinit var stockHistoryRepo: StockHistoryRepository
    private lateinit var stockInfoRepo: StockInfoRepository
    private lateinit var calendarRepo: TradingCalendarRepository
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
        notifier = FakeNotifier()
        replay = SentimentReplayServiceImpl(
            computeService = compute,
            sentimentCycleRepository = sentimentRepo,
            dragonCycleRepository = dragonRepo,
            stockHistoryRepository = stockHistoryRepo,
            stockInfoRepository = stockInfoRepo,
            tradingCalendarRepository = calendarRepo,
            notifier = notifier,
        )
        Mockito.`when`(stockInfoRepo.findByIsStTrue()).thenReturn(emptyList())
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(anyDate(), anyDate())).thenReturn(emptyList())
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
    ) = DragonCycle().apply {
        this.code = code
        this.startDate = startDate
        this.endDate = endDate
        this.status = status
        this.cycleType = cycleType
        this.maxStreak = 5
        this.rebreakCount = 0
        this.suspendedDays = 0
    }

    // ==================== 阵亡周期也落库（C2） ====================

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

        // then: 阵亡周期落库（end_date + cycle_type 定性；C2 静默数据丢失修复）
        assertEquals(2, summary.sentimentRows, "sentiment 2 行")
        assertEquals(1, summary.dragonRows, "dragonRows=实际保存列表大小 1（含阵亡行）")
        Mockito.verify(dragonRepo).saveAll(
            Mockito.argThat { saved: List<DragonCycle> ->
                saved.size == 1 && saved[0].endDate == day2 && saved[0].cycleType == CycleType.SMALL
            },
        )
    }

    // ==================== 删段重建顺序 ====================

    @Test
    fun `testReplay deletesRangeBeforeRebuild`() {
        // given: 2 个交易日，无龙头变更
        Mockito.`when`(calendarRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)).thenReturn(calendar(day1, day2))
        compute.results.add(SentimentComputeResult(sentimentRow(day1), emptyList(), emptyList()))
        compute.results.add(SentimentComputeResult(sentimentRow(day2), emptyList(), emptyList()))

        // when
        replay.replay(day1, day2)

        // then: 重放语义——先删区间行（sentiment+dragon），再逐日重建
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(day1, day2)
        Mockito.verify(dragonRepo).deleteByStartDateBetween(day1, day2)
        Mockito.verify(sentimentRepo).flush()
        assertEquals(2, compute.computeCalls, "逐日 computeFor 2 次")
        Mockito.verify(sentimentRepo, Mockito.times(2)).save(Mockito.any())
        Mockito.verify(dragonRepo).saveAll(Mockito.anyList())
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
