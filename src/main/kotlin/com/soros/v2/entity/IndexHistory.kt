package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * index_history —— 基准指数日线行情（qfq 口径同 stock_history；DailyCollectJob 顺带采 5 个基准指数）
 */
@Entity
@Table(
    name = "index_history",
    uniqueConstraints = [UniqueConstraint(name = "index_history_code_trade_date_key", columnNames = ["code", "trade_date"])],
)
class IndexHistory(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** sh000001（带前缀） */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 交易日 */
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 开盘价（元） */
    @Column(name = "open")
    var open: BigDecimal? = null,

    /** 收盘价（元） */
    @Column(name = "close")
    var close: BigDecimal? = null,

    /** 最高价（元） */
    @Column(name = "high")
    var high: BigDecimal? = null,

    /** 最低价（元） */
    @Column(name = "low")
    var low: BigDecimal? = null,

    /** 成交量（股） */
    @Column(name = "volume")
    var volume: Long? = null,

    /** 成交额（元） */
    @Column(name = "amount")
    var amount: BigDecimal? = null,

    /** 数据来源（failover 可见性） */
    @Column(name = "data_source")
    var dataSource: String = "UNKNOWN",

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
