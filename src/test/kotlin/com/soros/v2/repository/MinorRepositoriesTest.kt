package com.soros.v2.repository

import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.IndexHistory
import com.soros.v2.entity.StockFundamentals
import com.soros.v2.entity.StockIndex
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
 * Step 2 挂账的 4 个小仓储最小测试（@DataJpaTest + TestContainers PG16）：
 * - IndexHistoryRepository.findByCodeAndTradeDateBetween  区间语义（含两端/升序/按 code 过滤）
 * - StockIndexRepository.findByCode / findByCodeIn        指数基础信息（code UNIQUE / 批量查）
 * - DataQualityLogRepository.findByIssueTypeAndCheckDate / countByIssueTypeAndCheckDateAfter  质量日志查询
 * - StockFundamentalsRepository.findByCodeAndReportDate / existsByCodeAndReportDate  财务行查询/判重
 *
 * 全部为 Spring Data 派生查询 → 本批 GREEN。口径对齐 schema.sql：
 * code 值域（股票=裸数字；指数=带前缀 sh000001 特例）、issue_type 值域单点（代码 enum 无 CHECK）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MinorRepositoriesTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var indexHistoryRepository: IndexHistoryRepository

    @Autowired
    private lateinit var stockIndexRepository: StockIndexRepository

    @Autowired
    private lateinit var dataQualityLogRepository: DataQualityLogRepository

    @Autowired
    private lateinit var stockFundamentalsRepository: StockFundamentalsRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    // ==================== IndexHistoryRepository ====================

    private fun indexHistory(code: String, date: LocalDate) = IndexHistory().apply {
        this.code = code
        this.tradeDate = date
        this.open = BigDecimal("3000.0000")
        this.close = BigDecimal("3010.0000")
        this.high = BigDecimal("3020.0000")
        this.low = BigDecimal("2990.0000")
        this.volume = 100_000_000L
        this.amount = BigDecimal("5000000000.0000")
        this.dataSource = "AKSHARE"
    }

    @Test
    fun `testIndexHistory findByCodeAndTradeDateBetween returnsAscendingInclusive`() {
        // given: sh000001 三个交易日 + 他码 sz399001 同日（验证 code 过滤）
        indexHistoryRepository.saveAll(
            listOf(
                indexHistory("sh000001", LocalDate.of(2026, 9, 28)),
                indexHistory("sh000001", LocalDate.of(2026, 9, 29)),
                indexHistory("sh000001", LocalDate.of(2026, 9, 30)),
                indexHistory("sz399001", LocalDate.of(2026, 9, 29)),
                indexHistory("sh000001", LocalDate.of(2026, 9, 25)), // 区间外
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 区间 [9-28, 9-30]
        val rows = indexHistoryRepository.findByCodeAndTradeDateBetween(
            "sh000001", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
        )

        // then: 3 行（区间含两端、code 过滤、区间外排除）、升序
        assertEquals(3, rows.size, "区间内 sh000001 应回 3 行（含两端边界）")
        assertTrue(rows.all { it.code == "sh000001" }, "code 过滤：sz399001 不得混入")
        assertEquals(
            listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30)),
            rows.map { it.tradeDate },
            "升序返回且区间两端含",
        )
        assertFalse(rows.any { it.tradeDate == LocalDate.of(2026, 9, 25) }, "区间外 9-25 被排除")
    }

    @Test
    fun `testIndexHistory findByCodeAndTradeDateBetween emptyRange returnsEmpty`() {
        // given: 空表
        // when: 区间查
        val rows = indexHistoryRepository.findByCodeAndTradeDateBetween(
            "sh000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
        )
        // then: 空列表（非 null 非异常）
        assertTrue(rows.isEmpty(), "空表区间查询返回空列表")
    }

    // ==================== StockIndexRepository ====================

    @Test
    fun `testStockIndex findByCode hitAndMiss`() {
        // given: 落一只上证指数
        stockIndexRepository.save(
            StockIndex().apply {
                code = "sh000001"
                name = "上证指数"
            },
        )
        entityManager.flush()
        entityManager.clear()

        // when & then: code UNIQUE 至多 1 行
        val found = stockIndexRepository.findByCode("sh000001")
            ?: fail("sh000001 应命中")
        assertEquals("上证指数", found.name, "name 回读")
        assertNull(stockIndexRepository.findByCode("sz399001"), "未落库指数返回 null")
    }

    @Test
    fun `testStockIndex findByCodeIn returnsOnlyGivenCodes`() {
        // given: 3 个指数
        stockIndexRepository.saveAll(
            listOf(
                StockIndex().apply { code = "sh000001"; name = "上证指数" },
                StockIndex().apply { code = "sz399001"; name = "深证成指" },
                StockIndex().apply { code = "sh000300"; name = "沪深300" },
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 批量查（5 个基准指数一次载入场景）
        val rows = stockIndexRepository.findByCodeIn(setOf("sh000001", "sz399001"))

        // then: 只回指定 codes
        assertEquals(2, rows.size, "findByCodeIn 只回给定 codes")
        assertTrue(rows.map { it.code }.toSet() == setOf("sh000001", "sz399001"), "返回 sh000001/sz399001，不含 sh000300")
    }

    // ==================== DataQualityLogRepository ====================

    private fun qualityLog(code: String, issueType: String, checkDate: LocalDate) = DataQualityLog().apply {
        this.code = code
        this.issueType = issueType
        this.checkDate = checkDate
        this.detail = "test $issueType $code"
    }

    @Test
    fun `testDataQualityLog findByIssueTypeAndCheckDate filtersBoth`() {
        // given: 同 issue 同日多股 + 异 issue/异日
        dataQualityLogRepository.saveAll(
            listOf(
                qualityLog("600000", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 1)),
                qualityLog("600036", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 1)),
                qualityLog("600005", "DELIST_SUSPECT", LocalDate.of(2026, 10, 1)),
                qualityLog("600000", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 2)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 问题类型+检查执行日 联合过滤（§17.1 B2 条件跳过问题表消费）
        val rows = dataQualityLogRepository.findByIssueTypeAndCheckDate("ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 1))

        // then: 只回该 issue+该日 2 行（异 issue/异日被过滤）
        assertEquals(2, rows.size, "按 issue_type+check_date 联合过滤")
        assertTrue(rows.all { it.issueType == "ADJUSTMENT_DRIFT" && it.checkDate == LocalDate.of(2026, 10, 1) },
            "异 issue 或异日不得混入")
        assertTrue(rows.map { it.code }.toSet() == setOf("600000", "600036"), "命中两只漂移股")
    }

    @Test
    fun `testDataQualityLog countByIssueTypeAndCheckDateAfter`() {
        // given: 同 issue 不同日分布（含边界日）
        dataQualityLogRepository.saveAll(
            listOf(
                qualityLog("600000", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 9, 30)), // 边界日（不算 after）
                qualityLog("600000", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 1)),
                qualityLog("600036", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 1)),
                qualityLog("600000", "ADJUSTMENT_DRIFT", LocalDate.of(2026, 10, 2)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 2026-09-30 之后（不含该日）
        val count = dataQualityLogRepository.countByIssueTypeAndCheckDateAfter("ADJUSTMENT_DRIFT", LocalDate.of(2026, 9, 30))

        // then: 3 行（重复问题收敛看板口径：> check_date）
        assertEquals(3, count, "countByIssueTypeAndCheckDateAfter 统计 check_date > 基准日的同 issue 行数")
    }

    // ==================== StockFundamentalsRepository ====================

    private fun fundamentals(code: String, reportDate: LocalDate) = StockFundamentals().apply {
        this.code = code
        this.reportDate = reportDate
        this.revenue = BigDecimal("1000000000.00") // 元（源亿元 ×1e8）
        this.netProfit = BigDecimal("100000000.00")
    }

    @Test
    fun `testStockFundamentals findByCodeAndReportDate hitAndMiss`() {
        // given: 落一条 600000 2026-06-30 财报
        stockFundamentalsRepository.save(fundamentals("600000", LocalDate.of(2026, 6, 30)))
        entityManager.flush()
        entityManager.clear()

        // when & then: UNIQUE(code, report_date) 至多 1 行
        val found = stockFundamentalsRepository.findByCodeAndReportDate("600000", LocalDate.of(2026, 6, 30))
            ?: fail("600000 2026-06-30 应命中")
        assertNotNull(found.revenue, "revenue 元口径回读")
        assertNull(
            stockFundamentalsRepository.findByCodeAndReportDate("600000", LocalDate.of(2026, 3, 31)),
            "未入库报告期返回 null",
        )
    }

    @Test
    fun `testStockFundamentals existsByCodeAndReportDate idempotentDedup`() {
        // given: 已入库 600000 2026-06-30
        stockFundamentalsRepository.save(fundamentals("600000", LocalDate.of(2026, 6, 30)))
        entityManager.flush()
        entityManager.clear()

        // when & then: 季度循环拉取幂等判重
        assertTrue(
            stockFundamentalsRepository.existsByCodeAndReportDate("600000", LocalDate.of(2026, 6, 30)),
            "已存在组合 exists=true（幂等判重）",
        )
        assertFalse(
            stockFundamentalsRepository.existsByCodeAndReportDate("600000", LocalDate.of(2026, 3, 31)),
            "同码异报告期 exists=false",
        )
        assertFalse(
            stockFundamentalsRepository.existsByCodeAndReportDate("600036", LocalDate.of(2026, 6, 30)),
            "异码 exists=false",
        )
    }
}
