package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
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
}
