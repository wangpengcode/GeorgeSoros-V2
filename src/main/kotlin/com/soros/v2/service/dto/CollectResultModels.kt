package com.soros.v2.service.dto

/**
 * 采集结果/事件模型（§4.6/§4.7/§4.8 联动输出 + §13.4 完成握手事件）。
 */

/**
 * StockHistoryService.saveBatch 联动输出（供 Job 日志/MDC/指标/事件消费）。
 *
 * @property code              证券代码（裸数字）
 * @property totalRows         本批入库行数（含 upsert 覆盖）
 * @property driftDetected     §4.6 是否检测到除权漂移
 * @property refetched         是否触发单股全量重拉（漂移自愈）
 * @property invalidRowsSkipped DataValidator 拦截的非法行数（§4.7 防线②，不落库）
 */
data class SaveBatchResult(
    val code: String,
    val totalRows: Int,
    val driftDetected: Boolean,
    val refetched: Boolean,
    val invalidRowsSkipped: Int,
)

/**
 * §13.4 DailyCollectJob 完成握手事件（SentimentCycleJob/SorosJob 监听触发，依赖链显式化）。
 *
 * 字段定稿（§13.4）：successCodes / failedCodes / durationMs。
 * 消费方：failedCodes 占比 >10% 时照常派生，但 sentiment_cycle 行标 data_coverage=PARTIAL + 钉钉提示。
 */
data class DailyCollectCompleted(
    val successCodes: Set<String>,
    val failedCodes: Set<String>,
    val durationMs: Long,
)
