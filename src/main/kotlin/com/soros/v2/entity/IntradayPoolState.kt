package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * intraday_pool_state —— 池运行时开关（§17.5 C2 用户裁定 A：热启停落库，重启不丢）
 */
@Entity
@Table(name = "intraday_pool_state")
class IntradayPoolState(
    /** 池标识（ZT/ZB/DT/STRONG/PREV；schema CHAR(6) bpchar，JDBC CHAR 对齐 ddl-auto:validate） */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pool", nullable = false, length = 6)
    var pool: String = "",

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    /** 手动停用原因（如限频封禁规避） */
    @Column(name = "reason", length = 200)
    var reason: String? = null,

    /** 行更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
