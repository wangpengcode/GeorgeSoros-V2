package com.soros.v2.controller

import com.soros.v2.service.intraday.IntradayOverviewService
import com.soros.v2.service.intraday.dto.IntradayOverviewDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * §19.13.2 IntradayController：盘中监控页（intraday.html）数据端点。
 *
 * GET /api/v1/intraday/overview → {status, poll_time, alert_count, last_poll_at, sources}；
 * snake_case 信封与既有 controller 一致（BacktestController/StrategyController 同款）。
 */
@RestController
@RequestMapping("/api/v1")
class IntradayController(
    private val overviewService: IntradayOverviewService,
) {

    /** GET /api/v1/intraday/overview → 盘中监控 overview（intraday.html 消费） */
    @GetMapping("/intraday/overview")
    fun overview(): IntradayOverviewDto = overviewService.overview()
}
