package com.soros.v2.service.manual

import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.StockHistoryService
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
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.service.manual.dto.ManualStockInfoRequest
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §11.4 ManualDataService 契约测试（@DataJpaTest + TestContainers PG16，真实 stock_info/stock_index 仓储）。
 *
 * 契约（接口/实现 KDoc / §11.4）：
 * - upsertStockInfo：is_st=true 直接拒绝（ST 隔离铁律）；board 值域校验（MAIN/GEM/STAR，未知抛 BusinessException）；
 *   null board 降级 MAIN；新行创建 + 既有行更新（code UNIQUE 幂等）；
 * - replayDaily：走 §4.4 saveBatch 正道（Python 拉取 → saveBatch，source/board 映射）；无该股/无数据返回 null；
 * - info/index 全量列表 + stock_index upsert（code UNIQUE 幂等）。
 *
 * saveBatch 业务本身由 StockHistoryServiceTest 覆盖，本测试只验 ManualDataServiceImpl 装配链路（Fake Python + Fake History）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ManualDataServiceTest {

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

    @Autowired
    private lateinit var entityManager: TestEntityManager

    /** Fake Python 客户端：记录 fetchDailyBarsBatch 调用并支持失败注入 */
    private class FakePythonClient : PythonDataServiceClient {
        var batchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        var fetchBatchCalls = 0

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            fetchBatchCalls++
            return batchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    /** Fake History 服务：记录 saveBatch 调用（source/board 映射断言用） */
    private class FakeHistoryService : StockHistoryService {
        val saveBatchCalls = mutableListOf<Triple<String, DataSourceType, Board>>()
        var saveBatchResult: SaveBatchResult = SaveBatchResult("", 0, false, false, 0)

        override fun findMaxDate(code: String): LocalDate? = null
        override suspend fun saveBatch(
            code: String,
            bars: List<DailyBar>,
            source: DataSourceType,
            board: Board,
        ): SaveBatchResult {
            saveBatchCalls.add(Triple(code, source, board))
            return saveBatchResult
        }

        override suspend fun saveIndexBatch(code: String, bars: List<DailyBar>, source: DataSourceType): Int = bars.size
    }

    private fun service(
        python: FakePythonClient = FakePythonClient(),
        history: FakeHistoryService = FakeHistoryService(),
    ): ManualDataServiceImpl =
        ManualDataServiceImpl(python, history, stockInfoRepository, stockIndexRepository)

    private fun bar(date: LocalDate = LocalDate.of(2026, 9, 30)) = DailyBar(
        date = date,
        code = "600000",
        open = BigDecimal("12.0000"),
        high = BigDecimal("13.5000"),
        low = BigDecimal("11.5000"),
        close = BigDecimal("13.2000"),
        volume = 1_000_000L,
        amount = BigDecimal("13000000.0000"),
        changePercent = BigDecimal("9.98"),
        turnover = BigDecimal("2.00"),
        prevClose = BigDecimal("12.0000"),
    )

    // ==================== upsertStockInfo：ST 拒绝 / board 值域 ====================

    @Test
    fun `testUpsertStockInfo isStRejectedWithBusinessException`() {
        // given: 手动补数入口带 is_st=true（ST 隔离铁律：is_st 仅用于识别排除）
        val request = ManualStockInfoRequest(code = "000587", isSt = true)

        // when & then: 直接拒绝，不允许 ST 股票录入
        assertThrows(BusinessException::class.java) {
            service().upsertStockInfo(request)
        }
    }

    @Test
    fun `testUpsertStockInfo unknownBoardRejected`() {
        // given: board 越界（北交所/B 股不采集，值域 MAIN/GEM/STAR）
        val request = ManualStockInfoRequest(code = "830001", board = "BJ")

        // when & then: Board.fromPython 抛 BusinessException（值域校验）
        assertThrows(BusinessException::class.java) {
            service().upsertStockInfo(request)
        }
    }

    @Test
    fun `testUpsertStockInfo nullBoardDefaultsToMain`() {
        // given: board 缺省（V1 兼容最小集容错）
        val request = ManualStockInfoRequest(code = "600000", name = "浦发银行", market = "SH")

        // when
        val saved = service().upsertStockInfo(request)

        // then: board 默认 MAIN
        assertEquals("MAIN", saved.board, "board 缺省降级 MAIN")
        assertNotNull(stockInfoRepository.findByCode("600000"), "新行已入库")
    }

    // ==================== upsertStockInfo：新建 / 更新 ====================

    @Test
    fun `testUpsertStockInfo newRowCreatedWithGemBoard`() {
        // given: 新建 GEM 股（创业板）
        val request = ManualStockInfoRequest(code = "300750", name = "宁德时代", market = "SZ", board = "GEM")

        // when
        val saved = service().upsertStockInfo(request)

        // then: 创建成功 + board 落 GEM
        assertEquals("GEM", saved.board, "board 落 GEM")
        assertEquals("300750", saved.code, "code 回读")
        val found = stockInfoRepository.findByCode("300750")
        assertNotNull(found, "新行落库")
        assertEquals("宁德时代", found!!.name, "name 落库")
        assertTrue(!found.isSt, "is_st 默认 false")
    }

    @Test
    fun `testUpsertStockInfo existingRowUpdatedNotDuplicated`() {
        // given: 已存在 600000（先落一条）
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "600000"
                name = "浦发银行"
                board = "MAIN"
            },
        )
        entityManager.flush()
        entityManager.clear()

        // when: 同 code upsert 改 name
        val updated = service().upsertStockInfo(
            ManualStockInfoRequest(code = "600000", name = "浦发银行(改名)"),
        )

        // then: 更新非新增（code UNIQUE 幂等），行数保持 1
        assertEquals("浦发银行(改名)", updated.name, "既有行 name 更新")
        assertEquals(1, stockInfoRepository.count(), "同 code upsert 不产生重复行")
    }

    // ==================== replayDaily：saveBatch 正道 ====================

    @Test
    fun `testReplayDaily missingStockReturnsNullAndNoPythonCall`() {
        // given: 无该股 stock_info（未采集）
        val python = FakePythonClient()

        // when
        val result = kotlinx.coroutines.runBlocking {
            service(python).replayDaily("999999", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        }

        // then: 返回 null 且不拉 Python（无该股直接跳过）
        assertNull(result, "无该股返回 null")
        assertEquals(0, python.fetchBatchCalls, "无该股不触发 Python 拉取")
    }

    @Test
    fun `testReplayDaily isStRejectedWithBusinessException`() {
        // given: ST 股已入库（ST 隔离铁律：is_st 仅用于识别排除，手动重跑同口径拒绝）
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "000587"
                name = "*ST金洲"
                board = "MAIN"
                isSt = true
            },
        )
        val python = FakePythonClient()

        // when & then: 直接拒绝（BusinessException），不触发 Python 拉取
        assertThrows(BusinessException::class.java) {
            kotlinx.coroutines.runBlocking {
                service(python).replayDaily("000587", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
            }
        }
        assertEquals(0, python.fetchBatchCalls, "ST 股拒绝重跑，不触发 Python 拉取")
    }

    @Test
    fun `testReplayDaily delistedRejectedWithBusinessException`() {
        // given: 退市股已入库（缺失≠退市，人工确认才置 true）
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "600005"
                name = "武钢股份"
                board = "MAIN"
                delisted = true
            },
        )
        val python = FakePythonClient()

        // when & then: 退市股拒绝重跑（与 upsert 隔离口径一致）
        assertThrows(BusinessException::class.java) {
            kotlinx.coroutines.runBlocking {
                service(python).replayDaily("600005", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
            }
        }
        assertEquals(0, python.fetchBatchCalls, "退市股拒绝重跑，不触发 Python 拉取")
    }

    @Test
    fun `testReplayDaily noDataReturnsNull`() {
        // given: 有 stock_info 但 Python 无该股数据（空 data）
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "600000"
                name = "浦发银行"
                board = "MAIN"
            },
        )
        val python = FakePythonClient().apply {
            batchResponse = DailyBarsBatchResponse(
                status = "ok",
                results = mapOf("600000" to StockBarsResult("baostock", 0, emptyList())),
            )
        }

        // when
        val result = kotlinx.coroutines.runBlocking {
            service(python).replayDaily("600000", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        }

        // then: 无数据返回 null（增量锚点语义，不炸）
        assertNull(result, "无数据返回 null")
    }

    @Test
    fun `testReplayDaily goesThroughSaveBatchWithMappedSourceAndBoard`() {
        // given: 有 MAIN 主板 stock_info + Python 返回 1 根 baostock 日 K
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "600000"
                name = "浦发银行"
                board = "MAIN"
            },
        )
        val python = FakePythonClient().apply {
            batchResponse = DailyBarsBatchResponse(
                status = "ok",
                results = mapOf("600000" to StockBarsResult("baostock", 1, listOf(bar()))),
            )
        }
        val history = FakeHistoryService().apply {
            saveBatchResult = SaveBatchResult("600000", 1, false, false, 0)
        }

        // when
        val result = kotlinx.coroutines.runBlocking {
            service(python, history).replayDaily("600000", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        }

        // then: 走 §4.4 saveBatch 正道，source 小写→DataSourceType、board→Board 正确映射
        assertNotNull(result, "返回 saveBatch 结果")
        assertEquals(1, result!!.totalRows, "写入 1 行")
        assertEquals(1, history.saveBatchCalls.size, "saveBatch 恰调 1 次")
        val call = history.saveBatchCalls.first()
        assertEquals("600000", call.first, "saveBatch code=600000")
        assertEquals(DataSourceType.BAOSTOCK, call.second, "source baostock→BAOSTOCK（failover 可见性大写）")
        assertEquals(Board.MAIN, call.third, "board MAIN→Board.MAIN")
    }

    @Test
    fun `testReplayDaily gemBoardMappedFromStockInfo`() {
        // given: GEM 创业板 stock_info + Python 返回 akshare 日 K
        stockInfoRepository.save(
            com.soros.v2.entity.StockInfo().apply {
                code = "300750"
                name = "宁德时代"
                board = "GEM"
            },
        )
        val python = FakePythonClient().apply {
            batchResponse = DailyBarsBatchResponse(
                status = "ok",
                results = mapOf("300750" to StockBarsResult("akshare", 1, listOf(bar()))),
            )
        }
        val history = FakeHistoryService()

        // when
        kotlinx.coroutines.runBlocking {
            service(python, history).replayDaily("300750", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        }

        // then: board 从 stock_info.board 映射（GEM→Board.GEM，saveBatch 双创 20cm 阈值依赖）
        val call = history.saveBatchCalls.first()
        assertEquals(DataSourceType.AKSHARE, call.second, "source akshare→AKSHARE")
        assertEquals(Board.GEM, call.third, "board GEM→Board.GEM（§4.5 双创阈值依赖）")
    }

    // ==================== listAll / stock_index ====================

    @Test
    fun `testListAllStockInfo returnsAllRows`() {
        // given: 2 只 stock_info
        service().upsertStockInfo(ManualStockInfoRequest(code = "600000", name = "浦发银行", market = "SH"))
        service().upsertStockInfo(ManualStockInfoRequest(code = "300750", name = "宁德时代", market = "SZ", board = "GEM"))
        entityManager.flush()
        entityManager.clear()

        // when
        val all = service().listAllStockInfo()

        // then
        assertEquals(setOf("600000", "300750"), all.map { it.code }.toSet(), "全量 stock_info 列表")
    }

    @Test
    fun `testUpsertStockIndex newAndUpdateIdempotent`() {
        // given: 新建上证指数（code 带前缀值口径特例 sh000001）
        val created = service().upsertStockIndex("sh000001", "上证指数")

        // then
        assertEquals("sh000001", created.code, "指数 code 带前缀")
        assertEquals(1, stockIndexRepository.count(), "新建 1 行")

        // when: 同 code 更新 name
        val updated = service().upsertStockIndex("sh000001", "上证指数(改名)")

        // then: 更新非新增
        assertEquals("上证指数(改名)", updated.name, "name 更新")
        assertEquals(1, stockIndexRepository.count(), "code UNIQUE 幂等不重复")
    }

    @Test
    fun `testListAllStockIndex returnsAllRows`() {
        // given: 2 个指数
        service().upsertStockIndex("sh000001", "上证指数")
        service().upsertStockIndex("sz399001", "深证成指")
        entityManager.flush()
        entityManager.clear()

        // when
        val all = service().listAllStockIndex()

        // then
        assertEquals(setOf("sh000001", "sz399001"), all.map { it.code }.toSet(), "全量 stock_index 列表")
    }
}
