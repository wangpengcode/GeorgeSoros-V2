package com.soros.v2.integration

import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §六 回填 SQL 集成测试（@SpringBootTest + TestContainers PG16；Flyway V4 已建 stage 表）。
 *
 * 覆盖（PLAN Step 6 / §六.1 / §六.6）：
 * 1. V4 stage 表迁移结构：UNLOGGED / 16 列 / 无约束 / 无索引（COPY 两段式瞬态中转，不拖慢 COPY）；
 * 2. merge_stage_to_main.sql：同批重跑幂等不重复 / 部分行已存在时正确覆盖 / 15 列显式映射逐列断言
 *    （created_at 不覆盖，DO UPDATE 语义）；
 * 3. recompute_limit_streaks.sql（5 种子代码，期望值手工算好写死）：
 *    ① 连板/断板  ② 停牌断板（缺口两侧不续板，期望 1,2,1,2）  ③ IPO 首 5 日守卫
 *    ④ GEM/STAR 阈值 19.9 vs 其他 9.9  ⑤ 跌停镜像
 *    ⑥ 回填起点前数日内 IPO 早期行缺失不超额守卫（Step 6：守卫按交易日历计数，与 Kotlin 抽查严格等价）。
 *
 * 构造：程序化 INSERT（LocalDate 序列 + 涨跌幅设定），不依赖任何外部数据源。
 */
@SpringBootTest
@Testcontainers
class BackfillSqlIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    // 2026-01-05(Mon) ~ 2026-01-12 连续交易周（含 01-09 周五 / 01-12 周一）
    private val d1 = LocalDate.of(2026, 1, 5)
    private val d2 = LocalDate.of(2026, 1, 6)
    private val d3 = LocalDate.of(2026, 1, 7)
    private val d4 = LocalDate.of(2026, 1, 8)
    private val d5 = LocalDate.of(2026, 1, 9)
    private val d6 = LocalDate.of(2026, 1, 12)

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE stock_history_stage, stock_history, stock_info, trading_calendar")
    }

    // ==================== 1. V4 stage 迁移结构 ====================

    @Test
    fun `testStageTable unlogged16ColumnsNoConstraintsNoIndexes`() {
        // when: Flyway V4 已建（@SpringBootTest 上下文）
        val relpersistence = jdbc.queryForObject(
            "SELECT relpersistence FROM pg_class WHERE relname = 'stock_history_stage'",
            String::class.java,
        )
        assertEquals("u", relpersistence, "UNLOGGED（relpersistence='u'，不写 WAL，崩溃自清）")

        val cols = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'stock_history_stage'",
            Int::class.java,
        )
        assertEquals(16, cols, "16 列 = stock_history 数据列全同（无 id；含派生列默认 false/0）")

        val constraints = jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conrelid = 'stock_history_stage'::regclass",
            Int::class.java,
        )
        assertEquals(0, constraints, "无 UNIQUE/CHECK/PK 约束（约束拖慢 COPY；主表 merge 时兜底）")

        val indexes = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'stock_history_stage'",
            Int::class.java,
        )
        assertEquals(0, indexes, "无任何索引（瞬态中转，不建索引）")
    }

    // ==================== 2. merge_stage_to_main.sql ====================

    private fun stageFullRow(code: String, tradeDate: LocalDate) {
        jdbc.update(
            """
            INSERT INTO stock_history_stage (code, trade_date, open, close, high, low, volume, amount,
                                             change_pct, turnover_rate, is_limit_up, is_limit_down,
                                             limit_up_streak, limit_down_streak, data_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            code,
            Date.valueOf(tradeDate),
            BigDecimal("10.0000"),
            BigDecimal("13.2000"),
            BigDecimal("13.5000"),
            BigDecimal("9.8000"),
            1_000_000L,
            BigDecimal("13200000.0000"),
            BigDecimal("9.9800"),
            BigDecimal("2.1000"),
            true,
            false,
            3,
            0,
            "BAOSTOCK",
        )
    }

    private fun merge() {
        val sql = ClassPathResource("sql/backfill/merge_stage_to_main.sql")
            .inputStream.bufferedReader().use { it.readText() }
        jdbc.execute(sql)
    }

    @Test
    fun `testMerge sameBatchRerunIdempotentNoDuplicates`() {
        // given: 同批数据（幂等重跑 = 断点续传语义，§六.1）
        stageFullRow("600000", d1)
        merge()
        assertEquals(1L, rowCount("600000", d1), "首次合并落 1 行")

        // when: 中断重跑——同批数据经 TRUNCATE 清 stage 后再次 COPY → merge
        //       （BackfillJob 每批 COPY→merge→TRUNCATE，stage 逐批清空；见 §六.1③ 与测试下方 invariant 注释）
        jdbc.execute("TRUNCATE stock_history_stage")
        stageFullRow("600000", d1)
        merge()

        // then: ON CONFLICT (code, trade_date) DO UPDATE 幂等，不重复插行
        assertEquals(1L, rowCount("600000", d1), "重跑不产生重复行")

        // invariant 注释（供 Implementer）：merge 假定 stage 内 (code, trade_date) 唯一——
        // 若 stage 存在同键两行，INSERT SELECT ON CONFLICT DO UPDATE 抛
        // "cannot affect row a second time"。BackfillJob 靠每批后 TRUNCATE stage 维持此不变式；
        // 中断窗口（merge 后/truncate 前）恰好残留时，重跑需先清 stage 再 COPY。
    }

    @Test
    fun `testMerge overridesExistingRowValues`() {
        // given: 主表已有旧值行（如上一批错误数据）
        jdbc.update(
            "INSERT INTO stock_history (code, trade_date, close, data_source) VALUES (?, ?, ?, 'AKSHARE')",
            "600000",
            Date.valueOf(d1),
            BigDecimal("1.0000"),
        )
        stageFullRow("600000", d1)

        // when: 合并覆盖
        merge()

        // then: 既有行被正确覆盖（部分行已存在时正确覆盖）
        val close = jdbc.queryForObject(
            "SELECT close FROM stock_history WHERE code = ? AND trade_date = ?",
            BigDecimal::class.java,
            "600000",
            Date.valueOf(d1),
        )
        assertEquals(BigDecimal("13.2000"), close, "close 被覆盖为新值")
        val source = jdbc.queryForObject(
            "SELECT data_source FROM stock_history WHERE code = ? AND trade_date = ?",
            String::class.java,
            "600000",
            Date.valueOf(d1),
        )
        assertEquals("BAOSTOCK", source, "data_source 被覆盖")
        assertEquals(1L, rowCount("600000", d1), "仍 1 行")
    }

    @Test
    fun `testMerge mapsAll15ColumnsExplicitly`() {
        // given: stage 全 15 列唯一值
        stageFullRow("600000", d1)

        // when
        merge()

        // then: 15 列显式映射逐列断言（§六.1：SELECT 列清单 + DO UPDATE 列清单逐一核对）
        val row = jdbc.queryForMap(
            "SELECT code, trade_date, open, close, high, low, volume, amount, change_pct, turnover_rate, " +
                "is_limit_up, is_limit_down, limit_up_streak, limit_down_streak, data_source, created_at " +
                "FROM stock_history WHERE code = ? AND trade_date = ?",
            "600000",
            Date.valueOf(d1),
        )
        assertEquals("600000", row["code"], "code 映射")
        assertEquals(Date.valueOf(d1), row["trade_date"], "trade_date 映射")
        assertEquals(BigDecimal("10.0000"), row["open"], "open 映射")
        assertEquals(BigDecimal("13.2000"), row["close"], "close 映射")
        assertEquals(BigDecimal("13.5000"), row["high"], "high 映射")
        assertEquals(BigDecimal("9.8000"), row["low"], "low 映射")
        assertEquals(1_000_000L, (row["volume"] as Number).toLong(), "volume 映射（股）")
        assertEquals(BigDecimal("13200000.0000"), row["amount"], "amount 映射（元）")
        assertEquals(BigDecimal("9.9800"), row["change_pct"], "change_pct 映射（不复权）")
        assertEquals(BigDecimal("2.1000"), row["turnover_rate"], "turnover_rate 映射")
        assertEquals(true, row["is_limit_up"], "is_limit_up 映射")
        assertEquals(false, row["is_limit_down"], "is_limit_down 映射")
        assertEquals(3, (row["limit_up_streak"] as Number).toInt(), "limit_up_streak 映射")
        assertEquals(0, (row["limit_down_streak"] as Number).toInt(), "limit_down_streak 映射")
        assertEquals("BAOSTOCK", row["data_source"], "data_source 映射")
        assertTrue(row["created_at"] != null, "created_at 由主表 DEFAULT NOW() 填充（COPY 不传，不覆盖已有）")
    }

    @Test
    fun `testMerge doesNotOverwriteCreatedAtOnConflict`() {
        // given: 主表行带旧 created_at（如增量路径先落库）
        jdbc.update(
            "INSERT INTO stock_history (code, trade_date, close, data_source, created_at) VALUES (?, ?, ?, 'AKSHARE', '2020-01-01 00:00:00')",
            "600000",
            Date.valueOf(d1),
            BigDecimal("5.0000"),
        )
        stageFullRow("600000", d1)

        // when
        merge()

        // then: DO UPDATE 不覆盖 created_at（§六.1 注释口径；字典铁律：created_at 只记录行创建）
        val createdAt = jdbc.queryForObject(
            "SELECT created_at FROM stock_history WHERE code = ? AND trade_date = ?",
            java.sql.Timestamp::class.java,
            "600000",
            Date.valueOf(d1),
        )
        assertEquals(java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), createdAt, "created_at 不被覆盖（DO UPDATE 不设此列）")
    }

    private fun rowCount(code: String, tradeDate: LocalDate): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM stock_history WHERE code = ? AND trade_date = ?",
            Long::class.java,
            code,
            Date.valueOf(tradeDate),
        ) ?: 0L

    // ==================== 3. recompute_limit_streaks.sql ====================

    private fun seedCalendar() {
        var d = d1
        val end = LocalDate.of(2026, 1, 20)
        while (!d.isAfter(end)) {
            if (d.dayOfWeek.value <= 5) { // Mon-Fri 为交易日（§11.1 简化口径）
                jdbc.update("INSERT INTO trading_calendar (trade_date) VALUES (?)", Date.valueOf(d))
            }
            d = d.plusDays(1)
        }
    }

    private fun seedInfo(code: String, board: String, ipoDate: LocalDate? = null) {
        jdbc.update(
            "INSERT INTO stock_info (code, name, market, board, is_st, delisted, ipo_date) VALUES (?, ?, 'SH', ?, false, false, ?)",
            code,
            "测试$code",
            board,
            if (ipoDate != null) Date.valueOf(ipoDate) else null,
        )
    }

    /** 插入一根待补算日K（只给 code/trade_date/change_pct；派生列走默认 false/0，由 recompute 补算） */
    private fun seedBar(code: String, tradeDate: LocalDate, changePct: String) {
        jdbc.update(
            "INSERT INTO stock_history (code, trade_date, change_pct, data_source) VALUES (?, ?, ?, 'BAOSTOCK')",
            code,
            Date.valueOf(tradeDate),
            BigDecimal(changePct),
        )
    }

    private fun runRecompute() {
        dataSource.connection.use { conn ->
            ScriptUtils.executeSqlScript(conn, ClassPathResource("sql/backfill/recompute_limit_streaks.sql"))
        }
    }

    /** 断言 单行 派生四列（is_limit_up / is_limit_down / limit_up_streak / limit_down_streak）与手工期望一致 */
    private fun assertDerived(
        code: String,
        tradeDate: LocalDate,
        up: Boolean,
        down: Boolean,
        upStreak: Int,
        downStreak: Int,
    ) {
        val row = jdbc.queryForMap(
            "SELECT is_limit_up, is_limit_down, limit_up_streak, limit_down_streak FROM stock_history WHERE code = ? AND trade_date = ?",
            code,
            Date.valueOf(tradeDate),
        )
        assertEquals(up, row["is_limit_up"], "$code $tradeDate is_limit_up")
        assertEquals(down, row["is_limit_down"], "$code $tradeDate is_limit_down")
        assertEquals(upStreak, (row["limit_up_streak"] as Number).toInt(), "$code $tradeDate limit_up_streak")
        assertEquals(downStreak, (row["limit_down_streak"] as Number).toInt(), "$code $tradeDate limit_down_streak")
    }

    @Test
    fun `testRecompute fiveSeedsDerivedColumnsMatchManualExpectation`() {
        // given: 交易日历 + 5 只种子（跨 MAIN/GEM/STAR 边界）
        seedCalendar()
        seedInfo("600001", "MAIN")                 // A：连板/断板
        seedInfo("600002", "MAIN")                 // B：停牌断板（01-07 无行）
        seedInfo("300750", "GEM", ipoDate = d1)    // C：IPO 首 5 日守卫 + GEM 19.9
        seedInfo("600004", "MAIN")                 // D：跌停镜像
        seedInfo("688001", "STAR")                 // E：STAR 19.9 阈值

        // A 连板/断板：9.98 / 9.95 / 1.00 / 10.00 → streak 1,2,0,1
        seedBar("600001", d1, "9.98")
        seedBar("600001", d2, "9.95")
        seedBar("600001", d3, "1.00")
        seedBar("600001", d4, "10.00")
        // B 停牌断板：01-05,01-06,01-08,01-09（01-07 无行=停牌缺口）→ streak 1,2,1,2（PLAN §六.6 穿透修正）
        seedBar("600002", d1, "9.98")
        seedBar("600002", d2, "9.95")
        seedBar("600002", d4, "9.98")
        seedBar("600002", d5, "9.95")
        // C IPO 守卫 + GEM 19.9：前 5 根（01-05..01-09）20.00 被强制清零；第 6 根（01-12）起算板 1
        seedBar("300750", d1, "20.00")
        seedBar("300750", d2, "20.00")
        seedBar("300750", d3, "20.00")
        seedBar("300750", d4, "20.00")
        seedBar("300750", d5, "20.00")
        seedBar("300750", d6, "20.00")
        // D 跌停镜像：-9.98 / -9.95 / -1.00 → down streak 1,2,0
        seedBar("600004", d1, "-9.98")
        seedBar("600004", d2, "-9.95")
        seedBar("600004", d3, "-1.00")
        // E STAR 阈值 19.9：10.00 非板 / 19.90 板 / -10.00 非跌停 → up 0,1,0
        seedBar("688001", d1, "10.00")
        seedBar("688001", d2, "19.90")
        seedBar("688001", d3, "-10.00")

        // when: 回填合并后一次性补算（§六.6）
        runRecompute()

        // then: 逐行与手工期望一致（A）
        assertDerived("600001", d1, true, false, 1, 0)
        assertDerived("600001", d2, true, false, 2, 0)
        assertDerived("600001", d3, false, false, 0, 0)
        assertDerived("600001", d4, true, false, 1, 0)
        // then: B 停牌断板——缺口两侧不续板（01-08 重新从 1 起）
        assertDerived("600002", d1, true, false, 1, 0)
        assertDerived("600002", d2, true, false, 2, 0)
        assertDerived("600002", d4, true, false, 1, 0)
        assertDerived("600002", d5, true, false, 2, 0)
        // then: C IPO 首 5 日守卫 + GEM 19.9（20.00 全命中阈值但被守卫清零；第 6 根才算板）
        assertDerived("300750", d1, false, false, 0, 0)
        assertDerived("300750", d2, false, false, 0, 0)
        assertDerived("300750", d3, false, false, 0, 0)
        assertDerived("300750", d4, false, false, 0, 0)
        assertDerived("300750", d5, false, false, 0, 0)
        assertDerived("300750", d6, true, false, 1, 0)
        // then: D 跌停镜像（limit_down_streak 与 limit_up_streak 镜像派生，§4.9 崩塌池依据）
        assertDerived("600004", d1, false, true, 0, 1)
        assertDerived("600004", d2, false, true, 0, 2)
        assertDerived("600004", d3, false, false, 0, 0)
        // then: E STAR 19.9 阈值（10.00 主板视为板但 STAR 不算；-10.00 不触发 -19.9 跌停）
        assertDerived("688001", d1, false, false, 0, 0)
        assertDerived("688001", d2, true, false, 1, 0)
        assertDerived("688001", d3, false, false, 0, 0)
    }

    @Test
    fun `testRecompute ipoBeforeBackfillStartMissingEarlyRowsNotOverGuarded`() {
        // given: 交易日历 + IPO 股 F（2026-01-05 上市，MAIN）；回填起点前数日内 IPO——
        //        早期行 01-05..01-07 缺失，仅 01-08 起 3 根（§六.6 回填窗口起点晚于 IPO 的典型场景；
        //        旧 SQL 按已落库行 ROW_NUMBER 计数：d4=1,d5=2,d6=3 全≤5 → 超额守卫 d6，与 Kotlin 抽查不一致）
        seedCalendar()
        seedInfo("600005", "MAIN", ipoDate = d1)
        seedBar("600005", d4, "20.00")
        seedBar("600005", d5, "20.00")
        seedBar("600005", d6, "20.00")

        // when: 回填合并后一次性补算（§六.6）
        runRecompute()

        // then: 守卫按交易日历计数（d4=第4交易日、d5=第5交易日仍守卫；d6=第6交易日解除守卫 → 板 1）
        //        与 Kotlin LimitStreakComputer「自 ipo 起前 5 个交易日」严格等价
        assertDerived("600005", d4, false, false, 0, 0)
        assertDerived("600005", d5, false, false, 0, 0)
        assertDerived("600005", d6, true, false, 1, 0)
    }
}
