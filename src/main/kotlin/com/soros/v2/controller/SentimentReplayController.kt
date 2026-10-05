package com.soros.v2.controller

import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.service.sentiment.SentimentReplaySummary
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §13.5 SentimentReplayController：情绪历史冷启动回放。
 *
 * POST /api/v1/jobs/sentiment-replay?from=2021-10-01&to={today}
 * 前置：stock_history 5 年回填完成且派生列已补算（§六.6）。
 * 严格按 trading_calendar 顺序逐日 computeFor（followup 与龙头状态机都依赖前一日行，
 * 不可并行、不可跳日）；回放前删除区间内行（重放语义）；完成输出摘要 → 钉钉通知。
 */
@RestController
@RequestMapping("/api/v1/jobs")
class SentimentReplayController(
    private val replayService: SentimentReplayService,
) {

    /**
     * POST /api/v1/jobs/sentiment-replay?from=&to=&force=false|true → 情绪回放（耗时分钟级，同步返回摘要）。
     * force=false（默认）= 增量补缺断点续跑；force=true = 全量重建（BackfillJob §13.5 钩子传 true）。
     */
    @PostMapping("/sentiment-replay")
    fun replay(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam("force", defaultValue = "false") force: Boolean = false,
    ): SentimentReplaySummary = replayService.replay(from, to, force).copy(force = force)
}
