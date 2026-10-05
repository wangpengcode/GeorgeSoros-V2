package com.soros.v2.service.dto

import com.soros.v2.domain.DataCoverage
import java.time.LocalDate

/**
 * §13.4/§19.11.1 SentimentCycleJob 完成握手事件（SignalPrecomputeJob 监听触发，依赖链显式化）。
 *
 * 与 DailyCollectCompleted 同构：SentimentCycleJob derive 成功后发布（§13.4 链序留痕在
 * SentimentCycleJob KDoc 中预告，本事件即接线点）；21:30 兜底由 market_daily.data_coverage
 * 判跳过（存在且非 PARTIAL）。
 *
 * @property tradeDate 派生完成的交易日（事件携带日期，不依赖 LocalDate.now()）
 * @property coverage   当日情绪周期行数据覆盖标记（FULL/PARTIAL；PARTIAL 次日滚动重拉后重算）
 */
data class SentimentCycleCompleted(
    val tradeDate: LocalDate,
    val coverage: DataCoverage,
)
