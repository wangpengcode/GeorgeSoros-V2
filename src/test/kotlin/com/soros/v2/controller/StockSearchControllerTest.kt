package com.soros.v2.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.StockInfoService
import com.soros.v2.service.dto.StockSearchItem
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §11.1 StockSearchController 契约测试（直调 controller + MockMvc 信封，Fake StockInfoService）。
 *
 * 契约（类 KDoc / §11.1 / C1 / §19.13.1 K线页搜索框）：
 * - GET /api/v1/stock-search?q=&limit= → 服务委托（q/limit 透传）；limit 缺省 10；
 * - 匹配口径：code 前缀 OR name 小写包含（拼音挂账不做，§19.13.1）；排除 is_st/delisted；
 * - 响应 [{code,name,industry}]（键过命名字典 §17.6，snake_case）；
 * - q 空/全空白 → IllegalArgumentException → GlobalExceptionHandler → 400 BAD_REQUEST 信封。
 *
 * ⚠️ "上限 20"（任务契约）为服务层 SEARCH_LIMIT 常量（StockInfoServiceImpl 现值 10），
 * 非控制器可测项；PLAN §19.13.1 称现有端点"现状已满足"（kline 页传 limit=8）。是否提到 20 由 implementer 裁决。
 */
class StockSearchControllerTest {

    private class FakeStockInfoService : StockInfoService {
        var searchResult: List<StockSearchItem> = emptyList()
        var lastQuery: String? = null
        var lastLimit: Int = 10
        var throwOnSearch: Exception? = null

        override suspend fun refreshStockList(): List<StockInfo> = emptyList()
        override suspend fun backfillIpoDates(): Int = 0
        override suspend fun refreshBoardSnapshot(boardType: BoardType): Int = 0
        override fun findByCode(code: String): StockInfo? = null
        override fun search(query: String, limit: Int): List<StockSearchItem> {
            lastQuery = query
            lastLimit = limit
            throwOnSearch?.let { throw it }
            return searchResult
        }

        override fun saveBenchmarkIndices(): Int = 0
    }

    private lateinit var fake: FakeStockInfoService
    private lateinit var controller: StockSearchController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        fake = FakeStockInfoService()
        controller = StockSearchController(fake)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `testSearch delegatesQueryAndLimit`() {
        // given: 服务返回命中（含行业）
        fake.searchResult = listOf(StockSearchItem("600000", "浦发银行", listOf("银行")))

        // when
        val resp = controller.search("600", 8)

        // then: q/limit 透传 + 响应回传
        assertEquals("600", fake.lastQuery, "q 透传")
        assertEquals(8, fake.lastLimit, "limit 透传")
        assertEquals("600000", resp.first().code, "code 回传")
        assertEquals("浦发银行", resp.first().name, "name 回传")
        assertEquals(listOf("银行"), resp.first().industry, "industry 回传")
    }

    @Test
    fun `testSearch delegatesNameContainsQuery`() {
        // given: 中文名包含查询词（匹配口径：name 小写包含，§19.13.1 拼音挂账不做）
        fake.searchResult = listOf(StockSearchItem("600000", "浦发银行", listOf("银行")))

        // when
        controller.search("浦发", 10)

        // then: 中文名包含查询词原样透传
        assertEquals("浦发", fake.lastQuery, "中文名包含查询词透传")
    }

    @Test
    fun `testSearch defaultsLimitTo10`() {
        // when: 无 limit 参数（Kotlin 缺省值 10，§11.1 limit 10）
        controller.search("浦发")

        // then
        assertEquals(10, fake.lastLimit, "limit 缺省 10")
    }

    @Test
    fun `testSearch blankQueryRejected`() {
        // given: 服务守卫 q 非空（require），空串 → IllegalArgumentException
        fake.throwOnSearch = IllegalArgumentException("搜索词不能为空")

        // when & then: 向上传播（400 由全局异常处理器映射）
        assertThrows(IllegalArgumentException::class.java) {
            controller.search("   ", 10)
        }
    }

    @Test
    fun `testSearch blankQueryRejected400EnvelopeViaMockMvc`() {
        // given: q 空/全空白 → 服务抛 IllegalArgumentException
        fake.throwOnSearch = IllegalArgumentException("搜索词不能为空")

        // when & then: HTTP 层 400 + BAD_REQUEST 信封（GlobalExceptionHandler §11.1 契约）
        mockMvc.perform(get("/api/v1/stock-search").param("q", "   "))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
    }

    @Test
    fun `testSearch responseKeysSnakeCase`() {
        // given: 命中项序列化（响应键 code/name/industry 过命名字典 §17.6）
        val json = ObjectMapper().registerModule(KotlinModule.Builder().build())
            .writeValueAsString(listOf(StockSearchItem("600000", "浦发银行", listOf("银行"))))

        // then: snake_case 键，不出现驼峰
        assertTrue(json.contains("\"code\""), "code")
        assertTrue(json.contains("\"name\""), "name")
        assertTrue(json.contains("\"industry\""), "industry")
        assertTrue(!json.contains("\"tradeDate\""), "不得出现无关驼峰键")
    }
}
