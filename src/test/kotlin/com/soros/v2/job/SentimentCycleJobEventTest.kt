package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.DailyCollectCompleted
import com.soros.v2.service.dto.SentimentCycleCompleted
import com.soros.v2.service.sentiment.SentimentComputeContext
import com.soros.v2.service.sentiment.SentimentComputeResult
import com.soros.v2.service.sentiment.SentimentComputeService
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher

/**
 * §13.4 链序接线契约测试：SentimentCycleJob 完成后发布 SentimentCycleCompleted 事件（SignalPrecomputeJob 消费点）。
 *
 * 契约（§13.4 链序留痕 / SentimentCycleCompleted KDoc）：
 * - onDailyCollectCompleted 派生成功 → publishEvent(SentimentCycleCompleted(tradeDate, dataCoverage)) 恰一次；
 * - 失败率 >10% → dataCoverage=PARTIAL 进事件；否则 FULL；
 * - fallbackCron（21:30 兜底补算）→ 同样发布一次（coverage=FULL，兜底无失败）；
 * - 非交易日守卫 → 不派生不发布；computeFor 异常（runCatching 吞掉）→ 不发布。
 *
 * ⚠️ 接线层契约（当前红）：SentimentCycleJob 构造器已预留 eventPublisher（默认 null），但事件发布调用
 * 尚未填充（Test-Writer 步只写契约）；Implementer 在 derive 落库后补 `eventPublisher?.publishEvent(...)`
 * 后本测试应全绿。publishEvent 重载目标为 publishEvent(Object)（SentimentCycleCompleted 非 ApplicationEvent）。
 */
class SentimentCycleJobEventTest {

    private class FakeComputeService : SentimentComputeService {
        var computeCalls = 0
        var result: SentimentComputeResult = SentimentComputeResult(
            sentiment = SentimentCycle().apply { tradeDate = LocalDate.of(2026, 9, 30) },
            dragonUpdates = emptyList(),
            deadDragonCodes = emptyList(),
        )
        var throwOnCompute: RuntimeException? = null

        override fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult {
            computeCalls++
            throwOnCompute?.let { throw it }
            return result
        }
    }

    private class FakeCalendarService : TradingCalendarService {
        var isTrading = true
        override suspend fun ensureLoaded(): Int = 1
        override fun isTradingDay(date: LocalDate): Boolean = isTrading
        override fun previousTradingDay(date: LocalDate): LocalDate? = null
        override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> = emptyList()
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
    private lateinit var calendar: FakeCalendarService
    private lateinit var notifier: FakeNotifier
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var job: SentimentCycleJob

    @BeforeEach
    fun setUp() {
        compute = FakeComputeService()
        sentimentRepo = Mockito.mock(SentimentCycleRepository::class.java)
        dragonRepo = Mockito.mock(DragonCycleRepository::class.java)
        stockHistoryRepo = Mockito.mock(StockHistoryRepository::class.java)
        stockInfoRepo = Mockito.mock(StockInfoRepository::class.java)
        calendar = FakeCalendarService()
        notifier = FakeNotifier()
        publisher = Mockito.mock(ApplicationEventPublisher::class.java)
        job = SentimentCycleJob(
            compute,
            sentimentRepo,
            dragonRepo,
            calendar,
            notifier,
            stockHistoryRepo,
            stockInfoRepo,
            publisher,
        )
    }

    private val today = LocalDate.of(2026, 9, 30)

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（同 SentimentCycleJobTest 同款） */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return today
    }

    private fun event(success: Int, failed: Int) = DailyCollectCompleted(
        successCodes = (1..success).map { String.format("6000%02d", it) }.toSet(),
        failedCodes = (1..failed).map { String.format("6001%02d", it) }.toSet(),
        durationMs = 1000,
    )

    private fun publishedEventCaptor(): ArgumentCaptor<SentimentCycleCompleted> {
        val captor = ArgumentCaptor.forClass(SentimentCycleCompleted::class.java)
        Mockito.verify(publisher, Mockito.times(1)).publishEvent(captor.capture())
        return captor
    }

    // ==================== 事件触发 → 发布完成事件 ====================

    @Test
    fun `testOnDailyCollectCompleted publishesCompletedEventOnceWithFullCoverage`() {
        // given: 采集完成事件（失败率 0 → FULL）
        // when
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: publishEvent(SentimentCycleCompleted) 恰一次 + 携带正确 tradeDate 与 coverage=FULL
        val captor = publishedEventCaptor()
        assertEquals(today, captor.value.tradeDate, "事件携带正确 tradeDate（不依赖 LocalDate.now()）")
        assertEquals(DataCoverage.FULL, captor.value.coverage, "事件携带 coverage=FULL")
    }

    @Test
    fun `testOnDailyCollectCompleted publishesWithPartialWhenFailureRateOverThreshold`() {
        // given: 成功 8 + 失败 2（失败率 20% > 10% → PARTIAL，§13.4）
        // when
        job.onDailyCollectCompleted(event(success = 8, failed = 2))

        // then: 事件携带 coverage=PARTIAL（Signal 层据此保守跳过，次日重算）
        val captor = publishedEventCaptor()
        assertEquals(DataCoverage.PARTIAL, captor.value.coverage, "失败率>10% → 事件 coverage=PARTIAL")
        assertEquals(today, captor.value.tradeDate, "PARTIAL 事件同样携带正确 tradeDate")
    }

    // ==================== 21:30 兜底 cron → 也发布 ====================

    @Test
    fun `testFallbackCron publishesCompletedEventOnce`() {
        // given: 兜底 cron（今日行不存在 → 补算；空事件失败率 0 → FULL）
        Mockito.`when`(sentimentRepo.findByTradeDate(anyDate())).thenReturn(null)

        // when
        job.fallbackCron()

        // then: 兜底补算同样发布恰一次，coverage=FULL
        val captor = publishedEventCaptor()
        assertEquals(DataCoverage.FULL, captor.value.coverage, "兜底 cron 补算（无失败）→ coverage=FULL")
        assertEquals(today, captor.value.tradeDate, "兜底事件 tradeDate=今日")
    }

    // ==================== 守卫与失败：不发布 ====================

    @Test
    fun `testNonTradingDay doesNotPublishEvent`() {
        // given: 非交易日（§17.2 守卫：事件触发入口先判 isTradingDay）
        calendar.isTrading = false

        // when
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: 不派生不发布
        Mockito.verify(publisher, Mockito.never())
            .publishEvent(Mockito.any(SentimentCycleCompleted::class.java))
    }

    @Test
    fun `testComputeFailure doesNotPublishEvent`() {
        // given: computeFor 抛异常（runCatching 吞掉记日志 + 钉钉告警，不阻塞主采集）
        compute.throwOnCompute = IllegalStateException("派生失败")

        // when
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: 派生未成功 → 不发布完成事件
        Mockito.verify(publisher, Mockito.never())
            .publishEvent(Mockito.any(SentimentCycleCompleted::class.java))
    }
}
