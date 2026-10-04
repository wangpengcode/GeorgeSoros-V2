package com.soros.v2.service.backfill

import com.soros.v2.domain.SegmentReason
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.StockSpan
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 回填缺失段分类纯函数测试（BackfillClassifier，无 I/O 无 Spring，铁律用例全部在此层绿）。
 *
 * 铁律（Architect 设计 §1.4 / §7.1，用户已定稿）：
 * - 全齐票 → 零 segment（外部请求 = 0 由 Job 层断言，此处断言 segments.isEmpty()）；
 * - 缺哪段补哪段：HEAD/TAIL/NO_DATA 只产真实缺失区间，禁止从 defaultStart 全量重拉；
 * - 期望窗口口径 A：起点=max(defaultStart, ipo) 吸附到首个开市日，终点吸附到 ≤to 最后开市日；
 * - 节假日吸附（2021-10-01 国庆 → 首个开市日 2021-10-08）；
 * - MID 中间洞仅排除「完全重合 exact-match」已验证空段（部分重合/NO_DATA 不排除，保留重拉机会）；
 * - batchSegments：同批内 code 唯一（响应 results 按 code 键，同 code 多 segment 必须拆批）。
 *
 * Fake TradingDayLookup = 内存 Set<LocalDate>（周一~周五=开市日简化口径，与 §11.1 对齐）。
 */
class BackfillClassifierTest {

    // ==================== 构造辅助 ====================

    /** 周一~周五视为开市日（§11.1 简化口径），[from, to] 含端点 */
    private fun weekdaysInclusive(from: LocalDate, to: LocalDate): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            if (d.dayOfWeek.value <= 5) days.add(d)
            d = d.plusDays(1)
        }
        return days
    }

    private class FakeTradingDayLookup(private val days: Set<LocalDate>) : TradingDayLookup {
        override fun firstTradingDayOnOrAfter(date: LocalDate): LocalDate? =
            days.filter { !it.isBefore(date) }.minOrNull()

        override fun lastTradingDayOnOrBefore(date: LocalDate): LocalDate? =
            days.filter { !it.isAfter(date) }.maxOrNull()

        override fun countOpenDaysInclusive(start: LocalDate, end: LocalDate): Long =
            days.count { !it.isBefore(start) && !it.isAfter(end) }.toLong()

        override fun nextTradingDayAfter(date: LocalDate): LocalDate? =
            days.filter { it.isAfter(date) }.minOrNull()
    }

    private fun seg(code: String, from: LocalDate, to: LocalDate, reason: SegmentReason = SegmentReason.HEAD) =
        FetchSegment(code, from, to, reason)

    // ==================== expectedWindow（口径 A：起点=max(defaultStart,ipo) 吸附） ====================

    @Test
    fun `testExpectedWindow ipoNull holidayStart absorbsToFirstTradingDay`() {
        // given: 近 5 年回填起点 2021-10-01（周五，国庆假期），ipo_date=null → 用 defaultStartDate
        val cal = FakeTradingDayLookup(weekdaysInclusive(LocalDate.of(2021, 10, 8), LocalDate.of(2021, 10, 31)))

        // when: 起点吸附到 ≥defaultStart 的首个开市日
        val window = BackfillClassifier.expectedWindow(
            ipoDate = null,
            defaultStartDate = LocalDate.of(2021, 10, 1),
            to = LocalDate.of(2021, 10, 31),
            cal = cal,
        ) ?: throw AssertionError("窗口不应为 null")

        // then: 2021-10-01 在国庆假期内 → 首个开市日 2021-10-08；终点=≤2021-10-31 最后开市日 2021-10-29
        assertEquals(LocalDate.of(2021, 10, 8), window.first, "国庆假期吸附到 2021-10-08（首个开市日）")
        assertEquals(LocalDate.of(2021, 10, 29), window.second, "终点吸附到 ≤2021-10-31 最后开市日（周五）")
    }

    @Test
    fun `testExpectedWindow ipoAfterDefaultStart startAbsorbsIpoNotDefaultStart`() {
        // given: ipo_date（2021-11-06 周六）晚于 defaultStart（2021-10-01）→ 起点=ipo_date 吸附
        val cal = FakeTradingDayLookup(weekdaysInclusive(LocalDate.of(2021, 10, 8), LocalDate.of(2021, 11, 30)))

        // when
        val window = BackfillClassifier.expectedWindow(
            ipoDate = LocalDate.of(2021, 11, 6),
            defaultStartDate = LocalDate.of(2021, 10, 1),
            to = LocalDate.of(2021, 11, 30),
            cal = cal,
        ) ?: throw AssertionError("窗口不应为 null")

        // then: rawStart=max(2021-10-01, 2021-11-06)=2021-11-06，吸附到 2021-11-08（周一）
        assertEquals(LocalDate.of(2021, 11, 8), window.first, "上市日 2021-11-06（周六）吸附到下一开市日 2021-11-08")
        assertEquals(LocalDate.of(2021, 11, 30), window.second, "终点=2021-11-30（交易日）")
    }

    @Test
    fun `testExpectedWindow ipoBeforeDefaultStart startUsesDefaultStartNotIpo`() {
        // given: 上市日（2021-09-01）早于 defaultStart（2021-10-08）→ 起点=defaultStart 吸附（不从旧上市日重拉）
        val cal = FakeTradingDayLookup(weekdaysInclusive(LocalDate.of(2021, 10, 8), LocalDate.of(2021, 10, 31)))

        // when
        val window = BackfillClassifier.expectedWindow(
            ipoDate = LocalDate.of(2021, 9, 1),
            defaultStartDate = LocalDate.of(2021, 10, 8),
            to = LocalDate.of(2021, 10, 31),
            cal = cal,
        ) ?: throw AssertionError("窗口不应为 null")

        // then: rawStart=max(2021-10-08, 2021-09-01)=2021-10-08（=defaultStart 本身是交易日）
        assertEquals(LocalDate.of(2021, 10, 8), window.first, "起点=defaultStart 吸附，不从 2021-09 上市日重拉")
    }

    @Test
    fun `testExpectedWindow toTradingDayKeepsTo toNonTradingAbsorbsDown`() {
        val cal = FakeTradingDayLookup(weekdaysInclusive(LocalDate.of(2021, 10, 8), LocalDate.of(2021, 10, 31)))

        // 子用例 A：to 为交易日 → 终点=to 原样
        val onTrading = BackfillClassifier.expectedWindow(
            ipoDate = null,
            defaultStartDate = LocalDate.of(2021, 10, 8),
            to = LocalDate.of(2021, 10, 29),
            cal = cal,
        ) ?: throw AssertionError("窗口不应为 null")
        assertEquals(LocalDate.of(2021, 10, 29), onTrading.second, "to 为交易日 → 终点=to")

        // 子用例 B：to 为周日（非交易日）→ 终点=≤to 最后开市日 2021-10-29
        val offTrading = BackfillClassifier.expectedWindow(
            ipoDate = null,
            defaultStartDate = LocalDate.of(2021, 10, 8),
            to = LocalDate.of(2021, 10, 31),
            cal = cal,
        ) ?: throw AssertionError("窗口不应为 null")
        assertEquals(LocalDate.of(2021, 10, 29), offTrading.second, "to 非交易日 → 终点吸附到 ≤to 最后开市日")
    }

    @Test
    fun `testExpectedWindow noOpenDayInWindow returnsNull`() {
        // given: 起点吸附（2021-11-06 周六 → 2021-11-08）晚于终点（2021-11-07 周日无开市日）→ 窗口内无开市日
        val cal = FakeTradingDayLookup(weekdaysInclusive(LocalDate.of(2021, 11, 1), LocalDate.of(2021, 11, 30)))

        // when & then: 起点吸附后 > 终点 → null（零 segment，窗口塌缩防御）
        assertNull(
            BackfillClassifier.expectedWindow(
                ipoDate = LocalDate.of(2021, 11, 6),
                defaultStartDate = LocalDate.of(2021, 10, 1),
                to = LocalDate.of(2021, 11, 7),
                cal = cal,
            ),
            "窗口内无开市日 → null（零 segment）",
        )
    }

    // ==================== classifyStock — 整窗拉齐语义（2026-10-05） ====================

    /** 2021-11 连续交易周窗口（11-01 周一 ~ 11-30 周二；22 个开市日） */
    private fun novemberCal() = FakeTradingDayLookup(
        weekdaysInclusive(LocalDate.of(2021, 11, 1), LocalDate.of(2021, 11, 30)),
    )

    private val novStart = LocalDate.of(2021, 11, 1)
    private val novEnd = LocalDate.of(2021, 11, 30)

    @Test
    fun `testClassifyStock allCompleteProducesZeroSegments`() {
        // given: 全齐票——nRows == 窗口开市日数
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 0L,
        )

        // then: 零 segment（全齐票零外部请求铁律）
        assertTrue(segments.isEmpty(), "全齐票必须零 segment（零外部请求）")
    }

    @Test
    fun `testClassifyStock missingDaysAllCoveredByGapCheckProducesZeroSegments`() {
        // given: 库内 18 行 + 台账已验证空 4 天 → 未覆盖缺失 = 22-18-4 = 0 → 跳过
        val cal = novemberCal()
        val span = StockSpan("600000", novStart, novEnd, 18L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 4L,
        )

        assertTrue(segments.isEmpty(), "缺失日全部已验证空（停牌）→ 零 segment，下轮不再空拉")
    }

    @Test
    fun `testClassifyStock uncoveredMissingProducesSingleFullWindowSegment`() {
        // given: 部分缺失票（3 个洞未验证）→ 一个整窗段（不做洞级拆分，每票一次外部链）
        val cal = novemberCal()
        val span = StockSpan("600000", novStart, novEnd, 19L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 0L,
        )

        assertEquals(1, segments.size, "部分缺失 → 恰一个整窗段（洞数与请求数解耦）")
        assertEquals(novStart, segments.single().from, "整窗段 from=expectedStart")
        assertEquals(novEnd, segments.single().to, "整窗段 to=expectedEnd")
        assertEquals(SegmentReason.FULL, segments.single().reason, "reason=FULL（整窗拉齐）")
    }

    @Test
    fun `testClassifyStock headMissingCoveredOverFullWindowNotMinMax`() {
        // given: 头缺票——minD 在窗口中段；缺失判定必须覆盖整窗（含头缺日），产出整窗段
        val cal = novemberCal()
        val minD = LocalDate.of(2021, 11, 15)
        val span = StockSpan("600000", minD, novEnd, 12L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 0L,
        )

        assertEquals(1, segments.size, "头缺 → 一个整窗段（从 expectedStart 起拉齐）")
        assertEquals(novStart, segments.single().from, "整窗段 from=expectedStart（不从 minD 起，头缺日一并补）")
        assertEquals(SegmentReason.FULL, segments.single().reason, "reason=FULL")
    }

    @Test
    fun `testClassifyStock noDataProducesSingleNoDataSegment`() {
        // given: 库内无此码 → 整窗一段 NO_DATA
        val cal = novemberCal()
        val span = StockSpan("600000", null, null, 0L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 0L,
        )

        assertEquals(1, segments.size, "无数据 → 一个整窗段")
        assertEquals(novStart, segments.single().from, "整窗段 from=expectedStart")
        assertEquals(novEnd, segments.single().to, "整窗段 to=expectedEnd")
        assertEquals(SegmentReason.NO_DATA, segments.single().reason, "reason=NO_DATA")
    }

    @Test
    fun `testClassifyStock noDataWholeWindowVerifiedEmptySkips`() {
        // given: 无数据票整窗已验证空（台账覆盖=开市日数）→ 未覆盖缺失=0 → 跳过
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", null, null, 0L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = openDays,
        )

        assertTrue(segments.isEmpty(), "整窗已验证空的无数据票 → 零 segment（下轮零请求）")
    }

    @Test
    fun `testClassifyStock coveredDaysOvercountClampedDefensive`() {
        // given: 防御——台账覆盖数异常超限（重复记账/窗口变化）不得产出负缺失而误判全齐以外的行为
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = novStart, expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = openDays + 100,
        )

        assertTrue(segments.isEmpty(), "coerceAtLeast(0) 钳制：负缺失按 0 处理 → 零 segment")
    }

    @Test
    fun `testClassifyStock expectedStartAfterExpectedEndReturnsEmpty`() {
        // given: 期望窗口塌缩 → 零 segment，即使库内有行
        val cal = novemberCal()
        val span = StockSpan("600000", novStart, novEnd, 5L)

        val segments = BackfillClassifier.classifyStock(
            code = "600000", span = span,
            expectedStart = LocalDate.of(2021, 12, 1), expectedEnd = novEnd,
            cal = cal, verifiedCoveredDays = 0L,
        )

        assertTrue(segments.isEmpty(), "expectedStart > expectedEnd → 零 segment")
    }


    // ==================== batchSegments（同批 code 唯一铁律） ====================

    @Test
    fun `testBatchSegments emptyInputReturnsEmpty`() {
        // when & then
        assertTrue(BackfillClassifier.batchSegments(emptyList(), 50).isEmpty(), "空 segment → 空批列表")
    }

    @Test
    fun `testBatchSegments segmentsWithinBatchSizeSingleBatch`() {
        // given: 3 段 ≤ batchSize=5（各段 code 不同）
        val segments = listOf(
            seg("600000", novStart, novStart.plusDays(2)),
            seg("600036", novStart, novStart.plusDays(2)),
            seg("600050", novStart, novStart.plusDays(2)),
        )

        // when
        val batches = BackfillClassifier.batchSegments(segments, 5)

        // then: 单批，段数与顺序保持
        assertEquals(1, batches.size, "段数 ≤ batchSize → 单批")
        assertEquals(3, batches.single().size, "单批内 3 段")
        assertEquals(listOf("600000", "600036", "600050"), batches.single().map { it.code }, "顺序保持输入序")
    }

    @Test
    fun `testBatchSegments segmentsExceedBatchSizeMultipleBatches`() {
        // given: 5 段 > batchSize=2（各段 code 不同）→ 拆 3 批
        val segments = listOf(
            seg("600001", novStart, novStart),
            seg("600002", novStart, novStart),
            seg("600003", novStart, novStart),
            seg("600004", novStart, novStart),
            seg("600005", novStart, novStart),
        )

        // when
        val batches = BackfillClassifier.batchSegments(segments, 2)

        // then: 3 批，每批 ≤ batchSize，总段数不丢
        assertEquals(3, batches.size, "5 段 batchSize=2 → 3 批")
        assertTrue(batches.all { it.size <= 2 }, "每批 ≤ batchSize")
        assertEquals(5, batches.sumOf { it.size }, "总段数不丢")
    }

    @Test
    fun `testBatchSegments sameCodeSplitAcrossBatches`() {
        // given: 同 code 多 segment（如 HEAD+TAIL 两条）必须拆到不同批（响应 results 按 code 键 → 同批不得重复 code）
        val segments = listOf(
            seg("600000", novStart, LocalDate.of(2021, 11, 7)),
            seg("600036", novStart, novStart.plusDays(3)),
            seg("600000", LocalDate.of(2021, 11, 22), novEnd),
            seg("600050", novStart, novStart.plusDays(2)),
        )

        // when
        val batches = BackfillClassifier.batchSegments(segments, 10)

        // then: 同 code 拆到不同批；每批内 code 唯一；总段数不丢
        assertTrue(batches.all { it.map { s -> s.code }.distinct().size == it.size }, "同批内 code 必须唯一")
        assertEquals(2, batches.size, "600000 两条拆到两批（批容量足够大仍因 code 冲突拆批）")
        assertEquals(4, batches.sumOf { it.size }, "总段数不丢")
    }

    @Test
    fun `testBatchSegments nonPositiveBatchSizeDefensiveSingleBatch`() {
        // given: batchSize ≤ 0 → 防御性按单批处理（不炸、不无限拆批）
        val segments = listOf(
            seg("600000", novStart, novStart.plusDays(2)),
            seg("600036", novStart, novStart.plusDays(2)),
            seg("600050", novStart, novStart.plusDays(2)),
        )

        // when & then
        val batches = BackfillClassifier.batchSegments(segments, 0)
        assertEquals(1, batches.size, "batchSize=0 防御 → 单批")
        assertEquals(3, batches.single().size, "单批含全部段")
    }
}
