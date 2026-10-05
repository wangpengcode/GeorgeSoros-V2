package com.soros.v2.controller

import com.soros.v2.service.kline.KlineService
import com.soros.v2.service.kline.dto.KlineResponse
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §19.13.1 KlineController：K线复盘端点（GET /api/v1/kline）。
 *
 * 契约（§19.13.1 端点契约 / 行为矩阵 / GlobalExceptionHandler）：
 * - code 必填（缺失 → MissingServletRequestParameterException → 400 MISSING_PARAM）；
 * - from/to/date 可选 + ISO.DATE 解析（非法 → MethodArgumentTypeMismatchException → 400 PARAM_INVALID）；
 * - 窗口语义由服务层裁决（默认近 250 交易日 / date 终点吸附 / from>to 422 / 未知 code 404）。
 */
@RestController
@RequestMapping("/api/v1")
class KlineController(
    private val klineService: KlineService,
) {

    /** GET /api/v1/kline?code=&from=&to=&date= → K线 bars（qfq OHLC ∘ 筹码 8 列） */
    @GetMapping("/kline")
    fun getKline(
        @RequestParam("code") code: String,
        @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): KlineResponse = klineService.getKline(code, from, to, date)
}
