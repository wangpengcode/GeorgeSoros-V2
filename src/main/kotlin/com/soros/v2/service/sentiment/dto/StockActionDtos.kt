package com.soros.v2.service.sentiment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

/**
 * §4.9 GET /api/v1/stocks/{code}/actions 个股动作标签时间线 DTO。
 * 复盘视角：某股 6/18 断板、6/20 反包、6/23 创新高定性大周期。
 */
data class StockActionsResponse(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 逐日动作标签（升序） */
    @JsonProperty("actions") val actions: List<StockActionTimelineItem>,
)

/** actions[] 元素：单日动作标签 */
data class StockActionTimelineItem(
    /** 交易日 */
    @JsonProperty("trade_date") val tradeDate: LocalDate,
    /** 动作标签（反包|晋级|断板|反核止跌|继续大面|大肉|大面|停牌） */
    @JsonProperty("label") val label: String,
    /** 连板数 */
    @JsonProperty("limit_up_streak") val limitUpStreak: Int,
    /** 涨跌幅%（不复权口径） */
    @JsonProperty("change_pct") val changePct: BigDecimal?,
)
