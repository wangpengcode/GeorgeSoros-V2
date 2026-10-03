package com.soros.v2.job

import com.soros.v2.config.DataCollectionProperties
import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.StockInfo
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.StockHistoryService
import com.soros.v2.service.StockInfoService
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.DailyCollectCompleted
import com.soros.v2.service.dto.FailedBar
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.util.CollectMetrics
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher

/**
 * §4.4 DailyCollectJob 链路契约测试（Fake 依赖注入直接调 execute()，不依赖真实 cron）。
 *
 * 契约（§4.4 / §4.7 / §13.4 / 类 KDoc）：
 * 1. healthCheck 失败 → 快速失败，不 refreshStockList、不发 DailyCollectCompleted 事件；
 * 2. 成功链路：refreshStockList → 分批 fetchDailyBarsBatch → saveBatch → 完成发 DailyCollectCompleted(successCodes/failedCodes/durationMs)；
 * 3. failedCodes >10% 由消费者判 PARTIAL（§13.4），本 Job 只发布事件；
 *    且失败率 >10% 时钉钉告警 BATCH_FAILURE_RATE_HIGH（§11.3，≤10% 不发）。
 * 4. §17.2 非交易日守卫：isTradingDay=false → 不拉列表/行情、不发事件、不发告警。
 *
 * execute() 已实现（GREEN）；Python/History 用 Fake（规避 Mockito 对 suspend 方法 Continuation 参数匹配问题）。
 */
class DailyCollectJobTest {

    private lateinit var pythonClient: FakePythonClient
    private lateinit var historyService: FakeHistoryService
    private lateinit var infoService: FakeStockInfoService
    private lateinit var calendarService: TradingCalendarService
    private lateinit var properties: DataCollectionProperties
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var notifier: DingTalkNotifier
    private lateinit var metrics: CollectMetrics
    private lateinit var job: DailyCollectJob

    /**
     * Fake 钉钉通知：记录告警（Kotlin 接口非空参数 + Mockito 空 matcher 会触发边界 null-check NPE，
     * 故用 Fake 记录而非 verify(matcher)）。
     */
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

    /** Fake 交易日历：可设定 isTrading（§17.2 守卫；ensureLoaded 为 suspend，Mockito 规避 Continuation 匹配问题） */
    private class FakeCalendarService : TradingCalendarService {
        var isTrading = true
        var ensureLoadedCalls = 0

        override suspend fun ensureLoaded(): Int {
            ensureLoadedCalls++
            return 1
        }

        override fun isTradingDay(date: LocalDate): Boolean = isTrading
        override fun previousTradingDay(date: LocalDate): LocalDate? = null
        override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> = emptyList()
    }

    /** Fake Python 客户端：可设定 health/fetchDailyBarsBatch 响应并计数 */
    private class FakePythonClient : PythonDataServiceClient {
        var healthy = true
        var batchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        var fetchBatchCalls = 0

        override suspend fun healthCheck(): Boolean = healthy
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchBatchCalls++
            return batchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
    }

    /** Fake StockInfo 服务：可设定 refreshStockList 返回（suspend 规避 Mockito Continuation 匹配问题） */
    private class FakeStockInfoService : StockInfoService {
        var refreshResult: List<StockInfo> = emptyList()
        var refreshCalls = 0

        override suspend fun refreshStockList(): List<StockInfo> {
            refreshCalls++
            return refreshResult
        }
        override fun findByCode(code: String): StockInfo? = refreshResult.firstOrNull { it.code == code }
        override fun saveBenchmarkIndices(): Int = 0
    }

    /** Fake History 服务：记录 saveBatch 调用 */
    private class FakeHistoryService : StockHistoryService {
        var saveBatchCalls: MutableList<Pair<String, Int>> = mutableListOf()
        var findMaxDateResult: LocalDate? = null

        override fun findMaxDate(code: String): LocalDate? = findMaxDateResult
        override suspend fun saveBatch(
            code: String,
            bars: List<DailyBar>,
            source: DataSourceType,
            board: Board,
        ): SaveBatchResult {
            saveBatchCalls.add(code to bars.size)
            return SaveBatchResult(code, bars.size, false, false, 0)
        }

        override suspend fun saveIndexBatch(code: String, bars: List<DailyBar>, source: DataSourceType): Int = bars.size
    }

    @BeforeEach
    fun setUp() {
        pythonClient = FakePythonClient()
        historyService = FakeHistoryService()
        infoService = FakeStockInfoService()
        calendarService = FakeCalendarService() // §17.2 交易日守卫默认放行
        properties = DataCollectionProperties()
        eventPublisher = Mockito.mock(ApplicationEventPublisher::class.java)
        notifier = FakeNotifier()
        metrics = Mockito.mock(CollectMetrics::class.java)
        job = DailyCollectJob(
            pythonClient, historyService, infoService, calendarService, properties,
            eventPublisher, notifier, metrics, Dispatchers.IO,
        )
    }

    /** 构造一只正常主板股票（供 refreshStockList 返回） */
    private fun stock(code: String) = StockInfo().apply {
        this.code = code
        this.name = "测试股$code"
        this.market = "SH"
        this.board = Board.MAIN.name
        this.isSt = false
        this.delisted = false
    }

    /** 构造一根合法日K（主板涨停：不复权 change_pct=9.98） */
    private fun bar(date: LocalDate = LocalDate.of(2026, 9, 30)) = DailyBar(
        date = date,
        code = "600000",
        open = BigDecimal("12.00"),
        high = BigDecimal("13.50"),
        low = BigDecimal("11.50"),
        close = BigDecimal("13.20"),
        volume = 1_000_000L,
        amount = BigDecimal("13000000.00"),
        changePercent = BigDecimal("9.98"),
        turnover = BigDecimal("2.00"),
        prevClose = BigDecimal("12.00"),
    )

    // ==================== 异常路径：健康检查失败快速失败 ====================

    @Test
    fun `testExecute healthCheckFailed failFastNoEventNoFetch`() {
        // given: Python 离线（healthCheck=false）
        pythonClient.healthy = false

        // when
        job.execute()

        // then: 快速失败——不发事件、不拉列表、不拉行情；仅发 PYTHON_SERVICE_OFFLINE 告警（§11.3，M-6）
        Mockito.verify(eventPublisher, Mockito.never()).publishEvent(Mockito.any<Any>())
        assertEquals(0, infoService.refreshCalls, "健康检查失败不得 refreshStockList")
        assertTrue(historyService.saveBatchCalls.isEmpty(), "健康检查失败不得发起入库")
        assertEquals(0, pythonClient.fetchBatchCalls, "健康检查失败不得拉行情")
        val fakeNotifier = notifier as FakeNotifier
        assertTrue(
            fakeNotifier.notified.any { it.first == DingTalkEvent.PYTHON_SERVICE_OFFLINE },
            "健康检查失败应告警 PYTHON_SERVICE_OFFLINE（§11.3，M-6）",
        )
    }

    // ==================== 正常流程：成功链路 + §13.4 完成事件 ====================

    @Test
    fun `testExecute successPath collectsAndPublishesCompletedEvent`() {
        // given: 健康 ok + 2 只有效股票 + 批量行情响应（2 股各 1 根）
        infoService.refreshResult = listOf(stock("600000"), stock("600036"))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult("baostock", 1, listOf(bar())),
                "600036" to StockBarsResult("baostock", 1, listOf(bar())),
            ),
            failed = emptyList(),
        )

        // when
        job.execute()

        // then: 每只有效股票均入库
        assertEquals(2, historyService.saveBatchCalls.size, "两只股票各入库一次")
        assertTrue(historyService.saveBatchCalls.map { it.first }.containsAll(setOf("600000", "600036")),
            "saveBatch 覆盖 600000/600036")
        // then: §13.4 完成握手事件已发布，successCodes 覆盖两只股票
        val captor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(eventPublisher).publishEvent(captor.capture())
        val event = captor.value as DailyCollectCompleted
        assertEquals(setOf("600000", "600036"), event.successCodes, "successCodes 覆盖全部成功入库的股票")
        assertEquals(0, event.failedCodes.size, "无失败股票")
        assertTrue(event.durationMs >= 0, "durationMs 非负")
    }

    @Test
    fun `testExecute successPath failedCodesFromBatchFailure`() {
        // given: 一只成功 + 一只失败（failed[] 单股失败不炸整批，§11.1）
        infoService.refreshResult = listOf(stock("600000"), stock("600036"))
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf("600000" to StockBarsResult("baostock", 1, listOf(bar()))),
            failed = listOf(FailedBar("600036", "Python 单股拉取失败")),
        )

        // when
        job.execute()

        // then: 失败 code 进入 failedCodes（消费者判 data_coverage=PARTIAL，§13.4）
        val captor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(eventPublisher).publishEvent(captor.capture())
        val event = captor.value as DailyCollectCompleted
        assertEquals(setOf("600000"), event.successCodes, "成功股=600000")
        assertEquals(setOf("600036"), event.failedCodes, "失败股=600036（来自 failed[]）")
    }

    // ==================== §11.3 批次失败率告警 ====================

    @Test
    fun `testExecute failureRateOverThresholdNotifiesBatchFailureRateHigh`() {
        // given: 10 只股票，8 只有行情成功 + 2 只无数据失败（失败率 20% > 10% 阈值）
        val codes = (1..10).map { index -> String.format("6000%02d", index) }
        infoService.refreshResult = codes.map { stock(it) }
        val successCodes = codes.take(8)
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = successCodes.associateWith { StockBarsResult("baostock", 1, listOf(bar())) },
            failed = emptyList(),
        )

        // when
        job.execute()

        // then: 失败率 2/10=20% >10% → FakeNotifier 收到 BATCH_FAILURE_RATE_HIGH（§11.3，M-7）
        val fakeNotifier = notifier as FakeNotifier
        assertTrue(
            fakeNotifier.notified.any { it.first == DingTalkEvent.BATCH_FAILURE_RATE_HIGH },
            "失败率>10% 应告警 BATCH_FAILURE_RATE_HIGH（成功 8 失败 2）",
        )
    }

    @Test
    fun `testExecute failureRateAtThresholdDoesNotNotify`() {
        // given: 10 只股票，9 只有行情成功 + 1 只无数据失败（失败率 10%，不大于阈值）
        val codes = (1..10).map { index -> String.format("6000%02d", index) }
        infoService.refreshResult = codes.map { stock(it) }
        val successCodes = codes.take(9)
        pythonClient.batchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = successCodes.associateWith { StockBarsResult("baostock", 1, listOf(bar())) },
            failed = emptyList(),
        )

        // when
        job.execute()

        // then: 失败率 =10% 不大于阈值 → 不告警 BATCH_FAILURE_RATE_HIGH
        val fakeNotifier = notifier as FakeNotifier
        assertTrue(
            fakeNotifier.notified.none { it.first == DingTalkEvent.BATCH_FAILURE_RATE_HIGH },
            "失败率≤10% 不应告警 BATCH_FAILURE_RATE_HIGH（成功 9 失败 1）",
        )
    }

    // ==================== §17.2 非交易日守卫 ====================

    @Test
    fun `testExecute nonTradingDaySkipsFetchAndNotify`() {
        // given: 非交易日（§17.2 守卫：仅明确非交易日才跳过）
        (calendarService as FakeCalendarService).isTrading = false

        // when
        job.execute()

        // then: 不拉列表、不拉行情、不发完成事件、不发任何钉钉告警
        Mockito.verify(eventPublisher, Mockito.never()).publishEvent(Mockito.any<Any>())
        assertEquals(0, infoService.refreshCalls, "非交易日不得 refreshStockList")
        assertTrue(historyService.saveBatchCalls.isEmpty(), "非交易日不得入库")
        assertEquals(0, pythonClient.fetchBatchCalls, "非交易日不得拉行情")
        val fakeNotifier = notifier as FakeNotifier
        assertTrue(fakeNotifier.notified.isEmpty(), "非交易日不发任何钉钉告警（含离线/失败率/digest）")
    }
}
