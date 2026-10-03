package com.soros.v2.service

import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.BoardMembersSnapshot
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
import org.junit.jupiter.api.Assertions.assertNull
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
 * §4.8 StockInfoServiceImpl 增量（BoardCollectJob 消费侧）契约测试（@DataJpaTest + TestContainers PG16）。
 *
 * 契约（接口 KDoc / §4.8 / 类 KDoc）：
 * - refreshBoardSnapshot：Python {板块名:[codes]} → 反转成 code→[板块名] **覆盖写** stock_info.industry（INDUSTRY）
 *   / concept_boards（CONCEPT）；只关注当前成分不保留历史；无该 code 的行跳过不新增；
 *   同快照幂等（二次返回 0）、快照变化覆盖写（返回更新行数）；
 * - backfillIpoDates：批量回填 ipo_date（BaoStock query_stock_basic），**只更新已有行不新增**；无变化返回 0。
 *
 * Python 客户端用 Fake（规避 Mockito 对 suspend 方法 Continuation 参数匹配问题）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockInfoBoardSnapshotTest {

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

    /** Fake Python 客户端：可设定 boardMembers / stockList / degraded（suspend 规避 Mockito Continuation 匹配问题） */
    private class FakePythonClient : PythonDataServiceClient {
        var boardMembers: Map<String, List<String>> = emptyMap()
        var boardMembersDegraded: Boolean = false
        var stockList: List<StockListDto> = emptyList()

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = stockList
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse =
            DailyBarsBatchResponse("ok")
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = boardMembers
        override suspend fun fetchBoardMembersSnapshot(request: BoardMembersRequest): BoardMembersSnapshot =
            BoardMembersSnapshot(boards = boardMembers, degraded = boardMembersDegraded)
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    private fun service(python: FakePythonClient = FakePythonClient()): StockInfoServiceImpl =
        StockInfoServiceImpl(stockInfoRepository, stockIndexRepository, python)

    private fun saveStock(code: String, board: String = "MAIN"): StockInfo = stockInfoRepository.save(
        StockInfo().apply {
            this.code = code
            this.name = "测试股$code"
            this.market = "SH"
            this.board = board
            this.isSt = false
            this.delisted = false
        },
    )

    // ==================== refreshBoardSnapshot：反转 + 覆盖写 ====================

    @Test
    fun `testRefreshBoardSnapshot industryReversesBoardToCodeAndOverwrites`() {
        // given: 2 只股票 + 板块成分 {板块:[codes]}（一股可属多行业）
        saveStock("600000")
        saveStock("600036")
        val python = FakePythonClient().apply {
            boardMembers = mapOf(
                "半导体" to listOf("600000", "600036"),
                "AI算力" to listOf("600000"),
            )
        }

        // when: INDUSTRY 快照
        val updated = runBlocking { service(python).refreshBoardSnapshot(BoardType.INDUSTRY) }
        entityManager.flush()
        entityManager.clear()

        // then: {板块:[codes]} 反转成 code→[板块名] 覆盖写 industry（排序后落库）
        assertEquals(2, updated, "2 只股票各更新一次")
        val stock1 = stockInfoRepository.findByCode("600000")!!
        assertEquals(listOf("AI算力", "半导体"), stock1.industry, "600000 属多行业 → industry 数组含两个板块（升序）")
        val stock2 = stockInfoRepository.findByCode("600036")!!
        assertEquals(listOf("半导体"), stock2.industry, "600036 仅属半导体")
        assertNull(stock2.conceptBoards, "INDUSTRY 快照不写 concept_boards")
    }

    @Test
    fun `testRefreshBoardSnapshot conceptWritesConceptBoardsNotIndustry`() {
        // given: 1 只股票 + 概念成分
        saveStock("600000")
        val python = FakePythonClient().apply {
            boardMembers = mapOf(
                "人工智能" to listOf("600000"),
                "算力租赁" to listOf("600000"),
            )
        }

        // when: CONCEPT 快照
        val updated = runBlocking { service(python).refreshBoardSnapshot(BoardType.CONCEPT) }
        entityManager.flush()
        entityManager.clear()

        // then: 写 concept_boards，industry 不动
        assertEquals(1, updated, "1 只股票更新")
        val stock = stockInfoRepository.findByCode("600000")!!
        assertEquals(listOf("人工智能", "算力租赁"), stock.conceptBoards, "concept_boards 覆盖写两板块（升序）")
        assertNull(stock.industry, "CONCEPT 快照不写 industry")
    }

    @Test
    fun `testRefreshBoardSnapshot idempotentSameSnapshotReturnsZero`() {
        // given: 首次快照已落库
        saveStock("600000")
        val python = FakePythonClient().apply { boardMembers = mapOf("半导体" to listOf("600000")) }
        val svc = service(python)
        assertEquals(1, runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }, "首次更新 1 行")

        // when: 同快照二次执行（覆盖写但内容不变）
        val again = runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }

        // then: 幂等——内容未变不产生更新
        assertEquals(0, again, "同快照二次执行返回 0（幂等，覆盖写但内容不变）")
    }

    @Test
    fun `testRefreshBoardSnapshot overwritesPreviousSnapshot`() {
        // given: 已有旧快照 [半导体]
        saveStock("600000")
        val python = FakePythonClient().apply { boardMembers = mapOf("半导体" to listOf("600000")) }
        val svc = service(python)
        runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }

        // when: 新快照成分变化（加入 AI算力）
        python.boardMembers = mapOf("半导体" to listOf("600000"), "AI算力" to listOf("600000"))
        val updated = runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }
        entityManager.flush()
        entityManager.clear()

        // then: 覆盖写（旧快照被整体替换，不保留成分历史）
        assertEquals(1, updated, "快照变化触发覆盖写")
        assertEquals(listOf("AI算力", "半导体"), stockInfoRepository.findByCode("600000")!!.industry, "覆盖写为最新成分快照")
    }

    @Test
    fun `testRefreshBoardSnapshot unknownCodeSkippedNoInsert`() {
        // given: 板块成分含 stock_info 中不存在的 code（成分漂移/未采集）
        saveStock("600000")
        val before = stockInfoRepository.count()
        val python = FakePythonClient().apply {
            boardMembers = mapOf(
                "半导体" to listOf("600000", "999999"),
            )
        }

        // when
        val updated = runBlocking { service(python).refreshBoardSnapshot(BoardType.INDUSTRY) }

        // then: 未知 code 跳过（只更新已知行），不新增行
        assertEquals(1, updated, "仅 600000 更新，999999 跳过")
        assertEquals(before, stockInfoRepository.count(), "未知 code 不新增 stock_info 行")
    }

    // ==================== backfillIpoDates：只更新已有行不新增 ====================

    @Test
    fun `testBackfillIpoDates updatesExistingRowsOnly`() {
        // given: 2 只已有股票（ipoDate=null）+ Python 列表含这 2 只 + 1 只不在库的股票
        saveStock("600000")
        saveStock("600036")
        val before = stockInfoRepository.count()
        val python = FakePythonClient().apply {
            stockList = listOf(
                StockListDto("600000", "浦发银行", "SH", "MAIN", false, false, "2002-10-30"),
                StockListDto("600036", "招商银行", "SH", "MAIN", false, false, "2002-04-09"),
                StockListDto("601999", "不在库股", "SH", "MAIN", false, false, "2010-01-01"),
            )
        }

        // when
        val updated = runBlocking { service(python).backfillIpoDates() }
        entityManager.flush()
        entityManager.clear()

        // then: 2 只已有行 ipo_date 回填；不在库的 601999 不新增
        assertEquals(2, updated, "2 只已有行回填 ipo_date")
        assertEquals(before, stockInfoRepository.count(), "backfillIpoDates 只更新已有行，不新增")
        assertEquals(
            LocalDate.of(2002, 10, 30),
            stockInfoRepository.findByCode("600000")!!.ipoDate,
            "600000 ipo_date 回填为 BaoStock 上市日",
        )
        assertEquals(
            LocalDate.of(2002, 4, 9),
            stockInfoRepository.findByCode("600036")!!.ipoDate,
            "600036 ipo_date 回填",
        )
        assertNull(stockInfoRepository.findByCode("601999"), "不在库股票不新增")
    }

    @Test
    fun `testBackfillIpoDates unchangedSkipped`() {
        // given: 600000 已有 ipo_date（回填前已最新）
        saveStock("600000").apply { ipoDate = LocalDate.of(2002, 10, 30) }
        entityManager.flush()
        entityManager.clear()
        val python = FakePythonClient().apply {
            stockList = listOf(StockListDto("600000", "浦发银行", "SH", "MAIN", false, false, "2002-10-30"))
        }

        // when
        val updated = runBlocking { service(python).backfillIpoDates() }

        // then: 值未变不产生更新
        assertEquals(0, updated, "ipo_date 无变化返回 0")
    }

    @Test
    fun `testBackfillIpoDates unparsableDateSkipped`() {
        // given: Python ipo_date 非法字符串（源异常数据，runCatching 容错跳过）
        saveStock("600000")
        val python = FakePythonClient().apply {
            stockList = listOf(StockListDto("600000", "浦发银行", "SH", "MAIN", false, false, "not-a-date"))
        }

        // when
        val updated = runBlocking { service(python).backfillIpoDates() }

        // then: 非法日期跳过，不更新不抛
        assertEquals(0, updated, "非法 ipo_date 跳过")
        assertNull(stockInfoRepository.findByCode("600000")!!.ipoDate, "非法日期不写入")
    }

    /** 空板块源 = 无操作（不清除已有归属，待成分源恢复后再刷新） */
    @Test
    fun `testRefreshBoardSnapshot boardMembersEmptyIsNoOpKeepsPrevious`() {
        // given: 已有旧快照，新成分为空（板块列表源为空）
        saveStock("600000")
        val python = FakePythonClient().apply { boardMembers = mapOf("半导体" to listOf("600000")) }
        val svc = service(python)
        runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }

        // when: 次日成分源返回空（上游源异常/未就绪）
        python.boardMembers = emptyMap()
        val updated = runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }
        entityManager.flush()
        entityManager.clear()

        // then: 空源 = 无操作（byCode 为空循环不进），不覆盖不清除，旧快照保留（降级不破坏数据）
        assertEquals(0, updated, "空成分源不产生任何更新（防御性 no-op）")
        assertEquals(
            listOf("半导体"),
            stockInfoRepository.findByCode("600000")!!.industry,
            "旧快照保留（不因源空而误清历史归属）",
        )
    }

    /** 完整快照语义：DB 中不在新快照内的除名股清空对应数组（覆盖写不保留陈旧归属） */
    @Test
    fun `testRefreshBoardSnapshot completeSnapshotClearsRemovedStocks`() {
        // given: 2 只股票已落旧快照（600000→半导体，600036→银行）
        saveStock("600000")
        saveStock("600036")
        val python = FakePythonClient().apply {
            boardMembers = mapOf("半导体" to listOf("600000"), "银行" to listOf("600036"))
        }
        val svc = service(python)
        runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }

        // when: 新完整快照中 600036 被除名（不再属于任何板块）
        python.boardMembers = mapOf("半导体" to listOf("600000"))
        val updated = runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }
        entityManager.flush()
        entityManager.clear()

        // then: 覆盖写完整——除名股 industry 清空（不保留陈旧归属）
        assertEquals(1, updated, "600036 除名清空产生 1 次更新")
        assertEquals(listOf("半导体"), stockInfoRepository.findByCode("600000")!!.industry, "600000 仍属半导体")
        assertEquals(emptyList<String>(), stockInfoRepository.findByCode("600036")!!.industry, "600036 除名 → industry 清空")
    }

    /** 不完整快照语义：任一板块拉取失败/降级 → 跳过清空保留旧值（防部分源故障误清全库） */
    @Test
    fun `testRefreshBoardSnapshot degradedSnapshotKeepsOldValuesForMissingStocks`() {
        // given: 2 只股票已落旧快照（600000→半导体，600036→银行）
        saveStock("600000")
        saveStock("600036")
        val python = FakePythonClient().apply {
            boardMembers = mapOf("半导体" to listOf("600000"), "银行" to listOf("600036"))
        }
        val svc = service(python)
        runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }

        // when: 快照不完整（板块拉取失败/降级），新快照缺 600036 的板块
        python.boardMembers = mapOf("半导体" to listOf("600000"))
        python.boardMembersDegraded = true
        val updated = runBlocking { svc.refreshBoardSnapshot(BoardType.INDUSTRY) }
        entityManager.flush()
        entityManager.clear()

        // then: 跳过清空——600036 旧归属保留（防部分源故障误清全库）
        assertEquals(0, updated, "降级快照内容未变不产生更新")
        assertEquals(listOf("半导体"), stockInfoRepository.findByCode("600000")!!.industry, "600000 覆盖写不受影响")
        assertEquals(listOf("银行"), stockInfoRepository.findByCode("600036")!!.industry, "600036 旧归属保留（不清空）")
    }
}
