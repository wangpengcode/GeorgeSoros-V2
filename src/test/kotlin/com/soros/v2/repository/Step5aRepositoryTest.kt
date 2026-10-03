package com.soros.v2.repository

import com.soros.v2.entity.StockFundamentals
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Step 5a 仓储新方法契约测试（@DataJpaTest + TestContainers PG16）：
 * - StockInfoRepository.findRandomValidCodes：只回 非ST/非退市/MAIN-GEM-STAR（§11.2 交叉验证样本）
 * - StockInfoRepository.findByCodeIn：批量按 code 查（§4.8 涨停梯队装配）
 * - StockInfoRepository.countByIsStFalseAndDelistedFalse：有效股票数（§11.1 披露季幂等跳过水位）
 * - StockHistoryRepository.findByTradeDateAndIsLimitUpTrue：指定日涨停行（§4.8 涨停梯队）
 * - StockFundamentalsRepository.findByReportDate：指定报告期全部财务行（§11.1 幂等判重水位）
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class Step5aRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var stockInfoRepository: StockInfoRepository

    @Autowired
    private lateinit var stockHistoryRepository: StockHistoryRepository

    @Autowired
    private lateinit var stockFundamentalsRepository: StockFundamentalsRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    // ==================== 构造辅助 ====================

    private fun info(
        code: String,
        board: String = "MAIN",
        isSt: Boolean = false,
        delisted: Boolean = false,
    ) = StockInfo().apply {
        this.code = code
        this.name = "测试股$code"
        this.market = "SH"
        this.board = board
        this.isSt = isSt
        this.delisted = delisted
        this.searchKey = "$code 测试股"
    }

    private fun history(
        code: String,
        tradeDate: LocalDate,
        isLimitUp: Boolean,
        streak: Short = 1,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.open = BigDecimal("12.0000")
        this.close = BigDecimal("13.2000")
        this.high = BigDecimal("13.5000")
        this.low = BigDecimal("11.5000")
        this.volume = 1_000_000L
        this.amount = BigDecimal("13000000.0000")
        this.changePct = BigDecimal("9.9800")
        this.isLimitUp = isLimitUp
        this.isLimitDown = false
        this.limitUpStreak = if (isLimitUp) streak else 0
        this.limitDownStreak = 0
        this.dataSource = "BAOSTOCK"
    }

    private fun fundamentals(code: String, reportDate: LocalDate) = StockFundamentals().apply {
        this.code = code
        this.reportDate = reportDate
        this.revenue = BigDecimal("1000000000.00")
        this.netProfit = BigDecimal("100000000.00")
    }

    // ==================== findRandomValidCodes：只回非 ST/非退市/MAIN-GEM-STAR ====================

    @Test
    fun `testFindRandomValidCodes returnsOnlyCollectibleCodes`() {
        // given: 正常主板/创业板/科创板 + ST + 退市（board 值域由 DB CHECK 兜底 MAIN/GEM/STAR）
        stockInfoRepository.saveAll(
            listOf(
                info("600000", board = "MAIN"),
                info("300750", board = "GEM"),
                info("688001", board = "STAR"),
                info("000587", board = "MAIN", isSt = true),
                info("600005", board = "MAIN", delisted = true),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 抽 5 只（全表仅 3 只可采集）
        val codes = stockInfoRepository.findRandomValidCodes(5)

        // then: 只回 非ST/非退市/MAIN-GEM-STAR（§11.2 交叉验证样本口径；ORDER BY random 顺序不定但集合必达）
        assertEquals(3, codes.size, "全表仅 3 只可采集（600000/300750/688001）")
        assertEquals(setOf("600000", "300750", "688001"), codes.toSet(), "只含可采集有效股")
        assertTrue(codes.none { it == "000587" }, "ST 股不参与样本（隔离铁律）")
        assertTrue(codes.none { it == "600005" }, "退市股不参与样本")
    }

    @Test
    fun `testFindRandomValidCodes emptyTableReturnsEmpty`() {
        // given: 空表
        // when
        val codes = stockInfoRepository.findRandomValidCodes(5)

        // then: 空列表（非 null 非异常）
        assertTrue(codes.isEmpty(), "空表返回空列表")
    }

    @Test
    fun `testFindRandomValidCodes limitRespected`() {
        // given: 3 只可采集
        stockInfoRepository.saveAll(
            listOf(info("600000"), info("600036"), info("600050")),
        )
        entityManager.flush()
        entityManager.clear()

        // when: limit=2
        val codes = stockInfoRepository.findRandomValidCodes(2)

        // then: 数量受 LIMIT 约束
        assertEquals(2, codes.size, "LIMIT 约束返回 ≤2 只")
        assertTrue(codes.all { it in setOf("600000", "600036", "600050") }, "返回均为有效股")
    }

    // ==================== findByCodeIn：批量查 ====================

    @Test
    fun `testFindByCodeIn returnsOnlyGivenCodes`() {
        // given: 3 只股票
        stockInfoRepository.saveAll(listOf(info("600000"), info("600036"), info("600050")))
        entityManager.flush()
        entityManager.clear()

        // when: 批量查 2 只（§4.8 涨停梯队装配 join stock_info）
        val rows = stockInfoRepository.findByCodeIn(setOf("600000", "600036"))

        // then
        assertEquals(2, rows.size, "findByCodeIn 只回给定 codes")
        assertTrue(rows.map { it.code }.toSet() == setOf("600000", "600036"), "不含 600050")
    }

    // ==================== countByIsStFalseAndDelistedFalse：有效股票数 ====================

    @Test
    fun `testCountByIsStFalseAndDelistedFalse countsOnlyValid`() {
        // given: 3 有效 + 1 ST + 1 退市（board 值域由 DB CHECK 兜底 MAIN/GEM/STAR）
        stockInfoRepository.saveAll(
            listOf(
                info("600000"),
                info("600036"),
                info("300750", board = "GEM"),
                info("000587", isSt = true),
                info("600005", delisted = true),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: §11.1 披露季采集幂等跳过的期望水位
        val count = stockInfoRepository.countByIsStFalseAndDelistedFalse()

        // then: 只计 非ST 且 非退市（600000/600036/300750）
        assertEquals(3, count, "count 口径 = 非ST 且 非退市（600000/600036/300750）")
    }

    // ==================== findByTradeDateAndIsLimitUpTrue：指定日涨停行 ====================

    @Test
    fun `testFindByTradeDateAndIsLimitUpTrue returnsOnlySameDateLimitUpRows`() {
        // given: 9-30 涨停×2 + 9-30 非涨停 + 9-29 涨停
        val day = LocalDate.of(2026, 9, 30)
        stockHistoryRepository.saveAll(
            listOf(
                history("600000", day, isLimitUp = true, streak = 1),
                history("600036", day, isLimitUp = true, streak = 2),
                history("600050", day, isLimitUp = false),
                history("600085", LocalDate.of(2026, 9, 29), isLimitUp = true),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: §4.8 涨停梯队 / 当日最高板（is_limit_up 前置判定列）
        val rows = stockHistoryRepository.findByTradeDateAndIsLimitUpTrue(day)

        // then: 只回 该日 且 is_limit_up=true 的行
        assertEquals(2, rows.size, "9-30 涨停 2 只")
        assertTrue(rows.all { it.isLimitUp && it.tradeDate == day }, "全部为当日涨停行")
        assertEquals(setOf("600000", "600036"), rows.map { it.code }.toSet(), "非涨停/他日涨停不混入")
    }

    // ==================== findByReportDate：指定报告期财务行 ====================

    @Test
    fun `testFindByReportDate returnsOnlyGivenReportPeriod`() {
        // given: 两报告期财务行
        stockFundamentalsRepository.saveAll(
            listOf(
                fundamentals("600000", LocalDate.of(2026, 3, 31)),
                fundamentals("600036", LocalDate.of(2026, 3, 31)),
                fundamentals("600000", LocalDate.of(2026, 6, 30)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: §11.1 幂等跳过水位（已采集行数/有效股票数）
        val rows = stockFundamentalsRepository.findByReportDate(LocalDate.of(2026, 3, 31))

        // then: 只回指定报告期
        assertEquals(2, rows.size, "2026-03-31 报告期 2 行")
        assertTrue(rows.all { it.reportDate == LocalDate.of(2026, 3, 31) }, "异报告期不混入")
        assertEquals(setOf("600000", "600036"), rows.map { it.code }.toSet(), "命中 600000/600036")
    }
}
