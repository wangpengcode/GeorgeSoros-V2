package com.soros.v2.controller

import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.signal.SignalReplayService
import com.soros.v2.service.signal.SignalReplaySummary
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §19.11.1 SignalJobController 接线契约测试（直调验证委托 + MockMvc 验证 query 参数绑定/400 信封）。
 *
 * 契约（§19.11.1 / §13.5 sentiment-replay 同构 / GlobalExceptionHandler）：
 * - POST /api/v1/jobs/signal-replay?from=&to= → 调用 replay(from, to) 并返回 SignalReplaySummary；
 * - 缺 from/to → MissingServletRequestParameterException → 400 MISSING_PARAM（统一错误信封）；
 * - from=非法日期 → MethodArgumentTypeMismatchException → 400 PARAM_INVALID。
 *
 * ⚠️ 接线层空壳：SignalJobController 方法体为 TODO，成功用例当前红（NotImplementedError→500）；
 * Implementer 填充 `= replayService.replay(from, to)` 后应全绿。400 用例依赖参数绑定早于方法体，当前即绿。
 */
class SignalJobControllerTest {

    private lateinit var replayService: SignalReplayService
    private lateinit var controller: SignalJobController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        replayService = Mockito.mock(SignalReplayService::class.java)
        controller = SignalJobController(replayService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ==================== 成功：委托 replay(from, to) ====================

    @Test
    fun `testReplay delegatesToServiceAndReturnsSummary`() {
        // given: 回放服务返回摘要（三表落库行数 + 处理股票数）
        val summary = SignalReplaySummary(
            from = LocalDate.of(2026, 9, 1),
            to = LocalDate.of(2026, 9, 30),
            signalRows = 100,
            marketRows = 22,
            sectorRows = 30,
            codesProcessed = 500,
        )
        Mockito.`when`(replayService.replay(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
            .thenReturn(summary)

        // when
        val resp = controller.replay(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))

        // then: 委托 replay(from,to) 并原样返回摘要
        assertEquals(summary, resp, "控制器透传 replay(from,to) 摘要")
        Mockito.verify(replayService).replay(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    }

    // ==================== 缺参 → 400 MISSING_PARAM ====================

    @Test
    fun `testReplay missingFromParamRejected400MissingParam`() {
        // when & then: 缺 from → 400 + MISSING_PARAM 信封（GlobalExceptionHandler §11.1 契约）
        mockMvc.perform(
            post("/api/v1/jobs/signal-replay")
                .param("to", "2026-09-30"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("MISSING_PARAM"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("from")))
    }

    @Test
    fun `testReplay missingToParamRejected400MissingParam`() {
        // when & then: 缺 to → 400 + MISSING_PARAM 信封
        mockMvc.perform(
            post("/api/v1/jobs/signal-replay")
                .param("from", "2026-09-01"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("MISSING_PARAM"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("to")))
    }

    // ==================== 参数格式非法 → 400 PARAM_INVALID ====================

    @Test
    fun `testReplay invalidDateFormatRejected400ParamInvalid`() {
        // when & then: from 非 ISO 日期 → MethodArgumentTypeMismatchException → 400 PARAM_INVALID
        mockMvc.perform(
            post("/api/v1/jobs/signal-replay")
                .param("from", "not-a-date")
                .param("to", "2026-09-30"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("PARAM_INVALID"))
    }
}
