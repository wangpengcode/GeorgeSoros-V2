package com.soros.v2.controller

import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.intraday.IntradayOverviewService
import com.soros.v2.service.intraday.dto.IntradayOverviewDto
import com.soros.v2.service.intraday.dto.IntradaySourceStatusDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §19.13.2 IntradayController 端点契约测试（MockMvc standalone，风格对齐 KlineControllerTest/StrategyControllerTest）。
 *
 * 契约（§19.13.2 端点契约 + 任务定稿）：
 * - GET /api/v1/intraday/overview → 200；
 * - snake_case 信封：{status, poll_time, alert_count, last_poll_at, sources[]}；
 * - sources[] 元素：{source, ok, last_ok_at, rate_state}（§17.6 内存运行时键已定稿）。
 */
class IntradayControllerTest {

    private lateinit var overviewService: IntradayOverviewService
    private lateinit var controller: IntradayController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        overviewService = Mockito.mock(IntradayOverviewService::class.java)
        controller = IntradayController(overviewService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `testOverview successReturnsSnakeCaseEnvelope`() {
        // given: 全字段 overview（status/poll_time/alert_count/last_poll_at/sources 齐）
        val overview = IntradayOverviewDto(
            status = "OK",
            pollTime = "10:00:00",
            alertCount = 3L,
            lastPollAt = "2026-09-30T10:00:00",
            sources = listOf(
                IntradaySourceStatusDto("pools", ok = true, lastOkAt = "2026-09-30T10:00:00", rateState = "NORMAL"),
                IntradaySourceStatusDto("spot", ok = true, lastOkAt = "2026-09-30T10:00:00", rateState = "NORMAL"),
                IntradaySourceStatusDto("bid_ask", ok = true, lastOkAt = "2026-09-30T10:00:00", rateState = "NORMAL"),
            ),
        )
        Mockito.`when`(overviewService.overview()).thenReturn(overview)

        // when & then: 200 + snake_case 键逐字段对拍
        mockMvc.perform(get("/api/v1/intraday/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.poll_time").value("10:00:00"))
            .andExpect(jsonPath("$.alert_count").value(3))
            .andExpect(jsonPath("$.last_poll_at").value("2026-09-30T10:00:00"))
            .andExpect(jsonPath("$.sources.length()").value(3))
            .andExpect(jsonPath("$.sources[0].source").value("pools"))
            .andExpect(jsonPath("$.sources[0].ok").value(true))
            .andExpect(jsonPath("$.sources[0].rate_state").value("NORMAL"))

        Mockito.verify(overviewService).overview()
    }

    @Test
    fun `testOverview inactiveBeforeFirstPoll`() {
        // given: 首轮前 INACTIVE（poll_time/last_poll_at null，alert_count=0）
        val overview = IntradayOverviewDto(
            status = "INACTIVE",
            pollTime = null,
            alertCount = 0L,
            lastPollAt = null,
            sources = listOf(
                IntradaySourceStatusDto("pools", ok = true, lastOkAt = null, rateState = "NORMAL"),
                IntradaySourceStatusDto("spot", ok = true, lastOkAt = null, rateState = "NORMAL"),
                IntradaySourceStatusDto("bid_ask", ok = true, lastOkAt = null, rateState = "NORMAL"),
            ),
        )
        Mockito.`when`(overviewService.overview()).thenReturn(overview)

        // when & then: 200 + INACTIVE 信封（null 键不序列化/序列化为 null，JSON path 仍可达）
        mockMvc.perform(get("/api/v1/intraday/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("INACTIVE"))
            .andExpect(jsonPath("$.alert_count").value(0))
    }
}
