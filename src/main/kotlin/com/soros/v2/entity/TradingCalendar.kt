package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * trading_calendar —— 交易日历（AKShare tool_trade_date_hist_sina 全量；覆盖不足"明年年底"自动重拉续期）
 */
@Entity
@Table(name = "trading_calendar")
class TradingCalendar(
    /** 交易日 */
    @Id
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,
)
