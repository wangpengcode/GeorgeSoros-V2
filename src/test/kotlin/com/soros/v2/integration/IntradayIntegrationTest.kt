package com.soros.v2.integration

import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.intraday.IntradayPollService
import com.soros.v2.service.intraday.IntradayPythonClient
import com.soros.v2.service.intraday.dto.IntradayBidAskResponse
import com.soros.v2.service.intraday.dto.IntradayPoolRowDto
import com.soros.v2.service.intraday.dto.IntradayPoolsResponse
import com.soros.v2.service.intraday.dto.IntradaySpotResponse
import com.soros.v2.service.intraday.dto.IntradaySpotStockDto
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §19.13.2 盘中监控 Kotlin 侧集成测试（@SpringBootTest + TestContainers PG16 + @MockitoBean Python 客户端）。
 *
 * 覆盖「poll → 落库 → overview 读出」全链路（任务要求）：
 * - 首轮 pollRound：三池 snap 落库（ZT/ZB/DT）+ kpi_series 采样点（池+spot 双齐）+ 无 diff 事件（无基线）；
 * - 二轮 pollRound：diff 事件（OPEN/ZT/MAXCHG）落库 → overview.alert_count 反映；
 * - GET /api/v1/intraday/overview：snake_case 信封 {status, poll_time, alert_count, last_poll_at, sources[]}，
 *   status=OK（全部源 NORMAL 且有快照）；
 * - 幂等 upsert：同 snap_at+pool 不重复（同轮重跑零新增）。
 *
 * Python 客户端 @MockitoBean（真实会打 localhost:8000）；IntradayJob 的 @Scheduled 轮询在测试内
 * 因 trading_calendar 空（isTradingDay=false）自动跳过，零干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IntradayIntegrationTest {

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

    @Autowired
    private lateinit var pollService: IntradayPollService

    @MockitoBean
    private lateinit var pythonClient: IntradayPythonClient

    /** 交易日历 mock：isTradingDay=false → IntradayJob @Scheduled 轮询守卫恒跳过（防真实运行时段干扰断言） */
    @MockitoBean
    private lateinit var calendar: TradingCalendarService

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE intraday_pool_snap, intraday_pool_state, intraday_event, intraday_archive, intraday_replay")
        Mockito.reset(pythonClient, calendar)
        Mockito.`when`(calendar.isTradingDay(anyDate())).thenReturn(false)
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（LocalDate 占位） */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 9, 30)
    }

    // ==================== 构造辅助 ====================

    private fun row(code: String, lianban: Int? = null): IntradayPoolRowDto =
        IntradayPoolRowDto(code = code, name = code, changePct = BigDecimal("9.98"), lianban = lianban)

    /** Mockito 对 suspend 方法 stub：doReturn 不在非 suspend 上下文调 suspend（runBlocking 包裹） */
    private fun stubPools(resp: IntradayPoolsResponse) {
        runBlocking { Mockito.doReturn(resp).`when`(pythonClient).fetchPools(Mockito.anyString()) }
    }

    private fun stubSpot(resp: IntradaySpotResponse) {
        runBlocking { Mockito.doReturn(resp).`when`(pythonClient).fetchSpot() }
    }

    private fun stubBidAsk(resp: IntradayBidAskResponse) {
        runBlocking { Mockito.doReturn(resp).`when`(pythonClient).fetchBidAsk(Mockito.anyString()) }
    }

    // ==================== 1. poll→落库→overview 全链路 ====================

    @Test
    fun `testPollRoundWritesSnapsAndOverviewReadsBack`() {
        // given: 首轮三池（limit_up 1 / broken 0 / limit_down 1）+ spot
        stubPools(
            IntradayPoolsResponse(
                "ok", "20260930",
                limitUp = listOf(row("600001", 1)),
                broken = emptyList(),
                limitDown = listOf(row("600003", 1)),
            ),
        )
        stubSpot(
            IntradaySpotResponse(
                "ok", 2,
                listOf(
                    IntradaySpotStockDto("600001", changePct = BigDecimal("9.98")),
                    IntradaySpotStockDto("600003", changePct = BigDecimal("-9.98")),
                ),
            ),
        )
        stubBidAsk(IntradayBidAskResponse("ok", "600001", listOf(), listOf()))

        // when: 一轮 poll
        runBlocking { pollService.pollRound() }

        // then: 三池 snap 落库（真实 JPA + 真实 CHAR(6)/JSONB 列）
        val snapCount = jdbc.queryForObject(
            "SELECT count(*) FROM intraday_pool_snap", Int::class.java,
        ) ?: 0
        assertEquals(3, snapCount, "三池快照落库")
        val ztCount = jdbc.queryForObject(
            "SELECT count(*) FROM intraday_pool_snap WHERE pool = 'ZT'", Int::class.java,
        ) ?: 0
        assertEquals(1, ztCount, "ZT 池 1 帧")

        // then: 首轮无基线 → 零 diff 事件
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM intraday_event WHERE trade_date = ?",
                Long::class.java, LocalDate.now(),
            ) ?: -1L,
            "首轮无 diff 事件",
        )

        // then: kpi_series 采样点已追加（池+spot 双齐才记）
        val kpiPoints = jdbc.queryForObject(
            "SELECT count(*) FROM intraday_replay WHERE page::text LIKE '%kpi_series%'", Int::class.java,
        ) ?: 0
        assertEquals(1, kpiPoints, "kpi_series 采样点 1 个")

        // then: overview 读出（snake_case 信封 + status=OK + sources 3）
        mockMvc.perform(get("/api/v1/intraday/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.poll_time").isNotEmpty)
            .andExpect(jsonPath("$.alert_count").value(0))
            .andExpect(jsonPath("$.last_poll_at").isNotEmpty)
            .andExpect(jsonPath("$.sources.length()").value(3))
            .andExpect(jsonPath("$.sources[0].rate_state").value("NORMAL"))

        // then: 客户端被真实调用（verify 需 suspend 上下文）
        runBlocking {
            Mockito.verify(pythonClient).fetchPools(Mockito.anyString())
            Mockito.verify(pythonClient).fetchSpot()
        }
    }

    // ==================== 2. 二轮 diff：事件落库 → overview.alert_count ====================

    @Test
    fun `testSecondRoundDiffEmitsEventsAndAlertCountReflects`() {
        // given: 首轮 ZT=[A(3)]；二轮 ZT=[B(2)]（A 开板 / B 新封 / 最高板易主 A→B）
        stubPools(
            IntradayPoolsResponse("ok", "20260930", limitUp = listOf(row("A", 3)), broken = emptyList(), limitDown = emptyList()),
        )
        stubSpot(IntradaySpotResponse("ok", 1, listOf(IntradaySpotStockDto("A", changePct = BigDecimal("9.98")))))
        stubBidAsk(IntradayBidAskResponse("ok", "A", listOf(), listOf()))
        runBlocking { pollService.pollRound() }

        stubPools(
            IntradayPoolsResponse("ok", "20260930", limitUp = listOf(row("B", 2)), broken = emptyList(), limitDown = emptyList()),
        )
        stubSpot(IntradaySpotResponse("ok", 1, listOf(IntradaySpotStockDto("B", changePct = BigDecimal("9.98")))))
        stubBidAsk(IntradayBidAskResponse("ok", "B", listOf(), listOf()))

        // when: 二轮 poll
        runBlocking { pollService.pollRound() }

        // then: diff 事件落库（OPEN(A) + ZT(B) + MAXCHG(B) = 3）
        val types = jdbc.queryForList("SELECT ev_type FROM intraday_event WHERE trade_date = ?", LocalDate.now())
            .map { it["ev_type"].toString() }
        assertTrue("OPEN" in types, "A 开板")
        assertTrue("ZT" in types, "B 新封板")
        assertTrue("MAXCHG" in types, "最高板易主 A→B")

        // then: overview.alert_count=3
        mockMvc.perform(get("/api/v1/intraday/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.alert_count").value(3))
    }

    // ==================== 3. 幂等 upsert：同轮重跑零新增 ====================

    @Test
    fun `testPollRound idempotentSameSnapNoDuplicate`() {
        // given: 首轮三池
        stubPools(
            IntradayPoolsResponse("ok", "20260930", limitUp = listOf(row("600001", 1)), broken = emptyList(), limitDown = emptyList()),
        )
        stubSpot(IntradaySpotResponse("ok", 1, listOf(IntradaySpotStockDto("600001", changePct = BigDecimal("9.98")))))
        stubBidAsk(IntradayBidAskResponse("ok", "600001", listOf(), listOf()))

        // when: 同轮连续重跑 2 次（钉死 snap_at → 同 poll_time+pool 幂等 upsert）
        val fixed = java.time.LocalDateTime.of(2026, 9, 30, 10, 0, 0)
        Mockito.mockStatic(java.time.LocalDateTime::class.java, Mockito.CALLS_REAL_METHODS).use { mocked ->
            mocked.`when`<java.time.LocalDateTime> { java.time.LocalDateTime.now() }.thenReturn(fixed)
            runBlocking { pollService.pollRound() }
            runBlocking { pollService.pollRound() }
        }

        // then: 同 snap_at+pool 不重复 → 3 帧（不翻倍）
        val snapCount = jdbc.queryForObject("SELECT count(*) FROM intraday_pool_snap", Int::class.java) ?: 0
        assertEquals(3, snapCount, "同轮重跑零新增（幂等 upsert）")
    }
}
