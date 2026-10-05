package com.soros.v2.service.strategy.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

/**
 * §19.13.3 策略控制台 a 期 DTO（snake_case 响应/请求键对齐命名字典 §19.13.3）。
 */
data class StrategySummaryDto(
    val id: Long,
    val name: String,
    val version: Int,
    val status: String,
    @JsonProperty("alert_enabled") val alertEnabled: Boolean,
    val note: String? = null,
)

data class StrategyDetailDto(
    val id: Long,
    val name: String,
    val yaml: String,
    val version: Int,
    val status: String,
    @JsonProperty("alert_enabled") val alertEnabled: Boolean,
    val note: String? = null,
)

data class StrategyCreateRequest(
    val name: String,
    val conditions: List<ConditionInput>,
    @JsonProperty("alert_enabled") val alertEnabled: Boolean = false,
    val note: String? = null,
)

data class StrategyUpdateRequest(
    val conditions: List<ConditionInput>,
    @JsonProperty("alert_enabled") val alertEnabled: Boolean = false,
    val note: String? = null,
)

data class StrategyHistoryItemDto(
    val version: Int,
    val yaml: String,
    @JsonProperty("created_at") val createdAt: LocalDateTime? = null,
)

data class StrategyYamlDto(
    val yaml: String,
)

/** 结构化条件输入（服务端生成 YAML 的唯一事实来源；§12.9 决策 1 无自由 YAML 文本） */
data class ConditionInput(
    @JsonProperty("cond_id") val condId: String,
    val side: String,
    val source: String,
    val op: String,
    val value: Any? = null,
)
