package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.FollowupResult
import com.soros.v2.domain.SentimentStage
import com.soros.v2.domain.StockActionLabel
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.service.sentiment.dto.FollowupItem
import com.soros.v2.service.sentiment.dto.PoolListItem
import com.soros.v2.service.sentiment.dto.StockActionItem
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * §4.9 术语判定单点（SentimentClassifier）——SentimentCycleJob 落库与 /terms 接口现算
 * **共用同一实现**，口径永不漂移。
 *
 * 职责：
 * 1. 阶段标签映射（冰点~高潮）→ status_text 建议值
 * 2. 大/小周期建议值（big_cycle_sug/small_cycle_sug）：以龙头生命周期状态机为定性来源，
 *    1-6 数值评级保留为周期内阶段标注（连板高度 + 大肉/大面比 + 龙头晋级率映射，系数配置化）
 * 3. followup 四分类兑现（大肉名单延续/回落/转大面/停牌；大面名单反核止跌/弱势震荡/继续大面/停牌）
 * 4. 个股动作标签（反包/晋级/断板/反核止跌/继续大面/大肉/大面/停牌，含 evidence）
 */
@Component
class SentimentClassifier(
    private val properties: SentimentProperties,
) {

    /** 阶段标签映射：由当日 sentiment_cycle 派生列（maxStreak/大肉/大面/连板数）映射六阶段之一 */
    fun classifyStage(cycle: SentimentCycle): SentimentStage {
        val height = cycle.maxStreak?.toInt() ?: 0
        val meat = cycle.bigMeatCount
        val face = cycle.bigFaceCount
        val ladder = cycle.lianbanCount
        return when {
            // 高潮：情绪顶格（最高板 ≥8）
            height >= 8 -> SentimentStage.CLIMAX
            // 主升：最高板持续刷新 + 大肉批量
            height >= 6 && meat >= 8 -> SentimentStage.MAINRISE
            // 发酵：梯队扩张（连板家数 + 高度）
            ladder >= 8 && height >= 3 -> SentimentStage.FERMENT
            // 退潮：高标断板后大面激增
            height >= 3 && face >= 3 && face >= meat -> SentimentStage.RECEDE
            // 冰点：高度与大肉缩至底部、大面衰竭
            height <= 2 && meat == 0 -> SentimentStage.ICE
            // 混沌：涨跌互现无主线
            else -> SentimentStage.CHAOS
        }
    }

    /** 大/小周期建议值（1-6）：连板高度 + 大肉/大面比映射（系数配置化见 KDoc） */
    fun suggestCycles(cycle: SentimentCycle): Pair<Short?, Short?> {
        val height = cycle.maxStreak?.toInt() ?: 0
        val meat = cycle.bigMeatCount
        val face = cycle.bigFaceCount
        val big = cycleRating(height, meat, face)
        val small = (big - 1).coerceAtLeast(1)
        return big.toShort() to small.toShort()
    }

    /** 1-6 评级：连板高度基准 + 大肉批量加成 + 大面激增惩罚（coerce 1..6） */
    private fun cycleRating(height: Int, meat: Int, face: Int): Int {
        val base = when {
            height >= 8 -> 5
            height >= 6 -> 4
            height >= 4 -> 3
            height >= 3 -> 2
            else -> 1
        }
        val meatBonus = when {
            meat >= 10 -> 1
            meat >= 5 -> 0
            else -> -1
        }
        val facePenalty = if (face > meat * 2) -1 else 0
        return (base + meatBonus + facePenalty).coerceIn(1, 6)
    }

    /** followup 兑现：昨日大肉/大面名单 逐股取今日表现分类（今日行情 barsByCode 查询） */
    fun classifyFollowup(
        date: LocalDate,
        barsByCode: Map<String, List<StockHistory>>,
        bigMeatList: List<PoolListItem>,
        bigFaceList: List<PoolListItem>,
    ): List<FollowupItem> {
        val result = mutableListOf<FollowupItem>()
        for (item in bigMeatList) {
            val todayBar = barsByCode[item.code]?.firstOrNull { it.tradeDate == date }
            val pct = todayBar?.changePct
            val outcome = when {
                todayBar == null -> FollowupResult.SUSPENDED
                pct != null && pct.compareTo(properties.bigMeatThreshold) >= 0 -> FollowupResult.CONTINUE
                pct != null && pct.compareTo(properties.bigFaceThreshold) <= 0 -> FollowupResult.TURN_FACE
                else -> FollowupResult.RETREAT
            }
            result.add(FollowupItem(item.code, item.name, "meat", item.changePct, pct, outcome.label))
        }
        for (item in bigFaceList) {
            val todayBar = barsByCode[item.code]?.firstOrNull { it.tradeDate == date }
            val pct = todayBar?.changePct
            val outcome = when {
                todayBar == null -> FollowupResult.SUSPENDED
                pct != null && pct.compareTo(properties.bigMeatThreshold) >= 0 -> FollowupResult.REBOUND_STOP
                pct != null && pct.compareTo(properties.bigFaceThreshold) <= 0 -> FollowupResult.CONTINUE_FACE
                else -> FollowupResult.WEAK_SWING
            }
            result.add(FollowupItem(item.code, item.name, "face", item.changePct, pct, outcome.label))
        }
        return result
    }

    /** 当日个股动作标签清单（/terms 与钉钉日报共用）：反包/晋级/断板/反核止跌/继续大面/大肉/大面/停牌 + evidence */
    fun buildActions(
        bars: List<StockHistory>,
        activeDragon: DragonCycle?,
        reboundCodes: Set<String>,
    ): List<StockActionItem> {
        val sortedBars = bars.sortedBy { it.tradeDate }
        val result = mutableListOf<StockActionItem>()
        for (index in sortedBars.indices) {
            val today = sortedBars[index]
            val prev = sortedBars.getOrNull(index - 1)
            val label = labelFor(today, prev, activeDragon, reboundCodes, sortedBars)
            if (label != null) {
                result.add(StockActionItem(today.code, today.code, label.label, evidenceOf(label, today, prev)))
            }
        }
        return result
    }

    /**
     * 单根 bar 动作标签（buildActions 与 getStockActions 时间线**共用同一实现**，口径永不漂移）。
     * 优先级从高到低（§4.9 术语表）：反核止跌 → 反包 → 晋级 → 断板 → 继续大面 → 大肉 → 大面。
     */
    fun labelFor(
        today: StockHistory,
        prev: StockHistory?,
        activeDragon: DragonCycle?,
        reboundCodes: Set<String>,
        allBars: List<StockHistory>,
    ): StockActionLabel? {
        val pct = today.changePct
        return when {
            // 反核止跌（优先级最高）：崩塌股今日 ≥+5% 或涨停
            today.code in reboundCodes &&
                (pct != null && pct.compareTo(properties.bigMeatThreshold) >= 0 || today.isLimitUp) ->
                StockActionLabel.REBOUND_STOP
            // 反包：断板龙头观察期内再涨停
            activeDragon?.status == CycleStatus.BROKEN &&
                today.isLimitUp &&
                isWithinObserveWindow(activeDragon.brokenDate, today.tradeDate, allBars) ->
                StockActionLabel.REBREAK
            // 晋级：今日涨停且昨涨停（连板延续 n→n+1）
            today.isLimitUp && prev?.isLimitUp == true -> StockActionLabel.PROMOTE
            // 断板：昨涨停今未涨停
            prev?.isLimitUp == true && !today.isLimitUp -> StockActionLabel.BROKEN
            // 继续大面：跌停且 ≤-5%
            today.isLimitDown && pct != null && pct.compareTo(properties.bigFaceThreshold) <= 0 ->
                StockActionLabel.CONTINUE_FACE
            // 大肉：今日 ≥+5%
            pct != null && pct.compareTo(properties.bigMeatThreshold) >= 0 -> StockActionLabel.BIG_MEAT
            // 大面：今日 ≤-5%
            pct != null && pct.compareTo(properties.bigFaceThreshold) <= 0 -> StockActionLabel.BIG_FACE
            else -> null
        }
    }

    /** 反包观察窗口（broken_date 起 observeDays 个交易日，按 bar 序列粗判；精确判定由状态机 advance 用日历） */
    private fun isWithinObserveWindow(brokenDate: LocalDate?, today: LocalDate, bars: List<StockHistory>): Boolean {
        if (brokenDate == null || today.isBefore(brokenDate)) return false
        val tradingDays = bars.count { it.tradeDate in brokenDate..today }
        return tradingDays <= properties.dragon.observeDays + 1
    }

    /** evidence（判定口径说明，供日报/terms 展示） */
    private fun evidenceOf(label: StockActionLabel, today: StockHistory, prev: StockHistory?): String = when (label) {
        StockActionLabel.REBOUND_STOP -> "崩塌股今日${formatPct(today.changePct)}%（≥+5% 或涨停），资金逆势承接"
        StockActionLabel.REBREAK -> "断板后观察期内再涨停，反包（streak=${today.limitUpStreak}）"
        StockActionLabel.PROMOTE -> "连板延续 n→n+1（${prev?.limitUpStreak}→${today.limitUpStreak}）"
        StockActionLabel.BROKEN -> "昨涨停今未涨停，连板中断"
        StockActionLabel.CONTINUE_FACE -> "昨日大面名单今日仍 ${formatPct(today.changePct)}%（≤-5%）"
        StockActionLabel.BIG_MEAT -> "强势池内今日 ${formatPct(today.changePct)}%（≥+5%）"
        StockActionLabel.BIG_FACE -> "高位股今日 ${formatPct(today.changePct)}%（≤-5%）"
        StockActionLabel.SUSPENDED -> "停牌（交易日历开市但无 bar）"
    }

    private fun formatPct(pct: BigDecimal?): String = pct?.toPlainString() ?: "--"
}
