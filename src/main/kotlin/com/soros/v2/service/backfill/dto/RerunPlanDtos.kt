package com.soros.v2.service.backfill.dto

import com.soros.v2.domain.SegmentReason
import java.time.LocalDate

/**
 * 单票库内覆盖快照（StockHistoryRepository.aggregateCoverageByCodes 的一行，分类纯函数输入）。
 * minD/maxD 同时为 null = 库内无此码（NO_DATA）。
 */
data class StockSpan(
    val code: String,
    val minD: LocalDate?,
    val maxD: LocalDate?,
    val nRows: Long,
)

/**
 * 缺失拉取段：单次 /daily-bars/batch item = (code, from, to)。
 * 不变量（铁律）：from/to 之间的开市日 = 该票真实缺失区间；全量已补齐票不得产出任何 segment。
 */
data class FetchSegment(
    val code: String,
    val from: LocalDate,
    val to: LocalDate,
    val reason: SegmentReason,
)

/**
 * 重跑计划（BackfillPlanService.buildRerunPlan 出参）。
 * segments 为扁平清单（一票可多条）；批打包由 BackfillClassifier.batchSegments 承担（distinct code/批）。
 */
data class RerunPlan(
    val from: LocalDate,
    val to: LocalDate,
    val totalCodes: Int,
    val completeCodes: Int,     // 全齐：零 segment 票数
    val zeroWindowCodes: Int,   // expectedStart > expectedEnd（窗口无开市日）零 segment 票数
    val segments: List<FetchSegment>,
)
