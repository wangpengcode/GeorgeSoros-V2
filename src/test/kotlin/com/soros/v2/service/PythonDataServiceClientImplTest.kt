package com.soros.v2.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.soros.v2.exception.PythonClientException
import com.soros.v2.service.dto.DailyBarsBatchRequest
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.WebClient

/**
 * §4.3 Python 数据服务客户端 HTTP 契约测试（MockWebServer 模拟 Python FastAPI 端，无 Spring）。
 *
 * 契约（§13.2 / 接口 KDoc）：
 * - healthCheck：非 2xx/异常 → false，不抛；
 * - fetchStockList / fetchDailyBarsBatch / fetchTradingCalendar：2xx 解析 DTO；非 2xx → PythonClientException，
 *   并触发 PythonCircuitBreaker.recordFailure()（熔断失败计数）；
 * - 响应 DTO 解析依赖 Jackson fail-on-unknown 严格模式 + JavaTimeModule（LocalDate）。
 * - backfill profile 切换：请求区间 > 366 自然日 → response 60s / retry 1（requestRangeExceeds 判定）；
 *   = 366 自然日 → 默认 10s / retry 2。
 */
class PythonDataServiceClientImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PythonDataServiceClientImpl
    private lateinit var circuitBreaker: PythonCircuitBreaker

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 测试用 WebClient 必须显式配 JavaTimeModule+KotlinModule，否则 LocalDate 无法反序列化
        val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
        val webClient = WebClient.builder()
            .baseUrl(server.url("/").toString())
            .codecs { configurer ->
                configurer.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
                configurer.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
            }
            .build()
        circuitBreaker = Mockito.mock(PythonCircuitBreaker::class.java)
        Mockito.`when`(circuitBreaker.allowRequest()).thenReturn(true) // 熔断放行，聚焦 HTTP 契约
        client = PythonDataServiceClientImpl(webClient, circuitBreaker)
    }

    @AfterEach
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: IllegalStateException) {
            // 连接拒绝测试已主动 shutdown，重复 shutdown 幂等忽略
        }
    }

    // ==================== healthCheck ====================

    @Test
    fun `testHealthCheck ok returnsTrue`() {
        // given: Python 健康 ok
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","sources":{"baostock":"ok","akshare":"ok","mootdx":"down"}}"""),
        )
        // when & then
        assertTrue(runBlocking { client.healthCheck() }, "200 健康检查应返回 true")
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordSuccess()
    }

    @Test
    fun `testHealthCheck non2xx returnsFalse`() {
        // given: 500（Python 内部异常/降级）
        server.enqueue(MockResponse().setResponseCode(500))
        // when & then: 非 2xx 不抛异常，返回 false（契约：health 返回可用性，不抛）
        assertFalse(runBlocking { client.healthCheck() }, "500 健康检查应返回 false（不抛异常）")
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordFailure()
    }

    @Test
    fun `testHealthCheck connectionRefused returnsFalse`() {
        // given: 连接拒绝（Python 离线）——关闭服务器模拟断连
        server.shutdown()
        // when & then: 异常被吞，返回 false（契约：Python 离线返回 false，不抛）
        assertFalse(runBlocking { client.healthCheck() }, "连接拒绝应返回 false（不抛异常）")
    }

    // ==================== fetchStockList ====================

    @Test
    fun `testFetchStockList parses response`() {
        // given
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"status":"ok","stocks":[
                      {"code":"600000","name":"浦发银行","market":"SH","board":"MAIN","is_st":false,"delisted":false},
                      {"code":"000587","name":"*ST金洲","market":"SH","board":"MAIN","is_st":true,"delisted":false}
                    ]}
                    """.trimIndent(),
                ),
        )
        // when
        val stocks = runBlocking { client.fetchStockList() }
        // then: 完整解析（字段逐一对齐 §11.1 StockListDto 契约）
        assertEquals(2, stocks.size, "应解析 2 只股票")
        assertEquals("600000", stocks[0].code, "code 解析")
        assertEquals("MAIN", stocks[0].board, "board 解析")
        assertEquals(false, stocks[0].isSt, "is_st 解析")
        assertTrue(stocks[1].isSt, "ST 股 is_st=true 解析（用于识别排除）")
        // then: 熔断接线——调用前询问 + 成功登记（清零计数）
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordSuccess()
    }

    @Test
    fun `testFetchStockList non2xx throwsPythonClientException`() {
        // given: 500（含 retry 容量 3）
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        // when & then: 非 2xx 抛 PythonClientException 且触发熔断失败计数
        assertThrows(PythonClientException::class.java) {
            runBlocking { client.fetchStockList() }
        }
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordFailure()
    }

    // ==================== fetchDailyBarsBatch ====================

    @Test
    fun `testFetchDailyBarsBatch parses response`() {
        // given
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"status":"ok",
                     "results":{
                       "600000":{"source":"baostock","count":1,"data":[
                         {"date":"2026-09-30","code":"600000","open":12.34,"high":13.80,"low":12.10,"close":13.57,
                          "volume":1234567,"amount":15678900.00,"change_percent":9.98,"turnover":2.50,"prev_close":12.34}
                       ]}
                     },
                     "failed":[]}
                    """.trimIndent(),
                ),
        )
        // when
        val response = runBlocking {
            client.fetchDailyBarsBatch(DailyBarsBatchRequest(listOf("600000"), "2026-09-01", "2026-09-30", "qfq"))
        }
        // then: 逐字段解析对齐 §11.1 DailyBar 11 字段契约
        assertEquals("ok", response.status, "status 解析")
        val bars = response.results["600000"]?.data ?: error("results[600000] 不应为 null")
        assertEquals(1, bars.size, "600000 有 1 根日K")
        assertEquals(LocalDate.of(2026, 9, 30), bars[0].date, "date 解析为 LocalDate")
        assertEquals(BigDecimal("12.34"), bars[0].open, "open=qfq")
        assertEquals(BigDecimal("13.57"), bars[0].close, "close=qfq")
        assertEquals(BigDecimal("9.98"), bars[0].changePercent, "change_percent=不复权真实涨跌幅%")
        assertEquals(1_234_567L, bars[0].volume, "volume=股")
        assertEquals(BigDecimal("15678900.00"), bars[0].amount, "amount=元")
        assertEquals(BigDecimal("12.34"), bars[0].prevClose, "prev_close=传输字段")
        assertEquals("baostock", response.results["600000"]?.source, "source 小写原样")
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordSuccess()
    }

    @Test
    fun `testFetchDailyBarsBatch non2xx throwsPythonClientException`() {
        // given: 500（含 retry 容量 3：default profile 重试 2 次×500ms）
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        // when & then
        assertThrows(PythonClientException::class.java) {
            runBlocking { client.fetchDailyBarsBatch(DailyBarsBatchRequest(listOf("600000"), "2026-09-01", "2026-09-30", "qfq")) }
        }
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordFailure()
    }

    // ==================== backfill profile 切换（§13.2） ====================

    @Test
    fun `testRequestRangeExceeds over366NaturalDays isBackfill`() {
        // given: >366 自然日区间（2023-12-31 到 2025-01-01 = 367 天，跨闰年边界）
        val request = DailyBarsBatchRequest(listOf("600000"), "2023-12-31", "2025-01-01", "qfq")
        // when & then: 判为 backfill（response 60s / retry 1）
        assertTrue(client.requestRangeExceeds(request, 366), ">366 自然日应判 backfill（60s 超时 / retry 1）")
    }

    @Test
    fun `testRequestRangeExceeds equals366Days isNotBackfill`() {
        // given: =366 自然日区间（2024-01-01 到 2025-01-01 = 366 天，2024 闰年）
        val request = DailyBarsBatchRequest(listOf("600000"), "2024-01-01", "2025-01-01", "qfq")
        // when & then: 边界日不判 backfill（走默认 10s / retry 2）
        assertFalse(client.requestRangeExceeds(request, 366), "=366 自然日不应判 backfill（走默认 10s）")
    }

    @Test
    fun `testFetchDailyBarsBatch backfillRangeUsesSingleRetry`() {
        // given: backfill 区间（>366 日）+ 3 个 500 响应
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val request = DailyBarsBatchRequest(listOf("600000"), "2023-12-31", "2025-01-01", "qfq")
        // when & then: backfill retry=1 → 共 2 次尝试即耗尽（第 3 个 500 不被消费）
        assertThrows(PythonClientException::class.java) {
            runBlocking { client.fetchDailyBarsBatch(request) }
        }
        assertEquals(2, server.requestCount, "backfill profile retry=1：只消费 2 次尝试")
        Mockito.verify(circuitBreaker).recordFailure()
    }

    @Test
    fun `testFetchDailyBarsBatch defaultRangeAt366UsesTwoRetries`() {
        // given: 默认区间（=366 日）+ 3 个 500 响应
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val request = DailyBarsBatchRequest(listOf("600000"), "2024-01-01", "2025-01-01", "qfq")
        // when & then: 默认 retry=2 → 共 3 次尝试后耗尽
        assertThrows(PythonClientException::class.java) {
            runBlocking { client.fetchDailyBarsBatch(request) }
        }
        assertEquals(3, server.requestCount, "default profile retry=2：消费 3 次尝试")
        Mockito.verify(circuitBreaker).recordFailure()
    }

    // ==================== fetchTradingCalendar ====================

    @Test
    fun `testFetchTradingCalendar parses dates`() {
        // given
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"dates":["2026-09-30","2026-10-08","2026-10-09"]}"""),
        )
        // when
        val dates = runBlocking { client.fetchTradingCalendar() }
        // then
        assertEquals(listOf("2026-09-30", "2026-10-08", "2026-10-09"), dates, "dates 全量解析（YYYY-MM-DD）")
        Mockito.verify(circuitBreaker).allowRequest()
        Mockito.verify(circuitBreaker).recordSuccess()
    }
}
