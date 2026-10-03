package com.soros.v2.util

import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.QualityIssueType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

/**
 * §13.3 Micrometer 计数器（/actuator/metrics 暴露，不引 Prometheus/Grafana）。
 *
 * 指标（与 §13.3 全样对齐）：
 * - soros_collect_rows_total{source}          采集行数
 * - soros_collect_failed_codes_total          采集失败股票数
 * - soros_collect_batch_duration              批耗时 timer
 * - soros_quality_log_total{issue_type}       质量事件计数
 */
@Component
class CollectMetrics(
    private val meterRegistry: MeterRegistry,
) {

    private val failedCodesCounter = meterRegistry.counter("soros_collect_failed_codes_total")
    private val batchTimer: Timer = Timer.builder("soros_collect_batch_duration")
        .publishPercentileHistogram()
        .register(meterRegistry)

    /** 采集行数计数（按实际数据源打 tag，source 小写对齐 Python 侧） */
    fun incrementRows(source: DataSourceType) {
        meterRegistry.counter("soros_collect_rows_total", "source", source.name.lowercase()).increment()
    }

    /** 采集失败股票数计数 */
    fun incrementFailedCodes() {
        failedCodesCounter.increment()
    }

    /** 批耗时记录 */
    fun recordBatchDuration(durationMs: Long) {
        batchTimer.record(durationMs, TimeUnit.MILLISECONDS)
    }

    /** 数据质量问题事件计数（按 issue_type 打 tag） */
    fun incrementQualityLog(issueType: QualityIssueType) {
        meterRegistry.counter("soros_quality_log_total", "issue_type", issueType.name).increment()
    }
}
