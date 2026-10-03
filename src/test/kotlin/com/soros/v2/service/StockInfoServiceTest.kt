package com.soros.v2.service

import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockListDto
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §4.4/§2.2 StockInfoService 契约测试（@DataJpaTest + TestContainers PG16）。
 *
 * - findByCode 已实现（委托 repository）→ GREEN；
 * - refreshStockList / saveBenchmarkIndices 为骨架（TODO）→ RED，定义契约。
 *
 * 隔离铁律（§2.2/§4.4）：is_st / delisted 仅用于"识别并排除"，禁止作为业务可选项；
 * 北交所（83/87/43/920 前缀）不采集；仅 MAIN/GEM/STAR 入有效列表。
 * Python 客户端用 Fake（规避 Mockito 对 suspend 方法 Continuation 参数匹配问题）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockInfoServiceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var stockInfoRepository: StockInfoRepository

    @Autowired
    private lateinit var stockIndexRepository: StockIndexRepository

    /** Fake Python 客户端：可设定 fetchStockList 返回列表 */
    private class FakePythonClient : PythonDataServiceClient {
        var stockList: List<StockListDto> = emptyList()

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = stockList
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse =
            DailyBarsBatchResponse("ok")
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    private fun service(pythonClient: FakePythonClient = FakePythonClient()): StockInfoServiceImpl =
        StockInfoServiceImpl(stockInfoRepository, stockIndexRepository, pythonClient)

    private fun saveNormalStock(code: String = "600000"): StockInfo = stockInfoRepository.save(
        StockInfo().apply {
            this.code = code
            this.name = "浦发银行"
            this.market = "SH"
            this.board = "MAIN"
            this.isSt = false
            this.delisted = false
            this.ipoDate = LocalDate.of(1999, 11, 10)
            this.searchKey = "600000 浦发银行 pufayinhang pfyh"
        },
    )

    // ==================== 正常流程（已实现，GREEN） ====================

    @Test
    fun `testFindByCode returnsSavedRow`() {
        // given: 落一只正常股
        saveNormalStock()

        // when
        val found = service().findByCode("600000")

        // then
        assertNotNull(found, "已落库股票 findByCode 应命中")
        assertEquals("MAIN", found!!.board, "board 回读")
    }

    @Test
    fun `testFindByCode missReturnsNull`() {
        // given: 空表
        // when & then
        assertNull(service().findByCode("999999"), "未命中返回 null")
    }

    // ==================== 正常流程（骨架，RED：契约） ====================

    @Test
    fun `testRefreshStockList filtersStDelistedBseAndKeepsMainGemStar`() {
        // given: Python 列表含 正常主板/ST/退市/北交所/创业板
        val fake = FakePythonClient().apply {
            stockList = listOf(
                StockListDto("600000", "浦发银行", "SH", "MAIN", false, false),
                StockListDto("000587", "*ST金洲", "SH", "MAIN", true, false),
                StockListDto("600005", "武钢股份", "SH", "MAIN", false, true),
                StockListDto("830001", "北交所股", "BJ", "BJ", false, false),
                StockListDto("300750", "宁德时代", "SZ", "GEM", false, false),
            )
        }

        // when
        val valid = runBlocking { service(fake).refreshStockList() }

        // then: 返回仅 MAIN/GEM/STAR 有效股票（排除 ST/退市/北交所）
        assertEquals(listOf("600000", "300750"), valid.map { it.code }, "仅 MAIN/GEM/STAR 有效股票返回（ST/退市/北交所全排除）")

        // then: 已 upsert 有效股，ST/退市/北交所不入库
        assertNotNull(stockInfoRepository.findByCode("600000"), "600000 已入库")
        assertNotNull(stockInfoRepository.findByCode("300750"), "300750 已入库")
        assertNull(stockInfoRepository.findByCode("000587"), "ST 股被识别并排除，不入库（隔离铁律）")
        assertNull(stockInfoRepository.findByCode("600005"), "退市股不入库")
        assertNull(stockInfoRepository.findByCode("830001"), "北交所（83 前缀）不入库")
    }

    @Test
    fun `testRefreshStockList fillsBoardAndSearchKey`() {
        // given: 有效股 board 回填 + search_key 构造（小写 name+全拼+拼音首字母）
        val fake = FakePythonClient().apply {
            stockList = listOf(StockListDto("300750", "宁德时代", "SZ", "GEM", false, false))
        }

        // when
        val valid = runBlocking { service(fake).refreshStockList() }

        // then: board 回填 GEM；search_key 非空（下游 pg_trgm 模糊搜索依赖）
        assertEquals(1, valid.size, "仅 1 只有效")
        assertEquals("GEM", valid[0].board, "board 回填 GEM")
        assertNotNull(valid[0].searchKey, "search_key 回填非空（/stock-search 消费）")
        assertTrue(
            valid[0].searchKey!!.lowercase().contains("ningdeshidai") || valid[0].searchKey!!.contains("300750"),
            "search_key 含代码或小写全拼",
        )
    }

    @Test
    fun `testSaveBenchmarkIndices upsertsFiveIndices`() {
        // given: 空 stock_index 表
        assertEquals(0, stockIndexRepository.count(), "前置：stock_index 空表")

        // when
        val written = service().saveBenchmarkIndices()

        // then: 5 个基准指数全部入库（code 带前缀值口径特例 sh000001）
        assertEquals(5, written, "首次写入 5 行")
        assertEquals(5, stockIndexRepository.count(), "5 个基准指数落库")
        assertNotNull(stockIndexRepository.findByCode("sh000001"), "上证指数 sh000001 入库")
        assertNotNull(stockIndexRepository.findByCode("sz399001"), "深证成指 sz399001 入库")
        assertNotNull(stockIndexRepository.findByCode("sh000300"), "沪深300 sh000300 入库")
        assertNotNull(stockIndexRepository.findByCode("sh000905"), "中证500 sh000905 入库")
        assertNotNull(stockIndexRepository.findByCode("sz399006"), "创业板指 sz399006 入库")
    }

    @Test
    fun `testSaveBenchmarkIndices idempotentUpsert`() {
        // given: 已入库一次
        val svc = service()
        svc.saveBenchmarkIndices()

        // when: 再次执行（幂等 upsert，ON CONFLICT DO UPDATE）
        val written = svc.saveBenchmarkIndices()

        // then: 行数不重复（code UNIQUE），返回写入行数=5
        assertEquals(5, written, "幂等 upsert 仍返回 5 行写入（覆盖更新）")
        assertEquals(5, stockIndexRepository.count(), "不产生重复行")
    }
}
