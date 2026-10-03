package com.soros.v2.domain

/**
 * 情绪周期数据覆盖标记（sentiment_cycle.data_coverage 值域单点；schema.sql CHECK IN ('FULL','PARTIAL')）。
 *
 * §13.4：DailyCollectJob 失败率 >10% 时照常派生，但 sentiment_cycle 行标 data_coverage=PARTIAL + 钉钉提示。
 */
enum class DataCoverage {
    /** 全量正常 */
    FULL,

    /** 采集失败率 >10%（PARTIAL 提示，不阻塞派生） */
    PARTIAL,
    ;
}
