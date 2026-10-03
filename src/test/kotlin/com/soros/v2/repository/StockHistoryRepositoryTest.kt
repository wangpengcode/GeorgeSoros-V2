package com.soros.v2.repository

import com.soros.v2.entity.StockHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate

/**
 * stock_history Repository 集成测试（@DataJpaTest + TestContainers PG16）。
 *
 * 表结构/种子数据一律来自 Flyway V1/V2（ddl-auto=validate 只校验不建表，禁止 ddl-auto=create 绕过）；
 * 业务口径（PLAN §17.6）：OHLC=qfq 前复权、change_pct=不复权真实涨跌幅%（涨停判定依据，坐标永不混用）、
 * volume=股、amount=元；limit_up_streak 首板=1、断板归 0（§4.8 派生）；UNIQUE(code, trade_date) 幂等兜底。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockHistoryRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var repository: StockHistoryRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    /** 构造单条日线（默认主板涨停首板：不复权 change_pct=9.98；金额/比例全部 scale=4 对齐 NUMERIC 列精度） */
    private fun buildHistory(
        code: String = "600000",
        tradeDate: LocalDate = LocalDate.of(2026, 9, 30),
        open: BigDecimal = BigDecimal("12.3400"),
        close: BigDecimal = BigDecimal("13.5700"),
        high: BigDecimal = BigDecimal("13.8000"),
        low: BigDecimal = BigDecimal("12.1000"),
        volume: Long = 1_234_567L,
        amount: BigDecimal = BigDecimal("15678900.0000"),
        changePct: BigDecimal = BigDecimal("9.9800"),
        turnoverRate: BigDecimal = BigDecimal("2.5000"),
        isLimitUp: Boolean = true,
        isLimitDown: Boolean = false,
        limitUpStreak: Short = 1,
        limitDownStreak: Short = 0,
        dataSource: String = "BAOSTOCK",
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.open = open
        this.close = close
        this.high = high
        this.low = low
        this.volume = volume
        this.amount = amount
        this.changePct = changePct
        this.turnoverRate = turnoverRate
        this.isLimitUp = isLimitUp
        this.isLimitDown = isLimitDown
        this.limitUpStreak = limitUpStreak
        this.limitDownStreak = limitDownStreak
        this.dataSource = dataSource
    }

    // ==================== 正常流程 ====================

    @Test
    fun `testSaveSingle thenReadBack allFields`() {
        // given: 单条主板涨停首板（qfq OHLC + 不复权 change_pct 9.98 + 量额单位口径）
        repository.saveAndFlush(buildHistory())
        entityManager.clear() // 清一级缓存，强制从 DB 重读（真持久化回读，非一级缓存命中）

        // when: 按 code+trade_date 回读（UNIQUE(code, trade_date) 至多 1 行）
        val found = repository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30))
            ?: fail("命中行不应为 null")

        // then: 逐字段断言（BigDecimal 精度对齐 NUMERIC(12,4)/NUMERIC(20,4)/NUMERIC(10,4)，scale=4）
        assertEquals("600000", found.code, "code 裸数字，不带 sh/sz 前缀")
        assertEquals(LocalDate.of(2026, 9, 30), found.tradeDate, "trade_date 交易日回读")
        assertEquals(BigDecimal("12.3400"), found.open, "open=qfq 前复权价")
        assertEquals(BigDecimal("13.5700"), found.close, "close=qfq 前复权价")
        assertEquals(BigDecimal("13.8000"), found.high, "high=qfq 前复权价")
        assertEquals(BigDecimal("12.1000"), found.low, "low=qfq 前复权价")
        assertEquals(1_234_567L, found.volume, "volume 统一单位=股（AKShare/mootdx 手×100 已换算）")
        assertEquals(BigDecimal("15678900.0000"), found.amount, "amount 单位=元")
        assertEquals(BigDecimal("9.9800"), found.changePct, "change_pct=不复权真实涨跌幅%（涨停判定依据，与 qfq OHLC 不混用）")
        assertEquals(BigDecimal("2.5000"), found.turnoverRate, "turnover_rate 换手率%")
        assertTrue(found.isLimitUp, "is_limit_up 涨停布尔回读")
        assertFalse(found.isLimitDown, "is_limit_down 跌停布尔回读")
        assertEquals(1, found.limitUpStreak.toInt(), "limit_up_streak 首板=1（§4.8 连板派生，0=非涨停/断板）")
        assertEquals(0, found.limitDownStreak.toInt(), "limit_down_streak 跌停连板=0（§4.9 崩塌池镜像）")
        assertEquals("BAOSTOCK", found.dataSource, "data_source 实际来源（CHECK 白名单 BAOSTOCK/AKSHARE/MOOTDX/UNKNOWN）")
        assertNotNull(found.id, "IDENTITY 主键已回填")
        assertNotNull(found.createdAt, "created_at 由 @CreationTimestamp 写入")
    }

    @Test
    fun `testSaveAll batch thenReadBack allRows`() {
        // given: 同股三个交易日批量落库（增量 upsert 批量写入前身）
        repository.saveAll(
            listOf(
                buildHistory(tradeDate = LocalDate.of(2026, 9, 28), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 29), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 30)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 区间回读（Between 含两端）
        val found = repository.findByCodeAndTradeDateBetween(
            "600000", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
        )

        // then: 3 行全回（区间边界含两端）
        assertEquals(3, found.size, "saveAll 批量落库 + Between 边界含两端应回 3 行")
        val dates = found.map { it.tradeDate }.toSet()
        assertTrue(
            dates.containsAll(
                setOf(
                    LocalDate.of(2026, 9, 28),
                    LocalDate.of(2026, 9, 29),
                    LocalDate.of(2026, 9, 30),
                ),
            ),
            "区间两端日都应命中",
        )
        assertTrue(found.all { it.code == "600000" }, "code 过滤正确，无他股混入")
    }

    @Test
    fun `testFindMaxTradeDateByCode returnsLatest`() {
        // given: 三个交易日
        repository.saveAll(
            listOf(
                buildHistory(tradeDate = LocalDate.of(2026, 9, 28), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 29), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 30)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 采集水位 / 对账用最大交易日
        val maxDate = repository.findMaxTradeDateByCode("600000")

        // then
        assertEquals(LocalDate.of(2026, 9, 30), maxDate, "findMaxTradeDateByCode 应返回该股最大交易日")
    }

    @Test
    fun `testFindTopByCodeOrderByTradeDateDesc returnsLatestRow`() {
        // given
        repository.saveAll(
            listOf(
                buildHistory(tradeDate = LocalDate.of(2026, 9, 28), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 30)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 连板数派生前置取最近一行
        val top = repository.findTopByCodeOrderByTradeDateDesc("600000")
            ?: fail("最近交易日行不应为 null")

        // then
        assertEquals(LocalDate.of(2026, 9, 30), top.tradeDate, "应取 trade_date 最大（最近）的一行")
    }

    @Test
    fun `testExistsByCodeAndTradeDate hitAndMiss`() {
        // given
        repository.saveAndFlush(buildHistory())
        entityManager.clear()

        // when & then: 增量 upsert 幂等判重
        assertTrue(
            repository.existsByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30)),
            "已存在组合 exists=true（幂等判重）",
        )
        assertFalse(
            repository.existsByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 29)),
            "同码不同日 exists=false",
        )
        assertFalse(
            repository.existsByCodeAndTradeDate("000001", LocalDate.of(2026, 9, 30)),
            "不同码 exists=false",
        )
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testSaveDuplicateCodeTradeDate throws DataIntegrityViolationException`() {
        // given: 同一 (code, trade_date) 先落一条
        repository.saveAndFlush(buildHistory())
        entityManager.clear()
        // 再构造一个独立实例同码同日（新主键 id，触发 INSERT 撞 UNIQUE(code, trade_date)）
        val duplicate = buildHistory()

        // when & then: UNIQUE 约束兜底，重复插入必须抛数据完整性异常
        assertThrows(DataIntegrityViolationException::class.java) {
            repository.saveAndFlush(duplicate)
        }
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testFindByCodeAndTradeDate miss returnsNull`() {
        // given: 空表
        // when: 查不存在组合
        val found = repository.findByCodeAndTradeDate("600000", LocalDate.of(2026, 9, 30))
        // then
        assertNull(found, "未命中应返回 null（不允许返回 Optional 包装）")
    }

    @Test
    fun `testFindMaxTradeDateByCode emptyTable returnsNull`() {
        // given: 空表（无任何行情）
        // when & then: 空表 null 安全（采集水位初始态，MAX 聚合空集返回 null）
        assertNull(repository.findMaxTradeDateByCode("600000"), "空表 findMaxTradeDateByCode 应返回 null")
    }

    @Test
    fun `testFindTopByCodeOrderByTradeDateDesc emptyTable returnsNull`() {
        // given: 空表
        // when & then: 空表 null 安全（除权漂移检测前置，无最近行）
        assertNull(repository.findTopByCodeOrderByTradeDateDesc("600000"), "空表 findTop 应返回 null")
    }

    @Test
    fun `testFindByCodeAndTradeDateBetween boundaryExcludesOutside`() {
        // given: 区间外一行（9-25 早于 start）+ 区间内一行（9-30）
        repository.saveAll(
            listOf(
                buildHistory(tradeDate = LocalDate.of(2026, 9, 25), isLimitUp = false),
                buildHistory(tradeDate = LocalDate.of(2026, 9, 30)),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 区间 [9-28, 9-30]
        val found = repository.findByCodeAndTradeDateBetween(
            "600000", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
        )

        // then: 区间外 9-25 不得混入，区间内 9-30 命中
        assertEquals(1, found.size, "Between 只回区间内行")
        assertEquals(LocalDate.of(2026, 9, 30), found[0].tradeDate, "区间外行被正确排除")
    }
}
