package com.soros.v2.config

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §六 soros.backfill 配置树全字段绑定测试（@SpringBootTest + TestContainers；@EnableConfigurationProperties 装配）。
 *
 * yml 现值（application.yml soros.backfill，PLAN §13.1 / §六 定稿 2026-10-04）：
 * - batch-size: 50 / batch-pause-ms: 1000 / copy-batch-rows: 50000
 * - default-start-date: 2021-10-01 / max-codes-per-batch: 1000
 * - max-retries-per-batch: 2 / sample-check-codes: 3
 *
 * 契约要点：
 * - 与类内默认值一致（未显式配置时仍自洽；§六 与 soros.collect.default-start-date 语义一致）；
 * - maxCodesPerBatch 是 Python 侧 batch_max_codes=1000 的防御性硬上限（router.py 422 阈值）；
 * - sampleCheckCodes 供 PLAN §六.6⑤ 抽查对拍（BackfillJob seam 契约数量来源）。
 */
@SpringBootTest
@Testcontainers
class BackfillPropertiesTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var properties: BackfillProperties

    @Test
    fun `testBind allSevenFieldsMatchYmlAndDefaults`() {
        assertEquals(50, properties.batchSize, "batch-size: 50（每次 /daily-bars/batch 请求股票数）")
        assertEquals(1000L, properties.batchPauseMs, "batch-pause-ms: 1000（批间刻意停顿，间歇性获取铁律）")
        assertEquals(50000, properties.copyBatchRows, "copy-batch-rows: 50000（stage COPY→merge 批大小）")
        assertEquals(LocalDate.of(2021, 10, 1), properties.defaultStartDate, "default-start-date: 2021-10-01（近 5 年回填起点）")
        assertEquals(1000, properties.maxCodesPerBatch, "max-codes-per-batch: 1000（Python batch_max_codes 硬上限）")
        assertEquals(2, properties.maxRetriesPerBatch, "max-retries-per-batch: 2（失败批重试次数）")
        assertEquals(3, properties.sampleCheckCodes, "sample-check-codes: 3（§六.6⑤ 抽查对拍数量）")
    }
}
