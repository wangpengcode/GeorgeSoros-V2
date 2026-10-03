package com.soros.v2.service.sentiment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * §4.9 术语判定接口 DTO（SentimentClassifier 现算，与 Job 落库同实现）。
 */

/** GET /api/v1/sentiment-cycle/{date}/terms → 当日完整术语解读 */
data class SentimentTermsResponse(
    /** 交易日 */
    @JsonProperty("trade_date") val tradeDate: LocalDate,
    /** 阶段标签（冰点~高潮，status_text 建议标签） */
    @JsonProperty("stage") val stage: String,
    /** 大周期（建议值优先，人工确认值覆盖展示） */
    @JsonProperty("big_cycle") val bigCycle: Int,
    /** 小周期（建议值优先，人工确认值覆盖展示） */
    @JsonProperty("small_cycle") val smallCycle: Int,
    /** 龙头状态（RISING/BROKEN/SUSPENDED/DEAD；null=无进行中龙头） */
    @JsonProperty("dragon_status") val dragonStatus: String?,
    /** 当前龙头名称（null=无） */
    @JsonProperty("dragon_name") val dragonName: String?,
    /** 当日个股动作标签清单 */
    @JsonProperty("actions") val actions: List<StockActionItem>,
)

/** terms 接口 actions[] 元素：个股动作标签 */
data class StockActionItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 动作标签（反包|晋级|断板|反核止跌|继续大面|大肉|大面|停牌） */
    @JsonProperty("label") val label: String,
    /** 证据（判定口径说明） */
    @JsonProperty("evidence") val evidence: String,
)
