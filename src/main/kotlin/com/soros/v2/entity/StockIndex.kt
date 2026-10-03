package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * stock_index —— 基准指数基础信息（数据底座层一期采集；code 带前缀值口径特例）
 */
@Entity
@Table(
    name = "stock_index",
    uniqueConstraints = [UniqueConstraint(name = "stock_index_code_key", columnNames = ["code"])],
)
class StockIndex(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Int = 0,

    /** sh000001（指数带前缀，特例） */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 名称 */
    @Column(name = "name")
    var name: String? = null,

    /** 行更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
