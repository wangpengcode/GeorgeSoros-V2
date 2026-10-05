package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate

/**
 * intraday_replay —— 日维度整页渲染快照（一日一行；schema = GET /intraday/summary 同一 DTO 序列化）
 */
@Entity
@Table(name = "intraday_replay")
class IntradayReplay(
    /** 交易日 */
    @Id
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 15:10 归档补齐后置 true */
    @Column(name = "complete")
    var complete: Boolean = false,

    /** 整页渲染数据（=GET /intraday/summary 同一 DTO 序列化） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page")
    var page: JsonNode? = null,
)
