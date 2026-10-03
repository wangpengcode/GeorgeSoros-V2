package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * stock_fundamentals —— 财务基本面（数据底座层一期采集；金额单位=元，源亿元 ×1e8）
 */
@Entity
@Table(
    name = "stock_fundamentals",
    uniqueConstraints = [UniqueConstraint(name = "stock_fundamentals_code_report_date_key", columnNames = ["code", "report_date"])],
)
class StockFundamentals(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Int = 0,

    /** 证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history） */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 报告期（季度末），季度/年度通吃 */
    @Column(name = "report_date", nullable = false)
    var reportDate: LocalDate = LocalDate.EPOCH,

    /** 元（源亿元 ×1e8） */
    @Column(name = "revenue")
    var revenue: BigDecimal? = null,

    /** 元 */
    @Column(name = "net_profit")
    var netProfit: BigDecimal? = null,

    /** 行更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
