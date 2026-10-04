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

    // ==================== classifyStock — 铁律 ====================

    /** 2021-11 连续交易周窗口（11-01 周一 ~ 11-30 周二；22 个开市日） */
    private fun novemberCal() = FakeTradingDayLookup(
        weekdaysInclusive(LocalDate.of(2021, 11, 1), LocalDate.of(2021, 11, 30)),
    )

    private val novStart = LocalDate.of(2021, 11, 1)
    private val novEnd = LocalDate.of(2021, 11, 30)

    @Test
    fun `testClassifyStock allCompleteProducesZeroSegments`() {
        // given: 全齐票——minD≤expectedStart 且 maxD≥expectedEnd 且 nRows==countOpenDays(minD,maxD)
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays)
        var midSegmentsCalled = false

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> midSegmentsCalled = true; emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 零 segment（全齐票不得触发任何拉取窗口）
        assertTrue(segments.isEmpty(), "全齐票必须零 segment（拉取窗口=空 → Job 层零外部请求）")
        assertEquals(false, midSegmentsCalled, "nRows==开市日数 → 不调用 midSegments provider")
    }

    @Test
    fun `testClassifyStock completeStockNeverRebuildsFullWindow`() {
        // given: 覆盖 [expectedStart, expectedEnd] 全窗 + 行数=开市日数（全量重拉禁止铁律）
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 绝不产出任何 segment（禁止从 defaultStart 全量重拉空转）
        assertTrue(segments.isEmpty(), "全齐票 segments 必须为空（缺哪段补哪段，全齐=零段）")
    }

    @Test
    fun `testClassifyStock noDataProducesSingleNoDataSegment`() {
        // given: 库内无此码（minD/maxD 全 null）→ 整窗一段 NO_DATA
        val cal = novemberCal()
        val span = StockSpan("600000", null, null, 0L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> true },  // NO_DATA 不走 MID 排除，即使传 true 也应保留
        )

        // then: 单 NO_DATA segment [expectedStart, expectedEnd]
        assertEquals(1, segments.size, "无数据票 → 单 segment")
        val s = segments.single()
        assertEquals(SegmentReason.NO_DATA, s.reason, "reason=NO_DATA")
        assertEquals(novStart, s.from, "NO_DATA from=expectedStart")
        assertEquals(novEnd, s.to, "NO_DATA to=expectedEnd")
    }

    @Test
    fun `testClassifyStock headMissingProducesHeadSegment`() {
        // given: 头缺——minD(2021-11-08) > expectedStart(2021-11-01)，尾部到齐
        val cal = novemberCal()
        val span = StockSpan("600000", LocalDate.of(2021, 11, 8), novEnd, 17L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 单 HEAD segment [expectedStart, minD-1]，绝不含已有区间（缺哪段补哪段铁律）
        assertEquals(1, segments.size, "头缺票 → 单 HEAD segment")
        val s = segments.single()
        assertEquals(SegmentReason.HEAD, s.reason, "reason=HEAD")
        assertEquals(novStart, s.from, "HEAD from=expectedStart")
        assertEquals(LocalDate.of(2021, 11, 7), s.to, "HEAD to=minD-1（2021-11-07 周日）")
    }

    @Test
    fun `testClassifyStock tailMissingProducesTailFromNextTradingDay`() {
        // given: 尾缺——maxD(2021-11-19 周五) < expectedEnd；头部到齐
        val cal = novemberCal()
        val span = StockSpan("600000", novStart, LocalDate.of(2021, 11, 19), 15L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 单 TAIL segment [nextTradingDay(maxD), expectedEnd]（周五 → 下周一吸附）
        assertEquals(1, segments.size, "尾缺票 → 单 TAIL segment")
        val s = segments.single()
        assertEquals(SegmentReason.TAIL, s.reason, "reason=TAIL")
        assertEquals(LocalDate.of(2021, 11, 22), s.from, "TAIL from=nextTradingDay(2021-11-19)（周五→下周一吸附）")
        assertEquals(novEnd, s.to, "TAIL to=expectedEnd")
    }

    @Test
    fun `testClassifyStock headAndTailMissingProducesTwoSegments`() {
        // given: 头缺+尾缺同时（中间段连续）→ HEAD + TAIL 两条，互不重叠且不重拉已有
        val cal = novemberCal()
        val span = StockSpan("600000", LocalDate.of(2021, 11, 8), LocalDate.of(2021, 11, 19), 10L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 两条 segment（HEAD + TAIL），升序且覆盖真实缺失区间
        assertEquals(2, segments.size, "头缺+尾缺 → 两条 segment")
        val reasons = segments.map { it.reason }
        assertTrue(reasons.contains(SegmentReason.HEAD), "含 HEAD：$reasons")
        assertTrue(reasons.contains(SegmentReason.TAIL), "含 TAIL：$reasons")
        val head = segments.first { it.reason == SegmentReason.HEAD }
        val tail = segments.first { it.reason == SegmentReason.TAIL }
        assertEquals(LocalDate.of(2021, 11, 1), head.from, "HEAD from=expectedStart")
        assertEquals(LocalDate.of(2021, 11, 7), head.to, "HEAD to=minD-1")
        assertEquals(LocalDate.of(2021, 11, 22), tail.from, "TAIL from=nextTradingDay(maxD)")
        assertEquals(novEnd, tail.to, "TAIL to=expectedEnd")
    }

    @Test
    fun `testClassifyStock middleGapProducesMultipleMidSegments`() {
        // given: 中间洞——nRows(20) < countOpenDays(22)；midSegments 返回两条缺失开市日 island（升序、不相交）
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd) // 22
        val span = StockSpan("600000", novStart, novEnd, openDays - 2)
        val gaps = listOf(
            LocalDate.of(2021, 11, 9) to LocalDate.of(2021, 11, 10),
            LocalDate.of(2021, 11, 24) to LocalDate.of(2021, 11, 24),
        )

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { c, f, t -> assertEquals("600000", c); assertEquals(novStart, f); assertEquals(novEnd, t); gaps },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 两条 MID segment（升序、from/to 逐岛一致）
        assertEquals(2, segments.size, "中间洞 → 两条 MID segment")
        assertTrue(segments.all { it.reason == SegmentReason.MID }, "全为 MID")
        assertEquals(LocalDate.of(2021, 11, 9), segments[0].from, "第 1 岛 from 升序")
        assertEquals(LocalDate.of(2021, 11, 10), segments[0].to, "第 1 岛 to")
        assertEquals(LocalDate.of(2021, 11, 24), segments[1].from, "第 2 岛 from 升序")
        assertEquals(LocalDate.of(2021, 11, 24), segments[1].to, "第 2 岛 to")
    }

    @Test
    fun `testClassifyStock middleGapExactMatchVerifiedEmptyExcluded`() {
        // given: 中间洞 + 已验证空段完全重合（exact-match）→ 该 MID segment 被排除
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays - 1)
        val gaps = listOf(LocalDate.of(2021, 11, 9) to LocalDate.of(2021, 11, 10))

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> gaps },
            isVerifiedEmpty = { c, f, t -> c == "600000" && f == LocalDate.of(2021, 11, 9) && t == LocalDate.of(2021, 11, 10) },
        )

        // then: 完全重合 → 排除，segments 空（停牌防反复空拉）
        assertTrue(segments.isEmpty(), "MID 与已验证空段完全重合 → 排除（exact-match）")
    }

    @Test
    fun `testClassifyStock middleGapPartialOverlapNotExcluded`() {
        // given: 中间洞 + 已验证空段仅部分重合（subset，非 exact）→ 不排除（严格 exact-match）
        val cal = novemberCal()
        val openDays = cal.countOpenDaysInclusive(novStart, novEnd)
        val span = StockSpan("600000", novStart, novEnd, openDays - 3)
        val gap = LocalDate.of(2021, 11, 8) to LocalDate.of(2021, 11, 10)  // 洞岛 3 天

        // when: 已验证空段 = [2021-11-09, 2021-11-10]（subset，非 exact）
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> listOf(gap) },
            isVerifiedEmpty = { c, f, t -> c == "600000" && f == LocalDate.of(2021, 11, 9) && t == LocalDate.of(2021, 11, 10) },
        )

        // then: 部分重合不排除 → 保留重拉机会（仅 exact-match 排除）
        assertEquals(1, segments.size, "部分重合不排除")
        assertEquals(SegmentReason.MID, segments.single().reason, "仍为 MID")
        assertEquals(LocalDate.of(2021, 11, 8), segments.single().from, "洞岛原样保留")
        assertEquals(LocalDate.of(2021, 11, 10), segments.single().to, "洞岛原样保留")
    }

    @Test
    fun `testClassifyStock noDataVerifiedEmptyNotExcluded`() {
        // given: 无数据票 + 已验证空段完全重合 → NO_DATA 不走 MID 排除（保留重拉机会，源可能临时故障）
        val cal = novemberCal()
        val span = StockSpan("600000", null, null, 0L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = novStart,
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { c, f, t -> c == "600000" && f == novStart && t == novEnd },
        )

        // then: 仍产出 NO_DATA（HEAD/TAIL/NO_DATA 保留重拉机会，MID 排除只对中间洞生效）
        assertEquals(1, segments.size, "NO_DATA 不受已验证空段排除影响")
        assertEquals(SegmentReason.NO_DATA, segments.single().reason, "reason=NO_DATA")
    }

    @Test
    fun `testClassifyStock expectedStartAfterExpectedEndReturnsEmpty`() {
        // given: 期望窗口塌缩（expectedStart > expectedEnd）→ 零 segment，即使库内有行
        val cal = novemberCal()
        val span = StockSpan("600000", novStart, novEnd, 5L)

        // when
        val segments = BackfillClassifier.classifyStock(
            code = "600000",
            span = span,
            expectedStart = LocalDate.of(2021, 12, 1),
            expectedEnd = novEnd,
            cal = cal,
            midSegments = { _, _, _ -> emptyList() },
            isVerifiedEmpty = { _, _, _ -> false },
        )

        // then: 窗口无开市日 → 零 segment（不因库内有行而硬造窗口）
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
