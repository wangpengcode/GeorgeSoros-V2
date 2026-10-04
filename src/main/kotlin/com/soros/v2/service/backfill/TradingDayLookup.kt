package com.soros.v2.service.backfill

import java.time.LocalDate

/**
 * 交易日吸附/计数查询（BackfillClassifier 纯函数注入；生产=DbTradingDayLookup，测试=内存表 Fake）。
 */
interface TradingDayLookup {
    /** ≥ date 的首个开市日（含 date 本身；无 → null） */
    fun firstTradingDayOnOrAfter(date: LocalDate): LocalDate?

    /** ≤ date 的最后开市日（含 date 本身；无 → null） */
    fun lastTradingDayOnOrBefore(date: LocalDate): LocalDate?

    /** [start, end] 区间内开市日数（含端点） */
    fun countOpenDaysInclusive(start: LocalDate, end: LocalDate): Long

    /** date 后下一个开市日（严格大于；无 → null） */
    fun nextTradingDayAfter(date: LocalDate): LocalDate?
}
