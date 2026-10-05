package com.soros.v2.repository

import com.soros.v2.entity.IntradayReplay
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository

/**
 * intraday_replay 数据访问接口（日维度整页渲染快照，一日一行）。
 */
interface IntradayReplayRepository : JpaRepository<IntradayReplay, LocalDate> {

    /** 指定交易日整页快照（回放态=单表单查询零 join） */
    fun findByTradeDate(tradeDate: LocalDate): IntradayReplay?

    /** 幂等重放：同日整页覆盖写（PK=trade_date 天然防重） */
    fun deleteByTradeDate(tradeDate: LocalDate)
}
