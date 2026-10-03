package com.soros.v2.job

import com.soros.v2.domain.DataCoverage
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.dto.DailyCollectCompleted
import com.soros.v2.service.sentiment.SentimentComputeService
import com.soros.v2.service.sentiment.SentimentComputeContext
import com.soros.v2.service.TradingCalendarService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §4.9/§13.4 SentimentCycleJob：每日情绪周期派生 + 龙头状态机推进 + 钉钉日报。
 *
 * 触发：DailyCollectCompleted 事件（§13.4 握手，依赖链显式化）+ 21:30 兜底 cron（幂等跳过已完成日）。
 * - 事件丢失（JVM 重启等）由兜底补算；同日重跑先删后插（事件重发不撞 trade_date 唯一约束，§17.2）；
 *   21:30 兜底跳过条件 = 今日行存在且非 PARTIAL（PARTIAL 行次日兜底重算为 FULL）。
 * - failedCodes 占比 >10%：照常派生，但 sentiment_cycle 行标 data_coverage=PARTIAL + 钉钉提示（§13.4）。
 * - 失败不阻塞主采集：异常吞掉记日志 + 钉钉告警。
 * - ST 隔离（铁律）：落库前对 barsByCode 做 ST 防御性再过滤（stock_info 为准，识别并排除）。
 * - §13.4 链序留痕：Sentiment 完成后应发完成事件供 Signal 消费（SignalPrecomputeJob 落地步接线），
 *   当前 SignalPrecomputeJob 未存在，本轮不发事件、仅在本 KDoc 留痕。
 */
@Component
class SentimentCycleJob(
    private val computeService: SentimentComputeService,
    private val sentimentCycleRepository: SentimentCycleRepository,
    private val dragonCycleRepository: DragonCycleRepository,
    private val calendarService: TradingCalendarService,
    private val notifier: DingTalkNotifier,
    private val stockHistoryRepository: StockHistoryRepository,
    private val stockInfoRepository: StockInfoRepository,
) {
    private val logger = LoggerFactory.getLogger(SentimentCycleJob::class.java)

    /** §13.4 事件触发：DailyCollectJob 完成后派生（日期取 LocalDate.now()，§13.4 事件无日期字段） */
    @EventListener
    fun onDailyCollectCompleted(event: DailyCollectCompleted) {
        runCatching { derive(LocalDate.now(), event) }
            .onFailure { exception ->
                logger.error("[Step SentimentCycle] 情绪周期派生失败：{}", exception.message)
                notifier.notify(
                    DingTalkEvent.SENTIMENT_DERIVE_FAILED,
                    "情绪周期派生失败",
                    "交易日=${LocalDate.now()}，异常=${exception.message}",
                )
            }
    }

    /** §13.4 兜底 cron 21:30（MON-FRI）：跳过条件=今日行存在且非 PARTIAL（§17.2，PARTIAL 行次日重算） */
    @Scheduled(cron = "0 30 21 * * MON-FRI")
    fun fallbackCron() {
        val today = LocalDate.now()
        val existing = sentimentCycleRepository.findByTradeDate(today)
        if (existing != null && existing.dataCoverage != DataCoverage.PARTIAL) {
            logger.info("[Step SentimentCycle] 兜底 cron 幂等跳过：今日已派生且非 PARTIAL {}", today)
            return
        }
        runCatching { derive(today, DailyCollectCompleted(emptySet(), emptySet(), 0L)) }
            .onFailure { exception ->
                logger.error("[Step SentimentCycle] 兜底补算失败：{}", exception.message)
                notifier.notify(
                    DingTalkEvent.SENTIMENT_DERIVE_FAILED,
                    "情绪周期兜底补算失败",
                    "交易日=$today，异常=${exception.message}",
                )
            }
    }

    /** 派生主路径（事件触发与兜底 cron 同路径，§13.5 computeFor 纯函数单点） */
    private fun derive(today: LocalDate, event: DailyCollectCompleted) {
        if (!calendarService.isTradingDay(today)) {
            logger.info("[Step SentimentCycle] 非交易日跳过派生：{}", today)
            return
        }
        val prevDay = calendarService.previousTradingDay(today) ?: today.minusDays(1)
        val barsByCode = stockHistoryRepository.findByTradeDateBetween(today.minusDays(JOB_BAR_WINDOW), today)
            .groupBy { it.code }
            .let { raw ->
                // ST 防御性再过滤（铁律：is_st 仅识别并排除，采集侧已隔离，此处双保险）
                val stCodes = stockInfoRepository.findByIsStTrue().map { it.code }.toSet()
                if (stCodes.isEmpty()) raw else raw.filterKeys { it !in stCodes }
            }
        val ctx = SentimentComputeContext(
            date = today,
            prevCycle = sentimentCycleRepository.findByTradeDate(prevDay),
            barsByCode = barsByCode,
            calendar = calendarService.recentTradingDays(today, CALENDAR_WINDOW),
            activeDragonCycles = dragonCycleRepository.findAllByEndDateIsNull(),
        )
        val result = computeService.computeFor(today, ctx)
        val failureRate = failureRateOf(event)
        val sentiment = result.sentiment
        sentiment.dataCoverage = if (failureRate > PARTIAL_THRESHOLD) DataCoverage.PARTIAL else DataCoverage.FULL
        // 同日重跑幂等：先删后插（事件重发 / PARTIAL 兜底重算均不撞 trade_date 唯一约束，§17.2）
        sentimentCycleRepository.deleteByTradeDateBetween(today, today)
        sentimentCycleRepository.save(sentiment)
        dragonCycleRepository.saveAll(result.dragonUpdates)
        if (failureRate > PARTIAL_THRESHOLD) {
            val pct = failureRate.multiply(BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString()
            notifier.notify(
                DingTalkEvent.SOURCE_DEGRADED,
                "情绪周期 PARTIAL",
                "交易日 $today 采集失败率 $pct%（>10%），照常派生但数据覆盖降级（§13.4）",
            )
        }
        notifier.notifyDailyDigest(
            "情绪周期派生完成：交易日 $today，涨停 ${sentiment.limitUpCount} 家，" +
                "最高板 ${sentiment.maxStreak}，阶段 ${sentiment.statusText}",
        )
    }

    /** 失败率 = failed/(success+failed)，比例一律 BigDecimal（铁律）；事件缺省（兜底 cron）视为 0 */
    private fun failureRateOf(event: DailyCollectCompleted): BigDecimal {
        val total = event.successCodes.size + event.failedCodes.size
        return if (total == 0) {
            BigDecimal.ZERO
        } else {
            BigDecimal(event.failedCodes.size).divide(BigDecimal(total), FAILURE_RATE_SCALE, RoundingMode.HALF_UP)
        }
    }

    private companion object {
        val PARTIAL_THRESHOLD: BigDecimal = BigDecimal("0.10")
        const val FAILURE_RATE_SCALE = 4
        const val JOB_BAR_WINDOW = 6L
        const val CALENDAR_WINDOW = 30
    }
}
