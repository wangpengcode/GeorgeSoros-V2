package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.sql.Types

/**
 * intraday_pool_snap —— 盘中每轮池快照（追加，原始轮次保留 3 天供回溯调试，定期清理）
 */
@Entity
@Table(name = "intraday_pool_snap")
class IntradayPoolSnap(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 快照时间（每轮采样点） */
    @Column(name = "snap_at", nullable = false)
    var snapAt: LocalDateTime = LocalDateTime.now(),

    /** 池标识（ZT/ZB/DT/STRONG/PREV；schema CHAR(6) bpchar，JDBC CHAR 对齐 ddl-auto:validate） */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pool", nullable = false, length = 6)
    var pool: String = "",

    /** 接口原样行（未来字段升级不丢） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    var payload: JsonNode,
)
