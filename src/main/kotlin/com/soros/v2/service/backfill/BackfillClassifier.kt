package com.soros.v2.service.backfill

import com.soros.v2.domain.SegmentReason
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.StockSpan
import java.time.LocalDate

/**
 * 缺失段分类纯函数（无 I/O、无 Spring）。生产/测试共用，铁律用例必须在此层绿。
 */
object BackfillClassifier {

    /**
     * 期望窗口 A 计算（用户已确认口径）：
     * - 起点 = max(defaultStartDate, ipo_date)，吸附到 ≥ 起点的首个开市日；
     * - 终点 = ≤ to 的最后开市日；
     * - 起点 > 终点（窗口内无开市日）→ null（零 segment）。
     */
    fun expectedWindow(
        ipoDate: LocalDate?,             // stock_info.ipo_date（null=忽略，用 defaultStartDate）
        defaultStartDate: LocalDate,     // 回填起点（近 5 年=2021-10-01）
        to: LocalDate,                   // 回填终点
        cal: TradingDayLookup,
    ): Pair<LocalDate, LocalDate>? {
        val rawStart = maxOf(defaultStartDate, ipoDate ?: defaultStartDate)
        val start = cal.firstTradingDayOnOrAfter(rawStart) ?: return null
        val end = cal.lastTradingDayOnOrBefore(to) ?: return null
        return if (start > end) null else start to end
    }

    /**
     * 单票分类（2026-10-05 计划语义重写：整窗拉齐，不做洞级拆分）。
     *
     * 未覆盖缺失天数 = 窗口开市日数 − 库内行数 − gap_check 已验证空天数；
     * = 0 → 全齐（或缺失日全部已验证停牌）→ 零 segment（零外部请求铁律）；
     * > 0 → 产出**一个整窗段** [expectedStart, expectedEnd]——每票成本=一次外部链，
     * 与洞数无关（用户口径「一次性把所有股票的数据拉齐然后来检查」；窗内已有行随段
     * 拉回、merge upsert 幂等无害）。
     *
     * @param verifiedCoveredDays 该票 gap_check 台账覆盖的开市日数（Planner 一次聚合查询提供）
     */
    fun classifyStock(
        code: String,
        span: StockSpan,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
        cal: TradingDayLookup,
        verifiedCoveredDays: Long,
    ): List<FetchSegment> {
        if (expectedStart > expectedEnd) return emptyList()
        val openDays = cal.countOpenDaysInclusive(expectedStart, expectedEnd)
        val missingUncovered = (openDays - span.nRows - verifiedCoveredDays).coerceAtLeast(0)
        if (missingUncovered == 0L) return emptyList()
        val reason = if (span.nRows == 0L) SegmentReason.NO_DATA else SegmentReason.FULL
        return listOf(FetchSegment(code, expectedStart, expectedEnd, reason))
    }

    /**
     * segment 批打包（请求批 = /daily-bars/batch 一次调用）。
     * 约束：**同批内 code 不得重复**（响应 results 按 code 键，重复会造成数据归属歧义）；
     * 同 code 多 segment 自动拆到不同批（批容量略浪费可接受——绝大多数票 0/1 段）。
     */
    fun batchSegments(segments: List<FetchSegment>, batchSize: Int): List<List<FetchSegment>> {
        if (segments.isEmpty()) return emptyList()
        if (batchSize <= 0) return listOf(segments)   // 防御：非正批量按单批处理
        val batches = mutableListOf<List<FetchSegment>>()
        val current = mutableListOf<FetchSegment>()
        val codesInCurrent = mutableSetOf<String>()
        for (seg in segments) {
            if (current.size >= batchSize || seg.code in codesInCurrent) {
                batches += current.toList()
                current.clear()
                codesInCurrent.clear()
            }
            current += seg
            codesInCurrent += seg.code
        }
        if (current.isNotEmpty()) batches += current.toList()
        return batches
    }
}
