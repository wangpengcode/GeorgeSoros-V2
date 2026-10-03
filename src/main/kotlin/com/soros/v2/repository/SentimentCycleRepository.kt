package com.soros.v2.repository

import com.soros.v2.entity.SentimentCycle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * sentiment_cycle 数据访问接口（情绪派生层）。
 */
interface SentimentCycleRepository : JpaRepository<SentimentCycle, Long> {

    /** 按 交易日 查单日行（trade_date UNIQUE，至多 1 行） */
    fun findByTradeDate(tradeDate: LocalDate): SentimentCycle?

    /** 按 交易日区间 查序列（升序，情绪曲线数据源） */
    fun findByTradeDateBetweenOrderByTradeDateAsc(from: LocalDate, to: LocalDate): List<SentimentCycle>

    /** 是否已存在该交易日行（对账/回放水位/测试消费；§17.2 兜底跳过已改为查行判 data_coverage） */
    fun existsByTradeDate(tradeDate: LocalDate): Boolean

    /** 区间内最小交易日（回放删除区间行的水位） */
    @Query("SELECT MIN(s.tradeDate) FROM SentimentCycle s WHERE s.tradeDate BETWEEN :from AND :to")
    fun findMinTradeDateInRange(@Param("from") from: LocalDate, @Param("to") to: LocalDate): LocalDate?

    /** 区间内删除（§13.5 回放前删后重建语义） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SentimentCycle s WHERE s.tradeDate BETWEEN :from AND :to")
    fun deleteByTradeDateBetween(@Param("from") from: LocalDate, @Param("to") to: LocalDate)
}
