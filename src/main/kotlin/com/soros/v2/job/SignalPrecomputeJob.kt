package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SectorDailyRepository
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.SentimentCycleCompleted
import com.soros.v2.service.signal.SignalReplayService
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §19.11.1 / §17.2 SignalPrecomputeJob：信号预计算接线层（增量事件 + 21:30 兜底 + 启动对账）。
 *
 * 触发（§19.11.1 定稿 / §17.2 调度）：
 * - 增量：监听 SentimentCycleCompleted 事件（依赖链显式化，链序 DailyCollect→Sentiment→Signal）。
 *   **PARTIAL 双机制并存口径**：事件侧 coverage=PARTIAL → 保守跳过 + WARN（次日滚动重拉后重算，不即时派生）；
 *   21:30 cron 侧不判事件 coverage，以「signal_daily 有行 且 market_daily 存在且非 PARTIAL」为跳过条件，
 *   PARTIAL 行不跳过 → 补算重算为 FULL（§17.2「存在且非 PARTIAL」）。
 * - 21:30 兜底 cron：跳过条件 = 当日 signal_daily 行数>0 且 market_daily 存在且非 PARTIAL；否则 replayDay(today) 补算。
 * - 启动对账（ApplicationReadyEvent）：最近 N 交易日（N=RECONCILE_WINDOW=5）三表
 *   （signal/market/sector_daily）齐全 → 零动作；缺任一 → 对缺失日 replayDay 补算；
 *   **空库守卫（§19.12）**：缺失窗口首日（或当日）之前无 signal_daily 历史行 → 逐日补算不适用填空库，
 *   WARN + 钉钉 SIGNAL_DERIVE_FAILED 告警 + 直接返回（零 replayDay），引导全历史回放 POST /api/v1/jobs/signal-replay。
 *
 * 全部盘后入口统一 isTradingDay 守卫（§17.2）；失败降级：补算异常 runCatching 吞掉记日志 + 钉钉告警，不阻塞主流程。
 */
@Component
class SignalPrecomputeJob(
    private val replayService: SignalReplayService,
    private val signalDailyRepository: SignalDailyRepository,
    private val marketDailyRepository: MarketDailyRepository,
    private val sectorDailyRepository: SectorDailyRepository,
    private val calendarService: TradingCalendarService,
    private val notifier: DingTalkNotifier,
) {
    private val logger = LoggerFactory.getLogger(SignalPrecomputeJob::class.java)

    /** §19.11.1 增量：SentimentCycleJob 完成事件触发单日派生（FULL → replayDay；PARTIAL → 保守跳过 + WARN） */
    @EventListener
    fun onSentimentCycleCompleted(event: SentimentCycleCompleted) {
        if (event.coverage == DataCoverage.PARTIAL) {
            logger.warn("[Step Signal] 增量事件 coverage=PARTIAL 保守跳过（次日滚动重拉后重算）：tradeDate={}", event.tradeDate)
            return
        }
        logger.info("[Step Signal] 增量事件 FULL：replayDay 派生 tradeDate={}", event.tradeDate)
        runCatching { replayService.replayDay(event.tradeDate) }
            .onFailure { exception ->
                logger.error("[Step Signal] 增量派生失败：tradeDate={} error={}", event.tradeDate, exception.message)
                notifier.notify(
                    DingTalkEvent.SIGNAL_DERIVE_FAILED,
                    "信号增量派生失败",
                    "交易日=${event.tradeDate}，异常=${exception.message}",
                )
            }
    }

    /** §17.2 21:30 兜底 cron（MON-FRI）：跳过条件 = signal_daily 当日有行 且 market_daily 存在且非 PARTIAL；否则 replayDay 补算 */
    @Scheduled(cron = "0 30 21 * * MON-FRI")
    fun fallbackCron() {
        val today = LocalDate.now()
        if (!calendarService.isTradingDay(today)) {
            logger.info("[Step Signal] 非交易日跳过兜底补算：tradeDate={}", today)
            return
        }
        if (signalDailyRepository.countByTradeDate(today) > 0) {
            val market = marketDailyRepository.findByTradeDate(today)
            if (market != null && market.dataCoverage != DataCoverage.PARTIAL) {
                logger.info("[Step Signal] 兜底 cron 幂等跳过（signal 有行且 market 存在非 PARTIAL）：tradeDate={}", today)
                return
            }
        }
        logger.warn("[Step Signal] 兜底 cron 补算：tradeDate={}（事件丢失或 market PARTIAL）", today)
        // §19.12 空库守卫：signal_daily 无当日之前的历史行 → 逐日补算不适用填空库，须全历史回放
        if (!signalDailyRepository.existsByTradeDateLessThan(today)) {
            logger.warn("[Step Signal] 空库守卫触发：signal_daily 无缺失窗口之前的历史行，逐日补算不适用填空库，请触发全历史回放 POST /api/v1/jobs/signal-replay")
            notifier.notify(
                DingTalkEvent.SIGNAL_DERIVE_FAILED,
                "信号兜底补算空库守卫",
                "signal_daily 无当日之前的历史行，逐日补算不适用填空库，请触发全历史回放 POST /api/v1/jobs/signal-replay",
            )
            return
        }
        runCatching { replayService.replayDay(today) }
            .onFailure { exception ->
                logger.error("[Step Signal] 兜底补算失败：tradeDate={} error={}", today, exception.message)
                notifier.notify(
                    DingTalkEvent.SIGNAL_DERIVE_FAILED,
                    "信号兜底补算失败",
                    "交易日=$today，异常=${exception.message}",
                )
            }
    }

    /** §17.2 启动对账：最近 N 交易日三表齐全性检查，缺则对缺失日补算（§19.12 空库守卫前置） */
    @EventListener(ApplicationReadyEvent::class)
    fun startupReconciliation() {
        val today = LocalDate.now()
        val recentDays = calendarService.recentTradingDays(today, RECONCILE_WINDOW)
        logger.info("[Step Signal] 启动对账：最近 {} 交易日三表齐全性检查", RECONCILE_WINDOW)
        val missingDays = mutableListOf<LocalDate>()
        for (day in recentDays) {
            val signalOk = signalDailyRepository.countByTradeDate(day) > 0
            val marketOk = marketDailyRepository.existsByTradeDate(day)
            val sectorOk = sectorDailyRepository.existsByTradeDate(day)
            if (signalOk && marketOk && sectorOk) {
                logger.info("[Step Signal] 启动对账齐全：tradeDate={}", day)
            } else {
                logger.warn(
                    "[Step Signal] 启动对账发现缺口补算：tradeDate={} signal={} market={} sector={}",
                    day, signalOk, marketOk, sectorOk,
                )
                missingDays.add(day)
            }
        }
        if (missingDays.isEmpty()) return
        // §19.12 空库守卫：signal_daily 无缺失窗口首日之前的历史行 → 逐日补算不适用填空库，须全历史回放
        val windowStart = missingDays.min()
        if (!signalDailyRepository.existsByTradeDateLessThan(windowStart)) {
            logger.warn("[Step Signal] 空库守卫触发：signal_daily 无缺失窗口之前的历史行，逐日补算不适用填空库，请触发全历史回放 POST /api/v1/jobs/signal-replay")
            notifier.notify(
                DingTalkEvent.SIGNAL_DERIVE_FAILED,
                "信号启动对账空库守卫",
                "signal_daily 无缺失窗口之前的历史行，逐日补算不适用填空库，请触发全历史回放 POST /api/v1/jobs/signal-replay",
            )
            return
        }
        for (day in missingDays) {
            runCatching { replayService.replayDay(day) }
                .onFailure { exception ->
                    logger.error("[Step Signal] 启动对账补算失败：tradeDate={} error={}", day, exception.message)
                    notifier.notify(
                        DingTalkEvent.SIGNAL_DERIVE_FAILED,
                        "信号启动对账补算失败",
                        "交易日=$day，异常=${exception.message}",
                    )
                }
        }
    }

    private companion object {
        /** §17.2 启动对账窗口：最近 N 交易日 */
        const val RECONCILE_WINDOW = 5
    }
}
