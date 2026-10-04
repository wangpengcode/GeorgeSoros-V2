package com.soros.v2.controller

import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.service.StockInfoService
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.service.dto.StockSearchItem
import com.soros.v2.service.manual.ManualDataService
import com.soros.v2.service.manual.dto.ManualHistoryDailyRequest
import com.soros.v2.service.manual.dto.ManualIndexInfoRequest
import com.soros.v2.service.manual.dto.ManualStockInfoRequest
import com.soros.v2.service.manual.dto.ManualStockListRefreshResponse
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §11.4 ManualDataController 6 端点契约测试（直调 controller，Mock ManualDataService 接口）。
 *
 * 契约（类 KDoc / §11.4）：
 * - POST /history/daily：**恒返 ok 语义不能改成 4xx/5xx**（被调用方依赖）——code 给出触发手动重跑，
 *   重跑失败也只降级 detail 带状态，不改变 ok；
 * - GET /history/max/date/{code}：增量锚点（无数据返回 null 字段）；
 * - POST /info/stock：upsert（is_st=true 由 Service 抛 BusinessException 透传）；
 * - GET /info/all、POST /index/info、GET /index/all：列表/upsert 映射。
 *
 * ManualDataService 为 suspend（replayDaily），用 Fake 规避 Mockito Continuation 匹配问题。
 */
class ManualDataControllerTest {

    private class FakeManualDataService : ManualDataService {
        var maxDateResult: LocalDate? = null
        var replayResult: SaveBatchResult? = null
        var replayError: Exception? = null
        var upsertedRequest: ManualStockInfoRequest? = null
        var upsertedInfo: StockInfo = StockInfo().apply {
            code = "600000"
            name = "浦发银行"
            market = "SH"
            board = "MAIN"
            isSt = false
            delisted = false
            ipoDate = LocalDate.of(1999, 11, 10)
            industry = listOf("银行")
            conceptBoards = listOf("上证50")
        }
        var infos: List<StockInfo> = emptyList()
        var indexCode: String = ""
        var indexName: String = ""
        var indexed: StockIndex = StockIndex().apply {
            code = "sh000001"
            name = "上证指数"
        }
        var indexes: List<StockIndex> = emptyList()

        override fun findMaxTradeDate(code: String): LocalDate? = maxDateResult
        override suspend fun replayDaily(code: String, startDate: LocalDate, endDate: LocalDate): SaveBatchResult? {
            replayError?.let { throw it }
            return replayResult
        }
        override fun upsertStockInfo(request: ManualStockInfoRequest): StockInfo {
            upsertedRequest = request
            return upsertedInfo
        }
        override fun listAllStockInfo(): List<StockInfo> = infos
        override fun upsertStockIndex(code: String, name: String): StockIndex {
            indexCode = code
            indexName = name
            return indexed
        }
        override fun listAllStockIndex(): List<StockIndex> = indexes
    }

    private lateinit var fake: FakeManualDataService
    private lateinit var fakeStockInfo: FakeStockInfoService
    private lateinit var controller: ManualDataController

    /** Fake StockInfoService：仅 refreshStockList 有行为，其余成员本控制器不触达 */
    private class FakeStockInfoService : StockInfoService {
        var refreshResult: List<StockInfo> = emptyList()
        var refreshCalls = 0
        override suspend fun refreshStockList(): List<StockInfo> {
            refreshCalls++
            return refreshResult
        }
        override suspend fun backfillIpoDates(): Int = 0
        override suspend fun refreshBoardSnapshot(boardType: BoardType): Int = 0
        override fun findByCode(code: String): StockInfo? = null
        override fun search(query: String, limit: Int): List<StockSearchItem> = emptyList()
        override fun saveBenchmarkIndices(): Int = 0
    }

    @BeforeEach
    fun setUp() {
        fake = FakeManualDataService()
        fakeStockInfo = FakeStockInfoService()
        controller = ManualDataController(fake, fakeStockInfo)
    }

    // ==================== POST /history/daily：恒返 ok ====================

    @Test
    fun `testHistoryDaily nullRequestReturnsOk`() {
        // when: V1 兼容——无 body（request null），恒返 ok
        val response = runBlocking { controller.historyDaily(null) }

        // then: 恒返 ok 语义（被调用方依赖，不能变 4xx/5xx）
        assertEquals("ok", response.status, "恒返 status=ok")
        assertNull(response.detail, "无 code 不触发重跑，detail=null")
    }

    @Test
    fun `testHistoryDaily codeGivenReplayOkDetailCarriesState`() {
        // given: 重跑成功（写入 5 行，无漂移）
        fake.replayResult = SaveBatchResult("600000", 5, false, false, 0)
        val request = ManualHistoryDailyRequest(
            code = "600000",
            startDate = "2026-09-01",
            endDate = "2026-09-30",
        )

        // when
        val response = runBlocking { controller.historyDaily(request) }

        // then: 恒返 ok + detail 带状态（rows/drift）
        assertEquals("ok", response.status, "恒返 status=ok")
        assertTrue(response.detail!!.contains("replay ok code=600000 rows=5 drift=false"), "detail=replay ok code=600000 rows=5 drift=false")
    }

    @Test
    fun `testHistoryDaily replaySkippedStillOk`() {
        // given: 无该股或无数据（replay 返回 null）
        fake.replayResult = null
        val request = ManualHistoryDailyRequest(code = "600000")

        // when
        val response = runBlocking { controller.historyDaily(request) }

        // then: 仍恒返 ok（skip 语义进 detail，不改状态）
        assertEquals("ok", response.status, "replay skip 仍恒返 ok")
        assertTrue(response.detail!!.contains("replay skipped: 无该股或无数据 code=600000"), "detail 带 skip 状态")
    }

    @Test
    fun `testHistoryDaily internalFailureStillOkWithFailureDetail`() {
        // given: 重跑内部抛异常（Python 挂了 / saveBatch 失败）
        fake.replayError = IllegalStateException("saveBatch 失败")
        val request = ManualHistoryDailyRequest(code = "600000")

        // when
        val response = runBlocking { controller.historyDaily(request) }

        // then: **恒返 ok（内部失败也不改状态）**，detail 带失败信息（§11.4 铁律）
        assertEquals("ok", response.status, "内部失败仍恒返 ok（V1 兼容语义不能变 4xx/5xx）")
        assertTrue(response.detail!!.contains("replay failed code=600000"), "detail 带 replay failed 状态")
        assertTrue(response.detail!!.contains("saveBatch 失败"), "detail 含失败原因")
    }

    @Test
    fun `testHistoryDaily cancellationPropagatedNotSwallowed`() {
        // given: 重跑协程被取消（CancellationException 不得被 catch(Exception) 吞掉——协程取消语义）
        fake.replayError = kotlinx.coroutines.CancellationException("manual cancel")
        val request = ManualHistoryDailyRequest(code = "600000")

        // when & then: CancellationException 必须向上传播（不能降级为恒返 ok）
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { controller.historyDaily(request) }
        }
    }

    @Test
    fun `testHistoryDaily invalidDateFallsBackToDefaultsStillOk`() {
        // given: 日期非法（缺省=近 10 自然日~当日，runCatching 容错）
        fake.replayResult = SaveBatchResult("600000", 1, false, false, 0)
        val request = ManualHistoryDailyRequest(code = "600000", startDate = "not-a-date", endDate = "also-bad")

        // when
        val response = runBlocking { controller.historyDaily(request) }

        // then: 非法日期降级默认仍重跑成功，恒返 ok
        assertEquals("ok", response.status, "非法日期容错后仍恒返 ok")
        assertTrue(response.detail!!.contains("replay ok code=600000"), "容错后照常重跑")
    }

    // ==================== GET /history/max/date/{code}：增量锚点 ====================

    @Test
    fun `testHistoryMaxDate returnsMaxDateWhenPresent`() {
        // given: 有数据
        fake.maxDateResult = LocalDate.of(2026, 9, 30)

        // when
        val response = controller.historyMaxDate("600000")

        // then: 增量锚点（语义原样）
        assertEquals("600000", response.code, "code 回显")
        assertEquals("2026-09-30", response.maxTradeDate, "max_trade_date=该股最大交易日")
    }

    @Test
    fun `testHistoryMaxDate noDataReturnsNullField`() {
        // given: 无数据
        fake.maxDateResult = null

        // when
        val response = controller.historyMaxDate("600000")

        // then: max_trade_date=null（增量锚点语义）
        assertNull(response.maxTradeDate, "无数据 max_trade_date=null")
    }

    // ==================== POST /info/stock + GET /info/all ====================

    @Test
    fun `testInfoStock returnsMappedDto`() {
        // when
        val dto = controller.infoStock(ManualStockInfoRequest(code = "600000", name = "浦发银行"))

        // then: Entity → StockInfoDto 映射（字段过命名字典）
        assertEquals("600000", dto.code, "code 映射")
        assertEquals("浦发银行", dto.name, "name 映射")
        assertEquals("MAIN", dto.board, "board 映射")
        assertEquals(LocalDate.of(1999, 11, 10), dto.ipoDate, "ipo_date 映射")
        assertEquals(listOf("银行"), dto.industry, "industry JSON 数组映射")
        assertEquals(listOf("上证50"), dto.conceptBoards, "concept_boards 映射")
        assertTrue(!dto.isSt && !dto.delisted, "is_st/delisted 默认 false")
    }

    @Test
    fun `testInfoAll returnsAllMappedRows`() {
        // given: 2 只 stock_info
        fake.infos = listOf(
            StockInfo().apply { code = "600000"; name = "浦发银行"; board = "MAIN" },
            StockInfo().apply { code = "300750"; name = "宁德时代"; board = "GEM" },
        )

        // when
        val dtos = controller.infoAll()

        // then
        assertEquals(2, dtos.size, "全量映射")
        assertEquals(setOf("600000", "300750"), dtos.map { it.code }.toSet(), "code 集合")
        assertEquals("GEM", dtos.first { it.code == "300750" }.board, "GEM board 映射")
    }

    // ==================== POST /index/info + GET /index/all ====================

    @Test
    fun `testIndexInfo upsertsAndReturnsDto`() {
        // when
        val dto = controller.indexInfo(ManualIndexInfoRequest(code = "sh000001", name = "上证指数"))

        // then: Service 收到 code/name，出参映射 StockIndexDto
        assertEquals("sh000001", fake.indexCode, "Service 收到指数 code")
        assertEquals("上证指数", fake.indexName, "Service 收到指数 name")
        assertEquals("sh000001", dto.code, "出参 code 带前缀特例")
        assertEquals("上证指数", dto.name, "出参 name")
    }

    @Test
    fun `testIndexAll returnsAllMappedRows`() {
        // given: 2 个指数
        fake.indexes = listOf(
            StockIndex().apply { code = "sh000001"; name = "上证指数" },
            StockIndex().apply { code = "sz399001"; name = "深证成指" },
        )

        // when
        val dtos = controller.indexAll()

        // then
        assertEquals(2, dtos.size, "全量映射")
        assertEquals(setOf("sh000001", "sz399001"), dtos.map { it.code }.toSet(), "code 集合")
    }

    // ==================== POST /info/refresh：股票清单手动刷新 ====================

    @Test
    fun `testInfoRefresh returnsCountFromService`() {
        // given: 服务返回 3 只有效股票（已过滤 ST/退市/北交所）
        fakeStockInfo.refreshResult = listOf(stockInfoOf("600000"), stockInfoOf("000001"), stockInfoOf("300750"))

        // when
        val response = runBlocking { controller.refreshStockList() }

        // then: 返回本次刷新后的有效股票数（snake_case 契约 stock_count）
        assertEquals(1, fakeStockInfo.refreshCalls, "调用了 refreshStockList")
        assertEquals(3, response.stockCount, "stock_count=服务返回的有效股票数")
    }

    @Test
    fun `testInfoRefresh emptyListReturnsZeroCount`() {
        // given: 空结果（如 BaoStock 异常返回空，不抛异常路径）
        fakeStockInfo.refreshResult = emptyList()

        // when
        val response = runBlocking { controller.refreshStockList() }

        // then: stock_count=0（正常返回，调用方可感知异常态）
        assertEquals(0, response.stockCount)
    }

    private fun stockInfoOf(code: String) = StockInfo().apply {
        this.code = code
        this.name = "测试股$code"
    }

    // ==================== 下游契约：ManualMaxDateResponse / ManualOkResponse 字段 ====================

    @Test
    fun `testHistoryMaxDate responseRequiredFieldsNonNull`() {
        // given: 有数据
        fake.maxDateResult = LocalDate.of(2026, 9, 30)

        // when
        val response = controller.historyMaxDate("600000")

        // then: code 必填非空（下游增量锚点依赖）
        assertNotNull(response.code, "code 为下游必填字段，不得 null")
    }
}
