package com.soros.v2.service

import java.time.LocalDate

/**
 * 交易日历服务（trading_calendar：AKShare tool_trade_date_hist_sina 一次全量，§11.1）。
 *
 * 消费方：DELIST_SUSPECT 连续 20 交易日无行判定（§4.7）、连板数派生前一交易日定位（§4.8）、
 * 回填对账、§17.2 全部盘后 Job 入口 isTradingDay 守卫。
 */
interface TradingCalendarService {

    /**
     * 一次拉取载入 + 每年 12 月自动续期（覆盖不足"明年年底"自动重拉，§11.1）。
     * 幂等：已存在日跳过；返回本次载入的交易日数量。
     */
    suspend fun ensureLoaded(): Int

    /** 某日是否交易日（§17.2 盘后 Job 入口守卫） */
    fun isTradingDay(date: LocalDate): Boolean

    /** 该日之前最近一个交易日（不含当日；§4.6 链式校验批首定位 / §4.8 连板派生前一日） */
    fun previousTradingDay(date: LocalDate): LocalDate?

    /** 以 end 结束（含）往前共 n 个交易日，升序返回（§4.7 防线④ 滚动窗口起点计算） */
    fun recentTradingDays(end: LocalDate, n: Int): List<LocalDate>
}
