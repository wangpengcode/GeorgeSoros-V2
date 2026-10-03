package com.soros.v2.service.backfill.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.soros.v2.domain.BackfillStatus
import com.soros.v2.service.sentiment.SentimentReplaySummary
import java.time.Instant
import java.time.LocalDate

/**
 * 历史回填 DTO（POST /api/v1/jobs/backfill / GET /api/v1/jobs/backfill/status）。
 *
 * 键名过命名字典（§六 响应键区段）；start_date/end_date 复用 §3 特例语义（区间端点）。
 */

/** POST /api/v1/jobs/backfill 入参（start_date/end_date 均可选；缺省 = 近 5 年起点 → 今日） */
data class BackfillRequest(
    /** 回填起始日（YYYY-MM-DD；缺省=soros.backfill.default-start-date=20211001） */
    @JsonProperty("start_date") val startDate: LocalDate? = null,
    /** 回填结束日（YYYY-MM-DD；缺省=今日） */
    @JsonProperty("end_date") val endDate: LocalDate? = null,
) {
    init {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw IllegalArgumentException("start_date 不能晚于 end_date")
        }
    }
}

/**
 * 回填进度快照（不可变，Job 逐批原子替换引用；JSON 键与命名字典 §六 对齐）。
 */
data class BackfillProgress(
    /** 本次回填股票总数（非 ST/非退市/MAIN+GEM+STAR 全量清单） */
    @JsonProperty("total_codes") val totalCodes: Int = 0,
    /** 已处理股票数（成功+失败） */
    @JsonProperty("processed_codes") val processedCodes: Int = 0,
    /** 入库成功股票数（有行落库） */
    @JsonProperty("succeeded_codes") val succeededCodes: Int = 0,
    /** 失败股票数（拉取失败/无数据/全部校验拒绝） */
    @JsonProperty("failed_codes") val failedCodes: Int = 0,
    /** 总批数 */
    @JsonProperty("total_batches") val totalBatches: Int = 0,
    /** 已处理批数 */
    @JsonProperty("processed_batches") val processedBatches: Int = 0,
    /** 本次回填目标总行数（含失败批，0=尚未确定） */
    @JsonProperty("total_rows") val totalRows: Long = 0,
    /** 已入库行数（COPY 两段式合并进主表行数） */
    @JsonProperty("processed_rows") val processedRows: Long = 0,
    /** 当前批序号（1-based） */
    @JsonProperty("current_batch") val currentBatch: Int = 0,
    /** 启动时间（ISO-8601 Instant；null=未启动） */
    @JsonProperty("started_at") val startedAt: Instant? = null,
    /** 结束时间（ISO-8601 Instant；null=运行中/未启动） */
    @JsonProperty("finished_at") val finishedAt: Instant? = null,
)

/** GET /api/v1/jobs/backfill/status 响应（内存态；RUNNING 期间为实时进度，终态保留上次结果） */
data class BackfillStatusResponse(
    /** 任务状态（IDLE/RUNNING/COMPLETED/FAILED，见 [BackfillStatus]） */
    @JsonProperty("status") val status: BackfillStatus,
    /** 进度快照（IDLE 首次查询为 null） */
    @JsonProperty("progress") val progress: BackfillProgress? = null,
    /** 失败原因（仅 FAILED 置位；COMPLETED/IDLE/RUNNING 均 null——补算/回放降级告警只进钉钉 digest，不入 error） */
    @JsonProperty("error") val error: String? = null,
)

/**
 * 回填完成摘要（内部用：钉钉 digest + COMPLETED 状态残留；不直接对 API 序列化）。
 */
data class BackfillSummary(
    /** 回填区间起点（实际执行值） */
    val from: LocalDate,
    /** 回填区间终点（实际执行值） */
    val to: LocalDate,
    /** 本次股票总数 */
    val totalCodes: Int,
    /** 成功股票数 */
    val succeededCodes: Int,
    /** 失败股票数 */
    val failedCodes: Int,
    /** 入库行数 */
    val totalRows: Long,
    /** 总耗时（毫秒） */
    val durationMs: Long,
    /** 派生列补算是否成功（失败仅告警，不阻塞回填结果） */
    val derivedColumnsRecomputed: Boolean,
    /** 情绪回放摘要（失败为 null，不阻塞回填结果） */
    val replaySummary: SentimentReplaySummary? = null,
)
