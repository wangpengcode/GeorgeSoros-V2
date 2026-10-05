package com.soros.v2.controller

import com.soros.v2.exception.BusinessException
import com.soros.v2.exception.GlobalExceptionHandler
import com.soros.v2.service.kline.KlineService
import com.soros.v2.service.kline.dto.KlineBar
import com.soros.v2.service.kline.dto.KlineChip
import com.soros.v2.service.kline.dto.KlineResponse
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * §19.13.1 KlineController 接线契约测试（MockMvc 风格对齐 SignalJobControllerTest）。
 *
 * 契约（§19.13.1 端点契约 / 行为矩阵 / GlobalExceptionHandler）：
 * - GET /api/v1/kline?code=&from=&to=&date= → 委托 klineService.getKline(code, from, to, date)；
 * - code 必填 → 缺 code 由 MissingServletRequestParameterException → 400 MISSING_PARAM 信封；
 * - 响应键与命名字典逐字段对齐：code/name/bars[].trade_date/open/high/low/close/volume/amount/
 *   change_pct/turnover_rate/chip{profit_ratio,cost_dev,c90_low,c90_high,c90_conc,c70_low,c70_high,c70_conc}
 *   （snake_case；chip 无 signal_daily 行 → null）；
 * - code 不存在（service 抛 BusinessException 含"不存在"）→ 404 BUSINESS_ERROR 信封；
 * - from>to（service 抛 BusinessException）→ 422 BUSINESS_ERROR 信封；
 * - from=非法日期 → MethodArgumentTypeMismatchException → 400 PARAM_INVALID。
 *
 * ⚠️ 接线层空壳：KlineController / KlineService / KlineDtos 尚未创建（TDD 红阶段），
 * 本文件编译失败即预期红；Implementer 按本契约创建后可编译并应全绿。
 */
class KlineControllerTest {

    private lateinit var klineService: KlineService
    private lateinit var controller: KlineController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        klineService = Mockito.mock(KlineService::class.java)
        controller = KlineController(klineService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ==================== 正常流程：响应键与字典逐字段对齐 ====================

    @Test
    fun `testGetKline successReturnsSnakeCaseResponseWithChipAndNullChip`() {
        // given: 服务返回 2 根 bar（第 1 根带 chip 8 列，第 2 根 chip=null——warm-up 段无 signal_daily 行）
        val barWithChip = KlineBar(
            tradeDate = LocalDate.of(2026, 9, 29),
            open = BigDecimal("9.22"),
            high = BigDecimal("9.49"),
            low = BigDecimal("9.16"),
            close = BigDecimal("9.48"),
            volume = 147_484_820L,
            amount = BigDecimal("1386209937.38"),
            changePct = BigDecimal("1.28"),
            turnoverRate = BigDecimal("0.42"),
            chip = KlineChip(
                profitRatio = BigDecimal("92.90"),
                costDev = BigDecimal("-1.20"),
                c90Low = BigDecimal("8.10"),
                c90High = BigDecimal("9.55"),
                c90Conc = BigDecimal("61.20"),
                c70Low = BigDecimal("8.65"),
                c70High = BigDecimal("9.40"),
                c70Conc = BigDecimal("43.80"),
            ),
        )
        val barWithoutChip = KlineBar(
            tradeDate = LocalDate.of(2026, 9, 30),
            open = BigDecimal("9.50"),
            high = BigDecimal("9.60"),
            low = BigDecimal("9.40"),
            close = BigDecimal("9.55"),
            volume = 100_000_000L,
            amount = BigDecimal("1000000000.00"),
            changePct = BigDecimal("0.74"),
            turnoverRate = BigDecimal("0.30"),
            chip = null,
        )
        val response = KlineResponse(
            code = "600000",
            name = "浦发银行",
            bars = listOf(barWithChip, barWithoutChip),
        )
        Mockito.`when`(klineService.getKline(
            Mockito.eq("600000"),
            Mockito.eq(LocalDate.of(2026, 9, 1)),
            Mockito.eq(LocalDate.of(2026, 9, 30)),
            Mockito.isNull(),
        )).thenReturn(response)

        // when & then: 200 + 响应键逐字段对齐命名字典（snake_case，chip 无行=null）
        mockMvc.perform(
            get("/api/v1/kline")
                .param("code", "600000")
                .param("from", "2026-09-01")
                .param("to", "2026-09-30"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("600000"))
            .andExpect(jsonPath("$.name").value("浦发银行"))
            .andExpect(jsonPath("$.bars.length()").value(2))
            // bar[0]：bars 元素键全对齐（trade_date 作废 C4 旧 date）
            .andExpect(jsonPath("$.bars[0].trade_date").value("2026-09-29"))
            .andExpect(jsonPath("$.bars[0].open").value(9.22))
            .andExpect(jsonPath("$.bars[0].high").value(9.49))
            .andExpect(jsonPath("$.bars[0].low").value(9.16))
            .andExpect(jsonPath("$.bars[0].close").value(9.48))
            .andExpect(jsonPath("$.bars[0].volume").value(147484820))
            .andExpect(jsonPath("$.bars[0].amount").value(1386209937.38))
            .andExpect(jsonPath("$.bars[0].change_pct").value(1.28))
            .andExpect(jsonPath("$.bars[0].turnover_rate").value(0.42))
            // chip 8 列键名=signal_daily DDL 一字不差
            .andExpect(jsonPath("$.bars[0].chip.profit_ratio").value(92.90))
            .andExpect(jsonPath("$.bars[0].chip.cost_dev").value(-1.20))
            .andExpect(jsonPath("$.bars[0].chip.c90_low").value(8.10))
            .andExpect(jsonPath("$.bars[0].chip.c90_high").value(9.55))
            .andExpect(jsonPath("$.bars[0].chip.c90_conc").value(61.20))
            .andExpect(jsonPath("$.bars[0].chip.c70_low").value(8.65))
            .andExpect(jsonPath("$.bars[0].chip.c70_high").value(9.40))
            .andExpect(jsonPath("$.bars[0].chip.c70_conc").value(43.80))
            // bar[1]：signal_daily 无行 → chip=null（warm-up 段）
            .andExpect(jsonPath("$.bars[1].trade_date").value("2026-09-30"))
            .andExpect(jsonPath("$.bars[1].chip").value(org.hamcrest.Matchers.nullValue()))
    }

    // ==================== 正常流程：参数透传委托 ====================

    @Test
    fun `testGetKline onlyCodeDelegatesDefaultWindowToService`() {
        // given: 仅 code → from/to/date 均 null，服务按默认近 250 交易日窗口处理
        val response = KlineResponse("600000", "浦发银行", emptyList())
        Mockito.`when`(klineService.getKline(
            Mockito.eq("600000"),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.isNull(),
        )).thenReturn(response)

        // when
        mockMvc.perform(get("/api/v1/kline").param("code", "600000"))
            .andExpect(status().isOk)

        // then: 委托 getKline("600000", null, null, null)
        Mockito.verify(klineService).getKline(
            Mockito.eq("600000"),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.isNull(),
        )
    }

    @Test
    fun `testGetKline dateParamDelegatedToService`() {
        // given: date 语义=该日为终点（吸附交给服务层，控制器原样透传）
        val response = KlineResponse("600000", "浦发银行", emptyList())
        Mockito.`when`(klineService.getKline(
            Mockito.eq("600000"),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.eq(LocalDate.of(2026, 9, 30)),
        )).thenReturn(response)

        // when
        mockMvc.perform(get("/api/v1/kline").param("code", "600000").param("date", "2026-09-30"))
            .andExpect(status().isOk)

        // then: date 透传
        Mockito.verify(klineService).getKline(
            Mockito.eq("600000"),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.eq(LocalDate.of(2026, 9, 30)),
        )
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testGetKline missingCodeRejected400MissingParam`() {
        // when & then: 缺 code（必填）→ 400 MISSING_PARAM 信封
        mockMvc.perform(get("/api/v1/kline"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("MISSING_PARAM"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("code")))
    }

    @Test
    fun `testGetKline unknownCodeRejected404Envelope`() {
        // given: 服务抛 BusinessException 含"不存在" → 404（stock_history 无行 且 stock_info 无此码）
        Mockito.`when`(klineService.getKline(
            Mockito.eq("999999"),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.isNull(),
        )).thenThrow(BusinessException("股票代码 999999 不存在"))

        // when & then: 404 + BUSINESS_ERROR 信封
        mockMvc.perform(get("/api/v1/kline").param("code", "999999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不存在")))
    }

    @Test
    fun `testGetKline fromGreaterThanToRejected422Envelope`() {
        // given: 服务抛 BusinessException（from>to）→ 422（不含"不存在"走 unprocessableEntity）
        Mockito.`when`(klineService.getKline(
            Mockito.eq("600000"),
            Mockito.eq(LocalDate.of(2026, 10, 1)),
            Mockito.eq(LocalDate.of(2026, 9, 1)),
            Mockito.isNull(),
        )).thenThrow(BusinessException("from 不能大于 to"))

        // when & then: 422 + BUSINESS_ERROR 信封
        mockMvc.perform(
            get("/api/v1/kline")
                .param("code", "600000")
                .param("from", "2026-10-01")
                .param("to", "2026-09-01"),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("BUSINESS_ERROR"))
    }

    @Test
    fun `testGetKline invalidDateFormatRejected400ParamInvalid`() {
        // when & then: from 非 ISO 日期 → MethodArgumentTypeMismatchException → 400 PARAM_INVALID
        mockMvc.perform(
            get("/api/v1/kline")
                .param("code", "600000")
                .param("from", "not-a-date"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.error.code").value("PARAM_INVALID"))
    }
}
