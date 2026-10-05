package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

/**
 * sector_daily —— 板块聚合（日×板块，industry 主口径，SignalPrecomputeJob 派生，§12.4/§19.11.1）
 */
@Entity
@Table(name = "sector_daily")
@IdClass(SectorDailyId::class)
class SectorDaily(
    /** 交易日 */
    @Id
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 行业（industry 主口径，概念不落表条件现算，市场板另置；§19.11.1 决策 1） */
    @Id
    @Column(name = "board", nullable = false)
    var board: String = "",

    /** 板块内涨停家数 */
    @Column(name = "limit_up_count")
    var limitUpCount: Short? = null,

    /** 板块内最高连板 */
    @Column(name = "max_streak")
    var maxStreak: Short? = null,

    /** 板块涨停名单平均涨幅% */
    @Column(name = "avg_chg_pct")
    var avgChgPct: BigDecimal? = null,

    /** 板块全成员平均涨幅%（词表 #9「板块涨幅榜前列」，§19.11.1 决策 3） */
    @Column(name = "avg_chg_pct_all")
    var avgChgPctAll: BigDecimal? = null,

    /** 当日板块驱动主线（LLM 生成，标"系统生成"） */
    @Column(name = "driver_text")
    var driverText: String? = null,
)

/** 复合主键（trade_date + board=industry） */
class SectorDailyId(
    val tradeDate: LocalDate = LocalDate.EPOCH,
    val board: String = "",
) : java.io.Serializable
