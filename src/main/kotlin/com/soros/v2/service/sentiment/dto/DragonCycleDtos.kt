package com.soros.v2.service.sentiment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * §11.1 龙头生命周期接口 DTO（查 dragon_cycle，网页时间轴数据源）。
 */

/** GET /api/v1/dragon-cycle?status=&limit= → 龙头生命周期列表 */
data class DragonCycleListResponse(
    /** 龙头周期列表（按 start_date DESC） */
    @JsonProperty("items") val items: List<DragonCycleItem>,
)

/** dragon_cycle 单条周期 */
data class DragonCycleItem(
    /** 行主键 */
    @JsonProperty("id") val id: Long,
    /** 龙头代码 */
    @JsonProperty("code") val code: String,
    /** 上位日 */
    @JsonProperty("start_date") val startDate: LocalDate,
    /** 阵亡/定性日（null=进行中） */
    @JsonProperty("end_date") val endDate: LocalDate?,
    /** 周期内最高连板 */
    @JsonProperty("max_streak") val maxStreak: Int,
    /** 反包次数 */
    @JsonProperty("rebreak_count") val rebreakCount: Int,
    /** 停牌天数（停牌周期延续） */
    @JsonProperty("suspended_days") val suspendedDays: Int,
    /** 停牌区间 [{from,to}] */
    @JsonProperty("suspend_json") val suspendJson: JsonNode?,
    /** 周期类型（BIG/SMALL；null=进行中未定性） */
    @JsonProperty("cycle_type") val cycleType: CycleType?,
    /** 周期状态（RISING/BROKEN/SUSPENDED/DEAD） */
    @JsonProperty("status") val status: CycleStatus,
    /** 最近断板日（反包观察期起点，默认 3 交易日） */
    @JsonProperty("broken_date") val brokenDate: LocalDate?,
    /** 备注 */
    @JsonProperty("note") val note: String?,
    /** 行创建时间 */
    @JsonProperty("created_at") val createdAt: LocalDateTime,
    /** 行更新时间 */
    @JsonProperty("updated_at") val updatedAt: LocalDateTime,
)

/** PUT /api/v1/dragon-cycle/{id}/confirm → 人工改判龙头周期大小/备注（body: {cycle_type, note}） */
data class DragonCycleConfirmRequest(
    /** 周期类型（BIG/SMALL；null=不改） */
    @JsonProperty("cycle_type") val cycleType: CycleType? = null,
    /** 备注（null=不改） */
    @JsonProperty("note") val note: String? = null,
)
