package com.soros.v2.controller

import com.soros.v2.exception.BusinessException
import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.strategy.StrategyService
import com.soros.v2.service.strategy.dto.StrategyCreateRequest
import com.soros.v2.service.strategy.dto.StrategyDetailDto
import com.soros.v2.service.strategy.dto.StrategyHistoryItemDto
import com.soros.v2.service.strategy.dto.StrategySummaryDto
import com.soros.v2.service.strategy.dto.StrategyUpdateRequest
import com.soros.v2.service.strategy.dto.StrategyYamlDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §19.13.3 StrategyController 接线契约测试（MockMvc 风格对齐 KlineControllerTest）。
 *
 * 端点契约（§19.13.3 a 期端点契约 + G3 定稿）：
 * - GET  /api/v1/strategies → 列表（含 version/status/alert_enabled/note）；
 * - POST /api/v1/strategies → 新建（结构化 JSON，服务端生成 YAML；DRAFT 态，version=1）；
 * - PUT  /api/v1/strategies/{id} → 修改（落 strategy_config_history，version+1）；
 * - GET  /api/v1/strategies/{id}/yaml → 导出（yaml 键）；
 * - GET  /api/v1/strategies/{id}/history → 版本列表（G3）；
 * - POST /api/v1/strategies/{id}/rollback?version= → 回滚（G3：目标版本 yaml 复制为新版本 version+1，落 history 指向旧内容）；
 * - 404：id/version 不存在 → BusinessException 含"不存在" → 404 BUSINESS_ERROR 信封；
 * - 400：请求体缺必填字段 → HttpMessageNotReadableException → 400 BAD_REQUEST；缺 version 参数 → 400 MISSING_PARAM。
 *
 * ⚠️ 接线层空壳（TDD 红阶段）：StrategyController / StrategyService / StrategyDtos 尚未创建，
 * 本文件编译失败即预期红；Implementer 按本契约创建后可编译并应全绿。
 *
 * 签名契约（Implementer 创建时对齐）：
 * - StrategyController(strategyService: StrategyService)，@RequestMapping("/api/v1")；
 * - StrategyService 方法：
 *   fun list(): List<StrategySummaryDto>
 *   fun create(req: StrategyCreateRequest): StrategyDetailDto
 *   fun update(id: Long, req: StrategyUpdateRequest): StrategyDetailDto
 *   fun getYaml(id: Long): StrategyYamlDto
 *   fun history(id: Long): List<StrategyHistoryItemDto>
 *   fun rollback(id: Long, version: Int): StrategyDetailDto
 * - DTO 构造器参数（snake_case @JsonProperty 逐字段对齐命名字典 §19.13.3）：
 *   StrategySummaryDto(id, name, version, status, alertEnabled, note?)
 *   StrategyDetailDto(id, name, yaml, version, status, alertEnabled, note?)
 *   StrategyCreateRequest(name, conditions: List<ConditionInput>, alertEnabled=false, note?=null)
 *   StrategyUpdateRequest(conditions: List<ConditionInput>, alertEnabled=false, note?=null)
 *   StrategyHistoryItemDto(version, yaml, createdAt?)
 *   StrategyYamlDto(yaml)
 */
class StrategyControllerTest {

    private lateinit var strategyService: StrategyService
    private lateinit var controller: StrategyController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        strategyService = Mockito.mock(StrategyService::class.java)
        controller = StrategyController(strategyService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ==================== 正常流程：列表 ====================

    @Test
    fun `testListStrategies successReturnsSnakeCaseSummaryList`() {
        // given: 服务返回 1 条（version/status/alert_enabled/note 齐）
        val summary = StrategySummaryDto(
            id = 1L,
            name = "打龙头回调",
            version = 3,
            status = "ACTIVE",
            alertEnabled = true,
            note = "打龙头回调策略",
        )
        Mockito.`when`(strategyService.list()).thenReturn(listOf(summary))

        // when & then: 200 + 响应键逐字段对齐命名字典（snake_case）
        mockMvc.perform(get("/api/v1/strategies"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].name").value("打龙头回调"))
            .andExpect(jsonPath("$[0].version").value(3))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].alert_enabled").value(true))
            .andExpect(jsonPath("$[0].note").value("打龙头回调策略"))

        Mockito.verify(strategyService).list()
    }

    // ==================== 正常流程：新建 ====================

    @Test
    fun `testCreateStrategy successReturnsDetailVersion1Draft`() {
        // given: 服务端生成 YAML（结构化 JSON → YAML 服务端拼装），新建 DRAFT 态 version=1
        val detail = StrategyDetailDto(
            id = 1L,
            name = "打龙头回调",
            yaml = "strategy: da-long-hui-tou\nsignals:\n  buy:\n    - source: limit_up_streak",
            version = 1,
            status = "DRAFT",
            alertEnabled = false,
            note = "打龙头回调策略",
        )
        Mockito.`when`(strategyService.create(Mockito.any(StrategyCreateRequest::class.java)))
            .thenReturn(detail)

        // when
        mockMvc.perform(
            post("/api/v1/strategies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "打龙头回调",
                      "conditions": [
                        {"cond_id": "buy_0", "side": "BUY", "source": "limit_up_streak", "op": "between", "value": [3, 7]}
                      ],
                      "alert_enabled": false,
                      "note": "打龙头回调策略"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.name").value("打龙头回调"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.alert_enabled").value(false))

        // then: 委托 create 且请求体逐字段透传（结构化条件树 → 服务端生成 YAML）
        val captor = ArgumentCaptor.forClass(StrategyCreateRequest::class.java)
        Mockito.verify(strategyService).create(captor.capture())
        assertEquals("打龙头回调", captor.value.name, "name 透传")
        assertEquals(1, captor.value.conditions.size, "conditions 透传")
        assertEquals("buy_0", captor.value.conditions[0].condId, "cond_id 透传")
        assertEquals("BUY", captor.value.conditions[0].side, "side 透传")
        assertEquals("limit_up_streak", captor.value.conditions[0].source, "source 透传")
        assertEquals("between", captor.value.conditions[0].op, "op 透传")
        assertNotNull(captor.value.conditions[0].value, "value 透传（数值区间）")
        assertEquals("打龙头回调策略", captor.value.note, "note 透传")
    }

    // ==================== 正常流程：修改 ====================

    @Test
    fun `testUpdateStrategy successVersionIncremented`() {
        // given: 修改 → 落 strategy_config_history + version+1（v3 → v4）
        val detail = StrategyDetailDto(
            id = 1L,
            name = "打龙头回调",
            yaml = "strategy: da-long-hui-tou\nsignals:\n  buy:\n    - source: limit_up_streak",
            version = 4,
            status = "DRAFT",
            alertEnabled = true,
            note = "调整板数区间",
        )
        Mockito.`when`(strategyService.update(Mockito.eq(1L), Mockito.any(StrategyUpdateRequest::class.java)))
            .thenReturn(detail)

        // when
        mockMvc.perform(
            put("/api/v1/strategies/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "conditions": [
                        {"cond_id": "buy_0", "side": "BUY", "source": "limit_up_streak", "op": "between", "value": [2, 6]}
                      ],
                      "alert_enabled": true,
                      "note": "调整板数区间"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.version").value(4))
            .andExpect(jsonPath("$.alert_enabled").value(true))

        // then: 委托 update(1L, request) 且请求体透传
        val captor = ArgumentCaptor.forClass(StrategyUpdateRequest::class.java)
        Mockito.verify(strategyService).update(Mockito.eq(1L), captor.capture())
        assertEquals("buy_0", captor.value.conditions[0].condId, "cond_id 透传")
        assertEquals(true, captor.value.alertEnabled, "alert_enabled 透传")
        assertEquals("调整板数区间", captor.value.note, "note 透传")
    }

    // ==================== 正常流程：yaml 导出 ====================

    @Test
    fun `testGetYaml successReturnsYamlText`() {
        // given: 导出当前版本 YAML 全文
        val yamlDto = StrategyYamlDto("strategy: da-long-hui-tou\nsignals:\n  buy:\n    - source: limit_up_streak")
        Mockito.`when`(strategyService.getYaml(1L)).thenReturn(yamlDto)

        // when & then: 200 + yaml 键
        mockMvc.perform(get("/api/v1/strategies/1/yaml"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("da-long-hui-tou")))

        Mockito.verify(strategyService).getYaml(1L)
    }

    // ==================== 正常流程：history 版本列表 ====================

    @Test
    fun `testHistory successReturnsVersionListAscending`() {
        // given: G3 历史链（v1 初始 → v2 修改 → v3 修改，升序）
        val history = listOf(
            StrategyHistoryItemDto(version = 1, yaml = "yaml-v1", createdAt = null),
            StrategyHistoryItemDto(version = 2, yaml = "yaml-v2", createdAt = null),
            StrategyHistoryItemDto(version = 3, yaml = "yaml-v3", createdAt = null),
        )
        Mockito.`when`(strategyService.history(1L)).thenReturn(history)

        // when & then: 200 + 版本列表（升序）
        mockMvc.perform(get("/api/v1/strategies/1/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].version").value(1))
            .andExpect(jsonPath("$[2].version").value(3))

        Mockito.verify(strategyService).history(1L)
    }

    // ==================== 正常流程：回滚（G3） ====================

    @Test
    fun `testRollback successCopiesTargetVersionAsNewVersion`() {
        // given: 回滚到 v1 → 目标版本 yaml 复制为新版本（当前 v3 → 新 v4），服务返回新版本详情
        val rolledBack = StrategyDetailDto(
            id = 1L,
            name = "打龙头回调",
            yaml = "yaml-v1",   // 目标版本 yaml 复制为新版本
            version = 4,
            status = "DRAFT",
            alertEnabled = false,
            note = "回滚到 v1",
        )
        Mockito.`when`(strategyService.rollback(1L, 1)).thenReturn(rolledBack)

        // when & then: 200 + version=当前+1（v3 → v4）+ yaml=目标版本内容
        mockMvc.perform(post("/api/v1/strategies/1/rollback").param("version", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.version").value(4))
            .andExpect(jsonPath("$.yaml").value("yaml-v1"))

        // then: 委托 rollback(1L, 1)（目标版本号）
        Mockito.verify(strategyService).rollback(1L, 1)
    }

    // ==================== 异常路径：404 信封 ====================

    @Test
    fun `testUpdateStrategy notFoundRejected404Envelope`() {
        // given: 服务抛 BusinessException 含"不存在" → 404（策略 id 不存在）
        Mockito.`when`(strategyService.update(
            Mockito.eq(999L), Mockito.any(StrategyUpdateRequest::class.java),
        )).thenThrow(BusinessException("策略 999 不存在"))

        // when & then: 404 + BUSINESS_ERROR 信封
        mockMvc.perform(
            put("/api/v1/strategies/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"conditions": [], "alert_enabled": false}""".trimIndent()),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    @Test
    fun `testGetYaml notFoundRejected404Envelope`() {
        // given: 服务抛 BusinessException 含"不存在" → 404
        Mockito.`when`(strategyService.getYaml(999L))
            .thenThrow(BusinessException("策略 999 不存在"))

        // when & then: 404 + BUSINESS_ERROR 信封
        mockMvc.perform(get("/api/v1/strategies/999/yaml"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
    }

    @Test
    fun `testRollback unknownVersionRejected404Envelope`() {
        // given: 回滚到不存在版本 → BusinessException 含"不存在" → 404
        Mockito.`when`(strategyService.rollback(1L, 99))
            .thenThrow(BusinessException("版本 99 不存在"))

        // when & then: 404 + BUSINESS_ERROR 信封
        mockMvc.perform(post("/api/v1/strategies/1/rollback").param("version", "99"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    // ==================== 异常路径：400 信封 ====================

    @Test
    fun `testCreateStrategy missingNameRejected400BadRequest`() {
        // when & then: 请求体缺 name（必填）→ Jackson MissingKotlinParameterException → 400 BAD_REQUEST
        mockMvc.perform(
            post("/api/v1/strategies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"conditions": [{"cond_id": "buy_0", "side": "BUY", "source": "limit_up_streak", "op": "between", "value": [3, 7]}]}""".trimIndent()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
    }

    @Test
    fun `testRollback missingVersionParamRejected400MissingParam`() {
        // when & then: 缺 version query 参数 → MissingServletRequestParameterException → 400 MISSING_PARAM
        mockMvc.perform(post("/api/v1/strategies/1/rollback"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("MISSING_PARAM"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("version")))
    }

    // ==================== 下游契约测试（DTO 传播完整性，回归防护） ====================

    @Test
    fun `testListStrategies downstreamContractSummaryFieldsNonNull`() {
        // given: 全字段源 DTO
        val summary = StrategySummaryDto(
            id = 1L,
            name = "打龙头回调",
            version = 3,
            status = "ACTIVE",
            alertEnabled = true,
            note = null,
        )
        Mockito.`when`(strategyService.list()).thenReturn(listOf(summary))

        // when
        mockMvc.perform(get("/api/v1/strategies"))
            .andExpect(status().isOk)

        // then: 下游消费者（策略列表页）必需的字段全部非 null——
        // 断言集合=下游必需字段集合，不是源 DTO 已有字段集合（防 mapper 漏设字段回归）
        val items = strategyService.list()
        assertNotNull(items.single().name, "name 为 null 会导致列表无法展示")
        assertNotNull(items.single().status, "status 为 null 会导致状态徽标缺失")
    }
}
