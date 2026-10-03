package com.soros.v2.config

import java.time.LocalDate
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * soros.collect 配置树（§13.1）。
 * - cron §4.7 防线①：默认 20:00（BaoStock 更新窗口 17:30-19:00，18:00 可能拉到半成品）
 * - rollingWindowDays §4.7 防线④：滚动重拉窗口（自然日，≈7 交易日）
 */
@ConfigurationProperties(prefix = "soros.collect")
class DataCollectionProperties {

    /** §4.7 防线① 采集时序 cron */
    var cron: String = "0 0 20 * * ?"

    /** 近 5 年回填起点 / 无数据时滚动窗口起点 */
    var defaultStartDate: LocalDate = LocalDate.of(2021, 10, 1)

    /** 每次 HTTP 请求股票数（§4.4 BATCH_SIZE=50） */
    var batchSize: Int = 50

    /** §4.7 防线④ 滚动重拉窗口（自然日） */
    var rollingWindowDays: Int = 10
}
