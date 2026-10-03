package com.soros.v2.service.limitup

import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.limitup.dto.LimitUpBoardItem
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * §4.8 LimitUpService 涨停梯队契约测试（@DataJpaTest + TestContainers PG16，真实仓储装配）。
 *
 * 契约（接口/实现 KDoc / §4.8）：
 * - 涨停行：stock_history.is_limit_up=true（限 MAIN/GEM/STAR，IPO 首 5 日已在 saveBatch 强制 false）；
 * - 排序：limit_up_streak DESC（最高板=leaderboard[0]），同板数并列按 code 升序全部返回；
 * - join stock_info 装配 name/board/industry/concept_boards（行业取数组第一个作展示主行业）；
 * - 空表 count=0（无涨停行不视为错误，返回空 leaderboard）；
 * - 未命中 stock_info 时降级：board 默认 MAIN、name=null、changePct 默认 0。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LimitUpServiceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var stockHistoryRepository: StockHistoryRepository

    @Autowired
    private lateinit var stockInfoRepository: StockInfoRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    private fun service(): LimitUpServiceImpl =
        LimitUpServiceImpl(stockHistoryRepository, stockInfoRepository)

    private val targetDate: LocalDate = LocalDate.of(2026, 9, 30)

    /** 构造涨停日线行（limit_up_streak 派生值直接落库，测试装配侧） */
    private fun limitUpRow(
        code: String = "600000",
        streak: Short = 1,
        changePct: BigDecimal = BigDecimal("9.9800"),
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = targetDate
        this.open = BigDecimal("12.0000")
        this.close = BigDecimal("13.2000")
        this.high = BigDecimal("13.5000")
        this.low = BigDecimal("11.5000")
        this.volume = 1_000_000L
        this.amount = BigDecimal("13000000.0000")
        this.changePct = changePct
        this.isLimitUp = true
        this.isLimitDown = false
        this.limitUpStreak = streak
        this.limitDownStreak = 0
        this.dataSource = "BAOSTOCK"
    }

    /** 构造 stock_info 行（industry/concept_boards 为 JSON 数组，一股可属多行业） */
    private fun stockInfo(
        code: String,
        name: String,
        board: String,
        industry: List<String> = listOf("半导体", "AI算力"),
        conceptBoards: List<String> = listOf("人工智能"),
    ) = StockInfo().apply {
        this.code = code
        this.name = name
        this.market = if (board == "GEM") "SZ" else "SH"
        this.board = board
        this.isSt = false
        this.delisted = false
        this.industry = industry
        this.conceptBoards = conceptBoards
    }

    // ==================== 正常流程：排序 ====================

    @Test
    fun `testGetLimitUpBoard ordersByStreakDescThenCodeAsc`() {
        // given: 4 只涨停股（3 板/2 板×2 并列/1 板），同板数并列需按 code 升序
        stockHistoryRepository.saveAll(
            listOf(
                limitUpRow(code = "600001", streak = 3),
                limitUpRow(code = "600002", streak = 2),
                limitUpRow(code = "600004", streak = 2),
                limitUpRow(code = "600003", streak = 1),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when
        val board = service().getLimitUpBoard(targetDate)

        // then: limit_up_streak DESC，同板数并列按 code 升序全部返回
        assertEquals(4, board.limitUpCount, "涨停家数=4")
        assertEquals(
            listOf("600001", "600002", "600004", "600003"),
            board.leaderboard.map { it.code },
            "排序：3 板 → 2 板(600002<600004 并列按 code) → 1 板",
        )
        assertEquals("600001", board.leaderboard[0].code, "最高板=leaderboard[0]（§4.8 当日最高板）")
        assertEquals(3, board.leaderboard[0].limitUpStreak, "最高板连板=3")
    }

    // ==================== 正常流程：join stock_info 装配 ====================

    @Test
    fun `testGetLimitUpBoard assemblesNameBoardIndustryFromStockInfo`() {
        // given: 涨停行 + 对应 stock_info（join 装配板块归属）
        stockHistoryRepository.save(limitUpRow(code = "600000"))
        stockInfoRepository.save(stockInfo("600000", "浦发银行", "MAIN"))
        entityManager.flush()
        entityManager.clear()

        // when
        val item = service().getLimitUpBoard(targetDate).leaderboard[0]

        // then: name/board/industry/concept_boards 完整装配（§4.8 梯队接口字段）
        assertEquals("600000", item.code)
        assertEquals("浦发银行", item.name, "join stock_info 装配 name")
        assertEquals("MAIN", item.board, "join 装配 board")
        assertEquals(listOf("半导体", "AI算力"), item.industry, "industry=JSON 数组（主行业=第一个，展示用）")
        assertEquals(listOf("人工智能"), item.conceptBoards, "concept_boards=JSON 数组")
        assertEquals(BigDecimal("9.9800"), item.changePct, "change_pct=不复权真实涨跌幅%")
        assertEquals(1, item.limitUpStreak, "首板=1")
    }

    @Test
    fun `testGetLimitUpBoard gemBoardAssembledFromStockInfo`() {
        // given: GEM 创业板涨停股
        stockHistoryRepository.save(limitUpRow(code = "300750", changePct = BigDecimal("19.9800")))
        stockInfoRepository.save(stockInfo("300750", "宁德时代", "GEM", listOf("电池"), emptyList()))
        entityManager.flush()
        entityManager.clear()

        // when
        val item = service().getLimitUpBoard(targetDate).leaderboard[0]

        // then: GEM board 正确装配（双创 20cm 阈值股）
        assertEquals("GEM", item.board, "GEM 板块归属装配")
        assertEquals(listOf("电池"), item.industry, "industry 数组回读")
    }

    // ==================== 边界条件：空表 / 无涨停行 / 缺 stock_info ====================

    @Test
    fun `testGetLimitUpBoard emptyTableCountZero`() {
        // given: 空表
        // when
        val board = service().getLimitUpBoard(targetDate)

        // then: 无涨停行不视为错误（返回空 leaderboard，count=0）
        assertEquals(0, board.limitUpCount, "空表涨停家数=0")
        assertTrue(board.leaderboard.isEmpty(), "空表 leaderboard 为空")
        assertEquals(targetDate, board.tradeDate, "trade_date 回显")
    }

    @Test
    fun `testGetLimitUpBoard excludesNonLimitUpRows`() {
        // given: 同一天 1 只涨停 + 1 只非涨停（非涨停行不得入梯队）
        stockHistoryRepository.save(limitUpRow(code = "600000"))
        stockHistoryRepository.save(
            limitUpRow(code = "600036", streak = 0).apply { isLimitUp = false },
        )
        entityManager.flush()
        entityManager.clear()

        // when
        val board = service().getLimitUpBoard(targetDate)

        // then: 只回 is_limit_up=true 的行
        assertEquals(1, board.limitUpCount, "非涨停行不入梯队")
        assertEquals(listOf("600000"), board.leaderboard.map { it.code }, "仅 600000 在梯队中")
    }

    @Test
    fun `testGetLimitUpBoard missingStockInfoFallsBackToDefaults`() {
        // given: 涨停行无对应 stock_info（未采集/未知股）
        stockHistoryRepository.save(limitUpRow(code = "688001", changePct = BigDecimal("19.9800")))
        entityManager.flush()
        entityManager.clear()

        // when
        val item = service().getLimitUpBoard(targetDate).leaderboard[0]

        // then: 降级默认：board=MAIN、name=null、changePct 有值（行内字段兜底）
        assertEquals("MAIN", item.board, "缺 stock_info 降级默认 MAIN（防御性兜底）")
        assertNull(item.name, "缺 stock_info name=null")
        assertEquals(BigDecimal("19.9800"), item.changePct, "change_pct 用行内值")
    }

    @Test
    fun `testGetLimitUpBoard otherDateRowsExcluded`() {
        // given: 涨停行跨两日（仅查询日 9-30 命中，9-29 不得混入）
        stockHistoryRepository.save(limitUpRow(code = "600000"))
        stockHistoryRepository.save(limitUpRow(code = "600001", streak = 2).apply {
            this.tradeDate = LocalDate.of(2026, 9, 29)
        })
        entityManager.flush()
        entityManager.clear()

        // when: 查 9-30
        val board = service().getLimitUpBoard(targetDate)

        // then: 仅当日涨停行
        assertEquals(1, board.limitUpCount, "非查询日涨停行被排除")
        assertEquals(listOf("600000"), board.leaderboard.map { it.code }, "仅查询日 9-30 的涨停股")
    }

    /** DTO 字段为必传（下游展示消费），编译期即保证非 null；此处再断言响应结构字段全齐 */
    private fun assertLeaderboardItemComplete(item: LimitUpBoardItem) {
        assertEquals(true, item.code.isNotBlank(), "code 必填")
        assertTrue(item.limitUpStreak >= 1, "limit_up_streak 首板=1 起步")
    }

    @Test
    fun `testGetLimitUpBoard leaderboardItemCoreFieldsNonNull`() {
        // given: 涨停行 + stock_info 齐备
        stockHistoryRepository.save(limitUpRow(code = "600000"))
        stockInfoRepository.save(stockInfo("600000", "浦发银行", "MAIN"))
        entityManager.flush()
        entityManager.clear()

        // when
        val item = service().getLimitUpBoard(targetDate).leaderboard[0]

        // then: 响应核心字段结构完整（下游网页/前端消费必需）
        assertLeaderboardItemComplete(item)
        assertTrue(item.board in setOf("MAIN", "GEM", "STAR"), "board 值域 MAIN/GEM/STAR")
    }
}
