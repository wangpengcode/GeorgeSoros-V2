package com.soros.v2.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §11.1 情绪周期 API 契约集成测试（@SpringBootTest + MockMvc + TestContainers PG16）。
 *
 * 覆盖（调度器裁定 #4 全局异常信封 + 单日查询/龙头周期端点 snake_case 契约）：
 * - GET /api/v1/sentiment-cycle?date= 无行 → 200 null body（§11.1 无行语义）
 * - PUT /api/v1/sentiment-cycle/{date}/confirm 无行 → 404 + 统一错误信封 {status:"error",error:{code,message}}
 * - GET /api/v1/dragon-cycle?limit=10 → 200 {items:[]}（snake_case 键）
 * - PUT /api/v1/dragon-cycle/{id}/confirm 不存在 → 404 + 统一错误信封
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SentimentApiIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET sentiment-cycle noRow returns 200 null body`() {
        // given: 空库（TestContainers 全新 PG）
        // when & then: §11.1 无行语义 = 200 + null/空 body
        mockMvc.perform(get("/api/v1/sentiment-cycle").param("date", "2026-09-30"))
            .andExpect(status().isOk)
            .andExpect { result -> assertTrue(result.response.contentAsString.isNullOrEmpty(), "无行返回空 body") }
    }

    @Test
    fun `PUT sentiment-cycle confirm noRow maps 404 with unified error envelope`() {
        // given: 空库 + 人工确认请求
        // when & then: BusinessException("不存在") → 404 + {status:"error",error:{code,message}}
        mockMvc.perform(
            put("/api/v1/sentiment-cycle/2026-09-30/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"big_cycle":3}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    @Test
    fun `GET dragon-cycle returns items key snake_case`() {
        // when & then: 空库 → 200 {items:[]}（§11.1 龙头时间轴数据源）
        mockMvc.perform(get("/api/v1/dragon-cycle").param("limit", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect { result ->
                val body = result.response.contentAsString
                assertTrue(body.contains("\"items\""), "dragon-cycle 响应键 items（snake_case 单键）")
            }
    }

    @Test
    fun `PUT dragon-cycle confirm nonExistent maps 404 with unified error envelope`() {
        // when & then: DragonCycleServiceImpl BusinessException("不存在") → 404 + 信封
        mockMvc.perform(
            put("/api/v1/dragon-cycle/999/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"cycle_type":"BIG","note":"人工改判"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    @Test
    fun `PUT sentiment-cycle confirm invalid big_cycle rejected with 400`() {
        // given: big_cycle=7 越界（DTO init require 值域校验前置，§11.1）
        // when & then: IllegalArgumentException → 400 + 统一错误信封
        mockMvc.perform(
            put("/api/v1/sentiment-cycle/2026-09-30/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"big_cycle":7}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
    }

    @Test
    fun `PUT sentiment-cycle lists invalid body rejected`() {
        // given: 非法 body（side 缺失 → Jackson 反序列化失败）
        // when & then: HttpMessageNotReadableException → 400（防御性：请求非法不落库）
        mockMvc.perform(
            put("/api/v1/sentiment-cycle/2026-09-30/lists")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"action":"ADD","code":"600000"}"""),
        )
            .andExpect(status().isBadRequest)
    }
}
