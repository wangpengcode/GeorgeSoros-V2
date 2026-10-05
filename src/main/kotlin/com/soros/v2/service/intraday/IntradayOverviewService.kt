package com.soros.v2.service.intraday

import com.soros.v2.service.intraday.dto.IntradayOverviewDto

/**
 * §19.13.2 盘中监控 overview（intraday.html 消费端点；读侧快照服务，无副作用）。
 */
interface IntradayOverviewService {

    /** GET /api/v1/intraday/overview：{status, poll_time, alert_count, last_poll_at, sources} */
    fun overview(): IntradayOverviewDto
}
