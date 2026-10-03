package com.soros.v2.repository

import com.soros.v2.entity.StockHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * stock_history 数据访问接口（数据底座层）。
 */
interface StockHistoryRepository : JpaRepository<StockHistory, Long> {

    /** 按 代码+交易日 查单行（UNIQUE(code, trade_date) 至多 1 行） */
    fun findByCodeAndTradeDate(code: String, tradeDate: LocalDate): StockHistory?

    /** 按 代码+交易日区间 查列表（升序） */
    fun findByCodeAndTradeDateBetween(code: String, start: LocalDate, end: LocalDate): List<StockHistory>

    /** 是否已存在 代码+交易日 行（增量 upsert 幂等判重） */
    fun existsByCodeAndTradeDate(code: String, tradeDate: LocalDate): Boolean

    /** 最近一个交易日行（除权漂移检测 / 连板数派生前置） */
    fun findTopByCodeOrderByTradeDateDesc(code: String): StockHistory?

    /** 该股最大交易日（采集水位 / 对账） */
    @Query("SELECT MAX(h.tradeDate) FROM StockHistory h WHERE h.code = :code")
    fun findMaxTradeDateByCode(@Param("code") code: String): LocalDate?

    /** 指定交易日全部涨停行（§4.8 涨停梯队 / 最高板：is_limit_up 前置判定列） */
    fun findByTradeDateAndIsLimitUpTrue(tradeDate: LocalDate): List<StockHistory>
}
