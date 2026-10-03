package com.soros.v2.controller

import com.soros.v2.service.backfill.BackfillService
import com.soros.v2.service.backfill.dto.BackfillRequest
import com.soros.v2.service.backfill.dto.BackfillStatusResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * §六 历史回填端点（PLAN Step 6；手动触发，不做 cron 自动全量）。
 *
 * - POST /api/v1/jobs/backfill：触发（start_date/end_date 可选；缺省=近 5 年起点→今日），
 *   后台协程执行；运行中二次触发 → 422（GlobalExceptionHandler 处理 [BusinessException]）。
 * - GET /api/v1/jobs/backfill/status：查询内存态进度（IDLE/RUNNING/COMPLETED/FAILED）。
 *
 * 完成链（设计定稿）：全部批次 COPY 合并 → 派生列补算 → 3 股抽查对拍 → 情绪回放
 * （§13.5，失败不阻塞回填结果只告警）→ 钉钉 digest。
 */
@RestController
@RequestMapping("/api/v1/jobs")
class BackfillController(
    private val backfillService: BackfillService,
) {

    /** POST /api/v1/jobs/backfill → 触发回填（立即返回 RUNNING 状态；运行中 422） */
    @PostMapping("/backfill")
    fun start(@RequestBody(required = false) request: BackfillRequest?): BackfillStatusResponse =
        backfillService.start(request ?: BackfillRequest())

    /** GET /api/v1/jobs/backfill/status → 查询回填进度 */
    @GetMapping("/backfill/status")
    fun status(): BackfillStatusResponse = backfillService.status()
}
