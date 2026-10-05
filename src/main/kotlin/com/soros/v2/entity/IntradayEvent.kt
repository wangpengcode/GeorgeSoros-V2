package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.domain.IntradayEventType
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
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * intraday_event —— 盘中状态 diff 事件（本轮 vs 上轮；全量落库，pushed_dd 标记钉钉已推）
 */
@Entity
@Table(name = "intraday_event")
class IntradayEvent(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 交易日 */
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 事件时间 */
    @Column(name = "ev_time", nullable = false)
    var evTime: LocalDateTime = LocalDateTime.now(),

    /** 事件类型（封板/炸板/ALERT…，CHECK 约束） */
    @Enumerated(EnumType.STRING)
    @Column(name = "ev_type", nullable = false, length = 20)
    var evType: IntradayEventType = IntradayEventType.ZT,

    /** 证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history） */
    @Column(name = "code", length = 20)
    var code: String? = null,

    /** 名称 */
    @Column(name = "name", length = 100)
    var name: String? = null,

    /** {limit_up_streak,seal_amount,zhaban_count,chg,kelly{...}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    var detail: JsonNode? = null,

    /** 钉钉已推送标记 */
    @Column(name = "pushed_dd")
    var pushedDd: Boolean = false,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
