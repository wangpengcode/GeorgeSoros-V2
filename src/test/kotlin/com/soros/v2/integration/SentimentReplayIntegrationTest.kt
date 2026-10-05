package com.soros.v2.integration

import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.TradingCalendar
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.sentiment.SentimentComputeContext
import com.soros.v2.service.sentiment.SentimentComputeResult
import com.soros.v2.service.sentiment.SentimentComputeService
import com.soros.v2.service.sentiment.SentimentReplayService
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §13.5 情绪回放落库集成测试（@SpringBootTest + TestContainers PG16 + @MockitoBean 计算服务）。
 *
 * 覆盖（M5 集成要求）：
 * - 小区间（2 交易日）回放 → sentiment_cycle 2 行 / dragon_cycle 1 行，阵亡行 end_date 非 null、
 *   cycle_type 定性均落库（C2 静默数据丢失防线）；
 * - 删段重建幂等：同一区间重复回放不撞 trade_date 唯一约束、行数不变（§13.5）。
 *
 * computeFor 用 mock 返回确定性结果（真实派生依赖大量行情/派生列，非本测试关注点；
 * 落库契约才是本测试验证对象）。
 */
@SpringBootTest
@Testcontainers
class SentimentReplayIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private val day1 = LocalDate.of(2026, 9, 28)
    private val day2 = LocalDate.of(2026, 9, 29)

    @MockitoBean
    private lateinit var compute: SentimentComputeService

    @Autowired
    private lateinit var replayService: SentimentReplayService

    @Autowired
    private lateinit var calendarRepo: TradingCalendarRepository

    @Autowired
    private lateinit var sentimentRepo: SentimentCycleRepository

    @Autowired
    private lateinit var dragonRepo: DragonCycleRepository

    @Autowired
    private lateinit var stockHistoryRepo: StockHistoryRepository

    /**
     * Mockito.any() 的 Kotlin 非空参数安全 matcher：注册 `any(LocalDate)` matcher，返回非空占位值。
     */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return day1
    }

    /**
     * 同上：`any(SentimentComputeContext)` matcher + 非空占位 ctx。
     */
    private fun anyCtx(): SentimentComputeContext {
        Mockito.any(SentimentComputeContext::class.java)
        return SentimentComputeContext(
            date = day1,
            prevCycle = null,
            barsByCode = emptyMap(),
            calendar = listOf(day1),
            activeDragonCycles = emptyList(),
        )
    }

    @BeforeEach
    fun setUp() {
        // 隔离：三个用例共享同一 PG 容器且顺序执行，先清两表——§19.12 增量语义下「已有行跳过」，
        // 前序用例残留行会把本用例的补算日全部判为已有，filledDays=0 导致断言失真
        sentimentRepo.deleteAll()
        dragonRepo.deleteAll()
        // 投影直查用例会插真实 stock_history 行，先清表防残留影响其余回放用例（compute 已 mock，空表不影响回放）
        stockHistoryRepo.deleteAll()
        // 种子：2 个交易日（trading_calendar @Id=trade_date，重复 save 为 merge，不累积）
        calendarRepo.saveAll(listOf(TradingCalendar(day1), TradingCalendar(day2)))
        // 确定性 compute：d1 选龙头 RISING；d2 同周期阵亡 DEAD（end_date=d2，cycle_type=SMALL）
        // §19.12 决策 3：day1/day2 复用同一 DragonCycle 对象引用（模拟真实状态机就地 mutate——
        // 状态机跨日推进即同一对象引用，d2 阵亡行由 d1 同一对象 update 而非新插行）
        val leader = DragonCycle().apply {
            code = "600000"
            startDate = day1
            maxStreak = 5
            status = CycleStatus.RISING
        }
        Mockito.`when`(compute.computeFor(anyDate(), anyCtx())).thenAnswer { inv ->
            val date: LocalDate = inv.getArgument(0)
            if (date == day1) {
                // 复位（重跑幂等：force=true 重建 / force=false 续跑均回到初始态）
                leader.endDate = null
                leader.status = CycleStatus.RISING
                leader.cycleType = null
                SentimentComputeResult(
                    sentiment = SentimentCycle().apply { tradeDate = day1 },
                    dragonUpdates = listOf(leader),
                    deadDragonCodes = emptyList(),
                )
            } else {
                leader.endDate = day2
                leader.status = CycleStatus.DEAD
                leader.cycleType = CycleType.SMALL
                SentimentComputeResult(
                    sentiment = SentimentCycle().apply { tradeDate = day2 },
                    dragonUpdates = listOf(leader),
                    deadDragonCodes = listOf("600000"),
                )
            }
        }
    }

    // ==================== 小区间回放落库 ====================

    @Test
    fun `testReplay persistsSentimentAndDeadDragonRows`() {
        // when
        val summary = replayService.replay(day1, day2)

        // then: 摘要数字与实际落库行数一致
        assertEquals(2, summary.sentimentRows, "回放摘要 sentimentRows=2")
        assertEquals(1, summary.dragonRows, "回放摘要 dragonRows=1（实际保存列表）")

        // then: sentiment_cycle 落库 2 行（升序）
        val sentiments = sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2)
        assertEquals(listOf(day1, day2), sentiments.map { it.tradeDate }, "sentiment_cycle 落库 2 行升序")

        // then: dragon_cycle 落库 1 行，阵亡行 end_date 非 null + cycle_type 定性（C2）
        val dragons = dragonRepo.findAll()
        assertEquals(1, dragons.size, "dragon_cycle 落库 1 行（含阵亡行）")
        val dead = dragons.single()
        assertEquals(day2, dead.endDate, "阵亡行 end_date=非 null d2（静默数据丢失修复）")
        assertEquals(CycleType.SMALL, dead.cycleType, "阵亡行 cycle_type 定性 SMALL")
        assertEquals(day1, dead.startDate, "阵亡行 start_date=反包周期起点 d1")
    }

    // ==================== 删段重建幂等（force=true 全量重建） ====================

    @Test
    fun `testReplay rerunDeletesThenRebuildsNoDuplicate`() {
        // given: 首次回放落库
        replayService.replay(day1, day2)

        // when: 同区间重跑 force=true（§19.12 决策 4：事务0 预清理后全量重建，BackfillJob §13.5 钩子传 true）
        val summary = replayService.replay(day1, day2, force = true)

        // then: 不撞 trade_date 唯一约束，行数不变（幂等）
        assertEquals(2, summary.sentimentRows, "重跑摘要 sentimentRows=2")
        assertEquals(1, summary.dragonRows, "重跑摘要 dragonRows=1")
        assertEquals(true, summary.force, "重跑摘要 force=true")
        assertEquals(2, sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2).size, "sentiment_cycle 仍 2 行")
        assertEquals(1, dragonRepo.findAll().size, "dragon_cycle 仍 1 行")
        val dead = dragonRepo.findAll().single()
        assertEquals(day2, dead.endDate, "重跑后阵亡行 end_date 仍=d2")
    }

    // ==================== force=false 续跑幂等（§19.12 决策 4 增量补缺） ====================

    @Test
    fun `testReplay rerunForceFalseIdempotentNoDuplicateRows`() {
        // given: 首次 force=false 增量回放落库
        replayService.replay(day1, day2)

        // when: 同区间 force=false 二次调用（续跑）→ 无缺日不写
        val summary = replayService.replay(day1, day2)

        // then: 行数不变（幂等）
        assertEquals(0, summary.filledDays, "续跑无缺日 filledDays=0（§19.12 决策 4）")
        assertEquals(2, summary.skippedDays, "已有 2 日行跳过 skippedDays=2")
        assertEquals(2, sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2).size, "sentiment_cycle 仍 2 行")
        assertEquals(1, dragonRepo.findAll().size, "dragon_cycle 仍 1 行")
    }

    // ==================== 瘦身投影直查（生产事故：Null return value from advice ... isLimitUp()） ====================

    @Test
    fun `testFindReplayBars projectionReturnsAllEightFields`() {
        // given: 真实 stock_history 行（8 个投影字段各异），直查 findReplayBars
        // 生产事故：force=true 回放抛 500 "Null return value from advice does not match primitive return type
        // for: public abstract boolean ...ReplayBarProjection.isLimitUp()"——Spring Data 接口投影从 getter
        // isLimitUp() 按 JavaBean 规范剥离 is → limitUp 推导属性名，与元组别名 isLimitUp 不匹配 → primitive
        // boolean 拿到 null → 代理抛错。本用例在 TestContainers 下用真实行复现（若未复现也保留：断言 8 字段契约）。
        stockHistoryRepo.saveAll(
            listOf(
                StockHistory().apply {
                    code = "600000"
                    tradeDate = day1
                    close = BigDecimal("10.5000")
                    changePct = BigDecimal("2.5000")
                    isLimitUp = true
                    isLimitDown = false
                    limitUpStreak = 3
                    limitDownStreak = 0
                },
                StockHistory().apply {
                    code = "600001"
                    tradeDate = day2
                    close = BigDecimal("9.9000")
                    changePct = BigDecimal("-1.2000")
                    isLimitUp = false
                    isLimitDown = true
                    limitUpStreak = 0
                    limitDownStreak = 2
                },
            ),
        )

        // when
        val bars = stockHistoryRepo.findReplayBars(day1, day2)

        // then: 8 字段逐字段值正确（ORDER BY code, tradeDate → 600000/day1 在前）
        assertEquals(2, bars.size, "投影 2 行")
        val first = bars[0]
        assertEquals("600000", first.code, "first.code")
        assertEquals(day1, first.tradeDate, "first.tradeDate")
        assertEquals(BigDecimal("10.5000"), first.close, "first.close")
        assertEquals(BigDecimal("2.5000"), first.changePct, "first.changePct")
        assertEquals(true, first.isLimitUp, "first.isLimitUp")
        assertEquals(false, first.isLimitDown, "first.isLimitDown")
        assertEquals(3, first.limitUpStreak?.toInt() ?: 0, "first.limitUpStreak")
        assertEquals(0, first.limitDownStreak?.toInt() ?: 0, "first.limitDownStreak")
        val second = bars[1]
        assertEquals("600001", second.code, "second.code")
        assertEquals(day2, second.tradeDate, "second.tradeDate")
        assertEquals(BigDecimal("9.9000"), second.close, "second.close")
        assertEquals(BigDecimal("-1.2000"), second.changePct, "second.changePct")
        assertEquals(false, second.isLimitUp, "second.isLimitUp")
        assertEquals(true, second.isLimitDown, "second.isLimitDown")
        assertEquals(0, second.limitUpStreak?.toInt() ?: 0, "second.limitUpStreak")
        assertEquals(2, second.limitDownStreak?.toInt() ?: 0, "second.limitDownStreak")
    }

    // ==================== 守卫分母「当日已上市」口径（2026-10-05 生产事故：固定全市场分母误拦全部历史日） ====================

    @Test
    fun `testReplay earlyDatePartialListingNotBlockedByGuard`() {
        // given: 2021-10-08 回放；当前宇宙 100 只中仅 60 只该日已上市（firstBar ≤ 当日），40 只 2024 才上市；
        // 当日 60 只全部有柱 → 新守卫分母=当日已上市 60，60/60=100% 通过
        // （旧固定分母 countByIsStFalseAndDelistedFalse 会把 60/5224≈1% 永远压到阈值下，误拦全部历史日）
        val early = LocalDate.of(2021, 10, 8)
        val late = LocalDate.of(2024, 6, 3)
        calendarRepo.save(TradingCalendar(early))
        stockHistoryRepo.saveAll(
            (1..60).map { StockHistory().apply { code = "600$it"; tradeDate = early } } +
                (1..40).map { StockHistory().apply { code = "601$it"; tradeDate = late } },
        )
        Mockito.`when`(compute.computeFor(anyDate(), anyCtx())).thenReturn(
            SentimentComputeResult(SentimentCycle().apply { tradeDate = early }, emptyList(), emptyList()),
        )

        // when
        val summary = replayService.replay(early, early)

        // then: 早期日期不再被误拦（分母随上市进度增长，未上市≠缺数据）
        assertEquals(1, summary.filledDays, "2021-10-08 60/60 通过守卫 filledDays=1")
        assertTrue(summary.deferredDates.isEmpty(), "早期日期不再被误拦 defer 空")
    }
}
