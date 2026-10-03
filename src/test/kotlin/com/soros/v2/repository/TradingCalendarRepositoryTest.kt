package com.soros.v2.repository

import com.soros.v2.entity.TradingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * trading_calendar Repository 集成测试。
 *
 * 基线：Flyway V2 种子 208 行（2026-03-02 ~ 2026-12-31，留 7 个月余量）。
 * 裁剪决策（V2 注释）：历史段落 2026-03 前不进库——回填对账/情绪回放的逐日推进用
 * stock_history 的 DISTINCT trade_date，不依赖旧日历；日历只服务"往前看"的消费方。
 * 每行即一个开市交易日（表仅 trade_date 一列主键，无 is_open 列，"开市日计数"由 count() 表达）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TradingCalendarRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var repository: TradingCalendarRepository

    // ==================== 正常流程 ====================

    @Test
    fun `testCount equalsSeedBaseline`() {
        // given: Flyway V2 种子（2026-03-02~2026-12-31）
        // when & then: 种子基线必须 == 208 行，任何种子增删都会破坏基线
        assertEquals(208, repository.count(), "trading_calendar 种子基线行数必须 == 208")
    }

    @Test
    fun `testFindByTradeDate existingDate returnsRow`() {
        // when: 查种子内交易日
        val found = repository.findByTradeDate(LocalDate.of(2026, 10, 9))
            ?: fail("2026-10-09 为种子内交易日，应命中")
        // then
        assertEquals(LocalDate.of(2026, 10, 9), found.tradeDate, "主键行回读")
    }

    @Test
    fun `testExistsByTradeDate hitAndMiss`() {
        // when & then: exists 判重（DELIST_SUSPECT/新鲜度/回填对账消费）
        assertTrue(
            repository.existsByTradeDate(LocalDate.of(2026, 10, 9)),
            "种子内日期 exists=true",
        )
        assertFalse(
            repository.existsByTradeDate(LocalDate.of(2026, 1, 1)),
            "2026-03 前不入库（裁剪决策），exists=false",
        )
    }

    @Test
    fun `testFindFirstByTradeDateAfter returnsNextTradingDay`() {
        // when: 2026-10-08（周四）之后最近交易日
        val next = repository.findFirstByTradeDateAfterOrderByTradeDateAsc(LocalDate.of(2026, 10, 8))
            ?: fail("种子内应有下一交易日")
        // then: 升序取首个 = 2026-10-09（下一个交易日，周五）
        assertEquals(LocalDate.of(2026, 10, 9), next.tradeDate, "该日之后最近一个交易日（下一个交易日）")
    }

    @Test
    fun `testFindFirstByTradeDateBefore returnsPrevTradingDay`() {
        // when: 2026-10-09 之前最近交易日
        val prev = repository.findFirstByTradeDateBeforeOrderByTradeDateDesc(LocalDate.of(2026, 10, 9))
            ?: fail("种子内应有前一交易日")
        // then: 倒序取首个 = 2026-10-08（上一个交易日）
        assertEquals(LocalDate.of(2026, 10, 8), prev.tradeDate, "该日之前最近一个交易日（上一个交易日）")
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testFindByTradeDate beforeSeedRange returnsNull`() {
        // given: 2026-01-01 早于种子起始 2026-03-02（裁剪决策：历史段落不进库）
        // when & then
        assertNull(repository.findByTradeDate(LocalDate.of(2026, 1, 1)), "2026-03 前交易日不入库，应返回 null")
    }

    @Test
    fun `testFindFirstByTradeDateBefore earliestDate returnsNull`() {
        // when: 早于种子最早日 2026-03-02
        val prev = repository.findFirstByTradeDateBeforeOrderByTradeDateDesc(LocalDate.of(2026, 3, 2))
        // then: 无更早交易日，null 安全（日历只服务往前看的消费方）
        assertNull(prev, "种子最早日之前无交易日，应返回 null")
    }

    @Test
    fun `testFindFirstByTradeDateAfter latestDate returnsNull`() {
        // when: 晚于种子最末日 2026-12-31
        val next = repository.findFirstByTradeDateAfterOrderByTradeDateAsc(LocalDate.of(2026, 12, 31))
        // then: 无更晚交易日，null 安全（2027+ 由日历 Job 自动续期）
        assertNull(next, "种子最末日之后无交易日，应返回 null")
    }
}
