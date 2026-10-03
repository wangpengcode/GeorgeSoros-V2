package com.soros.v2.service

import com.soros.v2.domain.DataSourceType
import com.soros.v2.entity.IndexHistory
import com.soros.v2.entity.StockHistory
import com.soros.v2.service.dto.DailyBar

/**
 * DailyBar → Entity 字段映射（§2.4 四端映射：change_percent→change_pct、turnover→turnover_rate、volume=股、amount=元）。
 *
 * 映射器模式铁律：跨类型转换通过扩展函数/Mapper，禁止 ServiceImpl 内联逐字段拷贝。
 */

/** DailyBar → StockHistory 全字段映射（含涨停/连板派生结果），返回自身便于链式 */
internal fun StockHistory.applyBar(
    code: String,
    bar: DailyBar,
    source: DataSourceType,
    isLimitUp: Boolean,
    isLimitDown: Boolean,
    streak: Int,
    streakDown: Int,
): StockHistory {
    this.code = code
    this.tradeDate = bar.date
    this.open = bar.open
    this.close = bar.close
    this.high = bar.high
    this.low = bar.low
    this.volume = bar.volume
    this.amount = bar.amount
    this.changePct = bar.changePercent
    this.turnoverRate = bar.turnover
    this.isLimitUp = isLimitUp
    this.isLimitDown = isLimitDown
    this.limitUpStreak = streak.toShort()
    this.limitDownStreak = streakDown.toShort()
    this.dataSource = source.name
    return this
}

/** DailyBar → IndexHistory 全字段映射，返回自身便于链式 */
internal fun IndexHistory.applyBar(
    code: String,
    bar: DailyBar,
    source: DataSourceType,
): IndexHistory {
    this.code = code
    this.tradeDate = bar.date
    this.open = bar.open
    this.close = bar.close
    this.high = bar.high
    this.low = bar.low
    this.volume = bar.volume
    this.amount = bar.amount
    this.dataSource = source.name
    return this
}
