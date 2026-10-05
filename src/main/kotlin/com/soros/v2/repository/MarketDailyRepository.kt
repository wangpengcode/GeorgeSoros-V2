package com.soros.v2.repository

import com.soros.v2.entity.MarketDaily
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * market_daily 数据访问接口（信号预计算层·全市场温度计）。
 */
interface MarketDailyRepository : JpaRepository<MarketDaily, LocalDate> {

    /** 按 交易日 查单日行（trade_date PRIMARY KEY，至多 1 行；21:30 兜底跳过条件依赖 dataCoverage） */
    fun findByTradeDate(tradeDate: LocalDate): MarketDaily?

    /** 该交易日是否已有行（增量 upsert 幂等判重 / 对账） */
    fun existsByTradeDate(tradeDate: LocalDate): Boolean

    /** 该交易日行数（0/1，回放水位与完成标记） */
    fun countByTradeDate(tradeDate: LocalDate): Long

    /** 区间内最小交易日（全历史回放删除区间行的水位） */
    @Query("SELECT MIN(m.tradeDate) FROM MarketDaily m WHERE m.tradeDate BETWEEN :from AND :to")
    fun findMinTradeDateInRange(@Param("from") from: LocalDate, @Param("to") to: LocalDate): LocalDate?
}
