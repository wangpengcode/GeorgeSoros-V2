package com.soros.v2.job

import com.soros.v2.config.IntradayProperties
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.intraday.IntradayArchiveService
import com.soros.v2.service.intraday.IntradayPollService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §19.13.2 IntradayJob 契约测试（Fake 服务 + Mockito mock 日历；直调 poll()/archive()/守卫方法不测 cron）。
 *
 * 契约（§19.13.2 / §17.2）：
 * - 交易时段守卫 [shouldPoll]：交易日 且 在 09:15-11:30 / 13:00-15:00 → true；午休/盘前盘后/非交易日 → false（零容忍空转）；
 * - poll()：窗口内 → 恰一次 pollRound()；窗口外/非交易日 → 零调用；
 * - archive()：交易日 → 恰一次 archiveDay(today)；非交易日 → 零调用。
 *
 * poll()/archive() 内部 runBlocking 用 Dispatchers.Unconfined（同线程），保证 mockStatic(LocalDateTime/LocalDate)
 * 线程局部钉死日期对守卫生效。
 */
class IntradayJobTest {

    private lateinit var calendar: TradingCalendarService
    private lateinit var job: IntradayJob
    private val properties = IntradayProperties()
    private val pollService = FakePollService()
    private val archiveService = FakeArchiveService()

    private class FakePollService : IntradayPollService {
        var rounds = 0
        override suspend fun pollRound() { rounds++ }
    }

    private class FakeArchiveService : IntradayArchiveService {
        var archivedDates = mutableListOf<LocalDate>()
        override suspend fun archiveDay(tradeDate: LocalDate) { archivedDates += tradeDate }
    }

    @BeforeEach
    fun setUp() {
        calendar = Mockito.mock(TradingCalendarService::class.java)
        job = IntradayJob(pollService, archiveService, calendar, properties, Dispatchers.Unconfined)
    }

    private fun tradingDay(on: LocalDate): Boolean {
        Mockito.`when`(calendar.isTradingDay(on)).thenReturn(true)
        return true
    }

    // ==================== 守卫：isInTradingWindow ====================

    @Test
    fun `testIsInTradingWindow morningAndAfternoonOnly`() {
        assertTrue(job.isInTradingWindow(LocalTime.of(9, 15)), "09:15 开盘即窗口")
        assertTrue(job.isInTradingWindow(LocalTime.of(10, 0)), "上午盘中")
        assertTrue(job.isInTradingWindow(LocalTime.of(11, 29)), "上午收盘前 1 分钟")
        assertFalse(job.isInTradingWindow(LocalTime.of(11, 30)), "11:30 午休起点不含")
        assertFalse(job.isInTradingWindow(LocalTime.of(12, 0)), "午休零轮询")
        assertFalse(job.isInTradingWindow(LocalTime.of(12, 59)), "午休尾")
        assertTrue(job.isInTradingWindow(LocalTime.of(13, 0)), "13:00 下午开盘即窗口")
        assertTrue(job.isInTradingWindow(LocalTime.of(14, 59)), "下午盘中")
        assertFalse(job.isInTradingWindow(LocalTime.of(15, 0)), "15:00 收盘不含")
    }

    // ==================== 守卫：shouldPoll ====================

    @Test
    fun `testShouldPoll tradingDayInWindow`() {
        Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 9, 30))).thenReturn(true)
        assertTrue(job.shouldPoll(LocalDateTime.of(2026, 9, 30, 10, 0)))
    }

    @Test
    fun `testShouldPoll tradingDayLunchBreakSkips`() {
        Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 9, 30))).thenReturn(true)
        assertFalse(job.shouldPoll(LocalDateTime.of(2026, 9, 30, 12, 0)), "午休零轮询")
    }

    @Test
    fun `testShouldPoll nonTradingDaySkipsRegardlessOfTime`() {
        Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 10, 3))).thenReturn(false)
        assertFalse(job.shouldPoll(LocalDateTime.of(2026, 10, 3, 10, 0)), "非交易日零请求")
    }

    // ==================== poll()：窗口内轮询 / 窗口外零请求 ====================

    @Test
    fun `testPoll inWindowCallsPollRoundOnce`() {
        val fixed = LocalDateTime.of(2026, 9, 30, 10, 0)
        pinNow(fixed) {
            Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 9, 30))).thenReturn(true)
            job.poll()
        }
        assertTrue(pollService.rounds == 1, "窗口内恰一次 pollRound")
    }

    @Test
    fun `testPoll lunchBreakZeroRequests`() {
        val fixed = LocalDateTime.of(2026, 9, 30, 12, 0)
        pinNow(fixed) {
            Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 9, 30))).thenReturn(true)
            job.poll()
        }
        assertTrue(pollService.rounds == 0, "午休零请求")
    }

    @Test
    fun `testPoll nonTradingDayZeroRequests`() {
        val fixed = LocalDateTime.of(2026, 10, 3, 10, 0)
        pinNow(fixed) {
            Mockito.`when`(calendar.isTradingDay(LocalDate.of(2026, 10, 3))).thenReturn(false)
            job.poll()
        }
        assertTrue(pollService.rounds == 0, "非交易日零请求")
    }

    // ==================== archive()：交易日归档 / 非交易日跳过 ====================

    @Test
    fun `testArchive tradingDayCallsArchiveDay`() {
        val fixedDate = LocalDate.of(2026, 9, 30)
        pinDate(fixedDate) {
            Mockito.`when`(calendar.isTradingDay(fixedDate)).thenReturn(true)
            job.archive()
        }
        assertTrue(archiveService.archivedDates == listOf(fixedDate), "交易日归档一次")
    }

    @Test
    fun `testArchive nonTradingDaySkips`() {
        val fixedDate = LocalDate.of(2026, 10, 3)
        pinDate(fixedDate) {
            Mockito.`when`(calendar.isTradingDay(fixedDate)).thenReturn(false)
            job.archive()
        }
        assertTrue(archiveService.archivedDates.isEmpty(), "非交易日零归档")
    }

    // ==================== 时间钉死辅助 ====================

    /** 钉死 LocalDateTime.now()（只拦 now()，其余静态方法走真实实现） */
    private fun pinNow(dateTime: LocalDateTime, block: () -> Unit) {
        Mockito.mockStatic(LocalDateTime::class.java, Mockito.CALLS_REAL_METHODS).use { mocked ->
            mocked.`when`<LocalDateTime> { LocalDateTime.now() }.thenReturn(dateTime)
            block()
        }
    }

    /** 钉死 LocalDate.now() */
    private fun pinDate(date: LocalDate, block: () -> Unit) {
        Mockito.mockStatic(LocalDate::class.java, Mockito.CALLS_REAL_METHODS).use { mocked ->
            mocked.`when`<LocalDate> { LocalDate.now() }.thenReturn(date)
            block()
        }
    }
}
