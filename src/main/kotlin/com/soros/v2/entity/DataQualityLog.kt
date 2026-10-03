package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * data_quality_log —— 数据质量日志（数据底座层一期采集）
 */
@Entity
@Table(name = "data_quality_log")
class DataQualityLog(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Int = 0,

    /** 检查执行日 */
    @Column(name = "check_date", nullable = false)
    var checkDate: LocalDate = LocalDate.EPOCH,

    /** 证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history） */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** ADJUSTMENT_DRIFT / DELIST_SUSPECT / CONDITION_SKIP(§17.1 B2 条件跳过问题表) / ... */
    @Column(name = "issue_type", nullable = false)
    var issueType: String = "",

    /** 明细/问题描述 */
    @Column(name = "detail")
    var detail: String? = null,

    /** 来源/触发源 */
    @Column(name = "source")
    var source: String? = null,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
