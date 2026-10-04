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
     * 单票分类：库内覆盖 vs 期望窗口 → 缺失 segment 清单（0..N 条）。
     *
     * @param midSegments   中间洞 provider（= StockHistoryRepository.findMissingDateIslands 的包装；
     *                      仅当 nRows < countOpenDays 时调用）。返回缺失开市日 islands（升序）。
     * @param isVerifiedEmpty 与 stock_history_gap_check 完全重合判定（exact match code+seg_from+seg_to）
     */
    fun classifyStock(
        code: String,
        span: StockSpan,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
        cal: TradingDayLookup,
        midSegments: (String, LocalDate, LocalDate) -> List<Pair<LocalDate, LocalDate>>,
        isVerifiedEmpty: (String, LocalDate, LocalDate) -> Boolean,
    ): List<FetchSegment> {
        if (expectedStart > expectedEnd) return emptyList()
        val segments = mutableListOf<FetchSegment>()
        val minD = span.minD
        val maxD = span.maxD
        val nRows = span.nRows
        if (minD == null || maxD == null || nRows == 0L) {
            // 无数据：整窗一段
            segments += FetchSegment(code, expectedStart, expectedEnd, SegmentReason.NO_DATA)
            return segments
        }
        // 头缺：min_d > expectedStart
        if (minD > expectedStart) {
            segments += FetchSegment(code, expectedStart, minD.minusDays(1), SegmentReason.HEAD)
        }
        // 尾缺：max_d < expectedEnd，段起点吸附 maxD 后下一开市日（+1 吸附等价）
        if (maxD < expectedEnd) {
            val tailFrom = cal.nextTradingDayAfter(maxD) ?: return segments // 理论不可达：expectedEnd 在其后必有开市日
            segments += FetchSegment(code, tailFrom, expectedEnd, SegmentReason.TAIL)
        }
        // 中间洞：行数 < [minD,maxD] 开市日数
        val openDays = cal.countOpenDaysInclusive(minD, maxD)
        if (nRows < openDays) {
            val gaps = midSegments(code, minD, maxD)
                .filter { (f, t) -> !isVerifiedEmpty(code, f, t) }
            segments += gaps.map { FetchSegment(code, it.first, it.second, SegmentReason.MID) }
        }
        return segments
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
