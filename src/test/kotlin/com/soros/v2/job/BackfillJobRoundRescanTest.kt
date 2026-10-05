package com.soros.v2.job

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.SegmentReason
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.PythonClientException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryGapCheckRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.BackfillPlanService
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.RerunPlan
import com.soros.v2.service.dto.BoardMembersRequest
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
import com.soros.v2.util.CollectMetrics
import java.io.Reader
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.postgresql.PGConnection
import org.postgresql.copy.CopyManager
import org.springframework.jdbc.core.JdbcTemplate

/**
 * BackfillJob 轮次重扫契约测试（2026-10-05 均分流量定稿）：
 *
 * - 失败/单源空（pending-empty）不推水位 → 下轮 buildRerunPlan（纯查库零外部请求）自动重扫；
 * - 停止条件三选一：① 计划 segments 空（全部拉齐）② 整轮零进展（0 行落库/0 验证空/0 待确认，
 *   = 源全坏或全部失败，防空转）③ 轮次硬上限 MAX_RESYNC_ROUNDS（防 pending 永不确认的病态循环）；
 * - 有进展（任一 >0）→ 继续下一轮，失败票交给其他健康源（Python 轮转分配）。
 */
class BackfillJobRoundRescanTest {

    private val from = LocalDate.of(2021, 10, 1)
    private val to = LocalDate.of(2021, 10, 5)

    private lateinit var pythonClient: QueuePythonClient
    private lateinit var planService: QueuePlanService
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

    /** Fake Python 客户端：fetch 响应按队列逐次弹出（轮次重扫逐轮不同响应），耗尽后回落 lastResponse */
    private class QueuePythonClient : PythonDataServiceClient {
        val responseQueue = ArrayDeque<DailyBarsBatchResponse>()
        var lastResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
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
            return if (responseQueue.isNotEmpty()) responseQueue.removeFirst() else lastResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    /** Fake 计划服务：buildRerunPlan 按队列逐次弹出（模拟逐轮水位推进后计划收敛），耗尽后回落 lastPlan */
    private class QueuePlanService : BackfillPlanService {
        val planQueue = ArrayDeque<RerunPlan>()
        lateinit var lastPlan: RerunPlan
        var buildCalls = 0

        override suspend fun buildRerunPlan(from: LocalDate, to: LocalDate): RerunPlan {
            buildCalls++
            return if (planQueue.isNotEmpty()) planQueue.removeFirst().also { lastPlan = it } else lastPlan
        }
    }

    /** Fake 交易日历（ensureLoaded 为 suspend，规避 Mockito Continuation 匹配） */
    private class FakeCalendarService : TradingCalendarService {
        override suspend fun ensureLoaded(): Int = 1
        override fun isTradingDay(date: LocalDate): Boolean = true
        override fun previousTradingDay(date: LocalDate): LocalDate? = null
        override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> = emptyList()
    }

    /** Fake 钉钉：记录 digest */
    private class FakeNotifier : DingTalkNotifier {
        val digests = mutableListOf<String>()
        override fun notify(event: DingTalkEvent, title: String, content: String) = Unit
        override fun notifyDailyDigest(digest: String) {
            digests.add(digest)
        }
    }

    @BeforeEach
    fun setUp() {
        pythonClient = QueuePythonClient()
        planService = QueuePlanService()
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

        // COPY 两段式 mock（同 BackfillJobRerunTest）
        val conn = Mockito.mock(Connection::class.java)
        val pgConn = Mockito.mock(PGConnection::class.java)
        val copyManager = Mockito.mock(CopyManager::class.java)
        Mockito.`when`(dataSource.connection).thenReturn(conn)
        Mockito.`when`(conn.unwrap(PGConnection::class.java)).thenReturn(pgConn)
        Mockito.`when`(pgConn.copyAPI).thenReturn(copyManager)
        Mockito.`when`(copyManager.copyIn(Mockito.anyString(), Mockito.any<Reader>())).thenReturn(0L)
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

    private fun segPlan(vararg codes: String): RerunPlan = RerunPlan(
        from = from,
        to = to,
        totalCodes = if (codes.isEmpty()) 1 else codes.size + 1,
        completeCodes = if (codes.isEmpty()) 1 else 0,
        zeroWindowCodes = 0,
        segments = codes.map { FetchSegment(it, from, to, SegmentReason.NO_DATA) },
    )

    private fun bar(code: String, date: LocalDate = from) = DailyBar(
        date = date,
        code = code,
        open = BigDecimal("10.00"),
        high = BigDecimal("11.00"),
        low = BigDecimal("9.00"),
        close = BigDecimal("10.50"),
        volume = 1_000_000L,
        amount = BigDecimal("10500000.00"),
        changePercent = BigDecimal("9.98"),
        turnover = BigDecimal("1.00"),
        prevClose = BigDecimal("9.55"),
    )

    private fun dataResponse(vararg codes: String): DailyBarsBatchResponse = DailyBarsBatchResponse(
        status = "ok",
        results = codes.associateWith { StockBarsResult("baostock", 1, listOf(bar(it))) },
        failed = emptyList(),
    )

    /** 单源空占位（pending-empty） */
    private fun pendingEmptyResponse(code: String): DailyBarsBatchResponse = DailyBarsBatchResponse(
        status = "ok",
        results = mapOf(
            code to StockBarsResult(source = "baostock", count = 0, data = emptyList(), emptySources = listOf("baostock")),
        ),
        failed = emptyList(),
    )

    /** 双源空占位（K=2 验证空成立） */
    private fun confirmedEmptyResponse(code: String): DailyBarsBatchResponse = DailyBarsBatchResponse(
        status = "ok",
        results = mapOf(
            code to StockBarsResult(
                source = "tencent", count = 0, data = emptyList(), emptySources = listOf("baostock", "tencent"),
            ),
        ),
        failed = emptyList(),
    )

    private fun failedResponse(code: String): DailyBarsBatchResponse = DailyBarsBatchResponse(
        status = "ok",
        results = emptyMap(),
        failed = listOf(FailedBar(code, "源故障")),
    )

    // ==================== 轮次重扫契约 ====================

    @Test
    fun `testRun planEmptyFirstRoundStopsWithZeroFetch`() {
        // given: 首轮计划就无待拉段（全部拉齐）→ 零外部请求直接结束
        planService.planQueue.add(segPlan())

        // when
        runBlocking { job.run(from, to) { } }

        // then: 零 fetch、计划只构建 1 次
        assertEquals(0, pythonClient.fetchCalls, "segments 空 → 零外部请求")
        assertEquals(1, planService.buildCalls, "首轮即收敛 → 只构建一次计划")
    }

    @Test
    fun `testRun zeroProgressRoundStopsNoRescan`() {
        // given: 拉取全部失败（failed[]）→ 整轮零进展 → 停止，不再重建计划空转
        planService.planQueue.add(segPlan("600000"))
        pythonClient.lastResponse = failedResponse("600000")

        // when
        runBlocking { job.run(from, to) { } }

        // then: 只跑 1 轮 1 次拉取、计划只构建 1 次（零进展防空转铁律）
        assertEquals(1, pythonClient.fetchCalls, "零进展轮后停止 → 只拉取一次")
        assertEquals(1, planService.buildCalls, "零进展轮后不再重建计划")
    }

    @Test
    fun `testRun progressTriggersNextRoundUntilPlanEmpty`() {
        // given: 第 1 轮拉到数据（有进展）→ 重建计划已空 → 第 2 轮收敛结束
        planService.planQueue.add(segPlan("600000"))
        planService.planQueue.add(segPlan())  // 第 2 次构建：水位已推进 → segments 空
        pythonClient.responseQueue.add(dataResponse("600000"))
        Mockito.`when`(stockInfoRepository.findByCode("600000")).thenReturn(StockInfo().apply { code = "600000" })

        // when
        runBlocking { job.run(from, to) { } }

        // then: 计划构建 2 次（首轮 + 收敛判定）、拉取只 1 次
        assertEquals(2, planService.buildCalls, "有进展 → 重建计划做收敛判定")
        assertEquals(1, pythonClient.fetchCalls, "第 2 轮计划已空 → 不再拉取")
    }

    @Test
    fun `testRun pendingEmptyRoundContinuesThenConfirmedBySecondSource`() {
        // given: 第 1 轮单源空（pending，不推水位）→ 第 2 轮重扫换源 → 双源空确认验证空 → 收敛
        planService.planQueue.add(segPlan("600000"))
        planService.planQueue.add(segPlan("600000"))  // pending 未推水位 → 计划仍含该段
        planService.planQueue.add(segPlan())          // 验证空已推进 → segments 空
        pythonClient.responseQueue.add(pendingEmptyResponse("600000"))
        pythonClient.responseQueue.add(confirmedEmptyResponse("600000"))

        // when
        runBlocking { job.run(from, to) { } }

        // then: 3 次计划构建、2 轮拉取、第 2 轮确认验证空落台账
        assertEquals(3, planService.buildCalls, "pending 轮必须继续重扫（K=2 验证空链路）")
        assertEquals(2, pythonClient.fetchCalls, "第 2 轮换源重拉确认")
        Mockito.verify(gapCheckRepository).upsertVerifiedEmpty("600000", from, to, 0)
    }

    @Test
    fun `testRun pendingNeverConfirmedStopsAtRoundCap`() {
        // given: 每轮都是单源空且永不确认（病态：Python 投票状态丢失）→ 轮次硬上限兜底停止
        planService.planQueue.add(segPlan("600000"))
        planService.lastPlan = segPlan("600000")  // 每次重建都还有该段
        repeat(20) { pythonClient.responseQueue.add(pendingEmptyResponse("600000")) }

        // when
        runBlocking { job.run(from, to) { } }

        // then: 轮次封顶 MAX_RESYNC_ROUNDS=10，不会无限循环
        assertTrue(pythonClient.fetchCalls <= 10, "轮次硬上限兜底：实际 ${pythonClient.fetchCalls} 次 fetch")
        assertTrue(planService.buildCalls <= 10, "轮次硬上限兜底：实际 ${planService.buildCalls} 次计划构建")
    }

    @Test
    fun `testRun fetchRetryExhaustedRoundCountsZeroProgressStops`() {
        // given: 批拉取重试耗尽全失败（failed 计数 >0 但 rows/verified/pending 全 0）
        // → 「失败不计进展」：整轮零进展 → 停止防空转
        planService.planQueue.add(segPlan("600000"))
        pythonClient.throwRemaining = 99

        // when
        runBlocking { job.run(from, to) { } }

        // then: 批拉取重试耗尽后整轮零进展 → 停止（fetchCalls = maxRetriesPerBatch 次重试）
        assertEquals(2, pythonClient.fetchCalls, "重试 2 次全败 → 本轮零进展 → 停止")
        assertEquals(1, planService.buildCalls, "零进展后不再重建计划")
    }
}
