package com.soros.v2.integration

import com.soros.v2.config.BackfillProperties
import com.soros.v2.job.BackfillJob
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryGapCheckRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.BackfillPlanService
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
import com.soros.v2.service.dto.BatchItem
import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.util.CollectMetrics
import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 回填重跑计划 SQL/端到端集成测试（@SpringBootTest + TestContainers PG16 + Flyway V1..V7 迁移）。
 *
 * 参考 BackfillSqlIntegrationTest 既有模式（TestContainers + 真 Flyway migrate + 真 SQL）。
 * 覆盖（Architect 设计 §7.3 用例 30-33）：
 * 30. Flyway V7 后 stock_history_gap_check 表存在 + upsert 幂等 + exact-match 查询；
 * 31. aggregateCoverageByCodes：造码造行 → 聚合 min/max/n_rows 正确；无行 code 不在结果集；
 * 32. findMissingDateIslands：calendar 有交易日 A/B/C，stock_history 缺 B → 输出 [B,B]；
 *     缺 B/C 连续 → [B,C] 单岛；
 * 33. 端到端（Fake Python + 真实 DB）：BackfillJob.run 全齐票零请求、头/尾/洞票按段拉取、
 *     gap_check 落库、MID 排除生效。
 *
 * 红线（预期红）：BackfillPlanService / StockHistoryGapCheckRepository / BackfillClassifier /
 * StockHistoryRepository 新查询 / BackfillJob 新构造器实现后才可编译与运行。
 */
@SpringBootTest
@Testcontainers
class BackfillRerunPlanIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var stockHistoryRepository: StockHistoryRepository

    @Autowired
    private lateinit var stockInfoRepository: StockInfoRepository

    @Autowired
    private lateinit var gapCheckRepository: StockHistoryGapCheckRepository

    @Autowired
    private lateinit var planService: BackfillPlanService

    @Autowired
    private lateinit var backfillProperties: BackfillProperties

    @Autowired
    private lateinit var dataQualityLogRepository: DataQualityLogRepository

    /** 真实 TradingCalendarService 替换为 mock：ensureLoaded 成功（不触 Python），日历数据已直接灌库 */
    @MockitoBean
    private lateinit var calendarService: TradingCalendarService

    // 2026-01-05(Mon) ~ 2026-01-08(Thu) 连续交易周
    private val d1 = LocalDate.of(2026, 1, 5)
    private val d2 = LocalDate.of(2026, 1, 6)
    private val d3 = LocalDate.of(2026, 1, 7)
    private val d4 = LocalDate.of(2026, 1, 8)

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE stock_history_stage, stock_history, stock_info, trading_calendar, stock_history_gap_check")
        // @MockitoBean 日历 mock 默认答案即满足：ensureLoaded()=0（不抛，日历已直接灌库）、
        // previousTradingDay=null（不触 Kotlin 抽查对拍派生基）、recentTradingDays=empty。
        // 注：ensureLoaded 为 suspend，不得用 Mockito.when 拦截（Continuation 匹配问题，与 BackfillJobTest 同惯例）。
    }

    // ==================== 构造辅助 ====================

    private fun seedCalendar(vararg dates: LocalDate) {
        dates.forEach { jdbc.update("INSERT INTO trading_calendar (trade_date) VALUES (?)", Date.valueOf(it)) }
    }

    private fun seedInfo(code: String, board: String = "MAIN") {
        jdbc.update(
            "INSERT INTO stock_info (code, name, market, board, is_st, delisted, ipo_date) VALUES (?, ?, 'SH', ?, false, false, NULL)",
            code,
            "测试$code",
            board,
        )
    }

    private fun seedBar(code: String, tradeDate: LocalDate, changePct: String = "1.00") {
        jdbc.update(
            "INSERT INTO stock_history (code, trade_date, change_pct, data_source) VALUES (?, ?, ?, 'BAOSTOCK')",
            code,
            Date.valueOf(tradeDate),
            BigDecimal(changePct),
        )
    }

    private fun seedGapCheck(code: String, from: LocalDate, to: LocalDate, rows: Int = 0) {
        jdbc.update(
            "INSERT INTO stock_history_gap_check (code, seg_from, seg_to, rows_returned) VALUES (?, ?, ?, ?)",
            code,
            Date.valueOf(from),
            Date.valueOf(to),
            rows,
        )
    }

    /** Fake Python：fetchable 码按 item 窗口逐日返回 bar；其余码 0 行（不炸、不进 failed） */
    private class FakePythonClient(private val fetchable: Set<String>) : PythonDataServiceClient {
        val fetchedItems = mutableListOf<BatchItem>()
        var fetchCalls = 0

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchCalls++
            val results = mutableMapOf<String, StockBarsResult>()
            for (item in request.items) {
                fetchedItems.add(item)
                // 对齐真实 Python items 契约（router.py）：每段必落 results——无数据 = 占位 count=0 data=[]，
                // 缺席 results 属异常态（M1 修复后计 failed 不记 verified-empty）
                val bars = if (item.code in fetchable) buildBars(item.code, item.startDate, item.endDate) else emptyList()
                results[item.code] = StockBarsResult("baostock", bars.size, bars)
            }
            return DailyBarsBatchResponse("ok", results, emptyList())
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")

        private fun buildBars(code: String, startDate: String, endDate: String): List<DailyBar> {
            val bars = mutableListOf<DailyBar>()
            var d = LocalDate.parse(startDate)
            val end = LocalDate.parse(endDate)
            while (!d.isAfter(end)) {
                bars.add(
                    DailyBar(
                        date = d,
                        code = code,
                        open = BigDecimal("10.00"),
                        high = BigDecimal("10.50"),
                        low = BigDecimal("9.80"),
                        close = BigDecimal("10.20"),
                        volume = 1_000_000L,
                        amount = BigDecimal("10200000.00"),
                        changePercent = BigDecimal("1.00"),
                        turnover = BigDecimal("1.00"),
                        prevClose = BigDecimal("10.10"),
                    ),
                )
                d = d.plusDays(1)
            }
            return bars
        }
    }

    // ==================== 30. Flyway V7 表结构 + upsert 幂等 + exact-match ====================

    @Test
    fun `testV7 gapCheckTableExistsUpsertIdempotentExactMatch`() {
        // then: V7 迁移已建表
        val tableCount = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'stock_history_gap_check'",
            Int::class.java,
        )
        assertEquals(1, tableCount, "V7 迁移后 stock_history_gap_check 表存在")

        // when: 同一段重复验证（幂等 upsert：ON CONFLICT DO UPDATE 刷新 rows_returned/checked_at）
        gapCheckRepository.upsertVerifiedEmpty("600000", d1, d2, 0)
        gapCheckRepository.upsertVerifiedEmpty("600000", d1, d2, 5)

        // then: 仍 1 行且 rows_returned 被刷新
        val count = jdbc.queryForObject(
            "SELECT count(*) FROM stock_history_gap_check WHERE code = '600000'",
            Long::class.java,
        )
        assertEquals(1L, count, "upsert 幂等不重复插行")
        val rows = jdbc.queryForObject(
            "SELECT rows_returned FROM stock_history_gap_check WHERE code = '600000' AND seg_from = ? AND seg_to = ?",
            Int::class.java,
            Date.valueOf(d1),
            Date.valueOf(d2),
        )
        assertEquals(5, rows, "ON CONFLICT DO UPDATE 刷新 rows_returned")

        // then: exact-match 查询命中/未命中
        assertTrue(
            gapCheckRepository.existsByCodeAndRange("600000", d1, d2),
            "exact-match 命中",
        )
        assertTrue(
            !gapCheckRepository.existsByCodeAndRange("600000", d2, d3),
            "exact-match 未命中（不同区间）",
        )
    }

    // ==================== 31. aggregateCoverageByCodes ====================

    @Test
    fun `testAggregateCoverageByCodes groupsMinMaxRowsAndOmitsNoDataCodes`() {
        // given: 日历 4 日；600001 有 3 行、600002 有 2 行；600099 无行
        seedCalendar(d1, d2, d3, d4)
        seedInfo("600001")
        seedInfo("600002")
        seedInfo("600099")
        seedBar("600001", d1)
        seedBar("600001", d2)
        seedBar("600001", d3)
        seedBar("600002", d1)
        seedBar("600002", d3)

        // when
        val rows = stockHistoryRepository.aggregateCoverageByCodes(listOf("600001", "600002", "600099"))

        // then: 无行 code（600099）不在结果集（NO_DATA 判定依据=不在结果集）
        assertEquals(2, rows.size, "无行 code 不在结果集")
        val byCode = rows.associateBy { it.code }
        assertEquals("600001", byCode["600001"]!!.code, "code 透传")
        assertEquals(d1, byCode["600001"]!!.min_d, "600001 min_d")
        assertEquals(d3, byCode["600001"]!!.max_d, "600001 max_d")
        assertEquals(3L, byCode["600001"]!!.n_rows, "600001 n_rows")
        assertEquals(d1, byCode["600002"]!!.min_d, "600002 min_d")
        assertEquals(d3, byCode["600002"]!!.max_d, "600002 max_d")
        assertEquals(2L, byCode["600002"]!!.n_rows, "600002 n_rows")
    }

    // ==================== 32. findMissingDateIslands ====================

    @Test
    fun `testFindMissingDateIslands singleAndContinuousGaps`() {
        // given: 日历 4 日；600002 缺 d2（单日岛）；600003 缺 d2,d3（连续岛）
        seedCalendar(d1, d2, d3, d4)
        seedInfo("600002")
        seedInfo("600003")
        seedBar("600002", d1)
        seedBar("600002", d3)
        seedBar("600003", d1)
        seedBar("600003", d4)

        // when
        val single = stockHistoryRepository.findMissingDateIslands("600002")
        val continuous = stockHistoryRepository.findMissingDateIslands("600003")

        // then: 单日缺 → 单岛 [d2,d2]；连续缺 → 单岛 [d2,d3]（gaps-and-islands 合并连续）
        assertEquals(1, single.size, "缺 1 日 → 单岛")
        assertEquals(d2, single.single().seg_from, "单岛 from=d2")
        assertEquals(d2, single.single().seg_to, "单岛 to=d2")
        assertEquals(1, continuous.size, "连续缺 2 日 → 单岛（合并）")
        assertEquals(d2, continuous.single().seg_from, "连续岛 from=d2")
        assertEquals(d3, continuous.single().seg_to, "连续岛 to=d3")
    }

    // ==================== 33. 端到端：BackfillJob.run ====================

    @Test
    fun `testEndToEnd completeZeroFetch fullWindowOneSegmentPerCode gapCheckCoveredSkipped`() {
        // given: 日历 4 日 + 5 只（全齐/头缺/尾缺/缺失全被台账覆盖/无数据）
        // 2026-10-05 整窗拉齐语义：部分缺失票产出恰一个整窗段；台账覆盖的缺失日计入已覆盖
        seedCalendar(d1, d2, d3, d4)
        listOf("600001", "600002", "600003", "600004", "600005").forEach { seedInfo(it) }
        seedBar("600001", d1); seedBar("600001", d2); seedBar("600001", d3); seedBar("600001", d4) // 全齐
        seedBar("600002", d3); seedBar("600002", d4)                                             // 缺 d1,d2（2 天）
        seedBar("600003", d1); seedBar("600003", d2); seedBar("600003", d3)                       // 缺 d4（1 天）
        seedBar("600004", d1); seedBar("600004", d2); seedBar("600004", d4)                       // 缺 d3，台账已覆盖
        seedGapCheck("600004", d3, d3)                                                             // 覆盖 1 天 → 未覆盖缺失=0
        // 600005 无数据 → NO_DATA 整窗 [d1,d4]

        val python = FakePythonClient(fetchable = setOf("600002", "600003"))
        val notifier = Mockito.mock(DingTalkNotifier::class.java)
        val metrics = Mockito.mock(CollectMetrics::class.java)
        val replayService = Mockito.mock(SentimentReplayService::class.java)
        Mockito.`when`(replayService.replay(d1, d4)).thenReturn(null)

        val job = BackfillJob(
            pythonClient = python,
            stockInfoRepository = stockInfoRepository,
            stockHistoryRepository = stockHistoryRepository,
            calendarService = calendarService,
            dataQualityLogRepository = dataQualityLogRepository,
            dataSource = dataSource,
            jdbcTemplate = jdbc,
            backfillProperties = backfillProperties,
            metrics = metrics,
            notifier = notifier,
            replayService = replayService,
            planService = planService,
            gapCheckRepository = gapCheckRepository,
        )

        // when
        runBlocking { job.run(d1, d4) { } }

        // then ①: 全齐票 600001 与台账全覆盖票 600004 零请求；缺失票各恰一个整窗段
        val fetched = python.fetchedItems
        assertEquals(
            setOf("600002", "600003", "600005"),
            fetched.map { it.code }.toSet(),
            "全齐/台账全覆盖票不得进入请求（铁律）",
        )
        val byCode = fetched.associateBy { it.code }
        assertEquals(d1.toString() to d4.toString(), byCode["600002"]!!.startDate to byCode["600002"]!!.endDate, "部分缺失 → 整窗段 [d1,d4]")
        assertEquals(1, fetched.count { it.code == "600002" }, "每票恰一段（不做洞级拆分）")
        assertEquals(d1.toString() to d4.toString(), byCode["600003"]!!.startDate to byCode["600003"]!!.endDate, "尾缺 → 整窗段 [d1,d4]")
        assertEquals(d1.toString() to d4.toString(), byCode["600005"]!!.startDate to byCode["600005"]!!.endDate, "无数据整窗 [d1,d4]")

        // then ②: gap_check 落库（600005 HTTP200+0 行 → rows_returned=0）；600004 预置台账行保留
        val recorded = jdbc.queryForMap(
            "SELECT code, seg_from, seg_to, rows_returned FROM stock_history_gap_check WHERE code = '600005'",
        )
        assertEquals(Date.valueOf(d1), recorded["seg_from"], "verified-empty seg_from=d1")
        assertEquals(Date.valueOf(d4), recorded["seg_to"], "verified-empty seg_to=d4")
        assertEquals(0, (recorded["rows_returned"] as Number).toInt(), "verified-empty rows_returned=0")
        val excludedStill = jdbc.queryForObject(
            "SELECT count(*) FROM stock_history_gap_check WHERE code = '600004' AND seg_from = ? AND seg_to = ?",
            Long::class.java,
            Date.valueOf(d3),
            Date.valueOf(d3),
        )
        assertEquals(1L, excludedStill, "台账覆盖票零请求，预置 gap_check 行保留")

        // then ③: 缺失日已补齐落库
        val headFilled = jdbc.queryForObject(
            "SELECT count(*) FROM stock_history WHERE code = '600002' AND trade_date BETWEEN ? AND ?",
            Long::class.java,
            Date.valueOf(d1),
            Date.valueOf(d2),
        )
        assertEquals(2L, headFilled, "600002 缺失日 [d1,d2] 已补齐")
        val tailFilled = jdbc.queryForObject(
            "SELECT count(*) FROM stock_history WHERE code = '600003' AND trade_date = ?",
            Long::class.java,
            Date.valueOf(d4),
        )
        assertEquals(1L, tailFilled, "600003 缺失日 [d4] 已补齐")
    }
}
