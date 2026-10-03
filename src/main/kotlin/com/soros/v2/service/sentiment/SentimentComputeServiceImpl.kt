package com.soros.v2.service.sentiment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.soros.v2.config.SentimentProperties
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.service.sentiment.mapper.toPoolListItems
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * §4.9/§13.5 情绪周期计算服务（单点口径）。
 *
 * computeFor 为纯函数：依赖数据全部显式经 [SentimentComputeContext] 注入，函数体内不访问 Repository，
 * 回放与增量同路径（§13.5）。JSON 名单列（dragon_json/big_meat_list 等）需要 stock_info 名称/板块/行业
 * 富化，纯函数输出留 null，由 Job 落库层补齐（§4.9）。
 */
@Service
class SentimentComputeServiceImpl(
    private val poolEvaluator: SentimentPoolEvaluator,
    private val classifier: SentimentClassifier,
    private val stateMachine: DragonCycleStateMachine,
    private val properties: SentimentProperties,
) : SentimentComputeService {

    override fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult {
        val todayBars = ctx.barsByCode.mapValues { (_, bars) -> bars.maxByOrNull { it.tradeDate } }

        val sentiment = SentimentCycle().apply {
            tradeDate = date
            limitUpCount = todayBars.values.count { it?.isLimitUp == true }
            limitDownCount = todayBars.values.count { it?.isLimitDown == true }
            lianbanCount = todayBars.values.count { it != null && it.limitUpStreak >= 2 }
            maxStreak = todayBars.values.mapNotNull { it?.limitUpStreak?.toInt() }.maxOrNull()?.toShort()
        }

        derivePoolColumns(sentiment, ctx.barsByCode, todayBars)

        // 阶段标签 + 大/小周期建议值（分类器单点，Job 落库与 /terms 现算共用）
        sentiment.statusText = classifier.classifyStage(sentiment).label
        val (big, small) = classifier.suggestCycles(sentiment)
        sentiment.bigCycleSug = big
        sentiment.smallCycleSug = small

        // followup 兑现：前一日名单 → 今日表现（prevCycle null=回放首日，无昨日名单）
        if (ctx.prevCycle != null) {
            sentiment.followupJson = buildFollowupJson(date, ctx.prevCycle, ctx.barsByCode)
        }

        // 龙头状态机逐日推进 + 上位判定（无进行中龙头时最高板接棒）
        val dragonUpdates = mutableListOf<DragonCycle>()
        val deadDragonCodes = mutableListOf<String>()
        val survivors = mutableListOf<DragonCycle>()
        for (cycle in ctx.activeDragonCycles) {
            val todayBar = ctx.barsByCode[cycle.code]?.maxByOrNull { it.tradeDate }
            val transition = stateMachine.advance(date, cycle, todayBar, ctx.calendar)
            dragonUpdates.add(transition.cycle)
            if (transition.dead) deadDragonCodes.add(cycle.code) else survivors.add(transition.cycle)
        }
        if (survivors.isEmpty()) {
            val candidates = todayBars.values
                .filterNotNull()
                .filter { it.isLimitUp }
                .sortedWith(compareByDescending<StockHistory> { it.limitUpStreak }.thenBy { it.code })
            dragonUpdates.addAll(stateMachine.electLeader(date, candidates, emptyList()))
        }

        return SentimentComputeResult(sentiment, dragonUpdates, deadDragonCodes)
    }

    /** 强势池/崩塌池派生列（pool_count/big_meat_count/big_face_count/collapse_count/rebound_count） */
    private fun derivePoolColumns(sentiment: SentimentCycle, barsByCode: Map<String, List<StockHistory>>, todayBars: Map<String, StockHistory?>) {
        val strongCodes = barsByCode.filterValues { poolEvaluator.isInStrongPool(it) }.keys
        val collapseCodes = barsByCode.filterValues { poolEvaluator.isInCollapsePool(it) }.keys
        sentiment.poolCount = strongCodes.size
        sentiment.collapseCount = collapseCodes.size
        sentiment.bigMeatCount = strongCodes.count { code ->
            val pct = todayBars[code]?.changePct
            pct != null && pct.compareTo(properties.bigMeatThreshold) >= 0
        }
        sentiment.bigFaceCount = strongCodes.count { code ->
            val pct = todayBars[code]?.changePct
            pct != null && pct.compareTo(properties.bigFaceThreshold) <= 0
        }
        sentiment.reboundCount = collapseCodes.count { code ->
            val bar = todayBars[code]
            bar != null && (bar.changePct?.compareTo(properties.bigMeatThreshold)?.let { it >= 0 } == true || bar.isLimitUp)
        }
    }

    /** 昨日大肉/大面名单今日兑现（JSONB 键 snake_case，过命名字典 §17.6） */
    private fun buildFollowupJson(date: LocalDate, prevCycle: SentimentCycle, barsByCode: Map<String, List<StockHistory>>): JsonNode {
        val prevMeat = prevCycle.bigMeatList?.toPoolListItems() ?: emptyList()
        val prevFace = prevCycle.bigFaceList?.toPoolListItems() ?: emptyList()
        val followups = classifier.classifyFollowup(date, barsByCode, prevMeat, prevFace)
        return MAPPER.valueToTree(followups)
    }

    private companion object {
        val MAPPER: ObjectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    }
}
