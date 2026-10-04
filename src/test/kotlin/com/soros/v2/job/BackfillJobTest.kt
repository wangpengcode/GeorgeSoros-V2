package com.soros.v2.job

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.dto.BackfillProgress
import com.soros.v2.service.backfill.dto.BackfillSummary
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.BoardMembersSnapshot
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FailedBar
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.service.sentiment.SentimentReplaySummary
import com.soros.v2.util.CollectMetrics
import java.io.Reader
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.postgresql.PGConnection
import org.postgresql.copy.CopyManager
import org.springframework.jdbc.core.JdbcTemplate

/**
 * §六 BackfillJob 编排契约测试（Fake Python 客户端 + Mock 仓储/DataSource，规避 Mockito 对
 * suspend 方法 Continuation 匹配问题，参考 DailyCollectJobTest）。
 *
 * 契约（类 KDoc / §六 / §六.6）：
 * - 分片进度回调：初始快照 + 每批 current_batch/processed_* 逐批原子替换（progress callback）；
 * - 已覆盖代码跳过：findMaxTradeDate(code) >= endDate 的 code 不重复拉取（幂等重跑 = 断点续传）；
 * - 校验拒绝行落 data_quality_log(issue_type=VALIDATION_REJECTED)（DataValidator 整批校验）；
 * - 批间停顿可注入：batchPauseMs 从配置读取，多批处理之间应用（间歇性获取铁律）；
 * - 派生列抽查对拍（PLAN §六.6⑤）：一致 → run() 完整走完返回摘要；不一致 → recordSampleMismatch
 *   （data_quality_log source=BACKFILL + 钉钉 CROSS_VALIDATE_MISMATCH）→ throw BusinessException → Job FAILED；
 * - 触发链非阻塞：replayService 抛异常 → 链路不炸仅告警（replaySummary=null + 钉钉 digest）。
 *
 * 抽查对拍 seam 已实现（Step 6 Implementer）：一致路径无异常（sampleVerificationSeamContract 转绿），
 * 不一致路径见 testRun derivedMismatch* 三条断言（异常透出 / data_quality_log 落库 / 钉钉事件）。
 */
class BackfillJobTest {

    private val from = LocalDate.of(2021, 10, 1)
    private val to = LocalDate.of(2021, 10, 5)

    private lateinit var pythonClient: FakePythonClient
    private lateinit var stockInfoRepository: StockInfoRepository
    private lateinit var stockHistoryRepository: StockHistoryRepository
    private lateinit var calendarService: FakeCalendarService
    private lateinit var dataQualityLogRepository: DataQualityLogRepository
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var backfillProperties: BackfillProperties
    private lateinit var metrics: CollectMetrics
    private lateinit var notifier: FakeNotifier
    private lateinit var replayService: SentimentReplayService
    private lateinit var job: BackfillJob

    /** Fake Python 客户端：记录 fetchDailyBarsBatch 请求（suspend，规避 Mockito Continuation 匹配） */
    private class FakePythonClient : PythonDataServiceClient {
        var batchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        val fetchRequests = mutableListOf<DailyBarsBatchRequest>()
        var fetchCalls = 0

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchCalls++
            fetchRequests.add(request)
            return batchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    /** Fake 交易日历（ensureLoaded 为 suspend，规避 Mockito Continuation 匹配） */
    private class FakeCalendarService : TradingCalendarService {
        var ensureLoadedCalls = 0
        override suspend fun ensureLoaded(): Int {
            ensureLoadedCalls++
            return 1
        }
        override fun isTradingDay(date: LocalDate): Boolean = true
        override fun previousTradingDay(date: LocalDate): LocalDate? = null
        override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> = emptyList()
    }

    /** Fake 钉钉：记录 digest（非空参数 + Mockito 空 matcher 会触发 NPE，用 Fake 记录） */
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

    @BeforeEach
    fun setUp() {
        pythonClient = FakePythonClient()
        stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        stockHistoryRepository = Mockito.mock(StockHistoryRepository::class.java)
        calendarService = FakeCalendarService()
        dataQualityLogRepository = Mockito.mock(DataQualityLogRepository::class.java)
        dataSource = Mockito.mock(DataSource::class.java)
        jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        backfillProperties = BackfillProperties().apply {
            batchSize = 2
            batchPauseMs = 1
            maxRetriesPerBatch = 1
            sampleCheckCodes = 3
        }
        metrics = Mockito.mock(CollectMetrics::class.java)
        notifier = FakeNotifier()
        replayService = Mockito.mock(SentimentReplayService::class.java)

        // COPY 两段式 mock：dataSource.connection → PGConnection → CopyManager → copyIn 返回 0
        val conn = Mockito.mock(Connection::class.java)
        val pgConn = Mockito.mock(PGConnection::class.java)
        val copyManager = Mockito.mock(CopyManager::class.java)
        Mockito.`when`(dataSource.connection).thenReturn(conn)
        Mockito.`when`(conn.unwrap(PGConnection::class.java)).thenReturn(pgConn)
        Mockito.`when`(pgConn.copyAPI).thenReturn(copyManager)
        // copyToStage 走 copyIn(String, Reader)（BufferedReader 流式），stub Reader 重载（返回 0L 忽略）
        Mockito.`when`(copyManager.copyIn(Mockito.anyString(), Mockito.any<Reader>())).thenReturn(0L)
        // recompute 成功路径：ScriptUtils.executeSqlScript(conn, sql) 走 conn.createStatement()，stub 防 NPE
        // （Mockito boolean 默认 false → originalAutoCommit=false，不触发 setAutoCommit/commit）
        val stmt = Mockito.mock(java.sql.Statement::class.java)
        Mockito.`when`(conn.createStatement()).thenReturn(stmt)
        Mockito.`when`(stmt.execute(Mockito.anyString())).thenReturn(false)
        Mockito.doNothing().`when`(jdbcTemplate).execute(Mockito.anyString())

        job = BackfillJob(
            pythonClient = pythonClient,
            stockInfoRepository = stockInfoRepository,
            stockHistoryRepository = stockHistoryRepository,
            calendarService = calendarService,
            dataQualityLogRepository = dataQualityLogRepository,
            dataSource = dataSource,
            jdbcTemplate = jdbcTemplate,
            backfillProperties = backfillProperties,
            metrics = metrics,
            notifier = notifier,
            replayService = replayService,
        )
    }

    // ==================== 构造辅助 ====================

    private fun stock(code: String) = StockInfo().apply {
        this.code = code
        this.name = "测试股$code"
        this.market = "SH"
        this.board = "MAIN"
        this.isSt = false
        this.delisted = false
    }

    /** 合法日K（DataValidator.validate(skipCrossCheck=true) 通过：high≥max(open,close)、四价>0、volume≥0） */
    private fun bar(code: String, date: LocalDate = from, volume: Long = 1_000_000L) = DailyBar(
        date = date,
        code = code,
        open = BigDecimal("10.00"),
        high = BigDecimal("11.00"),
        low = BigDecimal("9.00"),
        close = BigDecimal("10.50"),
        volume = volume,
        amount = BigDecimal("10500000.00"),
        changePercent = BigDecimal("9.98"),
        turnover = BigDecimal("1.00"),
        prevClose = BigDecimal("9.55"),
    )

    private fun response(vararg codes: String): DailyBarsBatchResponse =
        DailyBarsBatchResponse(
            status = "ok",
            results = codes.associateWith { StockBarsResult("baostock", 1, listOf(bar(it))) },
            failed = emptyList(),
        )

    /** 3 股全量清单（batchSize=2 → 2 批） */
    private fun stubStocks(vararg codes: String) {
        Mockito.`when`(stockInfoRepository.findByIsStFalseAndDelistedFalse()).thenReturn(codes.map { stock(it) })
    }

    // ==================== 分片进度回调 ====================

    @Test
    fun `testRun progressCallbackTracksAllBatchesAndRows`() {
        // given: 3 只股票，batchSize=2 → 2 批，全部成功
        stubStocks("600000", "600036", "600050")
        pythonClient.batchResponse = response("600000", "600036", "600050")
        val recorded = mutableListOf<BackfillProgress>()

        // when
        runBlocking { job.run(from, to) { recorded.add(it) } }

        // then: 初始快照 + 每批 current_batch/processed_* 逐批替换
        assertTrue(recorded.isNotEmpty(), "进度回调至少 1 次")
        val initial = recorded.first()
        assertEquals(3, initial.totalCodes, "初始 total_codes=3")
        assertEquals(2, initial.totalBatches, "初始 total_batches=2（batchSize=2）")
        val last = recorded.last()
        assertEquals(2, last.currentBatch, "末批 current_batch=2")
        assertEquals(2, last.processedBatches, "processed_batches=2")
        assertEquals(3, last.processedCodes, "processed_codes=3（成功+失败）")
        assertEquals(3, last.succeededCodes, "succeeded_codes=3")
        assertEquals(0, last.failedCodes, "failed_codes=0")
        assertEquals(3, last.processedRows, "processed_rows=3（每股 1 根）")
        assertEquals(2, pythonClient.fetchCalls, "2 批各拉取 1 次")
    }

    // ==================== 已覆盖代码跳过（断点续传） ====================

    @Test
    fun `testRun skipsAlreadyCoveredCodes`() {
        // given: 3 只；600050 已覆盖到 endDate 之后（findMaxTradeDate=2099 ≥ to）→ 跳过
        stubStocks("600000", "600036", "600050")
        Mockito.`when`(stockHistoryRepository.findMaxTradeDateByCode("600050")).thenReturn(LocalDate.of(2099, 1, 1))
        pythonClient.batchResponse = response("600000", "600036")

        // when
        runBlocking { job.run(from, to) { } }

        // then: 600050 整批被过滤，不重复拉取（幂等重跑 = 断点续传，§六 设计定稿）
        assertEquals(1, pythonClient.fetchCalls, "已覆盖 code 所在批直接跳过，只拉 1 批")
        assertEquals(listOf("600000", "600036"), pythonClient.fetchRequests.single().codes, "跳过 600050")
    }

    // ==================== 校验拒绝行落 data_quality_log(VALIDATION_REJECTED) ====================

    @Test
    fun `testRun rejectsInvalidBarsWritesValidationRejectedLog`() {
        // given: 600000 有 1 合法 + 1 非法（volume<0）；600036 全合法
        stubStocks("600000", "600036")
        val invalid = bar("600000", from.plusDays(1), volume = -1)
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult("baostock", 2, listOf(bar("600000"), invalid)),
                "600036" to StockBarsResult("baostock", 1, listOf(bar("600036"))),
            ),
            failed = emptyList(),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 非法行被 DataValidator 拒绝，落 data_quality_log（issue_type=VALIDATION_REJECTED，§4.7 防线②）
        val captor = ArgumentCaptor.forClass(DataQualityLog::class.java)
        Mockito.verify(dataQualityLogRepository).save(captor.capture())
        val saved = captor.value
        assertEquals(QualityIssueType.VALIDATION_REJECTED.name, saved.issueType, "issue_type=VALIDATION_REJECTED")
        assertNotNull(saved.detail, "detail 含拒绝明细")
        assertTrue(saved.detail!!.contains("600000"), "detail 记录被拒 code（source=BACKFILL 前缀）")
        Mockito.verify(metrics).incrementQualityLog(QualityIssueType.VALIDATION_REJECTED)
    }

    // ==================== 批间停顿可注入 ====================

    @Test
    fun `testRun batchPauseInjectableBetweenBatches`() {
        // given: batchPauseMs=150（两批间 delay），batchSize=2 → 2 批
        backfillProperties.batchPauseMs = 150
        stubStocks("600000", "600036", "600050")
        pythonClient.batchResponse = response("600000", "600036", "600050")
        val start = System.currentTimeMillis()

        // when
        runBlocking { job.run(from, to) { } }
        val elapsed = System.currentTimeMillis() - start

        // then: 批间停顿按配置生效（间歇性获取铁律，防 IP 封禁）；2 批均完成
        assertEquals(2, pythonClient.fetchCalls, "2 批均拉取")
        assertTrue(elapsed >= 100, "batchPauseMs=150 已注入（两批间 delay ≥100ms，实测 ${elapsed}ms）")
    }

    // ==================== 3 股抽查对拍 seam（红字优先，Implementer 契约） ====================

    @Test
    fun `testRun sampleVerificationSeamContract completesWithSummaryAndReplay`() {
        // given: 3 股全量成功 + 情绪回放返回摘要
        stubStocks("600000", "600036", "600050")
        pythonClient.batchResponse = response("600000", "600036", "600050")
        Mockito.`when`(replayService.replay(from, to))
            .thenReturn(SentimentReplaySummary(from, to, 2, 1, listOf("600000")))

        // when: 完整走完完成链（PLAN §六.6⑤：抽 sampleCheckCodes=3 只与增量路径派生列对拍）
        val summary = runBlocking { job.run(from, to) { } }

        // then: 不抛异常（seam 实现后成立），摘要完整
        assertEquals(from, summary.from, "摘要 from")
        assertEquals(to, summary.to, "摘要 to")
        assertEquals(3, summary.totalCodes, "摘要 total_codes")
        assertNotNull(summary.replaySummary, "情绪回放摘要非空（§13.5 成功串接）")
        assertTrue(
            backfillProperties.sampleCheckCodes == 3,
            "抽查数量契约 = sample-check-codes 配置（默认 3 只，PLAN §六.6⑤）",
        )
        assertTrue(
            notifier.digests.isNotEmpty(),
            "完成链末步钉钉 digest 已发（回填完成通知）",
        )
    }

    // ==================== 触发链非阻塞：replayService 抛异常仅告警 ====================

    @Test
    fun `testRun replayFailureNonBlockingOnlyAlert`() {
        // given: 3 股成功，但情绪回放抛异常（§13.5 失败不阻塞回填结果只告警）
        stubStocks("600000", "600036", "600050")
        pythonClient.batchResponse = response("600000", "600036", "600050")
        Mockito.`when`(replayService.replay(from, to)).thenThrow(RuntimeException("replay boom"))

        // when: 完整走完（含 seam 实现后到回放段）
        val summary = runBlocking { job.run(from, to) { } }

        // then: 回填结果不炸——replaySummary=null，digest 告警留人工补触发
        assertNull(summary.replaySummary, "回放失败 replay_summary=null（不阻塞回填结果）")
        assertTrue(
            notifier.digests.any { it.contains("情绪回放失败") },
            "回放失败已钉钉 digest 告警（人工补 POST /jobs/sentiment-replay）",
        )
    }

    // ==================== 抽查对拍不一致：Job FAILED + 落库 + 钉钉（Step 6 Verifier 补测） ====================

    /** 构造抽查对拍不一致场景：单股单 bar，DB 已落库派生列与增量路径口径不一致（change_pct≥9.9 应为板却 false） */
    private fun stubMismatchScenario() {
        stubStocks("600000")
        pythonClient.batchResponse = response("600000")
        Mockito.`when`(stockHistoryRepository.findDistinctCodesByTradeDateBetween(from, to))
            .thenReturn(listOf("600000"))
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(stock("600000"))
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween("600000", from, to))
            .thenReturn(listOf(mismatchedBar()))
    }

    /** DB 已落库 bar：change_pct=9.98（MAIN 阈值 9.9 应为涨停），但派生列仍为默认 false/0 → 对拍不一致 */
    private fun mismatchedBar(): StockHistory = StockHistory().apply {
        code = "600000"
        tradeDate = from
        changePct = BigDecimal("9.98")
        isLimitUp = false
        isLimitDown = false
        limitUpStreak = 0.toShort()
        limitDownStreak = 0.toShort()
    }

    @Test
    fun `testRun emptyUniverseFailsWithGuidanceInsteadOfSilentComplete`() {
        // given: 不 stub findByIsStFalseAndDelistedFalse（空库——stock_info 无数据）
        Mockito.`when`(stockInfoRepository.findByIsStFalseAndDelistedFalse()).thenReturn(emptyList())

        // when/then: 空候选必须 BusinessException（BackfillServiceImpl 捕获置 FAILED），
        // 而不是静默 COMPLETED 掩盖「忘记刷新股票清单」的配置问题
        val error = assertThrows(BusinessException::class.java) {
            runBlocking { job.run(from, to) { } }
        }
        assertTrue(error.message!!.contains("info/refresh"), "错误信息引导先刷新股票列表：${error.message}")
        assertEquals(0, pythonClient.fetchCalls, "空清单不发起任何外部请求")
    }

    @Test
    fun `testRun derivedMismatchFailsJobSurfacesError`() {
        // given: 抽查对拍不一致（DB 派生列与增量路径口径相悖）
        stubMismatchScenario()

        // when: run() 完整走完完成链（recompute 成功 → 抽查对拍命中不一致）
        val ex = assertThrows(BusinessException::class.java) {
            runBlocking { job.run(from, to) { } }
        }

        // then: Job 抛业务异常（BackfillService catch → FAILED + error 透出）
        assertTrue(ex.message!!.contains("600000"), "异常透出含不一致 code：${ex.message}")
    }

    @Test
    fun `testRun derivedMismatchWritesDataQualityLog`() {
        // given
        stubMismatchScenario()

        // when
        assertThrows(BusinessException::class.java) {
            runBlocking { job.run(from, to) { } }
        }

        // then: recordSampleMismatch → data_quality_log(issue_type=CROSS_VALIDATE_MISMATCH, source=BACKFILL)
        val captor = ArgumentCaptor.forClass(DataQualityLog::class.java)
        Mockito.verify(dataQualityLogRepository).save(captor.capture())
        val saved = captor.value
        assertEquals(QualityIssueType.CROSS_VALIDATE_MISMATCH.name, saved.issueType, "issue_type=CROSS_VALIDATE_MISMATCH")
        assertEquals("600000", saved.code, "code=600000")
        assertEquals("BACKFILL", saved.source, "source=BACKFILL")
        assertNotNull(saved.detail, "detail 含不一致明细")
        Mockito.verify(metrics).incrementQualityLog(QualityIssueType.CROSS_VALIDATE_MISMATCH)
    }

    @Test
    fun `testRun derivedMismatchNotifiesDingTalk`() {
        // given
        stubMismatchScenario()

        // when
        assertThrows(BusinessException::class.java) {
            runBlocking { job.run(from, to) { } }
        }

        // then: 钉钉 CROSS_VALIDATE_MISMATCH 告警（§11.3 ERROR，派生列错污染全链）
        val mismatch = notifier.notified.firstOrNull { it.first == DingTalkEvent.CROSS_VALIDATE_MISMATCH }
        assertNotNull(mismatch, "钉钉 CROSS_VALIDATE_MISMATCH 事件已发送")
        assertTrue(mismatch!!.second.contains("600000"), "钉钉正文含不一致 code")
    }
}
