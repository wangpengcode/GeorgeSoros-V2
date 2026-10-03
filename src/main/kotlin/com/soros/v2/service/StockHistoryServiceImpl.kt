package com.soros.v2.service

import com.soros.v2.config.DataCollectionProperties
import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.IndexHistory
import com.soros.v2.entity.StockHistory
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.IndexHistoryRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.util.CollectMetrics
import com.soros.v2.util.DataValidator
import com.soros.v2.util.LimitUpDetector
import java.math.BigDecimal
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * StockHistoryService 实现。
 *
 * saveBatch 联动（②③④ 合并于一处，§4.7）：
 * 1. DataValidator.validate() 防线②全量：硬伤/软性违规行一律拒绝入库（VALIDATION_REJECTED 质量日志 + 指标），
 *    已识别漂移的 bar 豁免 prev_close 交叉校验（§4.6 除权日豁免，由本层保证）
 * 2. LimitUpDetector 涨停检测（不复权 change_pct + board 阈值，§4.5）
 * 3. prev_close 链式校验（§4.6：|prev_close − 前一根 close| ≤ max(0.01, prev_close×0.5%)；
 *    漂移 → data_quality_log(ADJUSTMENT_DRIFT) + 单股全量重拉；复检仍不一致 → ADJUSTMENT_DRIFT_UNRESOLVED + 钉钉告警）
 * 4. §4.8 连板数派生（读前一日 streak + trading_calendar 定位 + IPO 首 5 日守卫）
 * 5. upsert（UNIQUE(code,trade_date) 幂等）+ data_source 大写
 *
 * 事务说明：saveBatch 为 suspend（内含 pythonClient 重拉），阻塞 DB 写集中在内部方法，避免把
 * suspend 直接挂 @Transactional（跨线程失效）。测试直构场景下各仓储方法自带事务，调用线程即测试事务线程。
 */
@Service
class StockHistoryServiceImpl(
    private val historyRepository: StockHistoryRepository,
    private val dataQualityLogRepository: DataQualityLogRepository,
    private val tradingCalendarService: TradingCalendarService,
    private val pythonClient: PythonDataServiceClient,
    private val properties: DataCollectionProperties,
    private val stockInfoService: StockInfoService,
    private val metrics: CollectMetrics,
    private val indexHistoryRepository: IndexHistoryRepository,
    private val notifier: DingTalkNotifier? = null,
) : StockHistoryService {

    private val logger = LoggerFactory.getLogger(StockHistoryServiceImpl::class.java)

    override fun findMaxDate(code: String): LocalDate? =
        historyRepository.findMaxTradeDateByCode(code)

    override suspend fun saveBatch(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
        board: Board,
    ): SaveBatchResult {
        val drift = detectDrift(code, bars)
        val (validBars, invalidSkipped) = validateWithExemption(code, bars, source, driftExemptDates(drift))
        var actualBars = validBars
        var refetched = false

        if (drift != null) {
            logger.warn("[saveBatch] 检测到除权漂移 code={} {}", code, drift.description)
            writeQualityLog(code, QualityIssueType.ADJUSTMENT_DRIFT, drift.description, source)
            metrics.incrementQualityLog(QualityIssueType.ADJUSTMENT_DRIFT)

            val refetch = refetchAndValidate(code, source)
            refetched = true
            actualBars = refetch.bars
            if (refetch.raw.isEmpty()) {
                logger.warn("[saveBatch] 重拉返回空（停牌/无数据）code={} → NO_BAR_TODAY", code)
                writeQualityLog(code, QualityIssueType.NO_BAR_TODAY, "重拉批次为空", source)
                metrics.incrementQualityLog(QualityIssueType.NO_BAR_TODAY)
            } else {
                refetch.unresolved?.let { unresolved ->
                    logger.error("[saveBatch] 重拉复检仍不一致 code={} {} → ADJUSTMENT_DRIFT_UNRESOLVED（人工介入）", code, unresolved.description)
                    writeQualityLog(code, QualityIssueType.ADJUSTMENT_DRIFT_UNRESOLVED, unresolved.description, source)
                    metrics.incrementQualityLog(QualityIssueType.ADJUSTMENT_DRIFT_UNRESOLVED)
                    notifier?.notify(
                        DingTalkEvent.ADJUSTMENT_DRIFT_UNRESOLVED,
                        "除权漂移未解决 $code",
                        "code=$code ${unresolved.description}",
                    )
                }
            }
        }

        val written = deriveAndPersist(code, actualBars, source, board)
        if (written > 0) metrics.incrementRows(source)
        return SaveBatchResult(code, written, drift != null, refetched, invalidSkipped)
    }

    override suspend fun saveIndexBatch(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
    ): Int {
        if (bars.isEmpty()) return 0
        var written = 0
        for (bar in bars) {
            val entity = indexHistoryRepository.findByCodeAndTradeDate(code, bar.date) ?: IndexHistory()
            entity.applyBar(code, bar, source)
            indexHistoryRepository.save(entity)
            written++
        }
        if (written > 0) metrics.incrementRows(source)
        return written
    }

    /** §4.6 链式校验：返回首个漂移点；无漂移/无前一日数据返回 null（批内相邻互检 + 批首对库内昨收）。 */
    private fun detectDrift(code: String, bars: List<DailyBar>): DriftInfo? {
        val sorted = bars.sortedBy { it.date }
        var lastClose: BigDecimal? = null
        for (bar in sorted) {
            val prevClose = bar.prevClose
            if (prevClose != null && lastClose != null) {
                if (isDrifted(prevClose, lastClose)) {
                    return DriftInfo(bar.date, prevClose, lastClose)
                }
            }
            lastClose = bar.close
        }
        if (sorted.isNotEmpty()) {
            val first = sorted.first()
            val prev = first.prevClose
            if (prev != null) {
                val dbLastClose = historyRepository.findTopByCodeOrderByTradeDateDesc(code)?.close
                if (dbLastClose != null && isDrifted(prev, dbLastClose)) {
                    return DriftInfo(first.date, prev, dbLastClose)
                }
            }
        }
        return null
    }

    /** |prev_close − last_close| ≤ max(0.01, prev_close×0.5%)；否则判漂移 */
    private fun isDrifted(prevClose: BigDecimal, lastClose: BigDecimal): Boolean {
        val tolerance = maxOf(BigDecimal("0.01"), prevClose.multiply(BigDecimal("0.005")))
        return prevClose.subtract(lastClose).abs() > tolerance
    }

    /** 漂移豁免集合：仅漂移点所在 bar 豁免交叉校验（§4.6 除权日豁免） */
    private fun driftExemptDates(drift: DriftInfo?): Set<LocalDate> =
        if (drift == null) emptySet() else setOf(drift.date)

    /** DataValidator.validate() 全量（§4.7 防线②）：违规行拒绝入库 + VALIDATION_REJECTED 留痕 */
    private fun validateWithExemption(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
        exemptDates: Set<LocalDate>,
    ): Pair<List<DailyBar>, Int> {
        val valid = bars.filter { bar ->
            val result = DataValidator.validate(bar, skipCrossCheck = bar.date in exemptDates)
            if (result.valid) {
                true
            } else {
                logger.warn("[saveBatch] 校验失败行拒绝入库 code={} date={} {}", code, bar.date, result.description)
                writeQualityLog(code, QualityIssueType.VALIDATION_REJECTED, "date=${bar.date} ${result.description}", source)
                metrics.incrementQualityLog(QualityIssueType.VALIDATION_REJECTED)
                false
            }
        }
        return valid to (bars.size - valid.size)
    }

    /** §4.6 漂移触发单股全量重拉：重拉批次同样过校验；重拉仍漂移 → unresolved（UNRESOLVED 上游处理） */
    private suspend fun refetchAndValidate(code: String, source: DataSourceType): RefetchOutcome {
        val response = pythonClient.fetchDailyBarsBatch(
            DailyBarsBatchRequest(
                codes = listOf(code),
                startDate = properties.defaultStartDate.toString(),
                endDate = LocalDate.now().toString(),
                adjust = "qfq",
            ),
        )
        val raw = response.results[code]?.data ?: emptyList()
        if (raw.isEmpty()) return RefetchOutcome(raw, emptyList(), null)
        val recheck = detectDrift(code, raw)
        val (valid, _) = validateWithExemption(code, raw, source, driftExemptDates(recheck))
        return RefetchOutcome(raw, valid, recheck)
    }

    /** §4.5/§4.8 派生 + upsert 幂等（UNIQUE(code,trade_date) 语义）；返回写入行数 */
    private fun deriveAndPersist(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
        board: Board,
    ): Int {
        val sorted = bars.sortedBy { it.date }
        if (sorted.isEmpty()) return 0
        val ipoDate = stockInfoService.findByCode(code)?.ipoDate
        val derivedStreaks = mutableMapOf<LocalDate, Short>()
        val derivedDownStreaks = mutableMapOf<LocalDate, Short>()

        var written = 0
        for (bar in sorted) {
            val (isLimitUp, isLimitDown) = LimitUpDetector.detect(bar.changePercent, board)
            val ipoGuard = isWithinIpoGuard(ipoDate, bar.date)
            val finalLimitUp = isLimitUp && !ipoGuard
            val finalLimitDown = isLimitDown && !ipoGuard

            val prevDate = tradingCalendarService.previousTradingDay(bar.date)
            val baseStreak = derivedStreaks[prevDate]?.toInt()
                ?: prevDate?.let { d -> historyRepository.findByCodeAndTradeDate(code, d)?.limitUpStreak?.let { s -> s.toInt() } }
                ?: 0
            val baseDownStreak = derivedDownStreaks[prevDate]?.toInt()
                ?: prevDate?.let { d -> historyRepository.findByCodeAndTradeDate(code, d)?.limitDownStreak?.let { s -> s.toInt() } }
                ?: 0

            val streak = if (finalLimitUp) baseStreak + 1 else 0
            val streakDown = if (finalLimitDown) baseDownStreak + 1 else 0

            val entity = historyRepository.findByCodeAndTradeDate(code, bar.date) ?: StockHistory()
            entity.applyBar(code, bar, source, finalLimitUp, finalLimitDown, streak, streakDown)
            historyRepository.save(entity)
            derivedStreaks[bar.date] = streak.toShort()
            derivedDownStreaks[bar.date] = streakDown.toShort()
            written++
        }
        return written
    }

    /** §4.8 IPO 首 5 日守卫：距 ipo_date 不足 5 个交易日 → true（强制 is_limit_up/down=false、streak=0） */
    private fun isWithinIpoGuard(ipoDate: LocalDate?, barDate: LocalDate): Boolean {
        if (ipoDate == null) return false
        val fiveBack = tradingCalendarService.recentTradingDays(barDate, 5)
        if (fiveBack.size < 5) return true
        return ipoDate > fiveBack.first()
    }

    private fun writeQualityLog(code: String, issueType: QualityIssueType, detail: String, source: DataSourceType) {
        dataQualityLogRepository.save(
            DataQualityLog().apply {
                checkDate = LocalDate.now()
                this.code = code
                this.issueType = issueType.name
                this.detail = detail
                this.source = source.name
            },
        )
    }

    /** 漂移重拉结果（raw 原始 / bars 过校验后 / unresolved 复检漂移点） */
    private data class RefetchOutcome(
        val raw: List<DailyBar>,
        val bars: List<DailyBar>,
        val unresolved: DriftInfo?,
    )

    /** 漂移点信息（date/两值，供质量日志 detail） */
    private data class DriftInfo(
        val date: LocalDate,
        val prevClose: BigDecimal,
        val lastClose: BigDecimal,
    ) {
        val description: String get() = "date=$date prev_close=$prevClose vs last_close=$lastClose"
    }
}
