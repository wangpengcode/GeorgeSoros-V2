package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * strategy_config_history —— 配置 YAML 全文快照链（每次保存留痕，可回滚可 diff，G3 历史链可无限回退）
 */
@Entity
@Table(name = "strategy_config_history")
class StrategyConfigHistory(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 源配置 FK→strategy_config.id（DB 列 INT；字段用 Long 承载测试契约，@JdbcTypeCode 强制 JDBC 类型对齐 ddl-auto:validate） */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "config_id", nullable = false)
    var configId: Long = 0L,

    /** 配置 YAML 全文快照 */
    @Column(name = "yaml", nullable = false)
    var yaml: String = "",

    /** 版本号（每次保存 +1） */
    @Column(name = "version", nullable = false)
    var version: Int = 1,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,
)
