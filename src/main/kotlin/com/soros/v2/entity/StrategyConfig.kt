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
 * strategy_config —— 策略配置（落库即权威格式；backtest_result.params 的唯一来源，§12.9 双表 DB 版本化）
 */
@Entity
@Table(name = "strategy_config")
class StrategyConfig(
    /** 行主键（DB 列 SERIAL=INTEGER；字段用 Long 承载测试契约，@JdbcTypeCode 强制 JDBC 类型对齐 ddl-auto:validate） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 策略名（唯一） */
    @Column(name = "name", nullable = false)
    var name: String = "",

    /** 落库即权威格式；backtest_result.params 的唯一来源 */
    @Column(name = "yaml", nullable = false)
    var yaml: String = "",

    /** 保存即 version+1 */
    @Column(name = "version", nullable = false)
    var version: Int = 1,

    /** 配置状态（DRAFT/ACTIVE/FROZEN） */
    @Column(name = "status", nullable = false)
    var status: String = "DRAFT",

    /** 盘中开仓预警开关（§14.9，结果对比页开启） */
    @Column(name = "alert_enabled")
    var alertEnabled: Boolean = false,

    /** 创建/编辑人 */
    @Column(name = "created_by")
    var createdBy: String? = null,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 备注 */
    @Column(name = "note")
    var note: String? = null,
)
