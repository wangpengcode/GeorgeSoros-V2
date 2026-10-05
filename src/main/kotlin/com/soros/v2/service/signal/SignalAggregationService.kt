package com.soros.v2.service.signal

import com.soros.v2.entity.MarketDaily
import com.soros.v2.entity.SectorDaily
import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 信号聚合服务（SignalPrecomputeJob 路径 B，§12.4 / §19.11.1 穿透定稿）。
 *
 * 职责：从日线底座派生「必须全市场扫才得出」的跨股聚合列——
 * - market_daily：adv/dec 计数、limit_up/down 名单、zhaban 日线近似、yst_promotion、yst_limit_premium、yst_face_count
 * - sector_daily：industry 主口径板块聚合（limit_up_count/max_streak/avg_chg_pct/avg_chg_pct_all）
 * - signal_daily 梯队列：ladder_rank / sector_ladder_rank（全市场排序，并列同名次）
 *
 * 口径纪律（§19.11.1 / §4.8）：
 * - adv/dec 按不复权 change_pct 计：>0 计涨、<0 计跌、=0 两边不计
 * - zhaban 日线近似 = high ≥ round(昨收×(1+阈值),2) 且非涨停；阈值 MAIN=0.10、GEM/STAR=0.20
 * - yst_promotion：停牌成员计入分母视为未晋级（与 yst_limit_premium「停牌剔除分母」口径区分）
 * - yst_limit_premium：停牌成员剔除分母（无价不可算）
 * - yst_face_count 直读 sentiment_cycle(t-1).big_face_count
 * - 梯队排名按 limit_up_streak 降序 dense 排名（并列同名次）
 */
interface SignalAggregationService {

    /**
     * 单股炸板近似判定（日线口径，§4.8/§19.11.1 公式）。
     *
     * @param bar       今日 qfq 日线
     * @param board     市场板（MAIN/GEM/STAR；MAIN 阈值 0.10，GEM/STAR 阈值 0.20）
     * @param prevClose 昨收（不复权口径同源；null=无昨收不可判定 → false）
     * @return true=炸板：high ≥ round(昨收×(1+阈值),2) 且非涨停（触及涨停价位但未封板）
     */
    fun isZhaban(bar: StockHistory, board: String, prevClose: BigDecimal?): Boolean

    /**
     * 当日全市场温度计（market_daily 一行）。
     *
     * @param date     交易日
     * @param prevDate 前一交易日（null=无前文，yst_* 各列置空）
     * @return market_daily 行（limit_up_list 含当日涨停名单；yst_* 由 昨名单∘今行情 自算）
     */
    fun aggregateMarket(date: LocalDate, prevDate: LocalDate?): MarketDaily

    /**
     * 当日板块聚合（industry 主口径，一行一块；概念不落表条件现算，§19.11.1 决策 1）。
     *
     * @param date 交易日
     * @return sector_daily 行集合（含 avg_chg_pct_all 板块全成员平均涨幅%，§19.11.1 决策 3）
     */
    fun aggregateSectors(date: LocalDate): List<SectorDaily>

    /**
     * 当日全市场/板块内梯队排名（并列同名次 dense，§12.4 C 类跨股聚合）。
     *
     * @param date 交易日
     * @return 当日涨停股排序结果（按 limit_up_streak 降序；ladder_rank 全市场、sector_ladder_rank 板块内）
     */
    fun assignLadderRanks(date: LocalDate): List<SignalLadderRow>
}

/** 梯队排名结果（signal_daily 排序列；并列同名次 dense 排名） */
data class SignalLadderRow(
    /** 证券代码 */
    val code: String,
    /** 当日全市场梯队排名（按 limit_up_streak 降序，同名次并列） */
    val ladderRank: Short,
    /** 板块（industry）内板数排名（同名次并列） */
    val sectorLadderRank: Short,
)
