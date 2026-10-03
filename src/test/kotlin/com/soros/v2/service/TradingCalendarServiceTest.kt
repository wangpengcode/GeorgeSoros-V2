package com.soros.v2.service

import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.StockListDto
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
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

/**
 * §4.4/§4.7 TradingCalendarService 契约测试（@DataJpaTest + TestContainers PG16，禁 ddl-auto=create）。
 *
 * 基线：Flyway V2 种子 208 行（2026-03-02 ~ 2026-12-31）。
 * - isTradingDay / previousTradingDay 已实现（委托 repository）→ 本批 GREEN；
 * - ensureLoaded / recentTradingDays 为骨架（TODO）→ 本批 RED，定义契约供 Implementer 填充。
 *
 * 口径：日历只服务"往前看"的消费方（滚动重拉 10 日窗口 / DELIST_SUSPECT / 年底续期）。
 * Python 客户端用 Fake（规避 Mockito 对 suspend 方法 Continuation 参数匹配问题）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TradingCalendarServiceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var calendarRepository: TradingCalendarRepository

    /** Fake Python 客户端：可设定 fetchTradingCalendar 返回日期 */
    private class FakePythonClient : PythonDataServiceClient {
        var calendarDates: List<String> = emptyList()

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse =
            DailyBarsBatchResponse("ok")
        override suspend fun fetchTradingCalendar(): List<String> = calendarDates
    }

    private fun service(pythonClient: FakePythonClient = FakePythonClient()): TradingCalendarServiceImpl =
        TradingCalendarServiceImpl(calendarRepository, pythonClient)

    // ==================== 正常流程（已实现，GREEN） ====================

    @Test
    fun `testIsTradingDay seedDate true`() {
        // given: 种子内交易日 2026-10-09
        // when & then
        assertTrue(service().isTradingDay(LocalDate.of(2026, 10, 9)), "种子内交易日应判定为交易日")
    }

    @Test
    fun `testIsTradingDay holiday false`() {
        // given: 2026-10-07 国庆休市（种子从 09-30 直接跳到 10-08）
        // when & then
        assertFalse(service().isTradingDay(LocalDate.of(2026, 10, 7)), "国庆休市日不得判定为交易日")
    }

    @Test
    fun `testPreviousTradingDay returnsPreviousSeedDate`() {
        // when: 2026-10-09 的前一交易日
        val prev = service().previousTradingDay(LocalDate.of(2026, 10, 9))
        // then: 2026-10-08（跳过 10-07 休市）
        assertEquals(LocalDate.of(2026, 10, 8), prev, "前一交易日=10-08（国庆休市跳过）")
    }

    @Test
    fun `testPreviousTradingDay earliestSeedDate returnsNull`() {
        // when: 种子最早日 2026-03-02 之前无更早交易日
        // then: null 安全（§4.6 链式校验批首定位：无前一日数据跳过）
        assertNull(service().previousTradingDay(LocalDate.of(2026, 3, 2)), "种子最早日之前无交易日，返回 null")
    }

    // ==================== 正常流程（骨架，RED：契约） ====================

    @Test
    fun `testRecentTradingDays returnsAscendingInclusive`() {
        // given: end=2026-10-09（含），n=2
        val days = service().recentTradingDays(LocalDate.of(2026, 10, 9), 2)
        // then: [10-08, 10-09] 升序含两端
        assertEquals(
            listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9)),
            days,
            "recentTradingDays 返回以 end 结尾的 n 个交易日，升序",
        )
    }

    @Test
    fun `testRecentTradingDays skipsHolidays`() {
        // given: end=2026-10-09，n=3 —— 中间经过国庆休市
        val days = service().recentTradingDays(LocalDate.of(2026, 10, 9), 3)
        // then: 往前 3 个交易日 = [09-30, 10-08, 10-09]（10-07 休市被跳过，只数开市日）
        assertEquals(
            listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9)),
            days,
            "往前 3 个交易日跳过国庆休市，升序",
        )
    }

    @Test
    fun `testRecentTradingDays zero returnsEmpty`() {
        // given: n=0
        // when & then
        assertTrue(service().recentTradingDays(LocalDate.of(2026, 10, 9), 0).isEmpty(), "n=0 返回空列表")
    }

    // ==================== 异常路径/边界（骨架，RED：契约） ====================

    @Test
    fun `testRecentTradingDays insufficientHistory returnsAvailable`() {
        // given: 种子最早日 2026-03-02，n=3（只有 1 个可用）
        val days = service().recentTradingDays(LocalDate.of(2026, 3, 2), 3)
        // then: 返回可用数量（1 个），不抛异常（防御性）
        assertEquals(listOf(LocalDate.of(2026, 3, 2)), days, "历史不足时返回可用数量，升序不抛")
    }

    @Test
    fun `testEnsureLoaded insertsNewDatesAndSkipsExisting`() {
        // given: 种子已有 208 行；Python 全量日历返回种子日期 + 2 个新 2027 交易日
        val baseCount = calendarRepository.count()
        assertEquals(208, baseCount, "前置条件：种子基线 208 行")
        val fake = FakePythonClient().apply { calendarDates = listOf("2027-01-04", "2027-01-05") }

        // when
        val loaded = runBlocking { service(fake).ensureLoaded() }

        // then: 返回本次载入数量=2（已存在日跳过，幂等），count=210
        assertEquals(2, loaded, "ensureLoaded 返回本次载入交易日数量（已存在日跳过）")
        assertEquals(210, calendarRepository.count(), "幂等：已存在日不重复插入，仅新增 2 行")
        assertTrue(calendarRepository.existsByTradeDate(LocalDate.of(2027, 1, 4)), "2027-01-04 已载入")
    }

    @Test
    fun `testEnsureLoaded idempotentOnExistingData`() {
        // given: 二次载入同一批日期（已全部存在）
        val fake = FakePythonClient().apply { calendarDates = listOf("2026-10-09", "2026-10-08") }

        // when
        val loaded = runBlocking { service(fake).ensureLoaded() }

        // then: 已存在日全部跳过 → 0 新增
        assertEquals(0, loaded, "重复载入已存在日返回 0（幂等）")
        assertEquals(208, calendarRepository.count(), "count 不变")
    }

    @Test
    fun `testEnsureLoaded emptyCalendarLoadsAll`() {
        // given: 空日历（模拟新环境）+ Python 返回一批日期
        calendarRepository.deleteAll()
        val fake = FakePythonClient().apply { calendarDates = listOf("2026-10-08", "2026-10-09") }

        // when
        val loaded = runBlocking { service(fake).ensureLoaded() }

        // then: count()==0 → 全量载入 2 行
        assertEquals(2, loaded, "空日历全量载入")
        val found = calendarRepository.findByTradeDate(LocalDate.of(2026, 10, 8))
            ?: fail("2026-10-08 应已载入")
        assertEquals(LocalDate.of(2026, 10, 8), found.tradeDate, "载入后回读")
    }
}
