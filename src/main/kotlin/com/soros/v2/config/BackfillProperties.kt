package com.soros.v2.config

import java.time.LocalDate
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * soros.backfill 配置树（PLAN §六 历史回填 + §13.1 yml 全样扩展，2026-10-04 定稿）。
 *
 * yml 现状（application.yml）：
 * ```
 * soros.backfill:
 *   batch-size: 50                  # 每次 /daily-bars/batch 请求股票数
 *   batch-pause-ms: 1000            # 批间刻意停顿（间歇性获取铁律，防数据源风控）
 *   copy-batch-rows: 50000          # stage COPY→merge 批大小
 *   default-start-date: 2021-10-01  # 近 5 年回填起点
 *   max-codes-per-batch: 1000       # Python /daily-bars/batch batch_max_codes 硬上限
 *   max-retries-per-batch: 2        # 失败批重试次数
 * ```
 *
 * 说明：
 * - defaultStartDate 与 soros.collect.default-start-date 语义一致（近 5 年起点），
 *   本类默认值相同；BackfillJob 取本类值，未显式配置时二者天然一致。
 * - maxCodesPerBatch 是防御性硬上限（Python 侧 422 阈值 batch_max_codes=1000，
 *   router.py 实测）；实际每批请求数 = min(batchSize, maxCodesPerBatch)。
 */
@ConfigurationProperties(prefix = "soros.backfill")
class BackfillProperties {

    /** 每次 /daily-bars/batch 请求股票数（§4.4 BATCH_SIZE 对齐，默认 50） */
    var batchSize: Int = 50

    /** 批间刻意停顿（毫秒；间歇性获取铁律，防 IP 封禁，默认 1000） */
    var batchPauseMs: Long = 1000

    /** stage COPY→merge 批大小（行数；PLAN §13.1 copy-batch-rows=50000） */
    var copyBatchRows: Int = 50000

    /** 近 5 年回填起点（默认 2021-10-01；POST /jobs/backfill 不带 start_date 时生效） */
    var defaultStartDate: LocalDate = LocalDate.of(2021, 10, 1)

    /** Python /daily-bars/batch 单次 codes 硬上限（batch_max_codes=1000，router.py 422 阈值） */
    var maxCodesPerBatch: Int = 1000

    /** 失败批重试次数（重试只对瞬时故障/单股失败；批整体失败进 failed 不炸整批） */
    var maxRetriesPerBatch: Int = 2

    /** 派生列补算后抽查对拍的股票数（默认 3；PLAN §六.6 ⑤） */
    var sampleCheckCodes: Int = 3
}
