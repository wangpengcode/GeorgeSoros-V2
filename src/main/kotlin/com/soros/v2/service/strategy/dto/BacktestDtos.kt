package com.soros.v2.service.strategy.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * §19.13.3 G4 定稿 POST /api/v1/backtests 请求/响应 DTO（is_dry 显式必填、无默认值兜底）。
 *
 * 日期字段 @JsonFormat 强制 ISO 字符串（standalone MockMvc 的 ObjectMapper 默认
 * WRITE_DATES_AS_TIMESTAMPS=true，对齐 KlineDtos 既有模式）。
 */
data class BacktestCreateRequest(
    @JsonProperty("strategy_name") val strategyName: String,
    @JsonProperty("config_id") val configId: Long? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("start_date") val startDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("end_date") val endDate: LocalDate,
    @JsonProperty("is_dry") val isDry: Boolean? = null,
)

data class BacktestResultDto(
    val id: Long,
    @JsonProperty("strategy_name") val strategyName: String,
    @JsonProperty("config_id") val configId: Long? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("start_date") val startDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("end_date") val endDate: LocalDate,
    @JsonProperty("is_dry") val isDry: Boolean,
)
