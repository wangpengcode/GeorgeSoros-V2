package com.soros.v2.service.sentiment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.soros.v2.config.SentimentProperties
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.StockHistory
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * §4.9 龙头生命周期状态机（逐日推进，纯函数——可独立单测）。
 *
 * 用户口径：周期 = 龙头的生命周期。小周期=龙头 5-7 板断板且不反包；大周期=断板后反包再涨停创新高。
 * 龙头停牌不结束周期（停牌期间周期延续，复牌后再走断板/反包判定）。
 *
 * 状态流转：RISING → BROKEN（观察期）→ [反包→RISING | 阵亡→DEAD | 停牌→SUSPENDED]
 */
@Component
class DragonCycleStateMachine(
    private val properties: SentimentProperties,
) {

    /** 单日推进：给定龙头周期 + 当日 bar（null=无 bar 停牌）+ 交易日历，产出状态转移 */
    fun advance(date: LocalDate, cycle: DragonCycle, todayBar: StockHistory?, calendar: List<LocalDate>): StateTransition {
        // 停牌：交易日历开市但无 bar → SUSPENDED，周期延续（§4.9）
        if (todayBar == null) {
            cycle.status = CycleStatus.SUSPENDED
            cycle.suspendedDays = (cycle.suspendedDays + 1).toShort()
            cycle.suspendJson = appendSuspendInterval(cycle.suspendJson, date)
            return StateTransition(cycle, dead = false, message = "停牌：无当日 bar，周期延续")
        }
        return when (cycle.status) {
            CycleStatus.RISING -> advanceRising(date, cycle, todayBar)
            CycleStatus.BROKEN -> advanceBroken(date, cycle, todayBar, calendar)
            CycleStatus.SUSPENDED -> advanceSuspended(date, cycle, todayBar)
            CycleStatus.DEAD -> StateTransition(cycle, dead = false, message = "周期已结束，忽略推进")
        }
    }

    /** RISING 推进：涨停→max_streak 刷新留 RISING；未涨停→断板进观察期（BROKEN，broken_date=当日） */
    private fun advanceRising(date: LocalDate, cycle: DragonCycle, todayBar: StockHistory): StateTransition =
        if (todayBar.isLimitUp) {
            cycle.maxStreak = maxOf(cycle.maxStreak, todayBar.limitUpStreak)
            StateTransition(cycle, dead = false, message = "晋级：涨停延续至 ${cycle.maxStreak} 板")
        } else {
            cycle.status = CycleStatus.BROKEN
            cycle.brokenDate = date
            StateTransition(cycle, dead = false, message = "断板：涨停中断，进入反包观察期")
        }

    /** BROKEN 推进：观察期内涨停→反包回 RISING；超过 observeDays 交易日无反包→阵亡 DEAD */
    private fun advanceBroken(date: LocalDate, cycle: DragonCycle, todayBar: StockHistory, calendar: List<LocalDate>): StateTransition {
        if (todayBar.isLimitUp) {
            // 反包：rebreak_count+1，回 RISING；创新高 → 定性 BIG（§4.9 大周期）
            cycle.rebreakCount = (cycle.rebreakCount + 1).toShort()
            cycle.status = CycleStatus.RISING
            cycle.brokenDate = null
            if (todayBar.limitUpStreak > cycle.maxStreak) {
                cycle.cycleType = CycleType.BIG
                cycle.maxStreak = todayBar.limitUpStreak
            } else {
                cycle.maxStreak = maxOf(cycle.maxStreak, todayBar.limitUpStreak)
            }
            return StateTransition(cycle, dead = false, message = "反包：断板后观察期内再涨停")
        }
        // 观察期推进：broken_date 起 observeDays 个交易日内无反包 → 阵亡
        val brokenDate = cycle.brokenDate ?: date
        val tradingDays = calendar.count { it in brokenDate..date }
        if (tradingDays > properties.dragon.observeDays) {
            cycle.status = CycleStatus.DEAD
            cycle.endDate = date
            cycle.cycleType = qualifyCycleType(cycle)
            return StateTransition(cycle, dead = true, message = "观察期 ${tradingDays} 个交易日无反包，阵亡（cycle_type=${cycle.cycleType}）")
        }
        return StateTransition(cycle, dead = false, message = "观察期第 ${tradingDays} 个交易日未反包，继续观察")
    }

    /** SUSPENDED 推进：复牌有 bar → 涨停回 RISING / 未涨停进 BROKEN 观察期 */
    private fun advanceSuspended(date: LocalDate, cycle: DragonCycle, todayBar: StockHistory): StateTransition {
        if (todayBar.isLimitUp) {
            cycle.status = CycleStatus.RISING
            cycle.maxStreak = maxOf(cycle.maxStreak, todayBar.limitUpStreak)
            return StateTransition(cycle, dead = false, message = "复牌涨停：回到上升期")
        }
        cycle.status = CycleStatus.BROKEN
        cycle.brokenDate = date
        return StateTransition(cycle, dead = false, message = "复牌未涨停：进入反包观察期")
    }

    /**
     * 断板定性（用户口径 §4.9）：大周期 = 断板后反包再涨停并创新高（cycle_type 已在反包定格 BIG）
     * 或断板板高超过 small-max；反包未创新高（cycle_type 未定格）→ SMALL 并在 note 留痕。
     */
    private fun qualifyCycleType(cycle: DragonCycle): CycleType {
        val bigByRebreakNewHigh = cycle.cycleType == CycleType.BIG
        val bigByBoardHeight = cycle.maxStreak > properties.dragon.smallMaxLimitUpStreak
        if (bigByRebreakNewHigh || bigByBoardHeight) return CycleType.BIG
        if (cycle.rebreakCount > 0) {
            cycle.note = "反包未创新高，维持小周期（大周期=断板后反包再涨停并创新高，§4.9）"
        }
        return CycleType.SMALL
    }

    /** suspend_json 停牌区间追加/续写（[{from,to}]，连续停牌日延展 to） */
    private fun appendSuspendInterval(existing: JsonNode?, date: LocalDate): JsonNode {
        val mapper = ObjectMapper()
        val arr = existing as? ArrayNode ?: mapper.createArrayNode()
        val last = if (arr.size() > 0) arr.get(arr.size() - 1) else null
        if (last is ObjectNode && last.get("to")?.asText() == date.minusDays(1).toString()) {
            last.put("to", date.toString())
        } else {
            arr.add(mapper.createObjectNode().put("from", date.toString()).put("to", date.toString()))
        }
        return arr
    }

    /** 上位判定：前一龙头 DEAD 后，当日最高板股票接棒开新周期（并列取先到者；confirm 接口可人工改判） */
    fun electLeader(date: LocalDate, topBars: List<StockHistory>, active: List<DragonCycle>): List<DragonCycle> {
        if (active.isNotEmpty()) return emptyList()
        if (topBars.isEmpty()) return emptyList()
        val leader = topBars.maxByOrNull { it.limitUpStreak } ?: return emptyList()
        val newCycle = DragonCycle().apply {
            code = leader.code
            startDate = date
            maxStreak = leader.limitUpStreak
            rebreakCount = 0
            suspendedDays = 0
            status = CycleStatus.RISING
            endDate = null
        }
        return listOf(newCycle)
    }
}

/** 状态转移结果（纯函数产出，由 Job 持久化） */
data class StateTransition(
    /** 转移后的周期（字段已就地更新；新建周期 id=0 需持久化） */
    val cycle: DragonCycle,
    /** 是否本次推进将周期定性为结束（end_date 刚写入，供日报"今日阵亡"） */
    val dead: Boolean,
    /** 本次转移说明（日报 evidence） */
    val message: String,
)
