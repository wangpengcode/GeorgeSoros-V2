package com.soros.v2.repository

import com.soros.v2.entity.StockHistory
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
 * §六.6⑤ 派生列抽查对拍候选池查询集成测试（@DataJpaTest + TestContainers PG16）。
 *
 * 覆盖 `findDistinctCodesByTradeDateBetween`（BackfillJob.verifyDerivedColumnsSample 候选数据源）：
 * - 区间 [start, end] 内有行的 code 才进入候选（区间外行不混入）；
 * - 同 code 多日行只出现一次（DISTINCT）；
 * - 按 code 升序返回（确定性抽样前提，保证可复现）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockHistorySampleCheckRepositoryTest {

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

    private fun history(code: String, tradeDate: LocalDate) = StockHistory().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.changePct = BigDecimal("1.0000")
        this.dataSource = "BAOSTOCK"
    }

    @Test
    fun `testFindDistinctCodesByTradeDateBetween dedupesAscendingAndBoundsInclusive`() {
        // given: 3 只股票，600050 区间内多日行，另含区间外行 600999
        repository.saveAll(
            listOf(
                history("600000", LocalDate.of(2026, 9, 28)),
                history("600036", LocalDate.of(2026, 9, 30)),
                history("600050", LocalDate.of(2026, 9, 28)),
                history("600050", LocalDate.of(2026, 9, 30)),
                history("600999", LocalDate.of(2026, 9, 25)), // 区间外（start=9-28 之前）
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 区间 [2026-09-28, 2026-09-30]
        val codes = repository.findDistinctCodesByTradeDateBetween(
            LocalDate.of(2026, 9, 28),
            LocalDate.of(2026, 9, 30),
        )

        // then: DISTINCT（600050 只出现一次）+ 升序 + 区间外 600999 排除
        assertEquals(listOf("600000", "600036", "600050"), codes, "去重升序且区间外行排除")
        assertEquals(3, codes.size, "候选 3 只")
        assertTrue(codes == codes.sorted(), "升序（确定性抽样前提）")
    }

    @Test
    fun `testFindDistinctCodesByTradeDateBetween emptyWindowReturnsEmpty`() {
        // given: 空表
        // when & then: 无行区间返回空列表（抽查对拍提前返回，不抛）
        val codes = repository.findDistinctCodesByTradeDateBetween(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
        )
        assertTrue(codes.isEmpty(), "空窗口候选池为空（verifyDerivedColumnsSample 直接跳过）")
    }
}
