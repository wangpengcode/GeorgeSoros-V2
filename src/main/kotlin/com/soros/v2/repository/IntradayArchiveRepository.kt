package com.soros.v2.repository

import com.soros.v2.entity.IntradayArchive
import com.soros.v2.entity.IntradayArchiveId
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * intraday_archive 数据访问接口（收盘权威归档，UNIQUE(trade_date, code) 复合主键）。
 */
interface IntradayArchiveRepository : JpaRepository<IntradayArchive, IntradayArchiveId> {

    /** 某日全部归档行（回放/归档对拍） */
    @Query("SELECT a FROM IntradayArchive a WHERE a.id.tradeDate = :tradeDate ORDER BY a.id.code")
    fun findByTradeDate(@Param("tradeDate") tradeDate: LocalDate): List<IntradayArchive>

    /** 某日归档行数（归档完整性对拍 / 幂等重跑） */
    @Query("SELECT count(a) FROM IntradayArchive a WHERE a.id.tradeDate = :tradeDate")
    fun countByTradeDate(@Param("tradeDate") tradeDate: LocalDate): Long

    /** 某日归档行（ZT 池按连板数 DESC，ladder 现拼数据源） */
    @Query("SELECT a FROM IntradayArchive a WHERE a.id.tradeDate = :tradeDate ORDER BY a.limitUpStreak DESC NULLS LAST, a.id.code")
    fun findLadderByTradeDate(@Param("tradeDate") tradeDate: LocalDate): List<IntradayArchive>

    /** 幂等重归档：先清当日行再插入（15:10 权威重拉覆盖盘中快照口径） */
    @Modifying
    @Query("DELETE FROM IntradayArchive a WHERE a.id.tradeDate = :tradeDate")
    fun deleteByTradeDate(@Param("tradeDate") tradeDate: LocalDate): Int
}
