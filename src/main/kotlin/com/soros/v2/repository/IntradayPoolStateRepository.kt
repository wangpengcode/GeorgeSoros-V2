package com.soros.v2.repository

import com.soros.v2.entity.IntradayPoolState
import org.springframework.data.jpa.repository.JpaRepository

/**
 * intraday_pool_state 数据访问接口（池运行时开关；热启停落库，重启不丢）。
 */
interface IntradayPoolStateRepository : JpaRepository<IntradayPoolState, String> {

    /** 单池开关行（缺省=启用；轮询每轮读该表，enabled=false 的池跳过） */
    fun findByPool(pool: String): IntradayPoolState?
}
