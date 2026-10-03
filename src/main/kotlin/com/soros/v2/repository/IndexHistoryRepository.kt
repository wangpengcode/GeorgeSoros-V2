package com.soros.v2.repository

import com.soros.v2.entity.IndexHistory
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * index_history 数据访问接口（数据底座层）。
 */
interface IndexHistoryRepository : JpaRepository<IndexHistory, Long> {

    /** 按 指数代码+交易日 查单行（UNIQUE(code, trade_date) 至多 1 行） */
    fun findByCodeAndTradeDate(code: String, tradeDate: LocalDate): IndexHistory?

    /** 按 指数代码+交易日区间 查列表（升序，5 个基准指数历史） */
    fun findByCodeAndTradeDateBetween(code: String, start: LocalDate, end: LocalDate): List<IndexHistory>
}
