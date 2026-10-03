package com.soros.v2.util

import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.QualityIssueType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * §13.3 CollectMetrics Micrometer 计数器（纯单测：SimpleMeterRegistry，无 Spring）。
 *
 * 指标全样对齐（PLAN §13.3）：
 * - soros_collect_rows_total{source}          采集行数（source 小写对齐 Python 侧）
 * - soros_collect_failed_codes_total          采集失败股票数
 * - soros_collect_batch_duration              批耗时 timer
 * - soros_quality_log_total{issue_type}       质量事件计数
 */
class CollectMetricsTest {

    @Test
    fun `testIncrementRows tags source lowercase`() {
        // given: SimpleMeterRegistry
        val registry = SimpleMeterRegistry()
        val metrics = CollectMetrics(registry)

        // when: 两次 BAOSTOCK + 一次 AKSHARE
        metrics.incrementRows(DataSourceType.BAOSTOCK)
        metrics.incrementRows(DataSourceType.BAOSTOCK)
        metrics.incrementRows(DataSourceType.AKSHARE)

        // then: source tag 小写（对齐 Python 侧 baostock/akshare），计数正确
        val baostock = registry.find("soros_collect_rows_total")
            .tag("source", "baostock").counter()
        assertNotNull(baostock, "source=baostock 计数器应存在")
        assertEquals(2.0, baostock!!.count(), "BAOSTOCK 采集 2 次计数=2")

        val akshare = registry.find("soros_collect_rows_total")
            .tag("source", "akshare").counter()
        assertNotNull(akshare, "source=akshare 计数器应存在")
        assertEquals(1.0, akshare!!.count(), "AKSHARE 采集 1 次计数=1")
    }

    @Test
    fun `testIncrementFailedCodes accumulates`() {
        // given
        val registry = SimpleMeterRegistry()
        val metrics = CollectMetrics(registry)

        // when
        metrics.incrementFailedCodes()
        metrics.incrementFailedCodes()

        // then
        assertEquals(2.0, registry.counter("soros_collect_failed_codes_total").count(), "失败股票数累计=2")
    }

    @Test
    fun `testRecordBatchDuration recordsTimer`() {
        // given
        val registry = SimpleMeterRegistry()
        val metrics = CollectMetrics(registry)

        // when
        metrics.recordBatchDuration(100)
        metrics.recordBatchDuration(250)

        // then: timer 命中 2 次，总量 350ms
        val timer = registry.find("soros_collect_batch_duration").timer()
        assertNotNull(timer, "批耗时 timer 应存在")
        assertEquals(2L, timer!!.count(), "timer 记录 2 次")
        assertEquals(350_000_000.0, timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS), 1e6,
            "timer 总量 = 100+250 = 350ms（纳秒精度容差）")
    }

    @Test
    fun `testIncrementQualityLog tags issueType`() {
        // given
        val registry = SimpleMeterRegistry()
        val metrics = CollectMetrics(registry)

        // when: 两次 ADJUSTMENT_DRIFT + 一次 DELIST_SUSPECT
        metrics.incrementQualityLog(QualityIssueType.ADJUSTMENT_DRIFT)
        metrics.incrementQualityLog(QualityIssueType.ADJUSTMENT_DRIFT)
        metrics.incrementQualityLog(QualityIssueType.DELIST_SUSPECT)

        // then: issue_type 打 tag 计数
        assertEquals(
            2.0,
            registry.find("soros_quality_log_total").tag("issue_type", QualityIssueType.ADJUSTMENT_DRIFT.name).counter()!!.count(),
            "ADJUSTMENT_DRIFT 质量事件计数=2",
        )
        assertEquals(
            1.0,
            registry.find("soros_quality_log_total").tag("issue_type", QualityIssueType.DELIST_SUSPECT.name).counter()!!.count(),
            "DELIST_SUSPECT 质量事件计数=1",
        )
    }
}
