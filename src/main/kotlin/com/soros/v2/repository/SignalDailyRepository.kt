package com.soros.v2.repository

import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.SignalDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * signal_daily 数据访问接口（信号预计算层·个股×日，含筹码 8 列）。
 */
interface SignalDailyRepository : JpaRepository<SignalDaily, SignalDailyId> {

    /** 按 代码+交易日 查单行（PK 至多 1 行；K线 chip 聚合 / 增量幂等判重） */
    fun findByCodeAndTradeDate(code: String, tradeDate: LocalDate): SignalDaily?

    /** 按 代码+交易日区间 查序列（升序；K线页 OHLC∘筹码 8 列一次聚合；code/from/to 可空——Mockito 5.14.2 `eq()/any()` 返回默认值 null，Kotlin 非空参数字节码检查兼容） */
    fun findByCodeAndTradeDateBetween(code: String?, from: LocalDate?, to: LocalDate?): List<SignalDaily>

    /** 是否已存在 代码+交易日 行（增量 upsert 幂等判重） */
    fun existsByCodeAndTradeDate(code: String, tradeDate: LocalDate): Boolean

    /** 该交易日行数（§19.11.1 完成标记：signal_daily 按当日行数判存在；兜底 cron 跳过条件） */
    fun countByTradeDate(tradeDate: LocalDate): Long

    /** 是否已存在早于该交易日的行（§19.12 空库守卫：无前置历史 → 逐日补算不适用填空库，须全历史回放） */
    fun existsByTradeDateLessThan(tradeDate: LocalDate): Boolean

    /** 区间内最小交易日（全历史回放删除区间行的水位） */
    @Query("SELECT MIN(s.tradeDate) FROM SignalDaily s WHERE s.tradeDate BETWEEN :from AND :to")
    fun findMinTradeDateInRange(@Param("from") from: LocalDate, @Param("to") to: LocalDate): LocalDate?

    /** 该股最大交易日（断点续跑水位；与 stock_info.adj_processed_until 对账） */
    @Query("SELECT MAX(s.tradeDate) FROM SignalDaily s WHERE s.code = :code")
    fun findMaxTradeDateByCode(@Param("code") code: String): LocalDate?

    /** 区间内有行的代码清单（去重升序；全历史回放按股分批 200 股/事务的批次候选池） */
    @Query("SELECT DISTINCT s.code FROM SignalDaily s WHERE s.tradeDate BETWEEN :from AND :to ORDER BY s.code")
    fun findDistinctCodesByTradeDateBetween(@Param("from") from: LocalDate, @Param("to") to: LocalDate): List<String>

    /** 单日删除后重建（同日重跑幂等：先删后插，§17.2 语义） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SignalDaily s WHERE s.tradeDate = :tradeDate")
    fun deleteByTradeDate(@Param("tradeDate") tradeDate: LocalDate)

    /** 区间内删除（全历史回放删后重建语义，复用 §13.5 模式） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SignalDaily s WHERE s.tradeDate BETWEEN :from AND :to")
    fun deleteByTradeDateBetween(@Param("from") from: LocalDate, @Param("to") to: LocalDate)

    /** 该股区间删除（AdjustCheckStep 除权全历史重算：该票全日期重算重写，§17.1 B6） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SignalDaily s WHERE s.code = :code AND s.tradeDate BETWEEN :from AND :to")
    fun deleteByCodeAndTradeDateBetween(@Param("code") code: String, @Param("from") from: LocalDate, @Param("to") to: LocalDate)

    /** 该股全部删除（除权整票重写入口；幂等重跑安全） */
    @Modifying
    @Transactional
    @Query("DELETE FROM SignalDaily s WHERE s.code = :code")
    fun deleteByCode(@Param("code") code: String)
}
