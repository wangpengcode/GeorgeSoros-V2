package com.soros.v2.repository

import com.soros.v2.domain.IntradayEventType
import com.soros.v2.entity.IntradayEvent
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository

/**
 * intraday_event 数据访问接口（盘中状态 diff 事件，全量落库 + pushed_dd 标记）。
 */
interface IntradayEventRepository : JpaRepository<IntradayEvent, Long> {

    /** 当日事件全量（回放/归档 page 数据源） */
    fun findByTradeDate(tradeDate: LocalDate): List<IntradayEvent>

    /** 当日事件数（overview alert_count） */
    fun countByTradeDate(tradeDate: LocalDate): Long

    /** 当日最新 N 条（事件流面板；升序 by evTime desc + id desc 保序） */
    fun findTop10ByTradeDateOrderByEvTimeDescIdDesc(tradeDate: LocalDate): List<IntradayEvent>

    /** 当日已推钉钉事件（panels.ding_talk 数据源） */
    fun findByTradeDateAndPushedDdTrue(tradeDate: LocalDate): List<IntradayEvent>

    /** 当日指定类型事件（panels.big_face 数据源，ev_type=DM） */
    fun findByTradeDateAndEvType(tradeDate: LocalDate, evType: IntradayEventType): List<IntradayEvent>
}
