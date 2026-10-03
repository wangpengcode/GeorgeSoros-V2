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
 * stock_history —— 股票日线行情（数据底座层一期采集；OHLC=qfq，change_pct=不复权口径）
 */
@Entity
@Table(
    name = "stock_history",
    uniqueConstraints = [UniqueConstraint(name = "stock_history_code_trade_date_key", columnNames = ["code", "trade_date"])],
)
class StockHistory(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 600000（裸数字，不带 sh/sz） */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 交易日 */
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用 */
    @Column(name = "open")
    var open: BigDecimal? = null,

    /** qfq */
    @Column(name = "close")
    var close: BigDecimal? = null,

    /** qfq */
    @Column(name = "high")
    var high: BigDecimal? = null,

    /** qfq */
    @Column(name = "low")
    var low: BigDecimal? = null,

    /** 统一单位=股（AKShare/mootdx 手×100，探针实测校准） */
    @Column(name = "volume")
    var volume: Long? = null,

    /** 成交额（元） */
    @Column(name = "amount")
    var amount: BigDecimal? = null,

    /** 涨跌幅%（不复权口径） */
    @Column(name = "change_pct")
    var changePct: BigDecimal? = null,

    /** 换手率% */
    @Column(name = "turnover_rate")
    var turnoverRate: BigDecimal? = null,

    /** 涨停（按原始 change_pct + board 阈值判定） */
    @Column(name = "is_limit_up")
    var isLimitUp: Boolean = false,

    /** 跌停 */
    @Column(name = "is_limit_down")
    var isLimitDown: Boolean = false,

    /** 连板数（首板=1，0=非涨停/断板；§4.8 派生） */
    @Column(name = "limit_up_streak")
    var limitUpStreak: Short = 0,

    /** 跌停连板（§4.9 崩塌池，镜像派生） */
    @Column(name = "limit_down_streak")
    var limitDownStreak: Short = 0,

    /** 数据来源（failover 可见性） */
    @Column(name = "data_source")
    var dataSource: String = "UNKNOWN",

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
