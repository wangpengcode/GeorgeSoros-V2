package com.soros.v2.job

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.SegmentReason
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.exception.PythonClientException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryGapCheckRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.BackfillPlanService
import com.soros.v2.service.backfill.dto.RerunPlan
import com.soros.v2.service.backfill.dto.FetchSegment
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
import com.soros.v2.service.dto.BatchItem
import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.util.CollectMetrics
import java.io.Reader
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.postgresql.PGConnection
import org.postgresql.copy.CopyManager
import org.springframework.jdbc.core.JdbcTemplate

/**
 * BackfillJob「库内重跑计划」编排契约测试（Architect 设计 §5 改造点 1-12，用例 22-29）。
 *
 * 测试的是改造后的 BackfillJob：
 * - 替代 buildChunkPlan 全量重拉 → planService.buildRerunPlan（库内驱动，零外部请求当零 segment）；
 * - 批打包 = BackfillClassifier.batchSegments（同批 code 唯一）→ fetchWithRetry 收 items 逐段窗口；
 * - processSegment：HTTP 200 + 0 行且非 failed → K=2 验证空（2026-10-05 均分流量定稿：
 *   empty_sources ≥2 个不同源才记 verified-empty+推水位；缺字段/单源空=pending-empty 不推水位）；
 *   failed 含此码 → 计 failed 不记录；
 * - 空 segments → 跳过 collect 直接 recompute+verify（不炸）；空库 totalCodes==0 → BusinessException 防御保留；
 * - gap_check 排除 MID 在 Planner 层生效，Job 层透传（请求 items 不含被排除段）。
 *
 * 本文件是新行为契约（不改现有 BackfillJobTest，规避既有全量重拉路径回归）。
 * 红线：BackfillJob 构造器新增 planService/gapCheckRepository 依赖后本文件方可编译（预期红）。
 */
class BackfillJobRerunTest {

    private val from = LocalDate.of(2021, 10, 1)
    private val to = LocalDate.of(2021, 10, 5)

    private lateinit var pythonClient: FakePythonClient
    private lateinit var planService: FakePlanService
    private lateinit var gapCheckRepository: StockHistoryGapCheckRepository
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

    /** Fake Python 客户端：记录 fetchDailyBarsBatch 请求 + 可注入重试抛异常（SorosBaseException 语义） */
    private class FakePythonClient : PythonDataServiceClient {
        var batchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        val fetchRequests = mutableListOf<DailyBarsBatchRequest>()
        var fetchCalls = 0
        var throwRemaining = 0

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchCalls++
            fetchRequests.add(request)
            if (throwRemaining > 0) {
                throwRemaining--
                throw PythonClientException("transient boom（幂等重试安全）")
            }
            return batchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    /** Fake 计划服务：首轮返回注入的 plan，后续构建返回 subsequentPlan（模拟水位推进后计划收敛为空） */
    private class FakePlanService(var plan: RerunPlan) : BackfillPlanService {
        var subsequentPlan: RerunPlan? = null
        var buildCalls = 0
        override suspend fun buildRerunPlan(from: LocalDate, to: LocalDate): RerunPlan {
            buildCalls++
            return if (buildCalls == 1) plan else subsequentPlan ?: plan
        }
    }

    /** Fake 交易日历（ensureLoaded 为 suspend，规避 Mockito Continuation 匹配） */
    private class FakeCalendarService : TradingCalendarService {
        override suspend fun ensureLoaded(): Int = 1
        override fun isTradingDay(date: LocalDate): Boolean = true
        override fun previousTradingDay(date: LocalDate): LocalDate? = null
        override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> = emptyList()
    }

    /** Fake 钉钉：记录 digest（非空参数 + Mockito 空 matcher 会触发 NPE，用 Fake 记录） */
    private class FakeNotifier : DingTalkNotifier {
        val digests = mutableListOf<String>()
        override fun notify(event: DingTalkEvent, title: String, content: String) = Unit
        override fun notifyDailyDigest(digest: String) {
            digests.add(digest)
        }
    }

    @BeforeEach
    fun setUp() {
        pythonClient = FakePythonClient()
        planService = FakePlanService(
            RerunPlan(from, to, totalCodes = 0, completeCodes = 0, zeroWindowCodes = 0, segments = emptyList()),
        ).apply {
            // 轮次重扫语义：首轮有进展 → 重建计划已收敛（segments 空）→ 单轮收口（与生产水位推进一致）
            subsequentPlan = RerunPlan(from, to, totalCodes = 1, completeCodes = 1, zeroWindowCodes = 0, segments = emptyList())
        }
        gapCheckRepository = Mockito.mock(StockHistoryGapCheckRepository::class.java)
        stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        stockHistoryRepository = Mockito.mock(StockHistoryRepository::class.java)
        calendarService = FakeCalendarService()
        dataQualityLogRepository = Mockito.mock(DataQualityLogRepository::class.java)
        dataSource = Mockito.mock(DataSource::class.java)
        jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        backfillProperties = BackfillProperties().apply {
            batchSize = 2
            batchPauseMs = 1
            maxRetriesPerBatch = 2
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
        Mockito.`when`(copyManager.copyIn(Mockito.anyString(), Mockito.any<Reader>())).thenReturn(0L)
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
            planService = planService,
            gapCheckRepository = gapCheckRepository,
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

    private fun plan(vararg segments: FetchSegment, totalCodes: Int = 1): RerunPlan =
        RerunPlan(
            from = from,
            to = to,
            totalCodes = totalCodes,
            completeCodes = totalCodes - segments.size,
            zeroWindowCodes = 0,
            segments = segments.toList(),
        )

    // ==================== 22. 全齐票零外部请求铁律 ====================

    @Test
    fun `testRun completePlanZeroExternalFetchRequests`() {
        // given: 全齐 RerunPlan（segments 空，totalCodes>0）——分类产出零 segment
        planService.plan = plan(totalCodes = 3)

        // when
        val summary = runBlocking { job.run(from, to) { } }

        // then: 零外部请求（全齐票不产出任何 segment → batchSegments 空 → collect 跳过）
        assertEquals(0, pythonClient.fetchCalls, "全齐票必须零 fetch 调用（外部请求=0 铁律）")
        assertNotNull(summary, "run() 正常走完完成链")
    }

    // ==================== 23. 有 segment → items 模式请求 ====================

    @Test
    fun `testRun hasSegmentsSendsBatchItemsRequests`() {
        // given: 单段（NO_DATA 整窗）+ 假 Python 返回该码 1 根
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = response("600000")
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(stock("600000"))

        // when
        runBlocking { job.run(from, to) { } }

        // then: fetchDailyBarsBatch 收到 request.items（逐段窗口），codes 为空
        val request = pythonClient.fetchRequests.single()
        assertEquals(listOf(BatchItem("600000", from.toString(), to.toString())), request.items, "items=逐段拉取窗口")
        assertTrue(request.codes.isEmpty(), "items 模式 codes 必须为空（XOR 契约）")
    }

    // ==================== 24. 空结果 K=2 验证空契约（2026-10-05 均分流量定稿） ====================

    @Test
    fun `testProcessSegment missingEmptySourcesIsPendingEmptyNoUpsertNoAdvance`() {
        // given: 0 行占位但缺 empty_sources（旧版 Python 兼容）→ pending-empty：不落台账不推水位不计失败
        // （单源空不可信——源可能自己缺数据；缺字段=无法证明 ≥2 源都空，保守不推水位）
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf("600000" to StockBarsResult(source = "baostock", count = 0, data = emptyList())),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 不记 verified-empty、不推水位、不计失败
        Mockito.verify(gapCheckRepository, Mockito.never())
            .upsertVerifiedEmpty(Mockito.anyString(), Mockito.any<LocalDate>(), Mockito.any<LocalDate>(), Mockito.anyInt())
        Mockito.verify(jdbcTemplate, Mockito.never()).update(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())
        Mockito.verify(metrics, Mockito.never()).incrementFailedCodes()
    }

    @Test
    fun `testProcessSegment singleSourceEmptyIsPendingEmptyNoUpsertNoAdvance`() {
        // given: 0 行且 empty_sources 只有 1 个源 → 单源空待第二源确认（pending-empty）
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult(
                    source = "baostock", count = 0, data = emptyList(), emptySources = listOf("baostock"),
                ),
            ),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 不记 verified-empty、不推水位、不计失败（下轮计划重扫换源再验证）
        Mockito.verify(gapCheckRepository, Mockito.never())
            .upsertVerifiedEmpty(Mockito.anyString(), Mockito.any<LocalDate>(), Mockito.any<LocalDate>(), Mockito.anyInt())
        Mockito.verify(jdbcTemplate, Mockito.never()).update(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())
        Mockito.verify(metrics, Mockito.never()).incrementFailedCodes()
    }

    @Test
    fun `testProcessSegment twoDistinctSourcesEmptyConfirmsVerifiedEmptyAndAdvances`() {
        // given: 0 行且 empty_sources ≥2 个不同源 → 验证空成立：落台账 + 推水位
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult(
                    source = "tencent",
                    count = 0,
                    data = emptyList(),
                    emptySources = listOf("baostock", "tencent"),
                ),
            ),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 记录 verified-empty（防反复空拉）+ 水位推进（单调不减 UPDATE）
        Mockito.verify(gapCheckRepository).upsertVerifiedEmpty("600000", from, to, 0)
        Mockito.verify(jdbcTemplate).update(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `testProcessSegment duplicateEmptySourcesDoNotConfirmVerifiedEmpty`() {
        // given: empty_sources 两个条目但 distinct 后只有 1 个源（同源重复计票）→ 不得确认验证空
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult(
                    source = "baostock",
                    count = 0,
                    data = emptyList(),
                    emptySources = listOf("baostock", "baostock"),
                ),
            ),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 按源去重后单源 → pending-empty
        Mockito.verify(gapCheckRepository, Mockito.never())
            .upsertVerifiedEmpty(Mockito.anyString(), Mockito.any<LocalDate>(), Mockito.any<LocalDate>(), Mockito.anyInt())
    }

    @Test
    fun `testProcessSegment dataLandedStillAdvancesWatermark`() {
        // given: 有数据 → 落库 + 推水位（K=2 契约不影响成功路径，回归保护）
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = response("600000")
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(stock("600000"))

        // when
        runBlocking { job.run(from, to) { } }

        // then: 水位推进（advanceInputWatermark 走 jdbcTemplate.update）
        Mockito.verify(jdbcTemplate).update(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())
    }

    // ==================== 25. failed 含此码 → 计 failed 不记录 ====================

    @Test
    fun `testProcessSegment codeInFailedCountedFailedNoUpsert`() {
        // given: 段拉取失败（failed[] 含该码）→ 计 failed，不得记录 verified-empty
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = emptyMap(),
            failed = listOf(FailedBar("600000", "源故障")),
        )

        // when
        runBlocking { job.run(from, to) { } }

        // then: 不记 gap_check（仅 HTTP 200 且非 failed 才记）；失败进 metrics
        Mockito.verify(gapCheckRepository, Mockito.never())
            .upsertVerifiedEmpty(Mockito.anyString(), Mockito.any<LocalDate>(), Mockito.any<LocalDate>(), Mockito.anyInt())
        Mockito.verify(metrics).incrementFailedCodes()
    }

    // ==================== 26. fetchWithRetry 重试语义 ====================

    @Test
    fun `testFetchWithRetry retriesOnSorosBaseExceptionUpToMax`() {
        // given: 第一次抛 PythonClientException（SorosBaseException 子类）→ 应用层重试 maxRetriesPerBatch 轮
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.NO_DATA))
        pythonClient.throwRemaining = 1
        pythonClient.batchResponse = response("600000")
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(stock("600000"))

        // when
        runBlocking { job.run(from, to) { } }

        // then: 第 1 次失败 + 第 2 次成功（重试只对 SorosBaseException，幂等安全）
        assertEquals(2, pythonClient.fetchCalls, "瞬时故障触发重试至 maxRetriesPerBatch")
    }

    // ==================== 27. 空 segments → 跳过 collect 不炸 ====================

    @Test
    fun `testRun emptySegmentsSkipsCollectStillRecomputeAndVerify`() {
        // given: 全部 complete / zero-window → segments 空
        planService.plan = plan(totalCodes = 2)

        // when
        val summary = runBlocking { job.run(from, to) { } }

        // then: collect 跳过直接 recompute+verify+replay（不炸）
        assertEquals(0, pythonClient.fetchCalls, "空 segments → 零 fetch")
        assertNotNull(summary, "run() 不炸，走完完成链")
    }

    // ==================== 28. 空库 totalCodes==0 防御 ====================

    @Test
    fun `testRun emptyUniverseThrowsBusinessExceptionWithGuidance`() {
        // given: 库内无任何候选（totalCodes==0）——防御保留（引导先刷新股票清单）
        planService.plan = RerunPlan(from, to, totalCodes = 0, completeCodes = 0, zeroWindowCodes = 0, segments = emptyList())

        // when/then: 空库必须 BusinessException（不能静默 COMPLETED 掩盖配置问题）
        val error = assertThrows(BusinessException::class.java) {
            runBlocking { job.run(from, to) { } }
        }
        assertTrue(error.message!!.contains("info/refresh"), "错误信息引导先刷新股票列表：${error.message}")
        assertEquals(0, pythonClient.fetchCalls, "空库不发起任何外部请求")
    }

    // ==================== 29. gap_check 排除 MID 在 Planner 层生效，Job 层透传 ====================

    @Test
    fun `testRun midExcludedByGapCheckSegmentAbsentFromRequest`() {
        // given: Planner 已把与 gap_check 完全重合的 MID 段排除，仅产出 HEAD 段（Job 层透传）
        planService.plan = plan(FetchSegment("600000", from, to, SegmentReason.HEAD))
        pythonClient.batchResponse = response("600000")
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(stock("600000"))

        // when
        runBlocking { job.run(from, to) { } }

        // then: 请求 items 只含 Planner 排除后的段（被 gap_check 命中的 MID 不出现在请求里）
        val request = pythonClient.fetchRequests.single()
        assertEquals(listOf("600000"), request.items.map { it.code }, "被排除段不进入请求 items")
        assertEquals(listOf(from.toString() to to.toString()), request.items.map { it.startDate to it.endDate }, "逐段窗口透传")
    }
}
