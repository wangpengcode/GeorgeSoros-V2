package com.soros.v2.repository

import com.soros.v2.domain.CycleStatus
import com.soros.v2.entity.DragonCycle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * dragon_cycle 数据访问接口（龙头生命周期，大/小周期锚）。
 */
interface DragonCycleRepository : JpaRepository<DragonCycle, Long> {

    /** 按 状态 查周期（网页时间轴过滤） */
    fun findByStatusOrderByStartDateDesc(status: CycleStatus): List<DragonCycle>

    /** 按 状态可选 + 数量上限 查周期（§11.1 GET /dragon-cycle?status=&limit=） */
    @Query(
        "SELECT d FROM DragonCycle d " +
            "WHERE (:status IS NULL OR d.status = :status) " +
            "ORDER BY d.startDate DESC",
    )
    fun findTopN(@Param("status") status: CycleStatus?, pageable: org.springframework.data.domain.Pageable): List<DragonCycle>

    /** 进行中周期（uq_dragon_active：每股唯一 end_date IS NULL） */
    fun findByCodeAndEndDateIsNull(code: String): DragonCycle?

    /** 全部进行中周期（§13.4 Job 状态机推进入参；end_date IS NULL 全部） */
    fun findAllByEndDateIsNull(): List<DragonCycle>

    /** 该股最近一条已结束周期（上位判定前查旧龙头） */
    fun findTopByCodeAndEndDateIsNotNullOrderByEndDateDesc(code: String): DragonCycle?

    /** 当前进行中周期里最高板者（BOOT 首日龙头候选） */
    fun findTopByEndDateIsNullOrderByMaxStreakDescStartDateAsc(): DragonCycle?

    /** 区间内删除（§13.5 回放删后重建） */
    @Modifying
    @Query("DELETE FROM DragonCycle d WHERE d.startDate BETWEEN :from AND :to")
    fun deleteByStartDateBetween(@Param("from") from: LocalDate, @Param("to") to: LocalDate)

    /** 区间内最早上位日（回放水位） */
    @Query("SELECT MIN(d.startDate) FROM DragonCycle d WHERE d.startDate BETWEEN :from AND :to")
    fun findMinStartDateInRange(@Param("from") from: LocalDate, @Param("to") to: LocalDate): LocalDate?
}
