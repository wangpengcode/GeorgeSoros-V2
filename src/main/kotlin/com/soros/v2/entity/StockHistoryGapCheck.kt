package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * stock_history_gap_check —— 回填验证空段台账（停牌防反复空拉；每行=某 code 某段已确认 0 行）
 */
@Entity
@Table(name = "stock_history_gap_check")
@IdClass(StockHistoryGapCheckId::class)
class StockHistoryGapCheck(
    /** 证券代码（裸数字 600000） */
    @Id
    @Column(name = "code", nullable = false)
    var code: String = "",
    /** 已验证空段起点（含） */
    @Id
    @Column(name = "seg_from", nullable = false)
    var segFrom: LocalDate = LocalDate.EPOCH,
    /** 已验证空段终点（含） */
    @Id
    @Column(name = "seg_to", nullable = false)
    var segTo: LocalDate = LocalDate.EPOCH,
    /** 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空） */
    @Column(name = "rows_returned", nullable = false)
    var rowsReturned: Int = 0,
    /** 验证时间（timestamptz） */
    @Column(name = "checked_at", nullable = false, updatable = false)
    var checkedAt: Instant = Instant.now(),
)

/** 复合主键（code+seg_from+seg_to） */
class StockHistoryGapCheckId(
    val code: String = "",
    val segFrom: LocalDate = LocalDate.EPOCH,
    val segTo: LocalDate = LocalDate.EPOCH,
) : java.io.Serializable
