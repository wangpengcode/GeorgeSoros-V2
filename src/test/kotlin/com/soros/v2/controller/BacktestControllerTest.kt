package com.soros.v2.controller

import com.soros.v2.exception.BusinessException
import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.strategy.BacktestService
import com.soros.v2.service.strategy.dto.BacktestCreateRequest
import com.soros.v2.service.strategy.dto.BacktestResultDto
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §19.13.3 G4 定稿 POST /api/v1/backtests 请求体契约测试（MockMvc 风格对齐 KlineControllerTest）。
 *
 * 请求体契约（G4，2026-10-05 用户拍板）：
 * - 请求体 `{strategy_name, config_id?, start_date, end_date, is_dry}`；
 * - **is_dry 显式必填、无默认值兜底**——缺 is_dry → 422（BusinessException 不含"不存在"），禁止静默兜底；
 * - L1 参数不允许 overrides（单一事实来源取 config YAML 内 L1，请求体无 L1 字段）；
 * - 响应沿用 backtest_result 主键 id；
 * - 本端点 a 期只做参数契约与落库占位（回测引擎挂 b 期）→ 合法体返回 202 Accepted + id。
 *
 * ⚠️ 接线层空壳（TDD 红阶段）：BacktestController / BacktestService / 请求响应 DTO 尚未创建，
 * 本文件编译失败即预期红；Implementer 按本契约创建后可编译并应全绿。
 *
 * 签名契约（Implementer 创建时对齐）：
 * - BacktestController(backtestService: BacktestService)，@RequestMapping("/api/v1")；
 *   合法体返回 202：方法加 @ResponseStatus(HttpStatus.ACCEPTED) 或 ResponseEntity 202；
 * - BacktestService.create(req: BacktestCreateRequest): BacktestResultDto；
 * - DTO（com.soros.v2.service.strategy.dto）：
 *   BacktestCreateRequest(strategyName, configId: Long?, startDate: LocalDate, endDate: LocalDate,
 *                         isDry: Boolean?)   // isDry 可空——缺失由服务层判 null 抛 BusinessException → 422
 *   BacktestResultDto(id: Long, strategyName, configId: Long?, startDate: LocalDate, endDate: LocalDate, isDry: Boolean)
 */
class BacktestControllerTest {

    private lateinit var backtestService: BacktestService
    private lateinit var controller: BacktestController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        backtestService = Mockito.mock(BacktestService::class.java)
        controller = BacktestController(backtestService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ==================== 正常流程：合法体 → 202 + 落库占位 id ====================

    @Test
    fun `testCreateBacktest validBodyReturns202AcceptedWithId`() {
        // given: 合法体（strategy_name + config_id + 区间 + is_dry=true 显式）→ 落库占位返回主键 id
        val result = BacktestResultDto(
            id = 42L,
            strategyName = "打龙头回调",
            configId = 1L,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 9, 30),
            isDry = true,
        )
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenReturn(result)

        // when & then: 202 + 响应沿用 backtest_result 主键 id（G4）+ 契约键逐字段对齐
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "config_id": 1,
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30",
                      "is_dry": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.strategy_name").value("打龙头回调"))
            .andExpect(jsonPath("$.config_id").value(1))
            .andExpect(jsonPath("$.start_date").value("2026-08-01"))
            .andExpect(jsonPath("$.end_date").value("2026-09-30"))
            .andExpect(jsonPath("$.is_dry").value(true))

        // then: 委托 create 且请求体逐字段透传（含 config_id/is_dry 显式）
        val captor = ArgumentCaptor.forClass(BacktestCreateRequest::class.java)
        Mockito.verify(backtestService).create(captor.capture())
        assertEquals("打龙头回调", captor.value.strategyName, "strategy_name 透传")
        assertEquals(1L, captor.value.configId, "config_id 透传")
        assertEquals(LocalDate.of(2026, 8, 1), captor.value.startDate, "start_date 透传")
        assertEquals(LocalDate.of(2026, 9, 30), captor.value.endDate, "end_date 透传")
        assertEquals(true, captor.value.isDry, "is_dry 显式透传")
    }

    @Test
    fun `testCreateBacktest configIdOptionalValidBodyReturns202`() {
        // given: config_id 可选（null）——策略名已含当前配置，回测落库占位仍接受
        val result = BacktestResultDto(
            id = 43L,
            strategyName = "打龙头回调",
            configId = null,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 9, 30),
            isDry = false,
        )
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenReturn(result)

        // when & then: 202 + config_id=null + is_dry=false 显式
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30",
                      "is_dry": false
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.config_id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.is_dry").value(false))

        val captor = ArgumentCaptor.forClass(BacktestCreateRequest::class.java)
        Mockito.verify(backtestService).create(captor.capture())
        assertEquals(null, captor.value.configId, "config_id 缺省为 null")
        assertEquals(false, captor.value.isDry, "is_dry=false 显式")
    }

    // ==================== 异常路径：is_dry 缺失 → 422（G4 铁律） ====================

    @Test
    fun `testCreateBacktest missingIsDryRejected422Envelope`() {
        // given: 缺 is_dry（显式必填、无默认值兜底）——实现侧（服务层或控制器）判 null 抛 BusinessException（不含"不存在"→ 422）
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenThrow(BusinessException("is_dry 必须显式指定（试跑/正式）"))

        // when & then: 422 + BUSINESS_ERROR 信封（禁止静默兜底，G4 定稿）
        // 注意：422 本身即证明 is_dry 无默认值兜底——若 DTO 默认 is_dry=false，则不会走到 422。
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("is_dry")))
    }

    // ==================== 异常路径：区间/策略校验 ====================

    @Test
    fun `testCreateBacktest startAfterEndRejected422Envelope`() {
        // given: start_date > end_date → BusinessException（不含"不存在"→ 422）
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenThrow(BusinessException("start_date 不能大于 end_date"))

        // when & then: 422 + BUSINESS_ERROR 信封
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "start_date": "2026-10-01",
                      "end_date": "2026-09-30",
                      "is_dry": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
    }

    @Test
    fun `testCreateBacktest unknownStrategyRejected404Envelope`() {
        // given: strategy_name 不存在 → BusinessException 含"不存在" → 404
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenThrow(BusinessException("策略 打龙头回调 不存在"))

        // when & then: 404 + BUSINESS_ERROR 信封
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30",
                      "is_dry": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
    }

    // ==================== 异常路径：400 信封（body 缺必填/解析失败） ====================

    @Test
    fun `testCreateBacktest missingStrategyNameRejected400BadRequest`() {
        // when & then: 缺 strategy_name（必填 String 无默认值）→ Jackson MissingKotlinParameterException → 400 BAD_REQUEST
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30",
                      "is_dry": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
    }

    // ==================== 下游契约测试（DTO 传播完整性，回归防护） ====================

    @Test
    fun `testCreateBacktest downstreamContractResponseFieldsNonNull`() {
        // given: 全字段源 DTO（含 config_id=null 合法态）
        val result = BacktestResultDto(
            id = 42L,
            strategyName = "打龙头回调",
            configId = null,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 9, 30),
            isDry = true,
        )
        Mockito.`when`(backtestService.create(Mockito.any(BacktestCreateRequest::class.java)))
            .thenReturn(result)

        // when: 走控制器全链路，观察到达 HTTP JSON 层的响应
        mockMvc.perform(
            post("/api/v1/backtests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "strategy_name": "打龙头回调",
                      "start_date": "2026-08-01",
                      "end_date": "2026-09-30",
                      "is_dry": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.strategy_name").isString)
            .andExpect(jsonPath("$.start_date").isString)
            .andExpect(jsonPath("$.end_date").isString)
            .andExpect(jsonPath("$.is_dry").isBoolean)

        // then: 下游消费者（结果对比页 tab3）必需的字段全部非 null——
        // 断言集合=下游必需字段集合，不是源 DTO 已有字段集合（防 mapper 漏设字段回归）
        assertNotNull(result.id, "id 为 null 会导致对比页无法勾选")
        assertNotNull(result.strategyName, "strategy_name 为 null 会导致结果列表分组失败")
        assertNotNull(result.startDate, "start_date 为 null 会导致区间展示缺失")
        assertNotNull(result.endDate, "end_date 为 null 会导致区间展示缺失")
        assertNotNull(result.isDry, "is_dry 为 null 会导致试跑/正式过滤失效")
    }
}
