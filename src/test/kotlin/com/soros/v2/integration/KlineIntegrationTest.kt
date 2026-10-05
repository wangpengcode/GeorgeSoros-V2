package com.soros.v2.integration

import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §19.13.1 K线复盘端点集成测试（@SpringBootTest + MockMvc + TestContainers PG16，真实 SQL/吸附/join）。
 *
 * 覆盖（§19.13.1 端点契约 / 行为矩阵 / 测试要点）：
 * - 显式 from/to：bars 升序、snake_case 响应键、chip 按 code+trade_date join、无 signal_daily 行 → chip=null（warm-up）；
 * - ?date= 终点吸附：date=非交易日 → to=≤date 最近交易日（真实 trading_calendar 查询）；
 * - 仅 code：默认近 250 交易日窗口（真实 calendar 求 from=to−249）；
 * - 未知 code → 404 BUSINESS_ERROR 信封；from>to → 422 信封。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KlineIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE signal_daily, stock_history, stock_info, trading_calendar")
    }

    // ==================== 构造辅助 ====================

    private fun seedCalendar(dates: Collection<LocalDate>) {
        dates.forEach { jdbc.update("INSERT INTO trading_calendar (trade_date) VALUES (?)", Date.valueOf(it)) }
    }

    private fun seedInfo(code: String, name: String = "浦发银行") {
        jdbc.update(
            "INSERT INTO stock_info (code, name, market, board, is_st, delisted, industry) " +
                "VALUES (?, ?, 'SH', 'MAIN', false, false, '[\"银行\"]')",
            code, name,
        )
    }

    private fun seedBar(code: String, tradeDate: LocalDate, changePct: String = "1.28", turnoverRate: String = "0.42") {
        jdbc.update(
            """
            INSERT INTO stock_history (code, trade_date, open, close, high, low, volume, amount,
                                       change_pct, turnover_rate, data_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BAOSTOCK')
            """,
            code, Date.valueOf(tradeDate),
            BigDecimal("9.22"), BigDecimal("9.48"), BigDecimal("9.49"), BigDecimal("9.16"),
            147_484_820L, BigDecimal("1386209937.38"),
            BigDecimal(changePct), BigDecimal(turnoverRate),
        )
    }

    private fun seedChip(code: String, tradeDate: LocalDate) {
        jdbc.update(
            """
            INSERT INTO signal_daily (code, trade_date, profit_ratio, cost_dev,
                                      c90_low, c90_high, c90_conc, c70_low, c70_high, c70_conc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            code, Date.valueOf(tradeDate),
            BigDecimal("92.90"), BigDecimal("-1.20"),
            BigDecimal("8.10"), BigDecimal("9.55"), BigDecimal("61.20"),
            BigDecimal("8.65"), BigDecimal("9.40"), BigDecimal("43.80"),
        )
    }

    private fun consecutive(from: LocalDate, to: LocalDate): List<LocalDate> =
        (0..java.time.temporal.ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }

    // ==================== 1. 显式 from/to：bars 升序 + chip join + warm-up chip=null ====================

    @Test
    fun `testGetKline explicitRange returnsBarsAscendingWithChipJoinAndWarmupNull`() {
        // given: 3 交易日 + 3 bar + 前 2 日 signal_daily（第 3 日 warm-up 无行 → chip=null）
        val d1 = LocalDate.of(2026, 9, 28)
        val d2 = LocalDate.of(2026, 9, 29)
        val d3 = LocalDate.of(2026, 9, 30)
        seedCalendar(listOf(d1, d2, d3))
        seedInfo("600000")
        listOf(d1, d2, d3).forEach { seedBar("600000", it) }
        seedChip("600000", d1)
        seedChip("600000", d2)

        // when & then: 200 + 响应键 snake_case 逐字段对齐
        mockMvc.perform(get("/api/v1/kline").param("code", "600000")
            .param("from", "2026-09-28").param("to", "2026-09-30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("600000"))
            .andExpect(jsonPath("$.name").value("浦发银行"))
            .andExpect(jsonPath("$.bars.length()").value(3))
            .andExpect(jsonPath("$.bars[0].trade_date").value("2026-09-28"))
            .andExpect(jsonPath("$.bars[0].open").value(9.22))
            .andExpect(jsonPath("$.bars[0].close").value(9.48))
            .andExpect(jsonPath("$.bars[0].volume").value(147484820))
            .andExpect(jsonPath("$.bars[0].amount").value(1386209937.38))
            .andExpect(jsonPath("$.bars[0].change_pct").value(1.28))
            .andExpect(jsonPath("$.bars[0].turnover_rate").value(0.42))
            .andExpect(jsonPath("$.bars[0].chip.profit_ratio").value(92.90))
            .andExpect(jsonPath("$.bars[0].chip.cost_dev").value(-1.20))
            .andExpect(jsonPath("$.bars[0].chip.c90_low").value(8.10))
            .andExpect(jsonPath("$.bars[0].chip.c90_high").value(9.55))
            .andExpect(jsonPath("$.bars[0].chip.c90_conc").value(61.20))
            .andExpect(jsonPath("$.bars[0].chip.c70_low").value(8.65))
            .andExpect(jsonPath("$.bars[0].chip.c70_high").value(9.40))
            .andExpect(jsonPath("$.bars[0].chip.c70_conc").value(43.80))
            .andExpect(jsonPath("$.bars[1].trade_date").value("2026-09-29"))
            .andExpect(jsonPath("$.bars[1].chip.c70_conc").value(43.80))
            // 第 3 日无 signal_daily 行 → chip=null（warm-up 段）
            .andExpect(jsonPath("$.bars[2].trade_date").value("2026-09-30"))
            .andExpect(jsonPath("$.bars[2].chip").value(org.hamcrest.Matchers.nullValue()))
    }

    // ==================== 2. ?date= 终点吸附（真实 trading_calendar） ====================

    @Test
    fun `testGetKline dateParamAbsorbsToNearestTradingDay`() {
        // given: 日历含 2026-09-29/09-30/10-01，date=2026-10-03（周六，非交易日）→ 吸附到 10-01
        val d1 = LocalDate.of(2026, 9, 29)
        val d2 = LocalDate.of(2026, 9, 30)
        val d3 = LocalDate.of(2026, 10, 1)
        val nonTrading = LocalDate.of(2026, 10, 3)
        seedCalendar(listOf(d1, d2, d3))
        seedInfo("600000")
        listOf(d1, d2, d3).forEach { seedBar("600000", it) }

        // when: date=2026-10-03（非交易日）→ to 吸附到 2026-10-01
        mockMvc.perform(get("/api/v1/kline").param("code", "600000").param("date", "2026-10-03"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bars.length()").value(3))
            .andExpect(jsonPath("$.bars[2].trade_date").value("2026-10-01"))
    }

    // ==================== 3. 仅 code：默认近 250 交易日窗口（真实 calendar 求 from=to−249） ====================

    @Test
    fun `testGetKline onlyCodeResolvesDefault250TradingDayWindow`() {
        // given: 300 个连续日历日（末位=2026-10-05），300 根 bar；signal_daily 仅后 200 日（前 100 日 warm-up）
        val last = LocalDate.of(2026, 10, 5)
        val days = consecutive(last.minusDays(299), last)
        seedCalendar(days)
        seedInfo("600000")
        days.forEach { seedBar("600000", it) }
        days.takeLast(200).forEach { seedChip("600000", it) }

        // when: 仅 code → 默认近 250 交易日窗口（to=≤now 最近交易日吸附）
        mockMvc.perform(get("/api/v1/kline").param("code", "600000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bars.length()").value(250))
            // bars 升序贴合窗口：末位=最近交易日，首 bar 前 100 日为 warm-up（chip=null）
            .andExpect(jsonPath("$.bars[249].trade_date").value("2026-10-05"))
            .andExpect(jsonPath("$.bars[0].chip").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.bars[249].chip.profit_ratio").isNumber)
    }

    // ==================== 4. 异常路径：未知 code 404 / from>to 422 ====================

    @Test
    fun `testGetKline unknownCodeMaps404BusinessEnvelope`() {
        // given: stock_info 无 999999（stock_history 也无行）
        // when & then: 404 + BUSINESS_ERROR 信封（含"不存在"）
        mockMvc.perform(get("/api/v1/kline").param("code", "999999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    @Test
    fun `testGetKline fromGreaterThanToMaps422Envelope`() {
        // given: 有效 code + 显式 from>to
        seedInfo("600000")
        // when & then: 422 + BUSINESS_ERROR 信封
        mockMvc.perform(get("/api/v1/kline").param("code", "600000")
            .param("from", "2026-10-01").param("to", "2026-09-01"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("from")))
    }

    // ==================== 5. 响应键命名对拍（§17.6 命名字典） ====================

    @Test
    fun `testGetKline responseKeysSnakeCaseOnly`() {
        // given: 1 交易日 + 1 bar + 1 chip
        val d = LocalDate.of(2026, 9, 30)
        seedCalendar(listOf(d))
        seedInfo("600000")
        seedBar("600000", d)
        seedChip("600000", d)

        // when: 序列化后响应键不出现驼峰
        val body = mockMvc.perform(get("/api/v1/kline").param("code", "600000")
            .param("from", "2026-09-30").param("to", "2026-09-30"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // then: 全 snake_case 键（无 tradeDate/profitRatio 等驼峰）
        assertTrue(body.contains("\"trade_date\""), "trade_date 键")
        assertTrue(body.contains("\"turnover_rate\""), "turnover_rate 键")
        assertTrue(body.contains("\"change_pct\""), "change_pct 键")
        assertTrue(body.contains("\"profit_ratio\""), "profit_ratio 键")
        assertTrue(body.contains("\"c90_conc\""), "c90_conc 键")
        assertTrue(!body.contains("\"tradeDate\""), "不得出现 tradeDate 驼峰")
        assertTrue(!body.contains("\"profitRatio\""), "不得出现 profitRatio 驼峰")
    }
}
