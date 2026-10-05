package com.soros.v2.service.strategy

import com.soros.v2.domain.StockActionLabel
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * §19.13.3 ConditionEvaluator 无状态实现：按条件类别派发路径 A（聚合表直查）/ 路径 L（候选池扫描现算），
 * B/C 类只产 data_state 不就绪。求值窗口 = ctx.window，每条件逐日判真 → last_fired/fired_30d/firedDays。
 */
@Component
class ConditionEvaluatorImpl : ConditionEvaluator {

    override fun evaluate(cond: ParsedCondition, ctx: EvalContext): ConditionResult = when (cond.cls) {
        ConditionClass.A -> evaluateA(cond, ctx)
        ConditionClass.L -> evaluateL(cond, ctx)
        ConditionClass.B -> pending(ConditionClass.B, emptyMap())
        ConditionClass.C -> pending(ConditionClass.C, buildCDataState(ctx))
    }

    /** 路径 A：聚合表直查，条件逐日判真，零候选依赖。 */
    private fun evaluateA(cond: ParsedCondition, ctx: EvalContext): ConditionResult {
        val codes = if (ctx.universe.isEmpty()) ctx.signalTables.stockHistory.keys else ctx.universe
        val firedDays = ctx.window.filter { day ->
            codes.any { code ->
                val metric = metricFor(cond.source, code, day, ctx.signalTables)
                metric != null && matches(cond.op, metric, cond.value)
            }
        }
        return ConditionResult(
            cls = ConditionClass.A,
            ready = ReadyState.READY,
            lastFired = firedDays.lastOrNull(),
            fired30d = firedDays.size,
            firedDays = firedDays,
            dataState = emptyMap(),
        )
    }

    /** 路径 L：候选池扫描现算（G5），∃ code ∈ universe∩候选集 当日命中目标标签（within_days N 放宽至 [D−N+1, D]）。 */
    private fun evaluateL(cond: ParsedCondition, ctx: EvalContext): ConditionResult {
        val target = (cond.value as? String)?.let { StockActionLabel.fromLabel(it) }
        if (target == null) {
            // 目标标签不在词表值域 → 永不触发（不误导）
            return ConditionResult(ConditionClass.L, ReadyState.READY, null, 0, emptyList(), buildLDataState(emptyMap(), ctx.window.size))
        }
        val labels = ctx.labelService.getLabels(ctx.window.last(), ctx.window.size)
        val n = cond.withinDays.coerceAtLeast(1)
        val candidateCodes = ctx.universe.intersect(labels.keys)
        val firedDays = ctx.window.filter { day ->
            val windowStart = day.minusDays(n.toLong() - 1)
            candidateCodes.any { code ->
                val perCode = labels[code] ?: return@any false
                perCode.entries.any { (labelDate, label) ->
                    labelDate in windowStart..day && label == target
                }
            }
        }
        return ConditionResult(
            cls = ConditionClass.L,
            ready = ReadyState.READY,
            lastFired = firedDays.lastOrNull(),
            fired30d = firedDays.size,
            firedDays = firedDays,
            dataState = buildLDataState(labels, ctx.window.size),
        )
    }

    private fun pending(cls: ConditionClass, dataState: Map<String, Any>): ConditionResult =
        ConditionResult(cls, ReadyState.PENDING, null, 0, emptyList(), dataState)

    /** 路径 A 单票单日指标读取（source → 表/列映射；signal_daily 无 limit_up_streak 列，读 stock_history） */
    private fun metricFor(source: String, code: String, day: LocalDate, tables: SignalWindow): Any? = when (source) {
        "limit_up_streak" -> tables.stockHistory[code]?.firstOrNull { it.tradeDate == day }?.limitUpStreak?.toInt()
        "ladder_rank" -> tables.signalDaily[code]?.firstOrNull { it.tradeDate == day }?.ladderRank?.toInt()
        "is_zhaban" -> tables.signalDaily[code]?.firstOrNull { it.tradeDate == day }?.isZhaban
        "sector_ladder_rank" -> tables.signalDaily[code]?.firstOrNull { it.tradeDate == day }?.sectorLadderRank?.toInt()
        "adv_count" -> tables.marketDaily[day]?.advCount?.toInt()
        "dec_count" -> tables.marketDaily[day]?.decCount?.toInt()
        "yst_limit_premium" -> tables.marketDaily[day]?.ystLimitPremium
        "yst_face_count" -> tables.marketDaily[day]?.ystFaceCount?.toInt()
        "limit_up_count" -> tables.marketDaily[day]?.limitUpCount?.toInt()
        "avg_chg_pct_all" -> tables.sectorDaily.firstOrNull { it.tradeDate == day }?.avgChgPctAll
        else -> null
    }

    /** 运算符判真（between 闭区间；equals/gte/lte 数值比较；Boolean 走 equals） */
    private fun matches(op: String, metric: Any, value: Any?): Boolean {
        if (metric is Boolean) {
            val want = value as? Boolean ?: return false
            return op == "equals" && metric == want
        }
        val num = asBigDecimal(metric) ?: return false
        return when (op) {
            "between" -> {
                val bounds = (value as? List<*>)?.mapNotNull { asBigDecimal(it) }
                bounds != null && bounds.size == 2 && num >= bounds[0]!! && num <= bounds[1]!!
            }
            "equals" -> asBigDecimal(value)?.let { num.compareTo(it) == 0 } ?: false
            "gte" -> asBigDecimal(value)?.let { num >= it } ?: false
            "lte" -> asBigDecimal(value)?.let { num <= it } ?: false
            else -> false
        }
    }

    private fun asBigDecimal(v: Any?): BigDecimal? = when (v) {
        is BigDecimal -> v
        is Int -> BigDecimal.valueOf(v.toLong())
        is Long -> BigDecimal.valueOf(v)
        is Short -> BigDecimal.valueOf(v.toLong())
        is Number -> BigDecimal(v.toString())
        else -> null
    }

    /** C 类 data_state：表存在性 + latest + coverage（本期唯一有意义输出） */
    private fun buildCDataState(ctx: EvalContext): Map<String, Any> {
        val signalLatest = ctx.signalTables.signalDaily.values.flatten().maxOfOrNull { it.tradeDate }
        val marketLatest = ctx.signalTables.marketDaily.keys.maxOrNull()
        val sectorLatest = ctx.signalTables.sectorDaily.maxOfOrNull { it.tradeDate }
        return mapOf(
            "signal_daily" to mapOf(
                "latest" to signalLatest?.toString(),
                "days" to ctx.signalTables.signalDaily.values.flatten().count { it.tradeDate in ctx.window },
                "coverage" to if (ctx.signalTables.signalDaily.isNotEmpty()) "FULL" else "PARTIAL",
            ),
            "market_daily" to mapOf(
                "latest" to marketLatest?.toString(),
                "coverage" to if (ctx.signalTables.marketDaily.isNotEmpty()) "FULL" else "PARTIAL",
            ),
            "sector_daily" to mapOf(
                "latest" to sectorLatest?.toString(),
                "board_rows" to ctx.signalTables.sectorDaily.size,
            ),
        )
    }

    /** L 类 data_state：扫描范围 + 候选量 + 覆盖天数（G5） */
    private fun buildLDataState(labels: Map<String, Map<LocalDate, StockActionLabel>>, windowDays: Int): Map<String, Any> =
        mapOf(
            "scan_scope" to "五池并集∪龙头",
            "candidate_count" to labels.size,
            "covered_days" to windowDays,
            "candidate_cover" to "仅池内票可出标签；universe 候选集外票 ready=READY 但永不触发（L5）",
        )
}
