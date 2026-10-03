package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.DailyCollectCompleted
import com.soros.v2.service.sentiment.SentimentComputeContext
import com.soros.v2.service.sentiment.SentimentComputeResult
import com.soros.v2.service.sentiment.SentimentComputeService
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * §13.4 SentimentCycleJob 编排契约测试（Fake 依赖注入直调事件/cron 入口，不依赖真实 Spring 事件/cron）。
 *
 * 契约（§13.4 / 类 KDoc）：
 * - onDailyCollectCompleted：DailyCollectCompleted 事件触发派生 → computeFor → 落库 sentiment + dragon；
 * - fallbackCron（21:30 兜底）：今日行存在且非 PARTIAL → 幂等跳过（§17.2）；不存在或 PARTIAL → 与事件同路径补算；
 * - failedCodes 占比 >10%：照常派生，但 sentiment_cycle 行标 data_coverage=PARTIAL + 钉钉提示（§13.4）；
 * - 失败不阻塞主采集：异常吞掉记日志 + 钉钉告警（不向上抛）。
 *
 * ⚠️ 实现侧为 TODO 骨架（仅 bean 装配），本测试当前红；Implementer 填充事件体与 cron 体。
 */
class SentimentCycleJobTest {

    private class FakeComputeService : SentimentComputeService {
        var computeCalls = 0
        var lastDate: LocalDate? = null
        var lastCtx: SentimentComputeContext? = null
        var result: SentimentComputeResult = SentimentComputeResult(
            sentiment = SentimentCycle().apply { tradeDate = LocalDate.of(2026, 9, 30) },
            dragonUpdates = emptyList(),
            deadDragonCodes = emptyList(),
        )
        var throwOnCompute: RuntimeException? = null

        override fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult {
            computeCalls++
            lastDate = date
            lastCtx = ctx
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
        job = SentimentCycleJob(compute, sentimentRepo, dragonRepo, calendar, notifier, stockHistoryRepo, stockInfoRepo)
    }

    private val today = LocalDate.of(2026, 9, 30)

    /**
     * Mockito.any() 的 Kotlin 非空参数安全 matcher：
     * 注册 `any(LocalDate)` matcher，但返回非空占位值（避免 Kotlin 非空检查对 null 抛 NPE）。
     * 调用方：`Mockito.\`when\`(repo.existsByTradeDate(anyDate())).thenReturn(...)`
     */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return today
    }

    /** 同上：`any(SentimentCycle)` matcher + 非空占位行（save 校验用，防 matcher 栈脏泄漏） */
    private fun anySentimentCycle(): SentimentCycle {
        Mockito.any(SentimentCycle::class.java)
        return SentimentCycle().apply { tradeDate = today }
    }

    private fun event(success: Int, failed: Int) = DailyCollectCompleted(
        successCodes = (1..success).map { String.format("6000%02d", it) }.toSet(),
        failedCodes = (1..failed).map { String.format("6001%02d", it) }.toSet(),
        durationMs = 1000,
    )

    private fun savedSentimentCaptor(): ArgumentCaptor<SentimentCycle> =
        ArgumentCaptor.forClass(SentimentCycle::class.java).apply {
            Mockito.verify(sentimentRepo).save(capture())
        }

    // ==================== 事件触发 ====================

    @Test
    fun `testOnDailyCollectCompleted triggersComputeAndPersists`() {
        // given: 采集完成事件（失败率 0）
        // when
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: computeFor 恰调 1 次 + 先删后插落库 sentiment/dragon（§17.2 同日重跑幂等）
        assertEquals(1, compute.computeCalls, "computeFor 触发（§13.4 事件驱动）")
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(
            anyDate(),
            anyDate(),
        )
        savedSentimentCaptor()
        Mockito.verify(dragonRepo).saveAll(Mockito.anyList<DragonCycle>())
    }

    // ==================== 21:30 兜底幂等跳过 ====================

    @Test
    fun `testFallbackCron skipWhenTodayRowFull`() {
        // given: 今日行存在且非 PARTIAL（§17.2 跳过条件=存在且非 PARTIAL）；Job 日期取 LocalDate.now() → any matcher
        Mockito.`when`(sentimentRepo.findByTradeDate(anyDate()))
            .thenReturn(SentimentCycle().apply { tradeDate = today; dataCoverage = DataCoverage.FULL })

        // when
        job.fallbackCron()

        // then: 幂等跳过——不 compute、不落库
        assertEquals(0, compute.computeCalls, "FULL 行不重复 compute")
        Mockito.verify(sentimentRepo, Mockito.never()).save(anySentimentCycle())
        Mockito.verify(sentimentRepo, Mockito.never()).deleteByTradeDateBetween(
            anyDate(),
            anyDate(),
        )
    }

    @Test
    fun `testFallbackCron computesWhenNoRow`() {
        // given: 今日行不存在（事件丢失 JVM 重启，兜底补算）
        Mockito.`when`(sentimentRepo.findByTradeDate(anyDate())).thenReturn(null)

        // when
        job.fallbackCron()

        // then: 与事件触发同路径 computeFor + 先删后插落库（同日重跑无副作用，§17.2）
        assertEquals(1, compute.computeCalls, "兜底触发 computeFor")
        savedSentimentCaptor()
    }

    @Test
    fun `testFallbackCron recomputesWhenRowPartial`() {
        // given: 今日行存在但 data_coverage=PARTIAL（§17.2：PARTIAL 行次日兜底应重算为 FULL）
        Mockito.`when`(sentimentRepo.findByTradeDate(anyDate()))
            .thenReturn(SentimentCycle().apply { tradeDate = today; dataCoverage = DataCoverage.PARTIAL })

        // when
        job.fallbackCron()

        // then: 不跳过 → 先删后插重算（兜底 cron 事件空 failed → 重算行 FULL）
        assertEquals(1, compute.computeCalls, "PARTIAL 行触发兜底重算")
        Mockito.verify(sentimentRepo).deleteByTradeDateBetween(
            anyDate(),
            anyDate(),
        )
        val captor = savedSentimentCaptor()
        assertEquals(DataCoverage.FULL, captor.value.dataCoverage, "PARTIAL 兜底重算为 FULL（兜底 cron 无失败）")
    }

    @Test
    fun `testOnDailyCollectCompleted rerunDeletesThenInserts`() {
        // given: 事件重发（同日已存在行，§17.2 先删后插幂等，不撞 trade_date 唯一约束）
        // when: 同一事件触发两次
        job.onDailyCollectCompleted(event(success = 10, failed = 0))
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: 每次派生都先删后插
        assertEquals(2, compute.computeCalls, "事件重发触发 2 次 computeFor")
        Mockito.verify(sentimentRepo, Mockito.times(2)).deleteByTradeDateBetween(
            anyDate(),
            anyDate(),
        )
        Mockito.verify(sentimentRepo, Mockito.times(2)).save(anySentimentCycle())
    }

    // ==================== 失败率 >10% → PARTIAL + 钉钉提示 ====================

    @Test
    fun `testFailureRateOver10Percent marksPartialAndNotifies`() {
        // given: 成功 8 + 失败 2（失败率 20% > 10%）
        job.onDailyCollectCompleted(event(success = 8, failed = 2))

        // then: 照常派生但行标 PARTIAL（§13.4）+ 钉钉提示失败率
        val captor = savedSentimentCaptor()
        assertEquals(DataCoverage.PARTIAL, captor.value.dataCoverage, "失败率>10% 行标 PARTIAL")
        val allContent = notifier.notified.map { it.second } + notifier.digests
        assertTrue(
            allContent.any { it.contains("失败率") || it.contains("PARTIAL") },
            "PARTIAL 场景应钉钉提示失败率（§13.4）",
        )
    }

    @Test
    fun `testFailureRateAt10PercentStaysFullNoPartialNotify`() {
        // given: 成功 9 + 失败 1（失败率 10%，不大于阈值）
        job.onDailyCollectCompleted(event(success = 9, failed = 1))

        // then: 行保持 FULL，不触发 PARTIAL 钉钉提示
        val captor = savedSentimentCaptor()
        assertEquals(DataCoverage.FULL, captor.value.dataCoverage, "失败率≤10% 行标 FULL")
        val allContent = notifier.notified.map { it.second } + notifier.digests
        assertTrue(allContent.none { it.contains("失败率") || it.contains("PARTIAL") }, "失败率≤10% 不提示 PARTIAL")
    }

    // ==================== 非交易日守卫 ====================

    @Test
    fun `testOnDailyCollectCompleted nonTradingDaySkipsDerivation`() {
        // given: 非交易日（§17.2 守卫）
        calendar.isTrading = false

        // when
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: 不派生不落库
        assertEquals(0, compute.computeCalls, "非交易日不 compute")
        Mockito.verify(sentimentRepo, Mockito.never()).save(anySentimentCycle())
    }

    // ==================== 失败不阻塞主采集 ====================

    @Test
    fun `testComputeFailure doesNotPropagate and notifies`() {
        // given: computeFor 抛异常（失败不阻塞主采集，吞掉 + 钉钉告警）
        compute.throwOnCompute = IllegalStateException("派生失败")

        // when: 不应向上抛（被 Job 吞掉）
        job.onDailyCollectCompleted(event(success = 10, failed = 0))

        // then: 有钉钉告警记录
        assertTrue(notifier.notified.isNotEmpty(), "派生失败应有钉钉告警（不阻塞主采集，§13.4）")
    }
}
