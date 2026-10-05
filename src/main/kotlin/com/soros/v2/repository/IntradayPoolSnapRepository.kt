package com.soros.v2.repository

import com.soros.v2.entity.IntradayPoolSnap
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * intraday_pool_snap 数据访问接口（盘中每轮池快照，追加型）。
 */
interface IntradayPoolSnapRepository : JpaRepository<IntradayPoolSnap, Long> {

    /** 指定池最新一帧快照（读上轮/当前轮最新行；ladder 现拼数据源） */
    fun findTopByPoolOrderBySnapAtDesc(pool: String): IntradayPoolSnap?

    /** 指定池在 before 之前最新一帧快照（§19.13.2 diff 需「上轮最新 snap」，先读后写） */
    fun findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(pool: String, before: LocalDateTime): IntradayPoolSnap?

    /** 全池全局最新一帧（overview poll_time / last_poll_at 兜底） */
    fun findFirstByOrderBySnapAtDesc(): IntradayPoolSnap?

    /** 同 snap_at+pool 是否已存在（幂等 upsert：同 poll_time+code 不重复，§19.13.2） */
    fun existsBySnapAtAndPool(snapAt: LocalDateTime, pool: String): Boolean

    /** 指定快照时间的全部帧（集成测试对账 / 轮内重拉判重） */
    fun findBySnapAt(snapAt: LocalDateTime): List<IntradayPoolSnap>

    /** 定期清理过期快照（§schema.sql：原始轮次保留 3 天） */
    @Modifying
    @Query("DELETE FROM IntradayPoolSnap s WHERE s.snapAt < :threshold")
    fun deleteBySnapAtBefore(@Param("threshold") threshold: LocalDateTime): Int
}
