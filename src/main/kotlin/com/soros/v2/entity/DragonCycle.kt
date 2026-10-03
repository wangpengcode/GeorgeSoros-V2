package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * dragon_cycle —— 龙头生命周期（大/小周期的锚，§4.9 状态机逐日推进）
 */
@Entity
@Table(name = "dragon_cycle")
class DragonCycle(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 龙头代码 */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 上位日 */
    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate = LocalDate.EPOCH,

    /** 阵亡/定性日（null=进行中） */
    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    /** 周期内最高连板 */
    @Column(name = "max_streak", nullable = false)
    var maxStreak: Short = 0,

    /** 反包次数 */
    @Column(name = "rebreak_count", nullable = false)
    var rebreakCount: Short = 0,

    /** 停牌天数（停牌周期延续） */
    @Column(name = "suspended_days", nullable = false)
    var suspendedDays: Short = 0,

    /** [{from,to}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suspend_json")
    var suspendJson: JsonNode? = null,

    /** null=进行中未定性 */
    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_type")
    var cycleType: CycleType? = null,

    /** 周期状态（RISING/BROKEN/SUSPENDED/DEAD） */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: CycleStatus = CycleStatus.RISING,

    /** 最近断板日（反包观察期起点，默认 3 交易日） */
    @Column(name = "broken_date")
    var brokenDate: LocalDate? = null,

    /** 备注 */
    @Column(name = "note")
    var note: String? = null,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    /** 行更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
