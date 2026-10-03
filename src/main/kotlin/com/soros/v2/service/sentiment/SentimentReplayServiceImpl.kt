package com.soros.v2.service.sentiment

import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.exception.BusinessException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * §13.5 情绪历史冷启动回放服务（POST /api/v1/jobs/sentiment-replay）。
 *
 * 严格按 trading_calendar 顺序逐日 computeFor（不可并行、不可跳日）；回放前删除区间内行
 * （重放语义）；首日无"前文"，新建龙头 note 标 BOOT（§4.9 BOOT 语义经 note 表达，status=RISING）。
 * 全量 ~1300 交易日，单日计算秒级，总耗时分钟级。
 */
@Service
class SentimentReplayServiceImpl(
    private val computeService: SentimentComputeService,
    private val sentimentCycleRepository: SentimentCycleRepository,
    private val dragonCycleRepository: DragonCycleRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val stockInfoRepository: StockInfoRepository,
    private val tradingCalendarRepository: TradingCalendarRepository,
    private val notifier: DingTalkNotifier,
) : SentimentReplayService {

    private val logger = LoggerFactory.getLogger(SentimentReplayServiceImpl::class.java)

    @Transactional
    override fun replay(from: LocalDate, to: LocalDate): SentimentReplaySummary {
        if (from.isAfter(to)) throw BusinessException("回放区间非法：from($from) > to($to)")
        logger.info("[Step Replay] 情绪回放启动：from={} to={}（删后重建）", from, to)
        // 重放语义：区间内旧行删干净（sentiment + dragon），防新旧混杂
        sentimentCycleRepository.deleteByTradeDateBetween(from, to)
        dragonCycleRepository.deleteByStartDateBetween(from, to)
        sentimentCycleRepository.flush()

        val calendar = tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(from, to)
        if (calendar.isEmpty()) {
            logger.warn("[Step Replay] 区间内无交易日：from={} to={}（空回放）", from, to)
            return SentimentReplaySummary(from, to, 0, 0, emptyList())
        }
        val tradingDays = calendar.map { it.tradeDate }
        val stCodes = stockInfoRepository.findByIsStTrue().map { it.code }.toSet()

        var prevCycle: SentimentCycle? = null
        var activeDragon: MutableList<DragonCycle> = mutableListOf()
        var sentimentRows = 0
        val dragonCycleList = mutableListOf<String>()
        // 全部龙头更新按 (code, startDate) 去重保留最终状态：同一周期跨日推进是同一对象引用，去重后 saveAll
        // （阵亡周期含 cycle_type 定性也必须落库，对齐 Job 路径 SentimentCycleJob:103）
        val savedDragons = LinkedHashMap<Pair<String, LocalDate>, DragonCycle>()

        for ((dayIndex, day) in tradingDays.withIndex()) {
            val barsByCode = stockHistoryRepository.findByTradeDateBetween(day.minusDays(REPLAY_BAR_WINDOW), day)
                .groupBy { it.code }
                .filterKeys { it !in stCodes }
            val ctx = SentimentComputeContext(
                date = day,
                prevCycle = prevCycle,
                barsByCode = barsByCode,
                calendar = tradingDays.takeWhile { !it.isAfter(day) },
                activeDragonCycles = activeDragon,
            )
            val result = computeService.computeFor(day, ctx)
            // 回放首日无"前文"：新建龙头 note 标 BOOT（§4.9；status 由状态机 RISE 表达）
            if (dayIndex == 0 && result.dragonUpdates.isNotEmpty()) {
                result.dragonUpdates.forEach { if (it.note == null) it.note = "BOOT" }
            }
            val sentiment = result.sentiment
            sentimentCycleRepository.save(sentiment)
            sentimentRows++
            result.dragonUpdates.forEach { update ->
                dragonCycleList.add("${update.code}@${update.startDate}(${update.status.name})")
                savedDragons[update.code to update.startDate] = update
            }
            activeDragon = result.dragonUpdates.filter { it.endDate == null }.toMutableList()
            prevCycle = sentiment
        }
        val persistedDragons = savedDragons.values.toList()
        dragonCycleRepository.saveAll(persistedDragons)

        val summary = SentimentReplaySummary(
            from = from,
            to = to,
            sentimentRows = sentimentRows,
            dragonRows = persistedDragons.size,
            dragonCycleList = dragonCycleList,
        )
        notifier.notifyDailyDigest("情绪回放完成：$from~$to，sentiment ${summary.sentimentRows} 行，龙头 ${summary.dragonRows} 条")
        logger.info("[Step Replay] 情绪回放完成：sentiment={} dragon={}", sentimentRows, dragonCycleList.size)
        return summary
    }

    private companion object {
        const val REPLAY_BAR_WINDOW = 6L
    }
}
