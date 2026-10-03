package com.soros.v2.integration

import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.TradingCalendar
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.sentiment.SentimentComputeContext
import com.soros.v2.service.sentiment.SentimentComputeResult
import com.soros.v2.service.sentiment.SentimentComputeService
import com.soros.v2.service.sentiment.SentimentReplayService
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
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
        // 种子：2 个交易日（trading_calendar @Id=trade_date，重复 save 为 merge，不累积）
        calendarRepo.saveAll(listOf(TradingCalendar(day1), TradingCalendar(day2)))
        // 确定性 compute：d1 选龙头 RISING；d2 同周期阵亡 DEAD（end_date=d2，cycle_type=SMALL）
        Mockito.`when`(compute.computeFor(anyDate(), anyCtx())).thenAnswer { inv ->
            val date: LocalDate = inv.getArgument(0)
            if (date == day1) {
                SentimentComputeResult(
                    sentiment = SentimentCycle().apply { tradeDate = day1 },
                    dragonUpdates = listOf(
                        DragonCycle().apply {
                            code = "600000"
                            startDate = day1
                            maxStreak = 5
                            status = CycleStatus.RISING
                        },
                    ),
                    deadDragonCodes = emptyList(),
                )
            } else {
                SentimentComputeResult(
                    sentiment = SentimentCycle().apply { tradeDate = day2 },
                    dragonUpdates = listOf(
                        DragonCycle().apply {
                            code = "600000"
                            startDate = day1
                            endDate = day2
                            maxStreak = 5
                            status = CycleStatus.DEAD
                            cycleType = CycleType.SMALL
                        },
                    ),
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

    // ==================== 删段重建幂等 ====================

    @Test
    fun `testReplay rerunDeletesThenRebuildsNoDuplicate`() {
        // given: 首次回放落库
        replayService.replay(day1, day2)

        // when: 同区间重复回放（删段重建，§13.5 重放语义）
        val summary = replayService.replay(day1, day2)

        // then: 不撞 trade_date 唯一约束，行数不变（幂等）
        assertEquals(2, summary.sentimentRows, "重跑摘要 sentimentRows=2")
        assertEquals(1, summary.dragonRows, "重跑摘要 dragonRows=1")
        assertEquals(2, sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(day1, day2).size, "sentiment_cycle 仍 2 行")
        assertEquals(1, dragonRepo.findAll().size, "dragon_cycle 仍 1 行")
        val dead = dragonRepo.findAll().single()
        assertEquals(day2, dead.endDate, "重跑后阵亡行 end_date 仍=d2")
    }
}
