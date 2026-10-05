package com.soros.v2.integration

import com.soros.v2.service.signal.SignalReplayService
import com.soros.v2.service.signal.SignalReplaySummary
import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §19.11.1 信号预计算回放落库集成测试（@SpringBootTest + TestContainers PG16 + Flyway V1..V9 迁移）。
 *
 * 覆盖（§19.11.1 落码清单·回放服务）：
 * 1. 60 股小样本回放 → signal_daily / market_daily / sector_daily 三表行数断言；
 * 2. 重跑幂等 diff=0（删段重建语义 §17.2，PK 不撞、行数不变）；
 * 3. 分批 200 股/事务：250 股 = 2 批，故意失败点（批 1 含无 stock_info 的毒票）→
 *    批 1 事务回滚（毒票 0 行）、批 2 照常提交（断点续跑）；修复后重跑 → 全量补齐。
 *
 * 红线（预期红）：SignalReplayServiceImpl 为 TODO 空壳，本测试当前红；
 * Implementer 实现后应全绿（本测试即回放服务的落库契约规格）。
 */
@SpringBootTest
@Testcontainers
class SignalReplayServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var replayService: SignalReplayService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val d1 = LocalDate.of(2026, 9, 28)
    private val d2 = LocalDate.of(2026, 9, 29)
    private val d3 = LocalDate.of(2026, 9, 30)

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE signal_daily, market_daily, sector_daily, stock_history, stock_info, trading_calendar")
    }

    // ==================== 构造辅助 ====================

    private fun seedCalendar(vararg dates: LocalDate) {
        dates.forEach { jdbc.update("INSERT INTO trading_calendar (trade_date) VALUES (?)", Date.valueOf(it)) }
    }

    private fun seedInfo(code: String, board: String = "MAIN", industry: String = "银行") {
        jdbc.update(
            "INSERT INTO stock_info (code, name, market, board, is_st, delisted, ipo_date, industry) VALUES (?, ?, 'SH', ?, false, false, NULL, ?)",
            code, "测试$code", board, """["$industry"]""",
        )
    }

    private fun seedInfoRange(fromCode: Int, toCode: Int, board: String = "MAIN", industry: String = "银行") {
        (fromCode..toCode).forEach { seedInfo(it.toString(), board, industry) }
    }

    private fun seedBars(code: String, dates: List<LocalDate>, changePct: String = "1.00") {
        dates.forEach { d ->
            jdbc.update(
                """
                INSERT INTO stock_history (code, trade_date, open, close, high, low, volume, amount,
                                            change_pct, turnover_rate, is_limit_up, limit_up_streak, data_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BAOSTOCK')
                """,
                code, Date.valueOf(d),
                BigDecimal("10.00"), BigDecimal("10.20"), BigDecimal("10.50"), BigDecimal("9.80"),
                1_000_000L, BigDecimal("10200000.00"),
                BigDecimal(changePct), BigDecimal("1.00"),
                changePct == "9.98", if (changePct == "9.98") 1.toShort() else 0.toShort(),
            )
        }
    }

    private fun seedStockRange(fromCode: Int, toCode: Int, dates: List<LocalDate>, changePct: String = "1.00") {
        (fromCode..toCode).forEach { seedBars(it.toString(), dates, changePct) }
    }

    private fun countSignalDaily(from: LocalDate, to: LocalDate): Long = jdbc.queryForObject(
        "SELECT count(*) FROM signal_daily WHERE trade_date BETWEEN ? AND ?",
        Long::class.java, Date.valueOf(from), Date.valueOf(to),
    )

    private fun countMarketDaily(from: LocalDate, to: LocalDate): Long = jdbc.queryForObject(
        "SELECT count(*) FROM market_daily WHERE trade_date BETWEEN ? AND ?",
        Long::class.java, Date.valueOf(from), Date.valueOf(to),
    )

    private fun countSectorDaily(from: LocalDate, to: LocalDate): Long = jdbc.queryForObject(
        "SELECT count(*) FROM sector_daily WHERE trade_date BETWEEN ? AND ?",
        Long::class.java, Date.valueOf(from), Date.valueOf(to),
    )

    // ==================== 1. 60 股小样本回放 ====================

    @Test
    fun `testReplay60Stocks populates three tables with expected row counts`() {
        // given: 3 交易日 + 60 股（600001..600060，单 industry "银行"）×3 日 bar
        seedCalendar(d1, d2, d3)
        seedInfoRange(600001, 600060)
        seedStockRange(600001, 600060, listOf(d1, d2, d3))

        // when
        val summary = replayService.replay(d1, d3)

        // then: 三表行数 + 摘要数字一致（signal=股×日=180、market=日=3、sector=日×板块=3）
        assertEquals(180, summary.signalRows, "signal_daily 行数 = 60 股 × 3 日")
        assertEquals(3, summary.marketRows, "market_daily 行数 = 3 交易日")
        assertEquals(3, summary.sectorRows, "sector_daily 行数 = 3 日 × 1 板块")
        assertEquals(60, summary.codesProcessed, "处理股票数 = 60")
        assertEquals(180L, countSignalDaily(d1, d3), "signal_daily 实际行数")
        assertEquals(3L, countMarketDaily(d1, d3), "market_daily 实际行数")
        assertEquals(3L, countSectorDaily(d1, d3), "sector_daily 实际行数")
    }

    // ==================== 2. 重跑幂等 diff=0 ====================

    @Test
    fun `testReplay rerun idempotent diff zero`() {
        // given: 60 股 × 2 日
        seedCalendar(d1, d2)
        seedInfoRange(600001, 600060)
        seedStockRange(600001, 600060, listOf(d1, d2))

        // when: 同区间回放两次（删段重建语义 §17.2）
        val first = replayService.replay(d1, d2)
        val second = replayService.replay(d1, d2)

        // then: 摘要逐字段一致（幂等，不产生重复行）
        assertEquals(first.signalRows, second.signalRows, "signal_rows 幂等")
        assertEquals(first.marketRows, second.marketRows, "market_rows 幂等")
        assertEquals(first.sectorRows, second.sectorRows, "sector_rows 幂等")
        assertEquals(first.codesProcessed, second.codesProcessed, "codes_processed 幂等")
        // then: diff=0 —— 重跑后行数不变、无 PK 撞（signal_daily PK code+trade_date 天然防重）
        assertEquals(120L, countSignalDaily(d1, d2), "重跑后 signal_daily 行数不变 = 60×2")
        assertEquals(2L, countMarketDaily(d1, d2), "重跑后 market_daily 行数不变 = 2")
        assertEquals(2L, countSectorDaily(d1, d2), "重跑后 sector_daily 行数不变 = 2")
    }

    // ==================== 3. 分批 200 股/事务 + 断点续跑 ====================

    /**
     * §19.11.1：全历史回放按股分批 **200 股/事务**，故意失败点验证断点续跑。
     *
     * 毒票 = 有 bar 无 stock_info（无法派生 industry/board）→ 所在批事务回滚；
     * 其余批照常提交；修复毒票后重跑，全量补齐（断点续跑语义，§13.5 模式）。
     * 250 股 = 2 批（批1=600001..600200、批2=600201..600250）。
     */
    @Test
    fun `testReplay batch200 per transaction resume after failed batch`() {
        // given: 250 股 × 2 日 = 2 批；毒票 600001 无 stock_info
        seedCalendar(d1, d2)
        seedInfoRange(600002, 600250)
        seedStockRange(600001, 600250, listOf(d1, d2))

        // when: 首跑（批1 因毒票失败回滚；批2 应照常提交 —— 断点续跑）
        replayService.replay(d1, d2)

        // then: 批2（600201..600250 = 50 股）已提交；毒票批（600001..600200）0 行
        assertEquals(0L, countSignalDailyByCode("600001", d1, d2), "毒票 600001 无行（批1 回滚）")
        assertEquals(100L, countSignalDailyByCodes(600201, 600250, d1, d2), "批2 50 股 × 2 日 = 100 行已提交")
        assertEquals(0L, countSignalDailyByCodes(600001, 600200, d1, d2), "批1 200 股 0 行（整批回滚）")

        // when: 修复毒票（补 stock_info）后重跑
        seedInfo("600001")
        val second = replayService.replay(d1, d2)

        // then: 全量补齐 250 股 × 2 日 = 500 行（断点续跑完成）
        assertEquals(500, second.signalRows, "重跑后 signal_daily 全量 = 250×2")
        assertEquals(500L, countSignalDaily(d1, d2), "重跑后实际行数 = 500")
    }

    private fun countSignalDailyByCode(code: String, from: LocalDate, to: LocalDate): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_daily WHERE code = ? AND trade_date BETWEEN ? AND ?",
            Long::class.java, code, Date.valueOf(from), Date.valueOf(to),
        )

    private fun countSignalDailyByCodes(fromCode: Int, toCode: Int, from: LocalDate, to: LocalDate): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_daily WHERE code BETWEEN ? AND ? AND trade_date BETWEEN ? AND ?",
            Long::class.java, fromCode.toString(), toCode.toString(), Date.valueOf(from), Date.valueOf(to),
        )
}
