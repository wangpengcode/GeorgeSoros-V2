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
     * 单票分类（2026-10-05 用户定稿：stock_info.input_data_last_day 水位线断点续传）。
     *
     * 判定：水位线（input_data_last_day，= 该票数据已核对到的最近交易日）≥ expectedEnd →
     * 已核对到最新开市日 → 零 segment（零外部请求铁律）；否则产出**恰一段**：
     * - 段起点 = 水位线后首个开市日（断点续传，不重拉已核对区间）；
     *   水位线为空（从未导入）→ 从 expectedStart 整窗起拉；
     *   水位线早于 expectedStart → 钳制到 expectedStart（窗口外不重拉）；
     *   水位线为非交易日 → 吸附到下一开市日。
     * - 段终点 = expectedEnd。
     * - reason：库内 0 行 → NO_DATA，否则 FULL。
     *
     * 水位线之前的窗内中段洞不由本判定承载（导入→更新→检查 流程，由拉齐后检查兜底）；
     * gap_check 台账保留记录供检查报告，不再参与拉取判定。
     *
     * @param watermark stock_info.input_data_last_day（null=从未导入）
     */
    fun classifyStock(
        code: String,
        watermark: LocalDate?,
        span: StockSpan,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
        cal: TradingDayLookup,
    ): List<FetchSegment> {
        if (expectedStart > expectedEnd) return emptyList()
        if (watermark != null && !watermark.isBefore(expectedEnd)) return emptyList()
        val snapped = if (watermark == null) {
            expectedStart
        } else {
            cal.firstTradingDayOnOrAfter(watermark.plusDays(1)) ?: return emptyList()
        }
        val from = maxOf(snapped, expectedStart)
        if (from > expectedEnd) return emptyList()
        val reason = if (span.nRows == 0L) SegmentReason.NO_DATA else SegmentReason.FULL
        return listOf(FetchSegment(code, from, expectedEnd, reason))
    }

    /**
     * 板块导入优先级分组（2026-10-05 用户定稿「688 300 先导入，然后 600 000」）：
     * 0=科创板(688/689) → 1=创业板(300/301) → 2=沪主板(600/601/603/605/606) →
     * 3=深主板(000/001/002/003，含原中小板) → 4=其他(北交所/B股等，垫底)。
     */
    fun boardGroup(code: String): Int {
        val prefix = if (code.length >= 3) code.substring(0, 3) else code
        return when (prefix) {
            "688", "689" -> 0
            "300", "301" -> 1
            "600", "601", "603", "605", "606" -> 2
            "000", "001", "002", "003" -> 3
            else -> 4
        }
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
