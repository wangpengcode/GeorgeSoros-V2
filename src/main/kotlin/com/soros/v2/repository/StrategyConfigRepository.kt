package com.soros.v2.repository

import com.soros.v2.entity.StrategyConfig
import org.springframework.data.jpa.repository.JpaRepository

/**
 * strategy_config 数据访问接口（策略配置主表）。
 */
interface StrategyConfigRepository : JpaRepository<StrategyConfig, Long> {

    /** 按策略名查（name UNIQUE，至多 1 行；回测 G4 策略名存在性校验） */
    fun findByName(name: String): StrategyConfig?

    /** 策略名是否已存在（create 唯一冲突幂等判重） */
    fun existsByName(name: String): Boolean
}
