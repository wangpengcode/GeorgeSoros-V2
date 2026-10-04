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

    /** 按 交易日区间 查全部行情（升序；§13.4 情绪派生窗口 / §13.5 回放 barsByCode 数据源） */
    fun findByTradeDateBetween(start: LocalDate, end: LocalDate): List<StockHistory>

    /** 是否已存在 代码+交易日 行（增量 upsert 幂等判重） */
    fun existsByCodeAndTradeDate(code: String, tradeDate: LocalDate): Boolean

    /** 最近一个交易日行（除权漂移检测 / 连板数派生前置） */
    fun findTopByCodeOrderByTradeDateDesc(code: String): StockHistory?

    /** 该股最大交易日（采集水位 / 对账） */
    @Query("SELECT MAX(h.tradeDate) FROM StockHistory h WHERE h.code = :code")
    fun findMaxTradeDateByCode(@Param("code") code: String): LocalDate?

    /** 区间内有行的代码清单（去重升序，§六.6⑤ 派生列抽查对拍候选池，确定性抽样） */
    @Query("SELECT DISTINCT h.code FROM StockHistory h WHERE h.tradeDate BETWEEN :start AND :end ORDER BY h.code")
    fun findDistinctCodesByTradeDateBetween(@Param("start") start: LocalDate, @Param("end") end: LocalDate): List<String>

    /** 指定交易日全部涨停行（§4.8 涨停梯队 / 最高板：is_limit_up 前置判定列） */
    fun findByTradeDateAndIsLimitUpTrue(tradeDate: LocalDate): List<StockHistory>

    /** 随机抽 N 只含未校准行的代码（CalibrationJob 低频分批候选池；calibrated=false 常驻） */
    @Query(
        value = """
            SELECT DISTINCT code FROM stock_history
            WHERE calibrated = false
            ORDER BY random()
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRandomUncalibratedCodes(@Param("limit") limit: Int): List<String>

    /** 该股最早未校准行（CalibrationJob 校准窗起点） */
    fun findTopByCodeAndCalibratedFalseOrderByTradeDateAsc(code: String): StockHistory?

    /** 该股最新未校准行（CalibrationJob 校准窗终点；null=全部已校准） */
    fun findTopByCodeAndCalibratedFalseOrderByTradeDateDesc(code: String): StockHistory?
}
