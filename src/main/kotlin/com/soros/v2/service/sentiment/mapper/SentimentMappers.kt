package com.soros.v2.service.sentiment.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.service.sentiment.dto.CollapseListItem
import com.soros.v2.service.sentiment.dto.DragonCycleItem
import com.soros.v2.service.sentiment.dto.DragonJsonItem
import com.soros.v2.service.sentiment.dto.FollowupItem
import com.soros.v2.service.sentiment.dto.ManualListItem
import com.soros.v2.service.sentiment.dto.PoolListItem
import com.soros.v2.service.sentiment.dto.SentimentCycleResponse
import java.math.BigDecimal

/**
 * §4.9 情绪周期/龙头 Entity ↔ DTO 映射（业务代码禁止内联转换，一律走本文件扩展函数）。
 *
 * JSONB 列（dragon_json/big_meat_list/followup_json/lists_manual_json/leader_json/collapse_list）
 * 内部键全库统一（命名字典四节），解析直接遍历 JsonNode（数组元素 → 强类型 DTO）。
 */

/** sentiment_cycle → 单日响应（JSONB 数组解析为显式 DTO；leader_json 无固定键枚举原样透传） */
fun SentimentCycle.toDto(): SentimentCycleResponse = SentimentCycleResponse(
    tradeDate = tradeDate,
    limitUpCount = limitUpCount,
    limitDownCount = limitDownCount,
    lianbanCount = lianbanCount,
    maxStreak = maxStreak?.toInt(),
    dragonJson = dragonJson.toDragonJsonItems(),
    poolCount = poolCount,
    bigMeatCount = bigMeatCount,
    bigFaceCount = bigFaceCount,
    bigMeatList = bigMeatList.toPoolListItems(),
    bigFaceList = bigFaceList.toPoolListItems(),
    followupJson = followupJson.toFollowupItems(),
    listsManualJson = listsManualJson.toManualListItems(),
    leaderJson = leaderJson,
    collapseCount = collapseCount,
    collapseList = collapseList.toCollapseListItems(),
    reboundCount = reboundCount,
    bigCycleSug = bigCycleSug?.toInt(),
    smallCycleSug = smallCycleSug?.toInt(),
    bigCycle = bigCycle?.toInt(),
    smallCycle = smallCycle?.toInt(),
    statusText = statusText,
    dataCoverage = dataCoverage.name,
)

/** dragon_cycle → 龙头周期响应（maxStreak/rebreakCount/suspendedDays Short → Int；suspendJson 原样透传） */
fun DragonCycle.toDto(): DragonCycleItem = DragonCycleItem(
    id = id,
    code = code,
    startDate = startDate,
    endDate = endDate,
    maxStreak = maxStreak.toInt(),
    rebreakCount = rebreakCount.toInt(),
    suspendedDays = suspendedDays.toInt(),
    suspendJson = suspendJson,
    cycleType = cycleType,
    status = status,
    brokenDate = brokenDate,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** JSONB 数组 → 强类型列表（内部键命名字典对齐） */
internal fun JsonNode?.toDragonJsonItems(): List<DragonJsonItem> {
    if (this == null || !isArray) return emptyList()
    return mapNotNull { node ->
        if (!node.isObject) return@mapNotNull null
        DragonJsonItem(
            code = node.get("code")?.asText().orEmpty(),
            name = node.get("name")?.asText().orEmpty(),
            limitUpStreak = node.get("limit_up_streak")?.asInt() ?: 0,
            board = node.get("board")?.asText() ?: "MAIN",
            industry = node.get("industry")?.takeIf { it.isArray }?.map { it.asText() },
        )
    }
}

internal fun JsonNode?.toPoolListItems(): List<PoolListItem> {
    if (this == null || !isArray) return emptyList()
    return mapNotNull { node ->
        if (!node.isObject) return@mapNotNull null
        PoolListItem(
            code = node.get("code")?.asText().orEmpty(),
            name = node.get("name")?.asText().orEmpty(),
            changePct = node.get("change_pct").toBigDecimal() ?: BigDecimal.ZERO,
            limitUpStreak = node.get("limit_up_streak")?.asInt() ?: 0,
            industry = node.get("industry")?.takeIf { it.isArray }?.map { it.asText() },
        )
    }
}

internal fun JsonNode?.toCollapseListItems(): List<CollapseListItem> {
    if (this == null || !isArray) return emptyList()
    return mapNotNull { node ->
        if (!node.isObject) return@mapNotNull null
        CollapseListItem(
            code = node.get("code")?.asText().orEmpty(),
            name = node.get("name")?.asText().orEmpty(),
            limitDownStreak = node.get("limit_down_streak")?.asInt() ?: 0,
            industry = node.get("industry")?.takeIf { it.isArray }?.map { it.asText() },
        )
    }
}

internal fun JsonNode?.toFollowupItems(): List<FollowupItem> {
    if (this == null || !isArray) return emptyList()
    return mapNotNull { node ->
        if (!node.isObject) return@mapNotNull null
        FollowupItem(
            code = node.get("code")?.asText().orEmpty(),
            name = node.get("name")?.asText().orEmpty(),
            src = node.get("src")?.asText().orEmpty(),
            yestPct = node.get("yest_pct").toBigDecimal(),
            todayPct = node.get("today_pct").toBigDecimal(),
            result = node.get("result")?.asText().orEmpty(),
        )
    }
}

internal fun JsonNode?.toManualListItems(): List<ManualListItem> {
    if (this == null || !isArray) return emptyList()
    return mapNotNull { node ->
        if (!node.isObject) return@mapNotNull null
        ManualListItem(
            side = node.get("side")?.asText().orEmpty(),
            action = node.get("action")?.asText().orEmpty(),
            code = node.get("code")?.asText().orEmpty(),
            name = node.get("name")?.asText(),
            reason = node.get("reason")?.asText(),
            at = node.get("at")?.asText().orEmpty(),
        )
    }
}

/** JsonNode → BigDecimal（数值/字符串防御性解析，缺失/非法返回 null） */
private fun JsonNode?.toBigDecimal(): BigDecimal? = when {
    this == null -> null
    isNumber -> decimalValue()
    isTextual -> runCatching { BigDecimal(asText()) }.getOrNull()
    else -> null
}
