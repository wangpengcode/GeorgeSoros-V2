package com.soros.v2.repository

import com.soros.v2.entity.StockHistoryGapCheck
import com.soros.v2.entity.StockHistoryGapCheckId
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface StockHistoryGapCheckRepository : JpaRepository<StockHistoryGapCheck, StockHistoryGapCheckId> {

    /** 是否存在与给定区间完全重合的已验证空段（MID 排除 exact-match） */
    @Query(
        "SELECT COUNT(g) > 0 FROM StockHistoryGapCheck g " +
            "WHERE g.code = :code AND g.segFrom = :from AND g.segTo = :to",
    )
    fun existsByCodeAndRange(@Param("code") code: String, @Param("from") from: LocalDate, @Param("to") to: LocalDate): Boolean

    /**
     * 每票台账覆盖的开市日数（2026-10-05 计划语义：整窗拉齐的「未覆盖缺失」判定输入）。
     * gap_check 段 ∩ trading_calendar 计数，一次聚合全量票——计划构建零逐票查询。
     */
    @Query(
        "SELECT g.code AS code, COUNT(c.tradeDate) AS coveredDays FROM StockHistoryGapCheck g " +
            "JOIN TradingCalendar c ON c.tradeDate BETWEEN g.segFrom AND g.segTo " +
            "GROUP BY g.code",
    )
    fun coveredTradingDaysByCode(): List<GapCheckCoveredDaysProjection>

    /**
     * 记录已验证空段（幂等 upsert：重复验证仅刷新 rows_returned/checked_at）。
     *
     * 注：from/to 声明为可空仅因单测 verify 用 Mockito.any&lt;LocalDate&gt;()（返回 null）；
     * 生产调用恒传非空值，@Param 原生 SQL 不受影响。
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO stock_history_gap_check (code, seg_from, seg_to, rows_returned, checked_at)
            VALUES (:code, :from, :to, :rows, NOW())
            ON CONFLICT (code, seg_from, seg_to)
            DO UPDATE SET rows_returned = :rows, checked_at = NOW()
        """,
        nativeQuery = true,
    )
    fun upsertVerifiedEmpty(
        @Param("code") code: String,
        @Param("from") from: LocalDate?,
        @Param("to") to: LocalDate?,
        @Param("rows") rows: Int,
    )

    /** 台账覆盖开市日聚合投影（code → 覆盖天数） */
    interface GapCheckCoveredDaysProjection {
        val code: String
        val coveredDays: Long
    }
}
