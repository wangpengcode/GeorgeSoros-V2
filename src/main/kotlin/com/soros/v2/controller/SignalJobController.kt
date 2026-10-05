package com.soros.v2.controller

import com.soros.v2.service.signal.SignalReplayService
import com.soros.v2.service.signal.SignalReplaySummary
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §19.11.1 SignalJobController：信号全历史回放触发端点（§13.5 sentiment-replay 同构）。
 *
 * POST /api/v1/jobs/signal-replay?from=2026-09-01&to=2026-09-30
 * 前置：stock_history / sentiment_cycle 已就绪；同步返回 SignalReplaySummary 摘要（snake_case 响应键，§17.6）。
 * 缺 from/to → GlobalExceptionHandler MISSING_PARAM 400；from>to → Service 抛 BusinessException 422。
 */
@RestController
@RequestMapping("/api/v1/jobs")
class SignalJobController(
    private val replayService: SignalReplayService,
) {

    /** POST /api/v1/jobs/signal-replay → 全历史回放（同步返回摘要） */
    @PostMapping("/signal-replay")
    fun replay(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): SignalReplaySummary = replayService.replay(from, to)
}
