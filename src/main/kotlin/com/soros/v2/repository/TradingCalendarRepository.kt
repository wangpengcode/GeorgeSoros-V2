package com.soros.v2.repository

import com.soros.v2.entity.TradingCalendar
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * trading_calendar 数据访问接口（数据底座层）。
 *
 * 注：trading_calendar 表仅 trade_date 一列（主键），每行即一个开市交易日，无 is_open 列；
 * "开市日计数" 语义由 count()（JpaRepository 内建）表达，不声明不存在的 countByIsOpenTrue。
 */
interface TradingCalendarRepository : JpaRepository<TradingCalendar, LocalDate> {

    /** 按交易日查（主键至多 1 行） */
    fun findByTradeDate(tradeDate: LocalDate): TradingCalendar?

    /** 某交易日是否在日历内（DELIST_SUSPECT/新鲜度/回填对账消费） */
    fun existsByTradeDate(tradeDate: LocalDate): Boolean

    /** 该日之后最近一个交易日（升序取首个，下一个交易日） */
    fun findFirstByTradeDateAfterOrderByTradeDateAsc(tradeDate: LocalDate): TradingCalendar?

    /** 该日之前最近一个交易日（倒序取首个，上一个交易日） */
    fun findFirstByTradeDateBeforeOrderByTradeDateDesc(tradeDate: LocalDate): TradingCalendar?

    /** 区间内交易日（升序；§13.5 回放逐日序列 / §13.4 状态机观察期计数） */
    fun findByTradeDateBetweenOrderByTradeDateAsc(from: LocalDate, to: LocalDate): List<TradingCalendar>
}
