package com.soros.v2.service.signal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.domain.Board
import com.soros.v2.domain.DataCoverage
import com.soros.v2.entity.MarketDaily
import com.soros.v2.entity.SectorDaily
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * 信号聚合服务实现（SignalPrecomputeJob 路径 B，§12.4 / §19.11.1 穿透定稿）。
 *
 * 口径纪律（§19.11.1 / §4.8）：
 * - adv/dec 按不复权 change_pct 计：>0 涨、<0 跌、=0 两边不计
 * - zhaban 日线近似 = high ≥ round(昨收×(1+阈值),2) 且非涨停；MAIN=0.10、GEM/STAR=0.20
 * - yst_promotion：停牌计入分母视为未晋级；yst_limit_premium：停牌剔除分母（无价不可算）
 * - yst_face_count 直读 sentiment_cycle(t-1).big_face_count
 * - 梯队排名按 limit_up_streak 降序 dense（并列同名次）
 */
@Service
class SignalAggregationServiceImpl(
    private val stockHistoryRepository: StockHistoryRepository,
    private val stockInfoRepository: StockInfoRepository,
    private val sentimentCycleRepository: SentimentCycleRepository,
    private val marketDailyRepository: MarketDailyRepository,
) : SignalAggregationService {

    /** §19.11.1/§4.8 炸板日线近似：high ≥ round(昨收×(1+阈值),2) 且非涨停；无昨收/涨停 → false */
    override fun isZhaban(bar: StockHistory, board: String, prevClose: BigDecimal?): Boolean {
        if (prevClose == null) return false
        if (bar.isLimitUp) return false
        val threshold = if (board == Board.GEM.name || board == Board.STAR.name) BigDecimal("0.20") else BigDecimal("0.10")
        val limitPrice = prevClose.multiply(BigDecimal.ONE.add(threshold)).setScale(2, RoundingMode.HALF_UP)
        val high = bar.high ?: return false
        return high.compareTo(limitPrice) >= 0
    }

    /** §19.11.1 当日全市场温度计（market_daily 一行；yst_* 由昨名单∘今行情自算） */
    override fun aggregateMarket(date: LocalDate, prevDate: LocalDate?): MarketDaily {
        val bars = stockHistoryRepository.findByTradeDateBetween(date, date)
        val infoByCode = stockInfoRepository.findByCodeIn(bars.map { it.code }.distinct()).associateBy { it.code }
        val todayByCode = bars.groupBy { it.code }

        val advCount = bars.count { (it.changePct?.signum() ?: 0) > 0 }
        val decCount = bars.count { (it.changePct?.signum() ?: 0) < 0 }
        val limitUpCount = bars.count { it.isLimitUp }
        val limitDownCount = bars.count { it.isLimitDown }
        val limitUpList = buildLimitList(bars.filter { it.isLimitUp }, infoByCode)
        val limitDownList = buildLimitList(bars.filter { it.isLimitDown }, infoByCode)

        val zhabanCount = if (prevDate == null) 0 else {
            bars.count { bar ->
                val prevClose = stockHistoryRepository.findByCodeAndTradeDate(bar.code, prevDate)?.close
                prevClose != null && isZhaban(bar, infoByCode[bar.code]?.board ?: Board.MAIN.name, prevClose)
            }
        }

        val prevMarket = prevDate?.let { marketDailyRepository.findByTradeDate(it) }
        val prevSentiment = prevDate?.let { sentimentCycleRepository.findByTradeDate(it) }
        val prevEntries = parseLimitUpEntries(prevMarket?.limitUpList)

        val ystLimitPremium = if (prevEntries.isEmpty()) null else {
            val tradedToday = prevEntries.mapNotNull { (code, _) -> todayByCode[code]?.firstOrNull() }
            val changePcts = tradedToday.mapNotNull { it.changePct }
            if (changePcts.isEmpty()) null
            else changePcts.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                .divide(BigDecimal(changePcts.size), 2, RoundingMode.HALF_UP)
        }
        val ystPromotion = if (prevEntries.isEmpty()) null else buildPromotionJson(prevEntries, todayByCode)
        val ystFaceCount = prevSentiment?.bigFaceCount?.toShort()

        return MarketDaily().apply {
            this.tradeDate = date
            this.advCount = advCount.toShort()
            this.decCount = decCount.toShort()
            this.limitUpCount = limitUpCount.toShort()
            this.limitDownCount = limitDownCount.toShort()
            this.limitUpList = limitUpList
            this.limitDownList = limitDownList
            this.zhabanCount = zhabanCount.toShort()
            this.ystLimitPremium = ystLimitPremium
            this.ystPromotion = ystPromotion
            this.ystFaceCount = ystFaceCount
            this.dataCoverage = DataCoverage.FULL
        }
    }

    /** §19.11.1 决策 3：industry 主口径板块聚合（avg_chg_pct_all = 板块全成员均涨） */
    override fun aggregateSectors(date: LocalDate): List<SectorDaily> {
        val bars = stockHistoryRepository.findByTradeDateBetween(date, date)
        if (bars.isEmpty()) return emptyList()
        val infoByCode = stockInfoRepository.findByCodeIn(bars.map { it.code }.distinct()).associateBy { it.code }
        return bars.groupBy { infoByCode[it.code]?.industry?.firstOrNull() ?: UNKNOWN_INDUSTRY }
            .map { (industry, members) ->
                val limitUpMembers = members.filter { it.isLimitUp }
                SectorDaily().apply {
                    this.tradeDate = date
                    this.board = industry
                    this.limitUpCount = limitUpMembers.size.toShort()
                    this.maxStreak = limitUpMembers.maxOfOrNull { it.limitUpStreak } ?: 0.toShort()
                    this.avgChgPct = averageOf(limitUpMembers.mapNotNull { it.changePct })
                    this.avgChgPctAll = averageOf(members.mapNotNull { it.changePct })
                }
            }
    }

    /** §12.4 C 类：梯队 dense 排名（并列同名次，市场/板块双轨） */
    override fun assignLadderRanks(date: LocalDate): List<SignalLadderRow> {
        val limitUpBars = stockHistoryRepository.findByTradeDateAndIsLimitUpTrue(date)
        if (limitUpBars.isEmpty()) return emptyList()
        val infoByCode = stockInfoRepository.findByCodeIn(limitUpBars.map { it.code }).associateBy { it.code }
        val marketRanks = denseRanks(limitUpBars.map { it.limitUpStreak })
        val sectorRanks = limitUpBars
            .groupBy { infoByCode[it.code]?.industry?.firstOrNull() ?: UNKNOWN_INDUSTRY }
            .mapValues { (_, members) -> denseRanks(members.map { it.limitUpStreak }) }
        return limitUpBars.map { bar ->
            val sector = infoByCode[bar.code]?.industry?.firstOrNull() ?: UNKNOWN_INDUSTRY
            SignalLadderRow(
                code = bar.code,
                ladderRank = marketRanks[bar.limitUpStreak] ?: 0.toShort(),
                sectorLadderRank = sectorRanks[sector]?.get(bar.limitUpStreak) ?: 0.toShort(),
            )
        }
    }

    /** 涨停名单 JSONB（全库统一键名 code,name,change_pct,limit_up_streak,industry，§17.6） */
    private fun buildLimitList(bars: List<StockHistory>, infoByCode: Map<String, StockInfo>): JsonNode {
        val entries = bars.map { bar ->
            val info = infoByCode[bar.code]
            mapOf(
                "code" to bar.code,
                "name" to (info?.name ?: ""),
                "change_pct" to (bar.changePct ?: BigDecimal.ZERO),
                "limit_up_streak" to bar.limitUpStreak.toInt(),
                "industry" to (info?.industry?.firstOrNull() ?: ""),
            )
        }
        return MAPPER.valueToTree(entries)
    }

    /** §19.11.1 决策 4：分级晋级率 {"total":21.05,"by_level":{"1to2":33.3,...}}；停牌计入分母视为未晋级 */
    private fun buildPromotionJson(
        prevEntries: List<Pair<String, Int>>,
        todayByCode: Map<String, List<StockHistory>>,
    ): JsonNode {
        val root = MAPPER.createObjectNode()
        val byLevel = root.putObject("by_level")
        var promotedTotal = 0
        for ((level, entries) in prevEntries.groupBy { it.second }) {
            val promoted = entries.count { (code, streak) ->
                val bar = todayByCode[code]?.firstOrNull()
                bar != null && bar.isLimitUp && bar.limitUpStreak.toInt() == streak + 1
            }
            promotedTotal += promoted
            val rate = BigDecimal.valueOf(promoted * 100.0)
                .divide(BigDecimal(entries.size), 2, RoundingMode.HALF_UP)
            byLevel.put("${level}to${level + 1}", rate)
        }
        val totalRate = BigDecimal.valueOf(promotedTotal * 100.0)
            .divide(BigDecimal(prevEntries.size), 2, RoundingMode.HALF_UP)
        root.put("total", totalRate)
        return root
    }

    /** 昨涨停名单 → (code, limit_up_streak) 清单 */
    private fun parseLimitUpEntries(list: JsonNode?): List<Pair<String, Int>> {
        if (list == null || !list.isArray) return emptyList()
        return list.mapNotNull { node ->
            val code = node.get("code")?.asText() ?: return@mapNotNull null
            val streak = node.get("limit_up_streak")?.asInt() ?: 0
            code to streak
        }
    }

    /** 板块/名单平均涨幅%（空集 → null） */
    private fun averageOf(changes: List<BigDecimal>): BigDecimal? {
        if (changes.isEmpty()) return null
        return changes.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal(changes.size), 4, RoundingMode.HALF_UP)
    }

    /** dense 排名：distinct 降序后下标即名次（并列同名次） */
    private fun denseRanks(streaks: List<Short>): Map<Short, Short> {
        val distinct = streaks.distinct().sortedDescending()
        return distinct.withIndex().associate { (idx, v) -> v to (idx + 1).toShort() }
    }

    private companion object {
        val MAPPER: ObjectMapper = ObjectMapper()
        const val UNKNOWN_INDUSTRY = "未知"
    }
}
