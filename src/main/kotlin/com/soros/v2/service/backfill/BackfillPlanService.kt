package com.soros.v2.service.backfill

import com.soros.v2.service.backfill.dto.RerunPlan
import java.time.LocalDate

/**
 * 回填重跑计划（库内驱动，零外部请求；替代 BackfillJob 的 buildChunkPlan 全量重拉）。
 *
 * 前提：调用方已 prepareCalendar（ensureLoaded）。本方法内防御性再 ensureLoaded()，
 * 失败 → BusinessException（日历是计划的硬依赖，不再 fail-open）。
 */
interface BackfillPlanService {
    suspend fun buildRerunPlan(from: LocalDate, to: LocalDate): RerunPlan
}
