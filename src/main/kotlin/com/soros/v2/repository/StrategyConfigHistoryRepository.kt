package com.soros.v2.repository

import com.soros.v2.entity.StrategyConfigHistory
import org.springframework.data.jpa.repository.JpaRepository

/**
 * strategy_config_history 数据访问接口（配置版本快照链，G3 回滚/留痕）。
 */
interface StrategyConfigHistoryRepository : JpaRepository<StrategyConfigHistory, Long> {

    /** 按 配置+版本 查单行快照（G3 回滚目标版本读取） */
    fun findByConfigIdAndVersion(configId: Long, version: Int): StrategyConfigHistory?

    /** 按 配置 查版本快照链（升序；G3 history 列表，页面 diff 可回退） */
    fun findByConfigIdOrderByVersionAsc(configId: Long): List<StrategyConfigHistory>
}
