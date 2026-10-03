package com.soros.v2.service

import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.util.CollectMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §4.6/§4.7/§4.8 StockHistoryService.saveBatch 联动契约测试（@DataJpaTest + TestContainers PG16）。
 *
 * 契约（接口 KDoc / PLAN §4.6/§4.7/§4.8）：
 * 1. DataValidator 行内自洽（防线②，非法行跳过记日志，不落库）；
 * 2. LimitUpDetector 涨停检测（不复权 change_pct + board 阈值，§4.5）；
 * 3. prev_close 链式校验（§4.6：|prev_close − 前一根 close| ≤ max(0.01, prev_close×0.5%)；
 *    漂移 → data_quality_log(ADJUSTMENT_DRIFT) + 单股全量重拉；复检仍不一致 → ADJUSTMENT_DRIFT_UNRESOLVED）；
 * 4. §4.8 连板派生（升序 bars：is_limit_up ? 昨日 streak+1 : 0）+ IPO 首 5 日守卫（stock_info.ipo_date）；
 * 5. upsert（ON CONFLICT DO UPDATE 幂等）+ data_source 大写记录。
 *
 * saveBatch / saveIndexBatch / findMaxDate 均已实现（GREEN）。
 * Python 客户端用 Fake（规避 Mockito+协程 Continuation 参数匹配问题）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockHistoryServiceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var historyRepository: StockHistoryRepository

    @Autowired
    private lateinit var dataQualityLogRepository: DataQualityLogRepository

    @Autowired
    private lateinit var calendarRepository: TradingCalendarRepository

    @Autowired
    private lateinit var indexHistoryRepository: com.soros.v2.repository.IndexHistoryRepository

    /** Fake Python 客户端：可设定 fetchDailyBarsBatch 响应并计数（规避 Mockito 对 suspend 方法 Continuation 参数匹配问题） */
    private class FakePythonClient : PythonDataServiceClient {
        var fetchDailyBarsBatchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        var fetchDailyBarsBatchCalls = 0

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<com.soros.v2.service.dto.StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchDailyBarsBatchCalls++
            return fetchDailyBarsBatchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    private fun service(
        pythonClient: FakePythonClient = FakePythonClient(),
        stockInfoService: StockInfoService = Mockito.mock(StockInfoService::class.java),
    ): StockHistoryServiceImpl {
        val calendarService = TradingCalendarServiceImpl(calendarRepository, pythonClient)
        val metrics = CollectMetrics(SimpleMeterRegistry())
        return StockHistoryServiceImpl(
            historyRepository, dataQualityLogRepository, calendarService,
            pythonClient, com.soros.v2.config.DataCollectionProperties(), stockInfoService, metrics,
            indexHistoryRepository,
        )
    }

    /**
     * 构造一根行内自洽日K（配合 CRITICAL-A 修复后 DataValidator.validate() 严格校验）。
     * 默认 high=max(open,close)+0.5、low=min(open,close)-0.5；用例可显式传 high/low 覆盖。
     * prevClose 默认 null 跳过交叉校验，便于隔离链式/连板逻辑。
     */
    private fun bar(
        date: LocalDate,
        close: BigDecimal,
        changePct: BigDecimal,
        prevClose: BigDecimal? = null,
        open: BigDecimal = BigDecimal("12.0000"),
        high: BigDecimal? = null,
        low: BigDecimal? = null,
    ) = DailyBar(
        date = date,
        code = "600000",
        open = open,
        high = high ?: maxOf(open, close).add(BigDecimal("0.5000")),
        low = low ?: minOf(open, close).subtract(BigDecimal("0.5000")),
        close = close,
        volume = 1_000_000L,
        amount = BigDecimal("12000000.0000"),
        changePercent = changePct,
        turnover = BigDecimal("2.0000"),
        prevClose = prevClose,
    )

    // ==================== 正常流程（已实现，GREEN） ====================

    @Test
    fun `testFindMaxDate returnsLatest`() {
        // given: 三个交易日
        historyRepository.saveAll(
            listOf(
                StockHistory().apply { code = "600000"; tradeDate = LocalDate.of(2026, 9, 28); close = BigDecimal("10.0000") },
                StockHistory().apply { code = "600000"; tradeDate = LocalDate.of(2026, 9, 29); close = BigDecimal("10.5000") },
                StockHistory().apply { code = "600000"; tradeDate = LocalDate.of(2026, 9, 30); close = BigDecimal("11.0000") },
            ),
        )

        // when
        val max = service().findMaxDate("600000")

        // then: 采集水位（§4.7 滚动窗口起点 findMaxDate+1）
        assertEquals(LocalDate.of(2026, 9, 30), max, "findMaxDate 应返回该股最大交易日")
    }

    @Test
    fun `testFindMaxDate emptyTable returnsNull`() {
        // given: 空表
        // when & then
        assertNull(service().findMaxDate("600000"), "空表返回 null（采集初始态）")
    }

    // ==================== 正常流程（骨架，RED：契约） ====================

    @Test
    fun `testSaveBatch successPersistsWithLimitUpAndStreakAndSource`() {
        // given: 主板两根连板（不复权 change_pct=9.98 ≥ 9.9）
        val bars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("13.2000"), BigDecimal("9.98")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("14.5000"), BigDecimal("9.98")),
        )

        // when
        val result = runBlocking { service().saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 返回结果口径（§4.6/§4.7）
        assertEquals(2, result.totalRows, "入库行数=2")
        assertEquals("600000", result.code, "result.code 携带")
        assertFalse(result.driftDetected, "无漂移")
        assertFalse(result.refetched, "无重拉")
        assertEquals(0, result.invalidRowsSkipped, "无非法行跳过")

        // then: DB 行回读——涨停布尔 + 连板派生 + data_source 大写
        val day1 = historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 28))
            ?: error("9-28 应已入库")
        val day2 = historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29))
            ?: error("9-29 应已入库")
        assertTrue(day1.isLimitUp, "9-28 主板 9.98% 涨停")
        assertTrue(day2.isLimitUp, "9-29 主板 9.98% 涨停")
        assertEquals(1, day1.limitUpStreak.toInt(), "9-28 首板 streak=1（无前一日行，停牌断板重计）")
        assertEquals(2, day2.limitUpStreak.toInt(), "9-29 连板 streak=2（批内前一日 streak+1）")
        assertEquals("BAOSTOCK", day1.dataSource, "data_source 大写记录（BAOSTOCK）")
        assertEquals("BAOSTOCK", day2.dataSource, "data_source 大写记录")
    }

    @Test
    fun `testSaveBatch invalidRowSkippedAndNotPersisted`() {
        // given: 非法行（low > high）+ 合法行混批（§4.7 防线②：非法行跳过不落库，不抛中断整批）
        val invalid = DailyBar(
            date = LocalDate.of(2026, 9, 28), code = "600000",
            open = BigDecimal("10.0000"), high = BigDecimal("9.0000"),
            low = BigDecimal("11.0000"), close = BigDecimal("10.0000"),
            volume = 1_000_000L, amount = BigDecimal("10000000.0000"),
            changePercent = BigDecimal("0.00"), turnover = BigDecimal("1.0000"), prevClose = null,
        )
        val valid = bar(LocalDate.of(2026, 9, 29), BigDecimal("13.2000"), BigDecimal("9.98"))

        // when
        val result = runBlocking { service().saveBatch("600000", listOf(invalid, valid), DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 非法行跳过计数 + 只落合法行
        assertEquals(1, result.invalidRowsSkipped, "非法行跳过 1 行")
        assertEquals(1, result.totalRows, "只入库合法行 1 行")
        assertNull(historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 28)), "非法行不落库")
        assertNotNull(historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29)), "合法行入库")

        // then: 被拒行落 data_quality_log VALIDATION_REJECTED（issueType+code+detail+source）
        val rejected = dataQualityLogRepository.findAll()
            .filter { it.issueType == QualityIssueType.VALIDATION_REJECTED.name && it.code == "600000" }
        assertTrue(rejected.isNotEmpty(), "非法行应落 VALIDATION_REJECTED 质量日志（§4.7 防线②留痕）")
        assertTrue(
            rejected.any { it.detail?.contains("date=2026-09-28") == true },
            "detail 携带被拒行日期（date=2026-09-28）",
        )
        assertEquals(DataSourceType.BAOSTOCK.name, rejected.first().source, "source 大写记录（BAOSTOCK）")
    }

    @Test
    fun `testSaveBatch driftDetectedRefetchesAndLogsAdjustmentDrift`() {
        // given: 批内相邻 bar 漂移（9-29 prevClose=10.50 vs 9-28 close=10.00 → |0.50|>0.0525）
        val driftBars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("10.0000"), BigDecimal("0.00")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("10.5000"), BigDecimal("0.00"), prevClose = BigDecimal("10.5000")),
        )
        val fake = FakePythonClient()
        // 重拉返回正确批次（qfq 整条重置：9-28 close=9.00 / 9-29 close=9.50 prevClose=9.00）
        fake.fetchDailyBarsBatchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf(
                "600000" to StockBarsResult(
                    "baostock", 2, listOf(
                        bar(LocalDate.of(2026, 9, 28), BigDecimal("9.0000"), BigDecimal("0.00"),
                            open = BigDecimal("9.0000"), high = BigDecimal("9.5000"), low = BigDecimal("8.5000")),
                        bar(LocalDate.of(2026, 9, 29), BigDecimal("9.5000"), BigDecimal("5.56"),
                            prevClose = BigDecimal("9.0000"),
                            open = BigDecimal("9.5000"), high = BigDecimal("10.0000"), low = BigDecimal("9.0000")),
                    ),
                ),
            ),
            failed = emptyList(),
        )

        // when
        val result = runBlocking { service(pythonClient = fake).saveBatch("600000", driftBars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 漂移命中 → 重拉 → 返回口径
        assertTrue(result.driftDetected, "§4.6 检测到除权漂移")
        assertTrue(result.refetched, "触发单股全量重拉")
        assertEquals(1, fake.fetchDailyBarsBatchCalls, "重拉调用一次（单 code 全量）")

        // then: 质量日志落 ADJUSTMENT_DRIFT
        val logs = dataQualityLogRepository.findAll().filter { it.issueType == "ADJUSTMENT_DRIFT" && it.code == "600000" }
        assertTrue(logs.isNotEmpty(), "data_quality_log 应落 ADJUSTMENT_DRIFT（date/两值/来源）")

        // then: DB 行被重拉批次覆盖（整条序列重置为最新复权口径）
        val day1 = historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 28))
            ?: error("9-28 应存在")
        val day2 = historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29))
            ?: error("9-29 应存在")
        assertEquals(BigDecimal("9.0000"), day1.close, "重拉后 9-28 close 重置为 9.00")
        assertEquals(BigDecimal("9.5000"), day2.close, "重拉后 9-29 close 重置为 9.50")
    }

    @Test
    fun `testSaveBatch driftUnresolvedLogsUnresolved`() {
        // given: 同批漂移 + 重拉返回仍漂移的批次（复检仍不一致）
        val driftBars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("10.0000"), BigDecimal("0.00")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("10.5000"), BigDecimal("0.00"), prevClose = BigDecimal("10.5000")),
        )
        val fake = FakePythonClient()
        fake.fetchDailyBarsBatchResponse = DailyBarsBatchResponse(
            status = "ok",
            results = mapOf("600000" to StockBarsResult("baostock", 2, driftBars)),
            failed = emptyList(),
        )

        // when
        val result = runBlocking { service(pythonClient = fake).saveBatch("600000", driftBars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 复检仍不一致 → ADJUSTMENT_DRIFT_UNRESOLVED（人工介入）
        assertTrue(result.driftDetected, "首次漂移命中")
        val unresolved = dataQualityLogRepository.findAll().filter { it.issueType == "ADJUSTMENT_DRIFT_UNRESOLVED" && it.code == "600000" }
        assertTrue(unresolved.isNotEmpty(), "重拉复检仍不一致应落 ADJUSTMENT_DRIFT_UNRESOLVED（人工介入）")
    }

    @Test
    fun `testSaveBatch driftRefetchEmptyLogsNoBarTodayAndZeroRows`() {
        // given: 批内相邻 bar 漂移 + 重拉返回空（停牌/无数据，§4.6 重拉降级不崩）
        val driftBars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("10.0000"), BigDecimal("0.00")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("10.5000"), BigDecimal("0.00"), prevClose = BigDecimal("10.5000")),
        )
        val fake = FakePythonClient()
        fake.fetchDailyBarsBatchResponse = DailyBarsBatchResponse(status = "ok", results = emptyMap(), failed = emptyList())

        // when
        val result = runBlocking { service(pythonClient = fake).saveBatch("600000", driftBars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 漂移命中 → 重拉返回空 → NO_BAR_TODAY + 0 行入库（停牌正常现象，不告警不置位）
        assertTrue(result.driftDetected, "§4.6 检测到除权漂移")
        assertTrue(result.refetched, "触发单股全量重拉")
        assertEquals(1, fake.fetchDailyBarsBatchCalls, "重拉调用一次（单 code 全量）")
        assertEquals(0, result.totalRows, "重拉返回空 → 无入库行")
        val noBar = dataQualityLogRepository.findAll()
            .filter { it.issueType == QualityIssueType.NO_BAR_TODAY.name && it.code == "600000" }
        assertTrue(noBar.isNotEmpty(), "重拉空应落 NO_BAR_TODAY（当日无行，停牌正常现象）")
        assertNull(historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 28)), "重拉空后 9-28 不落库")
        assertNull(historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29)), "重拉空后 9-29 不落库")
    }

    @Test
    fun `testSaveBatch streakDerivation ascendingLimitUp`() {
        // given: 三连板（主板 9.98%）
        val bars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("13.2000"), BigDecimal("9.98")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("14.5000"), BigDecimal("9.98")),
            bar(LocalDate.of(2026, 9, 30), BigDecimal("16.0000"), BigDecimal("9.98")),
        )

        // when
        runBlocking { service().saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: §4.8 连板派生 1→2→3（升序遍历，批内前一日 streak+1）
        assertEquals(1, historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 28))!!.limitUpStreak.toInt(), "首板=1")
        assertEquals(2, historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29))!!.limitUpStreak.toInt(), "连板=2")
        assertEquals(3, historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30))!!.limitUpStreak.toInt(), "连板=3")
    }

    @Test
    fun `testSaveBatch streakBrokenResetsToZero`() {
        // given: 两连板后断板（9-30 非涨停）
        val bars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("13.2000"), BigDecimal("9.98")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("14.5000"), BigDecimal("9.98")),
            bar(LocalDate.of(2026, 9, 30), BigDecimal("14.5000"), BigDecimal("2.00")),
        )

        // when
        runBlocking { service().saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 断板归 0（§4.8：is_limit_up ? 昨日+1 : 0）
        assertEquals(2, historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29))!!.limitUpStreak.toInt(), "9-29 连板=2")
        assertEquals(0, historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30))!!.limitUpStreak.toInt(), "9-30 断板 streak=0")
        assertFalse(historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30))!!.isLimitUp, "9-30 非涨停")
    }

    @Test
    fun `testSaveBatch ipoFirstFiveDaysGuardForcesNoLimitUp`() {
        // given: IPO 首 5 日守卫——ipo_date=2026-09-28，bar 2026-09-29 仅 1 个交易日后
        val stockInfo = Mockito.mock(StockInfoService::class.java)
        Mockito.`when`(stockInfo.findByCode("600000")).thenReturn(
            StockInfo().apply {
                code = "600000"
                board = "MAIN"
                ipoDate = LocalDate.of(2026, 9, 28)
            },
        )
        val bars = listOf(bar(LocalDate.of(2026, 9, 29), BigDecimal("13.2000"), BigDecimal("9.98")))

        // when
        runBlocking { service(pythonClient = FakePythonClient(), stockInfoService = stockInfo)
            .saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 上市首 5 日无涨跌幅限制，is_limit_up 强制 false、streak=0（§4.8 守卫，修 §4.5 新股误标）
        val row = historyRepository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29))
            ?: error("9-29 应入库")
        assertFalse(row.isLimitUp, "IPO 首 5 日守卫强制 is_limit_up=false")
        assertEquals(0, row.limitUpStreak.toInt(), "IPO 首 5 日守卫 streak=0")
    }

    @Test
    fun `testSaveBatch upsertIdempotentNoDuplicate`() {
        // given: 同 code+date 重复入库（prevClose=null → 链式校验跳过，幂等聚焦 UNIQUE(code,trade_date)）
        val bars = listOf(bar(LocalDate.of(2026, 9, 30), BigDecimal("13.2000"), BigDecimal("9.98")))
        val svc = service()

        // when: 两次 saveBatch
        val first = runBlocking { svc.saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }
        val second = runBlocking { svc.saveBatch("600000", bars, DataSourceType.BAOSTOCK, Board.MAIN) }

        // then: 幂等 upsert——每次计 1 行入库（含覆盖），DB 不产生重复行
        assertEquals(1, first.totalRows, "首次入库 1 行")
        assertEquals(1, second.totalRows, "二次 upsert 覆盖计 1 行")
        assertEquals(1, historyRepository.count(), "UNIQUE(code,trade_date) 幂等：DB 仅 1 行")
    }

    @Test
    fun `testSaveIndexBatch upsertsIndexHistory`() {
        // given: 指数日K（code 带前缀 sh000001，qfq 口径同 stock_history）
        val bars = listOf(
            bar(LocalDate.of(2026, 9, 28), BigDecimal("3000.0000"), BigDecimal("0.50")),
            bar(LocalDate.of(2026, 9, 29), BigDecimal("3015.0000"), BigDecimal("0.50")),
        )

        // when
        val written = runBlocking { service().saveIndexBatch("sh000001", bars, DataSourceType.AKSHARE) }

        // then: 返回写入行数，DB 落 2 行 + data_source 大写
        assertEquals(2, written, "返回写入行数=2")
        val rows = indexHistoryRepository.findByCodeAndTradeDateBetween(
            "sh000001", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29),
        )
        assertEquals(2, rows.size, "index_history 落 2 行（upsert 幂等，UNIQUE(code,trade_date)）")
        assertEquals("AKSHARE", rows[0].dataSource, "data_source 大写记录")
        assertEquals("sh000001", rows[0].code, "code 带前缀值口径特例")
    }
}
