package com.soros.v2.job

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.domain.SegmentReason
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryGapCheckRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.BackfillClassifier
import com.soros.v2.service.backfill.BackfillPlanService
import com.soros.v2.service.backfill.dto.BackfillProgress
import com.soros.v2.service.backfill.dto.BackfillSummary
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.RerunPlan
import com.soros.v2.service.dto.BatchItem
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.service.sentiment.SentimentReplaySummary
import com.soros.v2.util.CollectMetrics
import com.soros.v2.util.DataValidator
import com.soros.v2.util.LimitStreakComputer
import com.soros.v2.util.LimitUpDetector
import com.soros.v2.util.MdcSupport
import java.io.BufferedReader
import java.io.StringReader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.delay
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.stereotype.Component

/**
 * §六 BackfillJob：历史数据回填（手动触发，POST /api/v1/jobs/backfill 编排核心）。
 *
 * 写入路径（PLAN §六.1 COPY 两段式）：
 * ```
 * ① 每批 /daily-bars/batch（backfill profile，>366 自然日自动切 60s 超时/1 次重试）
 *   → DataValidator 校验（skipCrossCheck=true：交叉校验是增量路径 §4.6 的职责，批量历史不适用）
 *   → CopyManager COPY INTO stock_history_stage（UNLOGGED 中转，TEXT/CSV 流式）
 * ② INSERT INTO stock_history SELECT * FROM stock_history_stage
 *   ON CONFLICT (code, trade_date) DO UPDATE        -- 幂等合并，单条 SQL
 * ③ TRUNCATE stock_history_stage → 下一批
 * ```
 *
 * 库内重跑计划（2026-10-04 设计定稿，替代全量重拉空转）：
 * - planService 注入 → 计划驱动：buildRerunPlan 按分类规则产出缺失 FetchSegment（真实缺失区间），
 *   全齐票零 segment → 零外部请求；批打包 = BackfillClassifier.batchSegments（同批 code 唯一）；
 * - processSegment 收口 verified-empty：HTTP 200 且该码不在 failed[] 且返回 0 行 →
 *   gapCheckRepository.upsertVerifiedEmpty（停牌防反复空拉）；failed 里出现绝不记录；
 * - planService 为 null（旧构造调用方）→ 保留 legacy 分片路径（findMaxTradeDate 断点续传 + codes 模式），
 *   兼容既有调用方/测试。
 *
 * 间歇性获取铁律（用户 memory：外部数据源必须间歇性获取）：批间 [BackfillProperties.batchPauseMs]
 * 刻意停顿；批次限速由 Python 侧 TokenBucket 承担（baostock 5rps / akshare 2rps）。
 */
@Component
class BackfillJob(
    private val pythonClient: PythonDataServiceClient,
    private val stockInfoRepository: StockInfoRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val calendarService: TradingCalendarService,
    private val dataQualityLogRepository: DataQualityLogRepository,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
    private val backfillProperties: BackfillProperties,
    private val metrics: CollectMetrics,
    private val notifier: DingTalkNotifier,
    private val replayService: SentimentReplayService,
    private val planService: BackfillPlanService?,
    private val gapCheckRepository: StockHistoryGapCheckRepository?,
) {
    private val logger = LoggerFactory.getLogger(BackfillJob::class.java)

    private companion object {
        /** 校验拒绝明细写入 data_quality_log 的封顶条数（避免海量拒绝日志打爆问题表） */
        const val MAX_REJECT_LOG_ROWS = 200
    }

    /** stage→主表 幂等合并 SQL（资源文件缓存） */
    private val mergeSql: String by lazy { readSql("sql/backfill/merge_stage_to_main.sql") }

    /**
     * 回填全流程编排（§六）：批次循环 → 派生列补算 → 抽查对拍 → 情绪回放 → 摘要。
     *
     * @param startDate 回填起点（近 5 年=2021-10-01）
     * @param endDate   回填终点（缺省=今日）
     * @param onProgress 进度回调（逐批原子替换 [BackfillProgress]，供状态端点消费）
     */
    suspend fun run(startDate: LocalDate, endDate: LocalDate, onProgress: (BackfillProgress) -> Unit): BackfillSummary {
        val startMs = System.currentTimeMillis()
        if (startDate.isAfter(endDate)) throw BusinessException("回填区间非法：from($startDate) > to($endDate)")
        val from = startDate
        val to = endDate

        // 防御加固：merge 与 TRUNCATE 之间的中断窗口可能残留 stale stage 行，
        // 下批 COPY 追加后 merge 抛 "cannot affect row a second time"——run() 起始先清空瞬态中转表（UNLOGGED，零成本）。
        truncateStage()
        prepareCalendar(from, to)

        // ── 执行计划装配：planService 注入=库内重跑计划（新）；null=legacy 分片（旧行为保留）──
        val isPlanDriven = planService != null
        val plan: RerunPlan
        val segmentBatches: List<List<FetchSegment>>
        val legacyChunks: List<List<StockInfo>>
        if (isPlanDriven) {
            plan = planService!!.buildRerunPlan(from, to)
            segmentBatches = BackfillClassifier.batchSegments(plan.segments, chunkSize())
            legacyChunks = emptyList()
        } else {
            val stocks = loadValidStocks()
            // 断点续传：已覆盖到 end_date 的 code 跳过（幂等重跑语义，不重复拉取）
            val pending = stocks.filter { (stockHistoryRepository.findMaxTradeDateByCode(it.code) ?: LocalDate.MIN) < to }
            plan = RerunPlan(
                from = from,
                to = to,
                totalCodes = stocks.size,
                completeCodes = stocks.size - pending.size,
                zeroWindowCodes = 0,
                segments = pending.map { FetchSegment(it.code, from, to, SegmentReason.NO_DATA) },
            )
            segmentBatches = emptyList()
            legacyChunks = pending.chunked(chunkSize())
        }
        // 空候选防御（部署冒烟发现）：空库直接回填=零代码可拉，不能静默 COMPLETED 掩盖配置问题，
        // 明确 FAILED 引导先刷新股票清单（POST /api/v1/info/refresh）
        if (plan.totalCodes == 0) {
            throw BusinessException("股票清单为空：请先 POST /api/v1/info/refresh 刷新股票列表后重试回填")
        }
        val totalBatches = if (isPlanDriven) segmentBatches.size else legacyChunks.size
        val initial = BackfillProgress(
            totalCodes = plan.totalCodes,
            totalBatches = totalBatches,
            startedAt = Instant.now(),
        )
        onProgress(initial)
        logger.info("[backfill] 启动：from={} to={} codes={} batches={} segments={}", from, to, plan.totalCodes, totalBatches, plan.segments.size)

        val collect = if (isPlanDriven) {
            collectAllSegmentBatches(segmentBatches, from, to, onProgress, initial)
        } else {
            collectAllChunks(legacyChunks, from, to, onProgress, initial)
        }
        val recomputed = recomputeAndVerify(from, to)
        val replay = replaySentiment(from, to)
        val durationMs = System.currentTimeMillis() - startMs

        val summary = buildSummary(from, to, plan.totalCodes, collect, durationMs, recomputed, replay)
        notifyDigest(summary, replay)
        logger.info(
            "[backfill] 完成：成功={} 失败={} 行={} 耗时={}ms 派生列补算={}",
            collect.succeeded, collect.failed, collect.totalRows, durationMs, recomputed,
        )
        return summary
    }

    /** 每批请求股票数（= min(batch-size, max-codes-per-batch)，与 Python batch_max_codes 对齐） */
    private fun chunkSize(): Int = minOf(backfillProperties.batchSize, backfillProperties.maxCodesPerBatch)

    /** §17.2 交易日历加载（fail-open：加载失败不阻塞回填，日期区间仍按用户/默认参数执行） */
    private suspend fun prepareCalendar(from: LocalDate, to: LocalDate) {
        MdcSupport.withMdcSuspend(
            MdcSupport.JOB to "backfill",
            MdcSupport.DATE_RANGE to "$from..$to",
            MdcSupport.PHASE to "prepare",
        ) {
            try {
                calendarService.ensureLoaded()
            } catch (e: Exception) {
                logger.warn("[backfill] 交易日历加载失败（fail-open 不阻塞回填）：{}", e.message)
            }
        }
    }

    // ==================== 新：重跑计划批循环（items 模式） ====================

    /** 段批循环累计：逐批拉取/合并/TRUNCATE，进度回调原子替换，批间刻意停顿（间歇性获取铁律） */
    private suspend fun collectAllSegmentBatches(
        batches: List<List<FetchSegment>>,
        from: LocalDate,
        to: LocalDate,
        onProgress: (BackfillProgress) -> Unit,
        initial: BackfillProgress,
    ): CollectResult {
        var succeeded = 0
        var failed = 0
        var processedRows = 0L
        var progress = initial
        for ((index, batch) in batches.withIndex()) {
            progress = progress.copy(currentBatch = index + 1)
            onProgress(progress)
            val result = MdcSupport.withMdcSuspend(
                MdcSupport.JOB to "backfill",
                MdcSupport.DATE_RANGE to "$from..$to",
                MdcSupport.PHASE to "copy-merge",
                MdcSupport.ATTEMPT to "1",
            ) {
                processSegmentBatch(batch, from, to)
            }
            succeeded += result.succeeded
            failed += result.failed
            processedRows += result.rows
            progress = progress.copy(
                processedCodes = succeeded + failed,
                succeededCodes = succeeded,
                failedCodes = failed,
                processedBatches = index + 1,
                processedRows = processedRows,
            )
            onProgress(progress)
            logger.info(
                "[backfill] 批 {}/{} 完成：成功 {} 失败 {} 行 {} 累计行 {}",
                index + 1, batches.size, result.succeeded, result.failed, result.rows, processedRows,
            )
            if (index < batches.lastIndex) {
                logger.debug("[backfill] 批间停顿 {}ms（间歇性获取铁律）", backfillProperties.batchPauseMs)
                delay(backfillProperties.batchPauseMs)
            }
        }
        return CollectResult(succeeded, failed, processedRows)
    }

    /** 单段批处理：批量拉取（items 逐段窗口，重试）→ 逐 segment 处理 → 合并 → TRUNCATE */
    private suspend fun processSegmentBatch(batch: List<FetchSegment>, from: LocalDate, to: LocalDate): ChunkResult {
        if (batch.isEmpty()) return ChunkResult(0, 0, 0)
        val response = fetchWithRetrySegments(batch)
        if (response == null) {
            metrics.incrementFailedCodes()
            return ChunkResult(0, batch.size, 0)
        }
        var succeeded = 0
        var failed = 0
        var rows = 0L
        val rejectDetails = mutableListOf<String>()
        for (seg in batch) {
            val result = processSegment(seg, response, rejectDetails)
            succeeded += result.succeeded
            failed += result.failed
            rows += result.rows
        }
        writeRejectLog(rejectDetails)
        return ChunkResult(succeeded, failed, rows)
    }

    /**
     * 单 segment 处理（回填重跑收口）：
     * - results[code] 为空 且 不在 failed[] → 记录 verified-empty（HTTP 200 + 0 行 = 停牌，防反复空拉）；
     * - failed[] 含此码 → 计 failed，**不**记录 verified-empty（语义铁律：failed 里出现绝不记录）；
     * - 空且既无 results 又无 failed（异常态，防御）→ 计 failed 不记录；
     * - 非空 → 过滤到 [seg.from, seg.to] 窗口内（防御，Python 已按段窗口返回）后校验 + COPY 合并。
     */
    private fun processSegment(
        seg: FetchSegment,
        response: DailyBarsBatchResponse,
        rejectDetails: MutableList<String>,
    ): ChunkResult {
        val result = response.results[seg.code]
        val inFailed = response.failed.any { it.code == seg.code }
        if (result == null || result.data.isEmpty()) {
            if (inFailed || result == null) {
                // 显式失败 或 异常态（code 既不在 results 也不在 failed，防御）→ 计 failed，绝不记录
                val reason = response.failed.firstOrNull { it.code == seg.code }?.reason ?: "results 缺席异常态"
                logger.warn("[backfill] 段失败 code={} reason={}", seg.code, reason)
                metrics.incrementFailedCodes()
                return ChunkResult(0, 1, 0)
            }
            // HTTP 200 且不在 failed[] 且 0 行 → verified-empty（仅记录，不拉重复）
            gapCheckRepository?.upsertVerifiedEmpty(seg.code, seg.from, seg.to, 0)
            logger.warn("[backfill] 段验证空 code={} from={} to={}", seg.code, seg.from, seg.to)
            metrics.incrementFailedCodes()
            return ChunkResult(0, 1, 0)
        }
        val windowed = result.data.filter { bar -> !bar.date.isBefore(seg.from) && !bar.date.isAfter(seg.to) }
        val valid = filterValidBars(seg.code, windowed, rejectDetails)
        if (valid.isEmpty()) {
            logger.warn("[backfill] 段全部校验拒绝 code={} from={} to={}", seg.code, seg.from, seg.to)
            metrics.incrementFailedCodes()
            return ChunkResult(0, 1, 0)
        }
        val source = DataSourceType.fromPython(result.source)
        copyMergeBatches(valid, source)
        metrics.incrementRows(source)
        return ChunkResult(1, 0, valid.size.toLong())
    }

    /** 段批量拉取（items 模式：逐段独立窗口；重试语义与 legacy codes 路径一致） */
    private suspend fun fetchWithRetrySegments(segments: List<FetchSegment>): DailyBarsBatchResponse? {
        val request = DailyBarsBatchRequest(
            items = segments.map { BatchItem(it.code, it.from.toString(), it.to.toString()) },
            adjust = "qfq",
        )
        for (attempt in 1..backfillProperties.maxRetriesPerBatch) {
            try {
                return pythonClient.fetchDailyBarsBatch(request)
            } catch (e: SorosBaseException) {
                logger.warn("[backfill] 批量拉取失败 attempt={}/{} segments={} error={}", attempt, backfillProperties.maxRetriesPerBatch, segments.size, e.message)
            }
        }
        logger.error("[backfill] 批量拉取重试仍失败，segments={} 记为 failed", segments.size)
        return null
    }

    // ==================== legacy：分片批循环（codes 模式，planService=null 时保留旧行为） ====================

    /** 批循环累计：逐批拉取/合并/TRUNCATE，进度回调原子替换，批间刻意停顿（间歇性获取铁律） */
    private suspend fun collectAllChunks(
        chunks: List<List<StockInfo>>,
        from: LocalDate,
        to: LocalDate,
        onProgress: (BackfillProgress) -> Unit,
        initial: BackfillProgress,
    ): CollectResult {
        var succeeded = 0
        var failed = 0
        var processedRows = 0L
        var progress = initial
        for ((index, chunk) in chunks.withIndex()) {
            progress = progress.copy(currentBatch = index + 1)
            onProgress(progress)
            val result = MdcSupport.withMdcSuspend(
                MdcSupport.JOB to "backfill",
                MdcSupport.DATE_RANGE to "$from..$to",
                MdcSupport.PHASE to "copy-merge",
                MdcSupport.ATTEMPT to "1",
            ) {
                processChunk(chunk, from, to)
            }
            succeeded += result.succeeded
            failed += result.failed
            processedRows += result.rows
            progress = progress.copy(
                processedCodes = succeeded + failed,
                succeededCodes = succeeded,
                failedCodes = failed,
                processedBatches = index + 1,
                processedRows = processedRows,
            )
            onProgress(progress)
            logger.info(
                "[backfill] 批 {}/{} 完成：成功 {} 失败 {} 行 {} 累计行 {}",
                index + 1, chunks.size, result.succeeded, result.failed, result.rows, processedRows,
            )
            if (index < chunks.lastIndex) {
                logger.debug("[backfill] 批间停顿 {}ms（间歇性获取铁律）", backfillProperties.batchPauseMs)
                delay(backfillProperties.batchPauseMs)
            }
        }
        return CollectResult(succeeded, failed, processedRows)
    }

    /**
     * 单批处理（legacy）：断点续传过滤 → 批量拉取（重试）→ 逐股校验 → COPY stage → 合并 → TRUNCATE。
     *
     * @return 批内 成功/失败/入库行数（单股失败进 failed，不炸整批）
     */
    private suspend fun processChunk(chunk: List<StockInfo>, from: LocalDate, to: LocalDate): ChunkResult {
        // 断点续传：已覆盖到 end_date 的 code 跳过（幂等重跑语义，不重复拉取）
        val pending = chunk.filter { (stockHistoryRepository.findMaxTradeDateByCode(it.code) ?: LocalDate.MIN) < to }
        if (pending.isEmpty()) return ChunkResult(0, 0, 0)

        val response = fetchWithRetry(pending.map { it.code }, from, to)
        if (response == null) {
            metrics.incrementFailedCodes()
            return ChunkResult(0, pending.size, 0)
        }

        var succeeded = 0
        var failed = 0
        var rows = 0L
        val rejectDetails = mutableListOf<String>()
        for (stock in pending) {
            val result = processOneStock(stock, response, rejectDetails)
            succeeded += result.succeeded
            failed += result.failed
            rows += result.rows
        }
        writeRejectLog(rejectDetails)
        return ChunkResult(succeeded, failed, rows)
    }

    /** 单股处理（legacy）：无数据/全部拒绝进 failed；合法行按 copy-batch-rows 分段 COPY→merge→TRUNCATE */
    private fun processOneStock(
        stock: StockInfo,
        response: DailyBarsBatchResponse,
        rejectDetails: MutableList<String>,
    ): ChunkResult {
        val result = response.results[stock.code]
        if (result == null || result.data.isEmpty()) {
            val reason = response.failed.firstOrNull { it.code == stock.code }?.reason ?: "无数据"
            logger.warn("[backfill] 单股无数据 code={} reason={}", stock.code, reason)
            metrics.incrementFailedCodes()
            return ChunkResult(0, 1, 0)
        }
        val valid = filterValidBars(stock.code, result.data, rejectDetails)
        if (valid.isEmpty()) {
            logger.warn("[backfill] 单股全部校验拒绝 code={}", stock.code)
            metrics.incrementFailedCodes()
            return ChunkResult(0, 1, 0)
        }
        val source = DataSourceType.fromPython(result.source)
        copyMergeBatches(valid, source)
        metrics.incrementRows(source)
        return ChunkResult(1, 0, valid.size.toLong())
    }

    /** 派生列补算 + 抽查对拍（§六.6）：补算失败 fail-open 跳过抽查；补算成功且抽查不一致抛 [BusinessException] 置 FAILED */
    private fun recomputeAndVerify(from: LocalDate, to: LocalDate): Boolean {
        val recomputed = recomputeDerivedColumns()
        if (recomputed) {
            verifyDerivedColumnsSample(from, to)
        } else {
            // 补算失败已 fail-open（digest 标注）；无"已落库派生列"可对拍，跳过抽查，避免误 FAILED
            logger.warn("[backfill] 派生列补算失败，跳过抽查对拍（digest 已标注）")
        }
        return recomputed
    }

    /** 回填完成摘要装配（钉钉 digest 输入） */
    private fun buildSummary(
        from: LocalDate,
        to: LocalDate,
        totalCodes: Int,
        collect: CollectResult,
        durationMs: Long,
        recomputed: Boolean,
        replay: SentimentReplaySummary?,
    ): BackfillSummary = BackfillSummary(
        from = from,
        to = to,
        totalCodes = totalCodes,
        succeededCodes = collect.succeeded,
        failedCodes = collect.failed,
        totalRows = collect.totalRows,
        durationMs = durationMs,
        derivedColumnsRecomputed = recomputed,
        replaySummary = replay,
    )

    /** 钉钉 digest 收口（§六 完成链末步） */
    private fun notifyDigest(summary: BackfillSummary, replay: SentimentReplaySummary?) {
        notifier.notifyDailyDigest(
            "历史回填完成：${summary.from}~${summary.to} 成功 ${summary.succeededCodes} 只，失败 ${summary.failedCodes} 只，" +
                "共 ${summary.totalRows} 行，耗时 ${summary.durationMs}ms，派生列补算=${summary.derivedColumnsRecomputed}" +
                (if (replay != null) "，情绪回放 ${replay.sentimentRows} 天" else "，情绪回放失败（人工补触发）"),
        )
    }

    /** 回填股票清单：当前非 ST/非退市（ST 隔离铁律：is_st 仅用于识别排除）+ board 限 MAIN/GEM/STAR */
    private fun loadValidStocks(): List<StockInfo> =
        stockInfoRepository.findByIsStFalseAndDelistedFalse()

    /** 逐股校验：跳过非法行并记录拒绝明细（DataValidator 整批校验，§4.7 防线②） */
    private fun filterValidBars(
        code: String,
        data: List<DailyBar>,
        rejectDetails: MutableList<String>,
    ): List<DailyBar> = data.filter { bar ->
        val check = DataValidator.validate(bar, skipCrossCheck = true)
        if (check.valid) {
            true
        } else {
            rejectDetails += "$code:${bar.date}:${check.description}"
            false
        }
    }

    /**
     * COPY→merge→TRUNCATE 分段（§13.1 copy-batch-rows）：单股日K按 [BackfillProperties.copyBatchRows] 行分段，
     * 每段独立完成两段式闭环，防超大批一次性 COPY 占内存；段间幂等（ON CONFLICT DO UPDATE）。
     */
    private fun copyMergeBatches(bars: List<DailyBar>, source: DataSourceType) {
        bars.chunked(backfillProperties.copyBatchRows).forEach { batch ->
            copyToStage(batch, source)
            mergeStageToMain()
            truncateStage()
        }
    }

    /**
     * 批量拉取（legacy codes 模式；backfill profile：PythonDataServiceClientImpl 按 >366 自然日区间自动切
     * response 60s / retry 1 次；此处再做应用层重试 maxRetriesPerBatch 轮）。
     *
     * 重试只对 [SorosBaseException]（网络/超时/5xx/熔断 open）触发——幂等安全；
     * 批内单股失败由 Python failed[] 承载，不进重试。
     */
    private suspend fun fetchWithRetry(codes: List<String>, from: LocalDate, to: LocalDate): DailyBarsBatchResponse? {
        val request = DailyBarsBatchRequest(codes = codes, startDate = from.toString(), endDate = to.toString(), adjust = "qfq")
        for (attempt in 1..backfillProperties.maxRetriesPerBatch) {
            try {
                return pythonClient.fetchDailyBarsBatch(request)
            } catch (e: SorosBaseException) {
                logger.warn("[backfill] 批量拉取失败 attempt={}/{} codes={} error={}", attempt, backfillProperties.maxRetriesPerBatch, codes.size, e.message)
            }
        }
        logger.error("[backfill] 批量拉取重试仍失败，codes={} 记为 failed", codes.size)
        return null
    }

    /** ① COPY INTO stock_history_stage（PG CopyManager，org.postgresql.copy；非逐行 INSERT） */
    private fun copyToStage(bars: List<DailyBar>, source: DataSourceType) {
        if (bars.isEmpty()) return
        val csv = buildStageCsv(bars, source)
        dataSource.connection.use { conn ->
            val pg = conn.unwrap(PGConnection::class.java)
            pg.copyAPI.copyIn(
                "COPY stock_history_stage (code, trade_date, open, close, high, low, volume, amount, change_pct, turnover_rate, data_source) " +
                    "FROM STDIN WITH (FORMAT csv)",
                BufferedReader(StringReader(csv)),
            )
        }
    }

    /** ② stage → 主表 ON CONFLICT (code, trade_date) DO UPDATE（幂等合并，单条 SQL） */
    private fun mergeStageToMain() {
        jdbcTemplate.execute(mergeSql)
    }

    /** ③ TRUNCATE stage → 下一批 */
    private fun truncateStage() {
        jdbcTemplate.execute("TRUNCATE TABLE stock_history_stage")
    }

    /** CSV 序列化（bar 数据列 → COPY 行；字段为 null 输出空串=PG CSV NULL；派生列不写走 stage 默认值） */
    private fun buildStageCsv(bars: List<DailyBar>, source: DataSourceType): String {
        val sb = StringBuilder(bars.size * 96)
        for (bar in bars) {
            sb.append(bar.code).append(',')
                .append(bar.date).append(',')
                .append(bar.open?.toPlainString() ?: "").append(',')
                .append(bar.close?.toPlainString() ?: "").append(',')
                .append(bar.high?.toPlainString() ?: "").append(',')
                .append(bar.low?.toPlainString() ?: "").append(',')
                .append(bar.volume ?: "").append(',')
                .append(bar.amount?.toPlainString() ?: "").append(',')
                .append(bar.changePercent?.toPlainString() ?: "").append(',')
                .append(bar.turnover?.toPlainString() ?: "").append(',')
                .append(source.name).append('\n')
        }
        return sb.toString()
    }

    /**
     * 派生列补算（§六.6）：执行 recompute_limit_streaks.sql——
     * is_limit_up/is_limit_down（board 阈值 9.9/19.9）→ IPO 首 5 日守卫 → gaps-and-islands
     * 连板/跌停连板。失败仅记日志返回 false（回填结果不阻塞，digest 标注）。
     */
    private fun recomputeDerivedColumns(): Boolean = try {
        dataSource.connection.use { conn ->
            ScriptUtils.executeSqlScript(conn, ClassPathResource("sql/backfill/recompute_limit_streaks.sql"))
        }
        true
    } catch (e: Exception) {
        logger.error("[backfill] 派生列补算失败（回填结果不阻塞）：{}", e.message)
        false
    }

    /**
     * 3 股抽查对拍（PLAN §六.6 ⑤）：抽 [BackfillProperties.sampleCheckCodes] 只股票，
     * 逐只按增量路径口径重算派生列（is_limit_up/is_limit_down/limit_up_streak/limit_down_streak，
     * 阈值单点走 [LimitUpDetector]，IPO 首 5 日守卫与 streak 累加共用 [LimitStreakComputer]），
     * 与 recompute SQL 已落库的值对拍；不一致 → data_quality_log(CROSS_VALIDATE_MISMATCH) +
     * 钉钉 CRITICAL 告警 + Job FAILED（设计裁定 2026-10-04：派生列错污染情绪/策略全链，属严重问题）。
     *
     * 确定性选取：候选（区间内有行的 code）排序后均匀取 sampleCheckCodes 只，保证可复现。
     */
    private fun verifyDerivedColumnsSample(from: LocalDate, to: LocalDate) {
        val candidates = stockHistoryRepository.findDistinctCodesByTradeDateBetween(from, to)
        if (candidates.isEmpty()) {
            logger.warn("[backfill] 抽查对拍无可验代码（回填窗口无行）：from={} to={}", from, to)
            return
        }
        val samples = pickSampleCodes(candidates, backfillProperties.sampleCheckCodes)
        logger.info("[backfill] 派生列抽查对拍：from={} to={} sample={}", from, to, samples)
        val mismatchedCodes = samples.mapNotNull { code ->
            val detail = verifyOneCode(code, from, to)
            if (detail != null) {
                recordSampleMismatch(code, detail)
                code
            } else {
                null
            }
        }
        if (mismatchedCodes.isEmpty()) {
            logger.info("[backfill] 抽查对拍全部一致：sample={} from={} to={}", samples, from, to)
            return
        }
        notifier.notify(
            DingTalkEvent.CROSS_VALIDATE_MISMATCH,
            "回填派生列抽查对拍不一致",
            "from=$from to=$to 不一致 ${mismatchedCodes.size}/${samples.size} 只：${mismatchedCodes.joinToString(",")}" +
                "（派生列错污染情绪/策略全链，Job 标 FAILED）",
        )
        throw BusinessException(
            "回填派生列抽查对拍不一致：${mismatchedCodes.size} 只 ${mismatchedCodes.joinToString(",")}（from=$from to=$to）",
        )
    }

    /**
     * 单只对拍：按增量路径口径重算 [from, to] 窗口内派生列（口径与 recompute_limit_streaks.sql
     * 对齐：阈值=不复权 change_pct+board、IPO 首 5 日守卫、前一交易日历日无行断板；只对拍
     * trade_date < 今日，SQL 不覆盖当日派生）。返回不一致明细；一致返回 null。
     */
    private fun verifyOneCode(code: String, from: LocalDate, to: LocalDate): String? {
        val info = stockInfoRepository.findByCode(code) ?: run {
            logger.warn("[backfill] 抽查对拍无 stock_info code={}（跳过）", code)
            return null
        }
        val board = try {
            Board.fromPython(info.board)
        } catch (e: BusinessException) {
            logger.warn("[backfill] 抽查对拍未知 board code={} board={}（跳过）", code, info.board)
            return null
        }
        val bars = stockHistoryRepository.findByCodeAndTradeDateBetween(code, from, to)
            .filter { it.tradeDate.isBefore(LocalDate.now()) }
        if (bars.isEmpty()) return null
        val ctx = BarDeriveContext(code, board, info.ipoDate)
        val derivedUp = mutableMapOf<LocalDate, Short>()
        val derivedDown = mutableMapOf<LocalDate, Short>()
        val mismatches = mutableListOf<String>()
        for (bar in bars) {
            val expected = expectedDerived(bar, ctx, derivedUp, derivedDown)
            if (bar.isLimitUp != expected.isLimitUp || bar.isLimitDown != expected.isLimitDown ||
                bar.limitUpStreak != expected.upStreak || bar.limitDownStreak != expected.downStreak
            ) {
                mismatches += "${bar.tradeDate}(db=up${bar.isLimitUp}/down${bar.isLimitDown}/us${bar.limitUpStreak}/ds${bar.limitDownStreak}" +
                    " vs exp=up${expected.isLimitUp}/down${expected.isLimitDown}/us${expected.upStreak}/ds${expected.downStreak})"
            }
        }
        if (mismatches.isEmpty()) {
            logger.info("[backfill] 抽查对拍一致 code={} bars={}", code, bars.size)
            return null
        }
        val detail = "code=$code 不一致 ${mismatches.size}/${bars.size} 根：${mismatches.take(5).joinToString("; ")}"
        logger.error("[backfill] 派生列抽查对拍不一致：{}", detail)
        return detail
    }

    /**
     * 单 bar 派生列期望值（增量路径口径，抽查对拍单点：阈值 LimitUpDetector + 守卫/streak
     * LimitStreakComputer）。prevDate 命中窗口内用已重算值，窗口外回查库内 recompute 已落库值
     * 作基数——与 SQL gaps-and-islands 的"前一交易日历日无行断板"语义一致。
     */
    private fun expectedDerived(
        bar: StockHistory,
        ctx: BarDeriveContext,
        derivedUp: MutableMap<LocalDate, Short>,
        derivedDown: MutableMap<LocalDate, Short>,
    ): ExpectedDerived {
        val (isLimitUp, isLimitDown) = LimitUpDetector.detect(bar.changePct ?: BigDecimal.ZERO, ctx.board)
        val ipoGuard = LimitStreakComputer.isWithinIpoGuard(ctx.ipoDate, bar.tradeDate) { d, n ->
            calendarService.recentTradingDays(d, n)
        }
        val finalLimitUp = isLimitUp && !ipoGuard
        val finalLimitDown = isLimitDown && !ipoGuard
        val prevDate = calendarService.previousTradingDay(bar.tradeDate)
        val baseUp = derivedUp[prevDate]?.toInt()
            ?: prevDate?.let { d -> stockHistoryRepository.findByCodeAndTradeDate(ctx.code, d)?.limitUpStreak?.toInt() }
            ?: 0
        val baseDown = derivedDown[prevDate]?.toInt()
            ?: prevDate?.let { d -> stockHistoryRepository.findByCodeAndTradeDate(ctx.code, d)?.limitDownStreak?.toInt() }
            ?: 0
        val upStreak = LimitStreakComputer.nextStreak(finalLimitUp, baseUp)
        val downStreak = LimitStreakComputer.nextStreak(finalLimitDown, baseDown)
        derivedUp[bar.tradeDate] = upStreak
        derivedDown[bar.tradeDate] = downStreak
        return ExpectedDerived(finalLimitUp, finalLimitDown, upStreak, downStreak)
    }

    /** 抽查对拍单 bar 派生列期望值（is_limit_up / is_limit_down / 两 streak） */
    private data class ExpectedDerived(
        val isLimitUp: Boolean,
        val isLimitDown: Boolean,
        val upStreak: Short,
        val downStreak: Short,
    )

    /** 抽查对拍单只上下文（code / board 阈值 / ipo_date 守卫） */
    private data class BarDeriveContext(
        val code: String,
        val board: Board,
        val ipoDate: LocalDate?,
    )

    /** 抽查不一致落 data_quality_log（issue_type=CROSS_VALIDATE_MISMATCH，每 code 一条摘要，防打爆问题表） */
    private fun recordSampleMismatch(code: String, detail: String) {
        dataQualityLogRepository.save(
            DataQualityLog().apply {
                checkDate = LocalDate.now()
                this.code = code
                issueType = QualityIssueType.CROSS_VALIDATE_MISMATCH.name
                this.detail = detail
                source = "BACKFILL"
            },
        )
        metrics.incrementQualityLog(QualityIssueType.CROSS_VALIDATE_MISMATCH)
    }

    /** 确定性均匀抽样：候选排序后均匀取 count 只（保证测试可复现，PLAN §六.6⑤） */
    private fun pickSampleCodes(candidates: List<String>, count: Int): List<String> {
        val sorted = candidates.sorted()
        if (sorted.size <= count) return sorted
        if (count <= 1) return listOf(sorted.first())
        return (0 until count).map { sorted[it * (sorted.size - 1) / (count - 1)] }
    }

    /** 情绪回放串接（§13.5）：失败不阻塞回填结果，只告警留人工补触发 */
    private fun replaySentiment(from: LocalDate, to: LocalDate): SentimentReplaySummary? = try {
        logger.info("[backfill] 触发情绪回放：from={} to={}", from, to)
        replayService.replay(from, to)
    } catch (e: Exception) {
        logger.error("[backfill] 情绪回放失败（回填已完成，人工补触发）：{}", e.message)
        notifier.notifyDailyDigest("情绪回放失败（回填已完成，人工 POST /jobs/sentiment-replay 补触发）：$from~$to，${e.message}")
        null
    }

    /** 校验拒绝行写 data_quality_log（封顶 [MAX_REJECT_LOG_ROWS] 条，防海量拒绝日志打爆问题表） */
    private fun writeRejectLog(details: List<String>) {
        if (details.isEmpty()) return
        details.take(MAX_REJECT_LOG_ROWS).forEach { detail ->
            dataQualityLogRepository.save(
                DataQualityLog().apply {
                    checkDate = LocalDate.now()
                    issueType = QualityIssueType.VALIDATION_REJECTED.name
                    this.detail = "backfill ${detail}"
                    source = "BACKFILL"
                },
            )
        }
        metrics.incrementQualityLog(QualityIssueType.VALIDATION_REJECTED)
        if (details.size > MAX_REJECT_LOG_ROWS) {
            logger.warn("[backfill] 校验拒绝行超 {} 条，仅记前 {} 条；共 {} 条被拒绝", MAX_REJECT_LOG_ROWS, MAX_REJECT_LOG_ROWS, details.size)
        }
    }

    private fun readSql(path: String): String =
        ClassPathResource(path).inputStream.bufferedReader().use { it.readText() }

    /** 批循环累计结果（成功/失败股票数与入库行数） */
    private data class CollectResult(
        val succeeded: Int,
        val failed: Int,
        val totalRows: Long,
    )

    /** 批处理结果（成功/失败股票数与入库行数） */
    private data class ChunkResult(
        val succeeded: Int,
        val failed: Int,
        val rows: Long,
    )
}
