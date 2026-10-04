package com.soros.v2.service.backfill

import com.soros.v2.repository.TradingCalendarRepository
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * TradingDayLookup 生产实现（trading_calendar 表直查；谓词语义与 [TradingDayLookup] 接口完全一致）。
 */
@Component
class DbTradingDayLookup(
    private val calendarRepository: TradingCalendarRepository,
) : TradingDayLookup {
    override fun firstTradingDayOnOrAfter(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateGreaterThanEqualOrderByTradeDateAsc(date)?.tradeDate

    override fun lastTradingDayOnOrBefore(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(date)?.tradeDate

    override fun countOpenDaysInclusive(start: LocalDate, end: LocalDate): Long =
        calendarRepository.countByTradeDateBetween(start, end)

    override fun nextTradingDayAfter(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateAfterOrderByTradeDateAsc(date)?.tradeDate
}
