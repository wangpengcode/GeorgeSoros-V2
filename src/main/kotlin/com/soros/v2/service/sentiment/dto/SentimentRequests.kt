package com.soros.v2.service.sentiment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.soros.v2.domain.PoolAction
import com.soros.v2.domain.PoolSide

/**
 * §11.1 情绪周期人工编辑接口入参 DTO。
 */

/** PUT /api/v1/sentiment-cycle/{date}/confirm → 人工确认/修正 大周期/小周期/状态（body: {big_cycle, small_cycle, status_text}） */
data class SentimentConfirmRequest(
    /** 大周期人工确认值 1-6（null=不修改） */
    @JsonProperty("big_cycle") val bigCycle: Int? = null,
    /** 小周期人工确认值 1-6（null=不修改） */
    @JsonProperty("small_cycle") val smallCycle: Int? = null,
    /** 状态建议标签人工终定（冰点~高潮，null=不修改） */
    @JsonProperty("status_text") val statusText: String? = null,
) {
    init {
        require(bigCycle == null || bigCycle in 1..6) { "big_cycle 必须在 1-6 之间" }
        require(smallCycle == null || smallCycle in 1..6) { "small_cycle 必须在 1-6 之间" }
    }
}

/**
 * PUT /api/v1/sentiment-cycle/{date}/lists → 人工增删大肉/大面名单
 * body: {side: MEAT/FACE, action: ADD/REMOVE, code, name?, reason?}。
 * 服务端校验代码存在且非 ST（ST 隔离铁律）、重算 count、写 lists_manual_json 留痕。
 */
data class SentimentListsRequest(
    /** 名单侧（MEAT=大肉 / FACE=大面） */
    @JsonProperty("side") val side: PoolSide,
    /** 动作（ADD=添加 / REMOVE=移除） */
    @JsonProperty("action") val action: PoolAction,
    /** 证券代码（裸数字） */
    @JsonProperty("code") val code: String,
    /** 名称（ADD 时可带，服务端以 stock_info 为准） */
    @JsonProperty("name") val name: String? = null,
    /** 理由（留痕用） */
    @JsonProperty("reason") val reason: String? = null,
) {
    init {
        require(code.isNotBlank()) { "code 不能为空" }
    }
}
