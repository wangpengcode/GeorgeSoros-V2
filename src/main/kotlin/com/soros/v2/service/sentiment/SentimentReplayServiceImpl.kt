package com.soros.v2.service.sentiment

import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.exception.BusinessException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.ReplayBar
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * §19.12 情绪历史回放服务（批量瘦身投影 + 按天分事务 + 增量补缺断点续跑）。
 *
 * - 批量加载：findReplayBars 瘦身投影（8 字段）按年分块（每块 ~130 万行安全；块间 prevCycle/activeDragon
 *   程序变量跨块衔接，bars 块内独立，每块加载 [块首−6日, 块末]）；
 * - 按天分事务：去 @Transactional，构造注入 PlatformTransactionManager（镜像 SignalReplayServiceImpl），
 *   每日 tx = delete(sentiment_cycle 当日) + delete(dragon_cycle 当日) + save + saveAll；computeFor 事务外；
 * - force=false 增量补缺：待算清单 = 交易日历∩[from,to] − 已有日期；D0 前一交易日断点热启动；
 *   空洞日内存推进状态但不落库；数据守卫（非 ST 有效股柱覆盖率 <90%）前缀截止 + deferredDates；
 * - force=true 全量重建：事务0 预清理区间两表 + flush（防 uq_dragon_active 冲突），随后冷启动重建；
 * - 进度日志：开始/结束 + 每 50 交易日一条（[Step Replay] 前缀、{} 占位符）。
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
    private val transactionManager: PlatformTransactionManager,
) : SentimentReplayService {

    private val logger = LoggerFactory.getLogger(SentimentReplayServiceImpl::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun replay(from: LocalDate, to: LocalDate, force: Boolean): SentimentReplaySummary {
        if (from.isAfter(to)) throw BusinessException("回放区间非法：from($from) > to($to)")
        val startMs = System.currentTimeMillis()
        logger.info("[Step Replay] 情绪回放启动：from={} to={} force={}", from, to, force)

        // 进行中龙头（断点热启动依赖；force=true 冷启动不用）→ 日历前缀扩展到 min(from, 全部进行中龙头 brokenDate)
        val activeDragon = if (force) mutableListOf<DragonCycle>() else dragonCycleRepository.findAllByEndDateIsNull().toMutableList()
        val calendarStart = if (force) from else minOf(from, activeDragon.mapNotNull { it.brokenDate }.minOrNull() ?: from)
        val fullCalendar = loadCalendar(calendarStart, to)
        val inRangeDays = fullCalendar.filter { it in from..to }
        if (inRangeDays.isEmpty()) {
            logger.warn("[Step Replay] 区间内无交易日：from={} to={}（空回放）", from, to)
            return SentimentReplaySummary(from, to, 0, 0, emptyList(), force = force)
        }

        val summary = if (force) {
            rebuildForceTrue(from, to, inRangeDays)
        } else {
            rebuildIncremental(from, to, inRangeDays, fullCalendar, activeDragon)
        }

        notifier.notifyDailyDigest("情绪回放完成：$from~$to，sentiment ${summary.sentimentRows} 行，龙头 ${summary.dragonRows} 条")
        logger.info(
            "[Step Replay] 情绪回放完成：from={} to={} 天数={} 总耗时={}ms 总行数={} 补算={} 跳过={} 守卫={}",
            from, to, inRangeDays.size, System.currentTimeMillis() - startMs, summary.sentimentRows,
            summary.filledDays, summary.skippedDays, summary.deferredDates.size,
        )
        return summary
    }

    // ==================== force=true：事务0 预清理后全量重建 ====================

    private fun rebuildForceTrue(from: LocalDate, to: LocalDate, tradingDays: List<LocalDate>): SentimentReplaySummary {
        // 事务0：区间整体预清理两表 + flush（防 uq_dragon_active 冲突，§19.12 决策 3）+ 区间前进行中龙头兜底 WARN
        transactionTemplate.execute {
            sentimentCycleRepository.deleteByTradeDateBetween(from, to)
            dragonCycleRepository.deleteByStartDateBetween(from, to)
            sentimentCycleRepository.flush()
            warnPreIntervalActiveDragons(from)
        }
        val stCodes = stockInfoRepository.findByIsStTrue().map { it.code }.toSet()
        val totalValid = stockInfoRepository.countByIsStFalseAndDelistedFalse()
        var prevCycle: SentimentCycle? = null
        var activeDragon: MutableList<DragonCycle> = mutableListOf()
        val savedDragons = LinkedHashMap<Pair<String, LocalDate>, DragonCycle>()
        val dragonCycleList = mutableListOf<String>()
        val deferred = mutableListOf<LocalDate>()
        var sentimentRows = 0
        var processed = 0
        var guardTriggered = false
        for (chunk in tradingDays.chunkedByYear()) {
            val bars = loadBars(chunk.first(), chunk.last(), stCodes)
            for (day in chunk) {
                if (guardTriggered) {
                    deferred.add(day)
                    continue
                }
                if (!barCoveragePasses(day, bars, totalValid)) {
                    logger.warn("[Step Replay] 数据守卫触发（柱子覆盖率不足）前缀截止：tradeDate={} totalValid={}", day, totalValid)
                    guardTriggered = true
                    deferred.add(day)
                    continue
                }
                logProgress(processed, tradingDays.size, sentimentRows)
                val result = computeForDay(day, prevCycle, bars, tradingDays, activeDragon, day == tradingDays.first())
                persistDay(day, result, savedDragons, dragonCycleList, resetStaleIds = true)
                sentimentRows++
                prevCycle = result.sentiment
                activeDragon = result.dragonUpdates.filter { it.endDate == null }.toMutableList()
                processed++
            }
        }
        return SentimentReplaySummary(
            from, to, sentimentRows, savedDragons.values.size, dragonCycleList,
            force = true, filledDays = processed, skippedDays = 0, deferredDates = deferred,
        )
    }

    // ==================== force=false：增量补缺断点续跑 ====================

    private fun rebuildIncremental(
        from: LocalDate,
        to: LocalDate,
        inRangeDays: List<LocalDate>,
        fullCalendar: List<LocalDate>,
        initialActiveDragon: List<DragonCycle>,
    ): SentimentReplaySummary {
        val existingDates = existingTradeDates(from, to)
        val missingDays = inRangeDays.filter { it !in existingDates }
        if (missingDays.isEmpty()) {
            logger.info("[Step Replay] 增量续跑无缺日：from={} to={} filled=0 skipped={}", from, to, inRangeDays.size)
            return SentimentReplaySummary(from, to, 0, 0, emptyList(), filledDays = 0, skippedDays = inRangeDays.size)
        }
        val window = ReplayWindow(from, to, inRangeDays, fullCalendar, existingDates, missingDays.first())
        return rebuildFromHotStart(window, initialActiveDragon)
    }

    /**
     * 断点热启动增量回放（§19.12 决策 5）：自 D0 前一交易日 prevCycle + 进行中龙头起，逐日内存推进；
     * 缺日落库、已有日跳过但状态推进；空洞日不落库；守卫前缀截止 + deferredDates。
     */
    private fun rebuildFromHotStart(
        window: ReplayWindow,
        initialActiveDragon: List<DragonCycle>,
    ): SentimentReplaySummary {
        val d0 = window.d0
        // 断点热启动（§19.12 决策 5）：prevCycle=D0 前一交易日行；缺失 WARN（消除静默断链）
        var prevCycle = hotStartPrevCycle(d0)
        var activeDragon = initialActiveDragon.toMutableList()
        val stCodes = stockInfoRepository.findByIsStTrue().map { it.code }.toSet()
        val totalValid = stockInfoRepository.countByIsStFalseAndDelistedFalse()
        val savedDragons = LinkedHashMap<Pair<String, LocalDate>, DragonCycle>()
        val dragonCycleList = mutableListOf<String>()
        val deferred = mutableListOf<LocalDate>()
        var sentimentRows = 0
        var filled = 0
        var processed = 0
        var guardTriggered = false
        for (chunk in window.inRangeDays.chunkedByYear()) {
            val bars = loadBars(chunk.first(), chunk.last(), stCodes)
            for (day in chunk) {
                if (day.isBefore(d0)) continue
                if (guardTriggered) {
                    if (day !in window.existingDates) deferred.add(day)
                    continue
                }
                val isMissing = day !in window.existingDates
                if (isMissing && !barCoveragePasses(day, bars, totalValid)) {
                    logger.warn("[Step Replay] 数据守卫触发（柱子覆盖率不足）前缀截止：tradeDate={} totalValid={}", day, totalValid)
                    guardTriggered = true
                    deferred.add(day)
                    continue
                }
                logProgress(processed, window.inRangeDays.size, sentimentRows)
                val result = computeForDay(day, prevCycle, bars, window.fullCalendar, activeDragon, day == d0)
                if (isMissing) {
                    persistDay(day, result, savedDragons, dragonCycleList, resetStaleIds = false)
                    sentimentRows++
                    filled++
                }
                prevCycle = result.sentiment
                activeDragon = result.dragonUpdates.filter { it.endDate == null }.toMutableList()
                processed++
            }
        }
        return SentimentReplaySummary(
            window.from, window.to, sentimentRows, savedDragons.values.size, dragonCycleList,
            force = false, filledDays = filled,
            skippedDays = window.inRangeDays.size - filled, deferredDates = deferred,
        )
    }

    /** 区间内已落库 sentiment_cycle 日期集合 */
    private fun existingTradeDates(from: LocalDate, to: LocalDate): Set<LocalDate> =
        sentimentCycleRepository.findByTradeDateBetweenOrderByTradeDateAsc(from, to).map { it.tradeDate }.toSet()

    /**
     * 断点热启动 prevCycle（§19.12 决策 5）：D0 前一交易日查交易日历严格前位（findFirstByTradeDateBefore…Desc，
     * 不依赖日历数组——d0==from 时数组前位越界会静默断链）；前一交易日无情绪行 → WARN（消除静默降级）。
     */
    private fun hotStartPrevCycle(d0: LocalDate): SentimentCycle? {
        val prevDate = tradingCalendarRepository.findFirstByTradeDateBeforeOrderByTradeDateDesc(d0)?.tradeDate
        val prevCycle = prevDate?.let { sentimentCycleRepository.findByTradeDate(it) }
        if (prevCycle == null) {
            logger.warn("[Step Replay] 热启动 prevCycle 缺失：D0={} 前一交易日无情绪行，followup 将断链", d0)
        }
        return prevCycle
    }

    /**
     * force=true 区间前进行中龙头兜底（§19.12 决策 3）：start_date<from 且 end_date 空的残留龙头在区间内
     * 同 code 再当选将撞 uq_dragon_active → 只 WARN 防误用（建议 from 取历史真起点或先手工终结），不自动终结、不改数据。
     */
    private fun warnPreIntervalActiveDragons(from: LocalDate) {
        val residual = dragonCycleRepository.findAllByEndDateIsNull().filter { it.startDate.isBefore(from) }
        if (residual.isNotEmpty()) {
            val earliest = residual.minByOrNull { it.startDate }?.startDate
            logger.warn(
                "[Step Replay] force=true 区间前存在进行中龙头 {} 只（最早 start_date={}），同 code 再当选将撞 uq_dragon_active，建议 from 取历史真起点或先手工终结",
                residual.size, earliest,
            )
        }
    }

    // ==================== 私有辅助 ====================

    /** 加载交易日历（升序；日历前缀扩展到 calendarStart，用于断点热启动观察期计数） */
    private fun loadCalendar(calendarStart: LocalDate, to: LocalDate): List<LocalDate> =
        tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(calendarStart, to)
            .map { it.tradeDate }
            .filter { !it.isBefore(calendarStart) && !it.isAfter(to) }
            .sorted()

    /** 瘦身投影批量加载（ST 隔离 + 按 code 分组每股 tradeDate 升序；加载范围 [块首−6日, 块末]） */
    private fun loadBars(chunkStart: LocalDate, chunkEnd: LocalDate, stCodes: Set<String>): Map<String, List<StockHistory>> =
        stockHistoryRepository.findReplayBars(chunkStart.minusDays(REPLAY_BAR_WINDOW), chunkEnd)
            .filter { it.code !in stCodes }
            .map { it.toReplayBar() }
            .groupBy { it.code }
            .mapValues { (_, bars) -> bars.sortedBy { it.tradeDate } }

    /** 投影物化为瘦身 StockHistory（id=0，仅 8 字段；computeFor 签名零改动，barsByCode 仍 Map<String, List<StockHistory>>） */
    private fun ReplayBar.toReplayBar(): StockHistory = StockHistory(
        id = 0L,
        code = code,
        tradeDate = tradeDate,
        close = close,
        changePct = changePct,
        isLimitUp = isLimitUp ?: false,
        isLimitDown = isLimitDown ?: false,
        limitUpStreak = limitUpStreak ?: 0,
        limitDownStreak = limitDownStreak ?: 0,
    )

    /** 6 日窗子列表（[day−6, day]，与原逐日 findByTradeDateBetween(day-6, day) 同口径） */
    private fun sliceWindow(barsByCode: Map<String, List<StockHistory>>, day: LocalDate): Map<String, List<StockHistory>> {
        val windowStart = day.minusDays(REPLAY_BAR_WINDOW)
        return barsByCode.mapValues { (_, bars) -> bars.filter { !it.tradeDate.isBefore(windowStart) && !it.tradeDate.isAfter(day) } }
    }

    /** 数据守卫：缺日非 ST 有效股柱子覆盖率 <90%（BAR_COVERAGE_THRESHOLD）→ 前缀截止 */
    private fun barCoveragePasses(day: LocalDate, bars: Map<String, List<StockHistory>>, totalValid: Long): Boolean {
        if (totalValid <= 0) return true
        val barCount = bars.values.flatten().count { it.tradeDate == day }
        return barCount >= totalValid * BAR_COVERAGE_THRESHOLD
    }

    /** 单日 computeFor（事务外，纯函数零 DB 访问）；回放/增量首日新建龙头 note=BOOT（§19.12 不变式⑥） */
    private fun computeForDay(
        day: LocalDate,
        prevCycle: SentimentCycle?,
        bars: Map<String, List<StockHistory>>,
        calendar: List<LocalDate>,
        activeDragon: List<DragonCycle>,
        isFirstDay: Boolean,
    ): SentimentComputeResult {
        val ctx = SentimentComputeContext(
            date = day,
            prevCycle = prevCycle,
            barsByCode = sliceWindow(bars, day),
            calendar = calendar.takeWhile { !it.isAfter(day) },
            activeDragonCycles = activeDragon,
        )
        val result = computeService.computeFor(day, ctx)
        if (isFirstDay && result.dragonUpdates.isNotEmpty()) {
            result.dragonUpdates.forEach { if (it.note == null) it.note = "BOOT" }
        }
        return result
    }

    /**
     * 每日独立事务：delete(sentiment 当日) + delete(dragon 当日) + save + saveAll；异常当日整体回滚向上抛。
     * force=true 全量重建（resetStaleIds=true）：事务0 已整窗删除两表，重建首日 computeFor 返回的龙头对象
     * 若携带上一轮回放的陈旧 id（IDENTITY 会把 id 写回内存对象）会触发"merge 已删行 → 乐观锁"；仅对
     * 本回放尚未见到的（code,startDate）重置 id=0 视为新行，已见（同对象跨日推进）保留 id 走 merge 更新
     * （§19.12 决策 3：阵亡行由 d1 同一对象 update 而非新插行）。
     */
    private fun persistDay(
        day: LocalDate,
        result: SentimentComputeResult,
        savedDragons: MutableMap<Pair<String, LocalDate>, DragonCycle>,
        dragonCycleList: MutableList<String>,
        resetStaleIds: Boolean,
    ) {
        transactionTemplate.execute {
            sentimentCycleRepository.deleteByTradeDateBetween(day, day)
            dragonCycleRepository.deleteByStartDateBetween(day, day)
            sentimentCycleRepository.save(result.sentiment)
            result.dragonUpdates.forEach { update ->
                if (resetStaleIds && savedDragons[update.code to update.startDate] == null && update.id != 0L) {
                    update.id = 0L
                }
                dragonCycleList.add("${update.code}@${update.startDate}(${update.status.name})")
                savedDragons[update.code to update.startDate] = update
            }
            dragonCycleRepository.saveAll(result.dragonUpdates)
        }
    }

    /** 进度日志（每 50 交易日一条，§19.12 决策 6） */
    private fun logProgress(processed: Int, totalDays: Int, sentimentRows: Int) {
        if (processed > 0 && processed % PROGRESS_LOG_STEP == 0) {
            logger.info("[Step Replay] 进度：已完成 {} 交易日（共 {}），sentiment 累计 {} 行", processed, totalDays, sentimentRows)
        }
    }

    /** 按年分块（§19.12 决策 2：每块 ~130 万行安全；区间 ≤1 年单块） */
    private fun List<LocalDate>.chunkedByYear(): List<List<LocalDate>> =
        groupBy { it.year }.toSortedMap().values.toList()

    /** 增量回放窗口上下文（跨方法传参 ≤5 封装） */
    private data class ReplayWindow(
        val from: LocalDate,
        val to: LocalDate,
        val inRangeDays: List<LocalDate>,
        val fullCalendar: List<LocalDate>,
        val existingDates: Set<LocalDate>,
        val d0: LocalDate,
    )

    private companion object {
        const val REPLAY_BAR_WINDOW = 6L
        const val PROGRESS_LOG_STEP = 50
        const val BAR_COVERAGE_THRESHOLD = 0.9
    }
}
