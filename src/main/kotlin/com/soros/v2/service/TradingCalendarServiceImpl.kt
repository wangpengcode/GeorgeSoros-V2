package com.soros.v2.service

import com.soros.v2.entity.TradingCalendar
import com.soros.v2.repository.TradingCalendarRepository
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * TradingCalendarService 实现。
 *
 * - ensureLoaded：空表全量载入；覆盖不足"次年 12-31"自动重拉续期（§11.1，等价"每年 12 月续期"）；
 *   逐日 upsert（UNIQUE 主键幂等，跳过已存在）；返回本次载入交易日数量。
 * - recentTradingDays：从 end 往前（含 end）收集 n 个交易日，升序返回。
 */
@Service
class TradingCalendarServiceImpl(
    private val calendarRepository: TradingCalendarRepository,
    private val pythonClient: PythonDataServiceClient,
) : TradingCalendarService {

    private val logger = LoggerFactory.getLogger(TradingCalendarServiceImpl::class.java)

    override suspend fun ensureLoaded(): Int {
        val existing = calendarRepository.findAll().map { it.tradeDate }.toHashSet()
        if (existing.isNotEmpty() && !needsRenewal(existing.max())) {
            return 0
        }
        val dates = pythonClient.fetchTradingCalendar()
        var loaded = 0
        for (dateStr in dates) {
            val date = LocalDate.parse(dateStr)
            if (existing.add(date)) {
                calendarRepository.save(TradingCalendar(tradeDate = date))
                loaded++
            }
        }
        logger.info("[trading-calendar] ensureLoaded 本次载入 {} 日，累计 {} 日", loaded, existing.size)
        return loaded
    }

    override fun isTradingDay(date: LocalDate): Boolean =
        calendarRepository.existsByTradeDate(date)

    override fun previousTradingDay(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateBeforeOrderByTradeDateDesc(date)?.tradeDate

    override fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate> {
        if (n <= 0) return emptyList()
        val days = mutableListOf<LocalDate>()
        var cursor: LocalDate? = if (isTradingDay(end)) end else previousTradingDay(end)
        while (cursor != null && days.size < n) {
            days.add(0, cursor)
            cursor = previousTradingDay(cursor)
        }
        return days
    }

    /** 覆盖不足"次年 12-31"即触发续期重拉（2027+ 无未来日历 SLA，§11.1） */
    private fun needsRenewal(maxDate: LocalDate): Boolean {
        val nextYearEnd = LocalDate.of(LocalDate.now().year + 1, 12, 31)
        return maxDate < nextYearEnd
    }
}
