package com.soros.v2.repository

import com.soros.v2.entity.SectorDaily
import com.soros.v2.entity.SectorDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * sector_daily 数据访问接口（信号预计算层·板块聚合，industry 主口径）。
 */
interface SectorDailyRepository : JpaRepository<SectorDaily, SectorDailyId> {

    /** 按 交易日 查当日全部板块行（升序；页面/回放数据源；tradeDate 可空——Mockito 5.14.2 `any()` 返回默认值 null，Kotlin 非空参数字节码检查兼容） */
    fun findByTradeDateOrderByBoardAsc(tradeDate: LocalDate?): List<SectorDaily>

    /** 该交易日是否有板块行（增量幂等判重） */
    fun existsByTradeDate(tradeDate: LocalDate): Boolean

    /** 该交易日板块行数（完成标记，0=缺算） */
    fun countByTradeDate(tradeDate: LocalDate): Long

    /** 区间内最小交易日（全历史回放删除区间行的水位） */
    @Query("SELECT MIN(s.tradeDate) FROM SectorDaily s WHERE s.tradeDate BETWEEN :from AND :to")
    fun findMinTradeDateInRange(@Param("from") from: LocalDate, @Param("to") to: LocalDate): LocalDate?

    /** 单日删除后重建（同日重跑幂等：先删后插，§17.2 语义） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SectorDaily s WHERE s.tradeDate = :tradeDate")
    fun deleteByTradeDate(@Param("tradeDate") tradeDate: LocalDate)

    /** 区间内删除（全历史回放删后重建语义，复用 §13.5 模式） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SectorDaily s WHERE s.tradeDate BETWEEN :from AND :to")
    fun deleteByTradeDateBetween(@Param("from") from: LocalDate, @Param("to") to: LocalDate)
}
