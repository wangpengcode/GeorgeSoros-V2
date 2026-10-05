package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.MarketDaily
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SectorDailyRepository
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.SentimentCycleCompleted
import com.soros.v2.service.signal.SignalReplayService
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §19.11.1 / §17.2 SignalPrecomputeJob 接线契约测试（Mock 依赖直调事件/cron/启动对账入口）。
 *
 * 契约（§19.11.1 定稿 / §17.2 调度）：
 * - onSentimentCycleCompleted：FULL → replayDay(事件日期) 恰一次（依赖链显式化，链序 Sentiment→Signal）；
 *   PARTIAL → 保守跳过 + WARN（SentimentCycleCompleted KDoc「PARTIAL 次日滚动重拉后重算」，不即时派生）；
 * - fallbackCron（21:30 兜底）：跳过条件 = 当日 signal_daily 行数>0 且 market_daily 存在且非 PARTIAL
 *   （§17.2「存在且非 PARTIAL」）；不满足 → replayDay(today) 补算；非交易日守卫跳过；
 * - startupReconciliation（ApplicationReadyEvent 启动对账）：最近 N 交易日三表齐全 → 零动作；
 *   缺（signal/market/sector 任一）→ 对缺失日 replayDay 补算。
 *
 * ⚠️ SignalPrecomputeJob 为接线层空壳（方法体 TODO），本测试当前红；Implementer 填充后应全绿。
 */
class SignalPrecomputeJobTest {

    private lateinit var replayService: SignalReplayService
    private lateinit var signalDailyRepo: SignalDailyRepository
    private lateinit var marketDailyRepo: MarketDailyRepository
    private lateinit var sectorDailyRepo: SectorDailyRepository
    private lateinit var calendar: TradingCalendarService
    private lateinit var notifier: DingTalkNotifier
    private lateinit var job: SignalPrecomputeJob

    @BeforeEach
    fun setUp() {
        replayService = Mockito.mock(SignalReplayService::class.java)
        signalDailyRepo = Mockito.mock(SignalDailyRepository::class.java)
        marketDailyRepo = Mockito.mock(MarketDailyRepository::class.java)
        sectorDailyRepo = Mockito.mock(SectorDailyRepository::class.java)
        calendar = Mockito.mock(TradingCalendarService::class.java)
        notifier = Mockito.mock(DingTalkNotifier::class.java)
        job = SignalPrecomputeJob(
            replayService = replayService,
            signalDailyRepository = signalDailyRepo,
            marketDailyRepository = marketDailyRepo,
            sectorDailyRepository = sectorDailyRepo,
            calendarService = calendar,
            notifier = notifier,
        )
        // §19.12 空库守卫默认：有前置历史（guard 不触发）；空库用例显式 stub false
        Mockito.`when`(signalDailyRepo.existsByTradeDateLessThan(anyDate())).thenReturn(true)
    }

    private val today = LocalDate.of(2026, 9, 30)

    /**
     * Mockito.any() 的 Kotlin 非空参数安全 matcher：
     * 注册 `any(LocalDate)` matcher，但返回非空占位值（避免 Kotlin 非空检查对 null 抛 NPE）。
     */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return today
    }

    /** Mockito.eq() 的 Kotlin 非空参数安全 matcher（同 anyDate() 模式：注册 matcher，返回非空占位） */
    private fun eqEvent(event: DingTalkEvent): DingTalkEvent {
        Mockito.eq(event)
        return event
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（同 anyDate() 模式） */
    private fun anyEvent(): DingTalkEvent {
        Mockito.any(DingTalkEvent::class.java)
        return DingTalkEvent.SENTIMENT_DERIVE_FAILED
    }

    /** 构造 job 的辅助（开关参数可选，缺省=启用，与既有用例零改动兼容） */
    private fun makeJob(enabled: Boolean = true): SignalPrecomputeJob = SignalPrecomputeJob(
        replayService = replayService,
        signalDailyRepository = signalDailyRepo,
        marketDailyRepository = marketDailyRepo,
        sectorDailyRepository = sectorDailyRepo,
        calendarService = calendar,
        notifier = notifier,
        startupReconciliationEnabled = enabled,
    )

    // ==================== 增量事件：onSentimentCycleCompleted ====================

    @Test
    fun `testOnSentimentCycleCompleted fullCoverageCallsReplayDayOnce`() {
        // given: 情绪周期派生完成事件（coverage=FULL，§13.4 握手链序 Sentiment→Signal）
        val event = SentimentCycleCompleted(today, DataCoverage.FULL)

        // when
        job.onSentimentCycleCompleted(event)

        // then: replayDay(该日期) 恰调一次
        Mockito.verify(replayService, Mockito.times(1)).replayDay(today)
    }

    @Test
    fun `testOnSentimentCycleCompleted partialCoverageSkipsReplay`() {
        // given: coverage=PARTIAL（SentimentCycleCompleted KDoc：PARTIAL 次日滚动重拉后重算）
        val event = SentimentCycleCompleted(today, DataCoverage.PARTIAL)

        // when
        job.onSentimentCycleCompleted(event)

        // then: 保守跳过——不触发 replayDay（依赖不完整，不即时派生）
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
    }

    // ==================== 21:30 兜底 cron ====================

    @Test
    fun `testFallbackCron skipsWhenTodaySignalRowsPresentAndMarketNonPartial`() {
        // given: 交易日 + signal_daily 当日有行 + market_daily 存在且非 PARTIAL（§17.2 幂等跳过）
        // cron 取 LocalDate.now()（与全部盘后 Job 同口径），stub 用 anyDate() matcher
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(true)
        Mockito.`when`(signalDailyRepo.countByTradeDate(anyDate())).thenReturn(50L)
        Mockito.`when`(marketDailyRepo.findByTradeDate(anyDate()))
            .thenReturn(MarketDaily().apply { this.tradeDate = today; dataCoverage = DataCoverage.FULL })

        // when
        job.fallbackCron()

        // then: 幂等跳过——不补算
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
    }

    @Test
    fun `testFallbackCron computesWhenSignalRowsMissing`() {
        // given: 事件丢失（JVM 重启等）→ signal_daily 当日无行，兜底补算
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(true)
        Mockito.`when`(signalDailyRepo.countByTradeDate(anyDate())).thenReturn(0L)

        // when
        job.fallbackCron()

        // then: 补算 replayDay(LocalDate.now() 当日)
        Mockito.verify(replayService).replayDay(anyDate())
    }

    @Test
    fun `testFallbackCron recomputesWhenMarketDailyPartial`() {
        // given: market_daily 存在但 dataCoverage=PARTIAL（§17.2「存在且非 PARTIAL」才跳过 → PARTIAL 不跳过）
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(true)
        Mockito.`when`(signalDailyRepo.countByTradeDate(anyDate())).thenReturn(50L)
        Mockito.`when`(marketDailyRepo.findByTradeDate(anyDate()))
            .thenReturn(MarketDaily().apply { this.tradeDate = today; dataCoverage = DataCoverage.PARTIAL })

        // when
        job.fallbackCron()

        // then: PARTIAL 不跳过 → 补算（次日滚动重拉后重算为 FULL 的前置）
        Mockito.verify(replayService).replayDay(anyDate())
    }

    @Test
    fun `testFallbackCron nonTradingDaySkips`() {
        // given: 非交易日（§17.2 全部盘后 Job 入口统一守卫）
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(false)

        // when
        job.fallbackCron()

        // then: 不补算
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
    }

    // ==================== 启动对账：ApplicationReadyEvent ====================

    @Test
    fun `testStartupReconciliation completeThreeTablesNoAction`() {
        // given: 最近 N 交易日三表齐全（signal/market/sector 均有行）
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1, d2))
        listOf(d1, d2).forEach { day ->
            Mockito.`when`(signalDailyRepo.countByTradeDate(day)).thenReturn(50L)
            Mockito.`when`(marketDailyRepo.existsByTradeDate(day)).thenReturn(true)
            Mockito.`when`(sectorDailyRepo.existsByTradeDate(day)).thenReturn(true)
        }

        // when
        job.startupReconciliation()

        // then: 零动作——不触发任何补算
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
        Mockito.verify(replayService, Mockito.never()).replay(anyDate(), anyDate())
    }

    @Test
    fun `testStartupReconciliation missingDayTriggersReplayDay`() {
        // given: d2 缺 signal_daily 行（对账发现派生缺口）
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1, d2))
        Mockito.`when`(signalDailyRepo.countByTradeDate(d1)).thenReturn(50L)
        Mockito.`when`(signalDailyRepo.countByTradeDate(d2)).thenReturn(0L)
        listOf(d1, d2).forEach { day ->
            Mockito.`when`(marketDailyRepo.existsByTradeDate(day)).thenReturn(true)
            Mockito.`when`(sectorDailyRepo.existsByTradeDate(day)).thenReturn(true)
        }

        // when
        job.startupReconciliation()

        // then: 对缺失日补算 replayDay(d2)
        Mockito.verify(replayService).replayDay(d2)
    }

    // ==================== 空库守卫（§19.12：signal_daily 无前置历史 → 逐日补算不适用填空库） ====================

    @Test
    fun `testStartupReconciliation emptyLibrarySkipsAndAlerts`() {
        // given: 最近 2 日全缺 signal_daily 行；signal_daily 无早于缺失窗口首日(d1)的历史行（空库）
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1, d2))
        Mockito.`when`(signalDailyRepo.countByTradeDate(d1)).thenReturn(0L)
        Mockito.`when`(signalDailyRepo.countByTradeDate(d2)).thenReturn(0L)
        listOf(d1, d2).forEach { day ->
            Mockito.`when`(marketDailyRepo.existsByTradeDate(day)).thenReturn(false)
            Mockito.`when`(sectorDailyRepo.existsByTradeDate(day)).thenReturn(false)
        }
        Mockito.`when`(signalDailyRepo.existsByTradeDateLessThan(d1)).thenReturn(false)

        // when
        job.startupReconciliation()

        // then: 空库守卫触发 → 零 replayDay（不做逐日补算）+ 钉钉 SIGNAL_DERIVE_FAILED 告警（复用既有告警通道）
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
        Mockito.verify(notifier).notify(
            eqEvent(DingTalkEvent.SIGNAL_DERIVE_FAILED),
            Mockito.anyString(),
            Mockito.anyString(),
        )
    }

    @Test
    fun `testStartupReconciliation priorHistoryPreservesDayByDayCompute`() {
        // given: 单日(d2)缺 signal_daily 行；signal_daily 有早于 d2 的历史行（有前置历史 → 现行为保持）
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1, d2))
        Mockito.`when`(signalDailyRepo.countByTradeDate(d1)).thenReturn(50L)
        Mockito.`when`(signalDailyRepo.countByTradeDate(d2)).thenReturn(0L)
        listOf(d1, d2).forEach { day ->
            Mockito.`when`(marketDailyRepo.existsByTradeDate(day)).thenReturn(true)
            Mockito.`when`(sectorDailyRepo.existsByTradeDate(day)).thenReturn(true)
        }
        Mockito.`when`(signalDailyRepo.existsByTradeDateLessThan(d2)).thenReturn(true)

        // when
        job.startupReconciliation()

        // then: 对缺失日补算 replayDay(d2)（现有逐日补算行为不变）
        Mockito.verify(replayService).replayDay(d2)
        Mockito.verify(notifier, Mockito.never()).notify(anyEvent(), Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `testStartupReconciliation partialPriorHistoryComputesMissingDays`() {
        // given: d1/d2 均缺；signal_daily 有早于缺失窗口首日(d1)的历史行（部分前置 → 正常逐日补算）
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1, d2))
        Mockito.`when`(signalDailyRepo.countByTradeDate(d1)).thenReturn(0L)
        Mockito.`when`(signalDailyRepo.countByTradeDate(d2)).thenReturn(0L)
        listOf(d1, d2).forEach { day ->
            Mockito.`when`(marketDailyRepo.existsByTradeDate(day)).thenReturn(false)
            Mockito.`when`(sectorDailyRepo.existsByTradeDate(day)).thenReturn(false)
        }
        Mockito.`when`(signalDailyRepo.existsByTradeDateLessThan(d1)).thenReturn(true)

        // when
        job.startupReconciliation()

        // then: 逐日补算（每缺失日 replayDay，窗口首日前有行 → 不做空库守卫）
        Mockito.verify(replayService).replayDay(d1)
        Mockito.verify(replayService).replayDay(d2)
    }

    @Test
    fun `testFallbackCron emptyLibrarySkipsReplayAndAlerts`() {
        // given: 交易日 + signal_daily 当日无行 + 无早于当日的前置历史行（空库守卫触发）
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(true)
        Mockito.`when`(signalDailyRepo.countByTradeDate(anyDate())).thenReturn(0L)
        Mockito.`when`(signalDailyRepo.existsByTradeDateLessThan(anyDate())).thenReturn(false)

        // when
        job.fallbackCron()

        // then: 跳过补算（不 replayDay）+ 钉钉 SIGNAL_DERIVE_FAILED 告警（请触发全历史回放）
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
        Mockito.verify(notifier).notify(
            eqEvent(DingTalkEvent.SIGNAL_DERIVE_FAILED),
            Mockito.anyString(),
            Mockito.anyString(),
        )
    }

    // ==================== 启动对账开关（运维：全历史回放期间关闭，防并发写死锁空转） ====================

    @Test
    fun `testStartupReconciliation disabledSwitchZeroAction`() {
        // given: 开关关闭（soros.signal.startupReconciliationEnabled=false）+ 近期日全缺（正常会触发补算）
        val d1 = today.minusDays(2)
        Mockito.`when`(calendar.recentTradingDays(anyDate(), Mockito.anyInt())).thenReturn(listOf(d1))
        Mockito.`when`(signalDailyRepo.countByTradeDate(d1)).thenReturn(0L)
        Mockito.`when`(marketDailyRepo.existsByTradeDate(d1)).thenReturn(false)
        Mockito.`when`(sectorDailyRepo.existsByTradeDate(d1)).thenReturn(false)
        val disabledJob = makeJob(enabled = false)

        // when
        disabledJob.startupReconciliation()

        // then: 零动作（不补算、不告警）——全历史回放独占窗口
        Mockito.verify(replayService, Mockito.never()).replayDay(anyDate())
        Mockito.verify(notifier, Mockito.never()).notify(anyEvent(), Mockito.anyString(), Mockito.anyString())
    }
}
