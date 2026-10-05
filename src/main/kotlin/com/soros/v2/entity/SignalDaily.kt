package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

/**
 * signal_daily —— 个股×日信号（只存"必须全市场排序才得出"的列 + 筹码 8 列；SignalPrecomputeJob 派生，§12.4.1）
 */
@Entity
@Table(name = "signal_daily")
@IdClass(SignalDailyId::class)
class SignalDaily(
    /** 证券代码（股票=裸数字 600000；本期不落指数行） */
    @Id
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 交易日 */
    @Id
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 当日梯队排名（全市场排序才得出） */
    @Column(name = "ladder_rank")
    var ladderRank: Short? = null,

    /** 炸板（日线近似，统一口径落库） */
    @Column(name = "is_zhaban")
    var isZhaban: Boolean? = null,

    /** 板块内板数排名 */
    @Column(name = "sector_ladder_rank")
    var sectorLadderRank: Short? = null,

    /** 获利盘% */
    @Column(name = "profit_ratio")
    var profitRatio: BigDecimal? = null,

    /** 成本偏离% = (close−avg_cost)/avg_cost×100（qfq 重对基免疫） */
    @Column(name = "cost_dev")
    var costDev: BigDecimal? = null,

    /** 90% 成本区间下沿（p5 分位，qfq 坐标） */
    @Column(name = "c90_low")
    var c90Low: BigDecimal? = null,

    /** 90% 成本区间上沿（p95 分位，qfq 坐标） */
    @Column(name = "c90_high")
    var c90High: BigDecimal? = null,

    /** 90% 集中度（东财口径 (p95−p5)/(p95+p5)×100，qfq 坐标） */
    @Column(name = "c90_conc")
    var c90Conc: BigDecimal? = null,

    /** 70% 成本区间下沿（p15 分位，qfq 坐标） */
    @Column(name = "c70_low")
    var c70Low: BigDecimal? = null,

    /** 70% 成本区间上沿（p85 分位，qfq 坐标） */
    @Column(name = "c70_high")
    var c70High: BigDecimal? = null,

    /** 70% 集中度（(p85−p15)/(p85+p15)×100，qfq 坐标） */
    @Column(name = "c70_conc")
    var c70Conc: BigDecimal? = null,
)

/** 复合主键（code + trade_date） */
class SignalDailyId(
    val code: String = "",
    val tradeDate: LocalDate = LocalDate.EPOCH,
) : java.io.Serializable
