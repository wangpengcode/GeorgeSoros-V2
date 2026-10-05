package com.soros.v2.service.kline.mapper

import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.StockHistory
import com.soros.v2.service.kline.dto.KlineBar
import com.soros.v2.service.kline.dto.KlineChip
import java.math.BigDecimal

/**
 * §19.13.1 K线出参映射（Entity → DTO，字段过命名字典；映射器模式铁律：禁止 ServiceImpl 内联逐字段拷贝）。
 *
 * 源列可空（schema 列级可空）→ DTO 契约"bar 9 字段+chip 8 字段全非 null"收口为 0 占位，
 * 保证下游页面消费不缺键（蜡烛图/量能/筹码带前端无缺口）；chip 整体无行=null 由 Service 决定。
 */

/** stock_history → KlineBar（chip 为 signal_daily 联查结果，无行=null） */
fun StockHistory.toKlineBar(chip: KlineChip?): KlineBar = KlineBar(
    tradeDate = tradeDate,
    open = open ?: BigDecimal.ZERO,
    high = high ?: BigDecimal.ZERO,
    low = low ?: BigDecimal.ZERO,
    close = close ?: BigDecimal.ZERO,
    volume = volume ?: 0L,
    amount = amount ?: BigDecimal.ZERO,
    changePct = changePct ?: BigDecimal.ZERO,
    turnoverRate = turnoverRate ?: BigDecimal.ZERO,
    chip = chip,
)

/** signal_daily → KlineChip（8 字段全映射，键名=DDL 一字不差） */
fun SignalDaily.toKlineChip(): KlineChip = KlineChip(
    profitRatio = profitRatio ?: BigDecimal.ZERO,
    costDev = costDev ?: BigDecimal.ZERO,
    c90Low = c90Low ?: BigDecimal.ZERO,
    c90High = c90High ?: BigDecimal.ZERO,
    c90Conc = c90Conc ?: BigDecimal.ZERO,
    c70Low = c70Low ?: BigDecimal.ZERO,
    c70High = c70High ?: BigDecimal.ZERO,
    c70Conc = c70Conc ?: BigDecimal.ZERO,
)
