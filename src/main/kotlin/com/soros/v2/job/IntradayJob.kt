package com.soros.v2.job

import com.soros.v2.config.IntradayProperties
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.intraday.IntradayArchiveService
import com.soros.v2.service.intraday.IntradayPollService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §19.13.2 IntradayJob：盘中 90s 轮询 + 15:10 收盘权威归档（@EnableScheduling 单线程调度器串行天然单飞）。
 *
 * - 90s 轮询（fixedDelay 属性占位符；交易时段守卫 [shouldPoll] → 非交易时段零请求，零容忍空转铁律）；
 * - 15:10 归档 cron（MON-FRI + isTradingDay 内层兜底）；
 * - 全部业务收口 sorosIo 受限 dispatcher（§13.1 coroutine dispatcher 收口）。
 */
@Component
class IntradayJob(
    private val pollService: IntradayPollService,
    private val archiveService: IntradayArchiveService,
    private val calendarService: TradingCalendarService,
    private val properties: IntradayProperties,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(IntradayJob::class.java)

    /** §19.13.2 盘中轮询：90s 一轮（交易时段守卫零请求） */
    @Scheduled(fixedDelayString = "\${soros.intraday.poll-interval-ms:90000}")
    fun poll() {
        runBlocking(sorosIo) {
            val now = LocalDateTime.now()
            if (!shouldPoll(now)) {
                logger.debug("[Step Intraday] 非交易时段跳过轮询：{}", now)
                return@runBlocking
            }
            pollService.pollRound()
        }
    }

    /** §19.13.2 收盘权威归档：15:10（MON-FRI；isTradingDay 兜底） */
    @Scheduled(cron = "\${soros.intraday.archive-cron:0 10 15 * * MON-FRI}")
    fun archive() {
        runBlocking(sorosIo) {
            val today = LocalDate.now()
            if (!calendarService.isTradingDay(today)) {
                logger.info("[Step Intraday] 非交易日跳过归档：date={}", today)
                return@runBlocking
            }
            archiveService.archiveDay(today)
        }
    }

    /** 交易时段守卫（§19.13.2）：交易日 且 在 09:15-11:30 / 13:00-15:00 窗口内 → 放行轮询 */
    fun shouldPoll(now: LocalDateTime): Boolean {
        if (!calendarService.isTradingDay(now.toLocalDate())) return false
        return isInTradingWindow(now.toLocalTime())
    }

    /** 交易时段窗口判定（暴露供测试；午休 11:30-13:00 零轮询） */
    fun isInTradingWindow(time: LocalTime): Boolean =
        inWindow(time, properties.morningStart, properties.morningEnd) ||
            inWindow(time, properties.afternoonStart, properties.afternoonEnd)

    private fun inWindow(time: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        !time.isBefore(start) && time.isBefore(end)
}
