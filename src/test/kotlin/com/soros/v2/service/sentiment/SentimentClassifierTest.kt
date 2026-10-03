package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.FollowupResult
import com.soros.v2.domain.SentimentStage
import com.soros.v2.domain.StockActionLabel
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.service.sentiment.dto.PoolListItem
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.9 术语判定单点（SentimentClassifier）契约测试。
 *
 * 铁律（类 KDoc）：SentimentCycleJob 落库与 /terms 接口现算**共用同一实现**，口径永不漂移。
 *
 * 六阶段规则映射（本测试定义的行为契约，Implementer 按此填充 classifyStage）：
 *   - 冰点 ICE   ：max_streak 缩至底部（≤2）且大肉为 0（大肉缩至底部、大面衰竭）
 *   - 退潮 RECEDE：高标（max_streak≥3）断板后大面激增（big_face_count ≥ big_meat_count 且 big_face_count≥3）
 *   - 混沌 CHAOS ：涨跌互现无主线（max_streak 2~4，大肉/大面均低迷）
 *   - 发酵 FERMENT：梯队扩张（lianban_count≥8 且 max_streak≥3，大肉温和）
 *   - 主升 MAINRISE：最高板持续刷新、大肉批量（max_streak≥6 且 big_meat_count≥8）
 *   - 高潮 CLIMAX：情绪顶格（max_streak≥8）
 *
 * 大/小周期建议值 suggestCycles：1-6 数值评级，以连板高度 + 大肉/大面比映射（系数配置化）；
 * 本测试锁定：值域 1-6 非 null；高度越高建议值越高；冰点日建议值低。
 *
 * followup 四分类兑现（§4.9 step9）与动作标签（§4.9 术语表）见各方法测试 KDoc。
 *
 * ⚠️ 实现侧为 TODO 骨架，本测试当前红；Implementer 按本契约填充。
 */
class SentimentClassifierTest {

    private fun classifier(properties: SentimentProperties = SentimentProperties()) =
        SentimentClassifier(properties)

    private val day = LocalDate.of(2026, 9, 30)

    /** 构造 sentiment_cycle（默认混沌场景，各测试覆盖写） */
    private fun cycle(
        maxStreak: Short? = 3,
        lianbanCount: Int = 5,
        bigMeatCount: Int = 3,
        bigFaceCount: Int = 2,
        collapseCount: Int = 0,
    ) = SentimentCycle().apply {
        this.tradeDate = day
        this.maxStreak = maxStreak
        this.lianbanCount = lianbanCount
        this.bigMeatCount = bigMeatCount
        this.bigFaceCount = bigFaceCount
        this.collapseCount = collapseCount
    }

    /** 构造一根日线 bar */
    private fun bar(
        code: String = "600000",
        date: LocalDate = day,
        close: BigDecimal = BigDecimal("10.0000"),
        changePct: BigDecimal = BigDecimal("0.00"),
        isLimitUp: Boolean = false,
        limitUpStreak: Short = 0,
        isLimitDown: Boolean = false,
        limitDownStreak: Short = 0,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.close = close
        this.changePct = changePct
        this.isLimitUp = isLimitUp
        this.limitUpStreak = limitUpStreak
        this.isLimitDown = isLimitDown
        this.limitDownStreak = limitDownStreak
    }

    /** 构造大肉/大面名单元素 */
    private fun item(
        code: String,
        changePct: BigDecimal,
        streak: Int = 1,
    ) = PoolListItem(
        code = code,
        name = "股$code",
        changePct = changePct,
        limitUpStreak = streak,
        industry = listOf("测试行业"),
    )

    // ==================== classifyStage 六阶段映射 ====================

    @Test
    fun `testClassifyStage ice when height and meat bottom out`() {
        // given: 连板高度缩至 1 板、大肉 0 只（大肉缩至底部）
        val c = cycle(maxStreak = 1, bigMeatCount = 0, bigFaceCount = 1)

        // when & then
        assertEquals(SentimentStage.ICE, classifier().classifyStage(c), "冰点：高度与大肉缩至底部")
    }

    @Test
    fun `testClassifyStage recede when high-标 broken with big face surge`() {
        // given: 高标 4 板 + 大面 4 只 ≥ 大肉 1 只（大面激增）
        val c = cycle(maxStreak = 4, bigMeatCount = 1, bigFaceCount = 4)

        // when & then
        assertEquals(SentimentStage.RECEDE, classifier().classifyStage(c), "退潮：大面激增、高标断板")
    }

    @Test
    fun `testClassifyStage chaos when mixed no main line`() {
        // given: 高度 3 板、大肉/大面均低迷、连板稀疏（涨跌互现无主线）
        val c = cycle(maxStreak = 3, lianbanCount = 4, bigMeatCount = 2, bigFaceCount = 2)

        // when & then
        assertEquals(SentimentStage.CHAOS, classifier().classifyStage(c), "混沌：涨跌互现无主线")
    }

    @Test
    fun `testClassifyStage ferment when ladder expanding`() {
        // given: 连板家数 10（梯队扩张）+ 高度 4 板 + 大肉温和
        val c = cycle(maxStreak = 4, lianbanCount = 10, bigMeatCount = 4, bigFaceCount = 1)

        // when & then
        assertEquals(SentimentStage.FERMENT, classifier().classifyStage(c), "发酵：梯队扩张")
    }

    @Test
    fun `testClassifyStage mainrise when top board refreshing and meat batch`() {
        // given: 高度 6 板 + 大肉 8 只（最高板持续刷新、大肉批量）
        val c = cycle(maxStreak = 6, lianbanCount = 12, bigMeatCount = 8, bigFaceCount = 1)

        // when & then
        assertEquals(SentimentStage.MAINRISE, classifier().classifyStage(c), "主升：最高板持续刷新、大肉批量")
    }

    @Test
    fun `testClassifyStage climax when top board at peak`() {
        // given: 高度 9 板（情绪顶格）
        val c = cycle(maxStreak = 9, lianbanCount = 15, bigMeatCount = 10, bigFaceCount = 0)

        // when & then
        assertEquals(SentimentStage.CLIMAX, classifier().classifyStage(c), "高潮：情绪顶格")
    }

    // ==================== suggestCycles 1-6 评级 ====================

    @Test
    fun `testSuggestCycles values in 1-6 range and non-null for populated cycle`() {
        // given: 正常周期
        val c = cycle(maxStreak = 5, lianbanCount = 10, bigMeatCount = 6, bigFaceCount = 1)

        // when
        val (big, small) = classifier().suggestCycles(c)

        // then: 值域 1-6，非 null（1-6 数值评级保留为周期内阶段标注）
        assertNotNull(big, "big_cycle_sug 非 null")
        assertNotNull(small, "small_cycle_sug 非 null")
        assertTrue(big!! in 1..6, "big_cycle_sug ∈ [1,6]")
        assertTrue(small!! in 1..6, "small_cycle_sug ∈ [1,6]")
    }

    @Test
    fun `testSuggestCycles higher height and meat yields higher big cycle`() {
        // given: 冷周期（1 板、大肉 0） vs 热周期（8 板、大肉 12）
        val cold = cycle(maxStreak = 1, lianbanCount = 1, bigMeatCount = 0, bigFaceCount = 1)
        val hot = cycle(maxStreak = 8, lianbanCount = 16, bigMeatCount = 12, bigFaceCount = 0)

        // when
        val coldBig = classifier().suggestCycles(cold).first!!
        val hotBig = classifier().suggestCycles(hot).first!!

        // then: 高度+大肉越高，大周期建议值越高（连板高度 + 大肉/大面比映射）
        assertTrue(hotBig > coldBig, "热周期大周期建议值 > 冷周期（高度+大肉批量推高评级）")
    }

    @Test
    fun `testSuggestCycles ice day suggests lowest rating`() {
        // given: 冰点日（高度缩至底部、大肉 0）
        val c = cycle(maxStreak = 1, lianbanCount = 1, bigMeatCount = 0, bigFaceCount = 0)

        // when & then
        assertEquals(1, classifier().suggestCycles(c).first, "冰点日大周期建议值=1（底部评级）")
    }

    // ==================== classifyFollowup 四分类兑现 ====================

    @Test
    fun `testClassifyFollowup meat list today gain continues`() {
        // given: 大肉名单 600000（昨日 6.5%），今日再 +6%（≥+5%）
        val barsByCode = mapOf("600000" to listOf(bar("600000", changePct = BigDecimal("6.00"))))

        // when
        val result = classifier().classifyFollowup(day, barsByCode, listOf(item("600000", BigDecimal("6.50"))), emptyList())

        // then
        assertEquals(1, result.size, "名单逐股一条")
        val r = result.first()
        assertEquals("600000", r.code, "code 透传")
        assertEquals("meat", r.src, "src=meat（来自大肉名单）")
        assertEquals(BigDecimal("6.50"), r.yestPct, "yest_pct=昨日涨跌幅")
        assertEquals(BigDecimal("6.00"), r.todayPct, "today_pct=今日涨跌幅")
        assertEquals(FollowupResult.CONTINUE.label, r.result, "延续：今日再 ≥+5%")
    }

    @Test
    fun `testClassifyFollowup meat list today retreats`() {
        // given: 大肉名单今日 +3%（0~+5）
        val barsByCode = mapOf("600000" to listOf(bar("600000", changePct = BigDecimal("3.00"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, listOf(item("600000", BigDecimal("6.50"))), emptyList()).first()
        assertEquals(FollowupResult.RETREAT.label, r.result, "回落：今日 0~+5%")
    }

    @Test
    fun `testClassifyFollowup meat list today turns to face`() {
        // given: 大肉名单今日 -6%（≤-5%）
        val barsByCode = mapOf("600000" to listOf(bar("600000", changePct = BigDecimal("-6.00"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, listOf(item("600000", BigDecimal("6.50"))), emptyList()).first()
        assertEquals(FollowupResult.TURN_FACE.label, r.result, "转大面：今日 ≤-5%")
    }

    @Test
    fun `testClassifyFollowup meat list boundary exactly plus 5 continues`() {
        // given: 今日恰 +5.00%（≥+5 含边界）
        val barsByCode = mapOf("600000" to listOf(bar("600000", changePct = BigDecimal("5.00"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, listOf(item("600000", BigDecimal("6.50"))), emptyList()).first()
        assertEquals(FollowupResult.CONTINUE.label, r.result, "延续：恰 +5% 含阈值")
    }

    @Test
    fun `testClassifyFollowup face list today rebound stops`() {
        // given: 大面名单 300750（昨日 -6.5%），今日 +6%（止跌反核）
        val barsByCode = mapOf("300750" to listOf(bar("300750", changePct = BigDecimal("6.00"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, emptyList(), listOf(item("300750", BigDecimal("-6.50")))).first()
        assertEquals("face", r.src, "src=face（来自大面名单）")
        assertEquals(FollowupResult.REBOUND_STOP.label, r.result, "反核止跌：今日 ≥+5%")
    }

    @Test
    fun `testClassifyFollowup face list today weak swings`() {
        // given: 大面名单今日 +0.5%（-5~+5）
        val barsByCode = mapOf("300750" to listOf(bar("300750", changePct = BigDecimal("0.50"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, emptyList(), listOf(item("300750", BigDecimal("-6.50")))).first()
        assertEquals(FollowupResult.WEAK_SWING.label, r.result, "弱势震荡")
    }

    @Test
    fun `testClassifyFollowup face list today continues big face`() {
        // given: 大面名单今日仍 -6%（≤-5%）
        val barsByCode = mapOf("300750" to listOf(bar("300750", changePct = BigDecimal("-6.00"))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, emptyList(), listOf(item("300750", BigDecimal("-6.50")))).first()
        assertEquals(FollowupResult.CONTINUE_FACE.label, r.result, "继续大面：今日仍 ≤-5%")
    }

    @Test
    fun `testClassifyFollowup no bar today suspended`() {
        // given: 名单内股票今日无 bar（停牌；交易日历开市但无行）
        val barsByCode = mapOf("600000" to listOf(bar("600000", date = day.minusDays(1))))

        // when & then
        val r = classifier().classifyFollowup(day, barsByCode, listOf(item("600000", BigDecimal("6.50"))), emptyList()).first()
        assertEquals(FollowupResult.SUSPENDED.label, r.result, "停牌：无今日 bar")
        assertNull(r.todayPct, "停牌 today_pct=null")
    }

    @Test
    fun `testClassifyFollowup both lists merged in order`() {
        // given: 大肉 600000 + 大面 300750 同日兑现
        val barsByCode = mapOf(
            "600000" to listOf(bar("600000", changePct = BigDecimal("6.00"))),
            "300750" to listOf(bar("300750", changePct = BigDecimal("-6.00"))),
        )

        // when
        val result = classifier().classifyFollowup(
            day, barsByCode,
            listOf(item("600000", BigDecimal("6.50"))),
            listOf(item("300750", BigDecimal("-6.50"))),
        )

        // then: 两条都产出，src 区分
        assertEquals(2, result.size, "大肉+大面名单逐股一条")
        assertEquals("meat", result.first { it.code == "600000" }.src, "600000 src=meat")
        assertEquals("face", result.first { it.code == "300750" }.src, "300750 src=face")
    }

    // ==================== buildActions 个股动作标签 ====================

    /**
     * buildActions 行为契约：给定单股近 N 日 bar 窗口（含当日，升序）+ 该股龙头周期 + 反核集合，
     * 产出逐日动作标签（每根 bar 一条，升序）。判定规则（§4.9 术语表，优先级从高到低）：
     *   反核止跌（code∈reboundCodes）→ 反包（龙头 BROKEN 且今日涨停）→ 晋级（今日涨停且 streak≥2）
     *   → 断板（昨涨停今未涨停）→ 继续大面（今日 isLimitDown 且 ≤-5%）→ 大肉（今日 ≥+5%）
     *   → 大面（今日 ≤-5%）。StockHistory 无名称列，name 允许占位（调用方补全），测试只锁定 label。
     */
    @Test
    fun `testBuildActions promote on streak continuation`() {
        // given: 昨涨停(streak=2) → 今继续涨停 streak=3（晋级 n→n+1）
        val d1 = day.minusDays(1)
        val bars = listOf(
            bar("600000", d1, isLimitUp = true, limitUpStreak = 2),
            bar("600000", day, isLimitUp = true, limitUpStreak = 3),
        )

        // when
        val actions = classifier().buildActions(bars, null, emptySet())

        // then: 今日标签=晋级
        val today = actions.first { it.code == "600000" }
        assertEquals(StockActionLabel.PROMOTE.label, today.label, "晋级：连板延续 n→n+1")
        assertNotNull(today.evidence, "evidence 非 null（判定口径说明）")
    }

    @Test
    fun `testBuildActions broken when yesterday limit up and today not`() {
        // given: 昨涨停(streak=2) → 今未涨停（连板中断）
        val d1 = day.minusDays(1)
        val bars = listOf(
            bar("600000", d1, isLimitUp = true, limitUpStreak = 2),
            bar("600000", day, changePct = BigDecimal("1.00")),
        )

        // when
        val actions = classifier().buildActions(bars, null, emptySet())

        // then: 今日标签=断板
        val today = actions.first { it.code == "600000" }
        assertEquals(StockActionLabel.BROKEN.label, today.label, "断板：今日未涨停")
    }

    @Test
    fun `testBuildActions rebreak when broken dragon limit up within observe window`() {
        // given: 龙头 600000 断板（BROKEN，broken_date=d1），今日再涨停 → 反包
        val d1 = day.minusDays(1)
        val activeDragon = DragonCycle().apply {
            code = "600000"
            startDate = d1.minusDays(3)
            status = CycleStatus.BROKEN
            brokenDate = d1
        }
        val bars = listOf(
            bar("600000", d1, changePct = BigDecimal("-2.00")),
            bar("600000", day, isLimitUp = true, limitUpStreak = 1),
        )

        // when
        val actions = classifier().buildActions(bars, activeDragon, emptySet())

        // then: 今日标签=反包（断板后观察期再涨停）
        val today = actions.first { it.code == "600000" }
        assertEquals(StockActionLabel.REBREAK.label, today.label, "反包：断板后 1-3 日观察期内再涨停")
    }

    @Test
    fun `testBuildActions reboundStop when code in reboundCodes`() {
        // given: 崩塌股 000587 今日被反核（code∈reboundCodes）
        val bars = listOf(
            bar("000587", day.minusDays(1), isLimitDown = true, limitDownStreak = 2),
            bar("000587", day, changePct = BigDecimal("6.00")),
        )

        // when
        val actions = classifier().buildActions(bars, null, setOf("000587"))

        // then: 今日标签=反核止跌（优先级最高）
        val today = actions.first { it.code == "000587" }
        assertEquals(StockActionLabel.REBOUND_STOP.label, today.label, "反核止跌：崩塌股今日 ≥+5% 或涨停")
    }

    @Test
    fun `testBuildActions bigMeat when strong stock up 5pct`() {
        // given: 强势股今日 +6%（大肉）
        val bars = listOf(
            bar("600000", day.minusDays(1), changePct = BigDecimal("1.00")),
            bar("600000", day, changePct = BigDecimal("6.00")),
        )

        // when
        val actions = classifier().buildActions(bars, null, emptySet())

        // then
        assertEquals(StockActionLabel.BIG_MEAT.label, actions.first { it.code == "600000" }.label, "大肉：今日 ≥+5%")
    }

    @Test
    fun `testBuildActions bigFace when high stock drops 5pct`() {
        // given: 高位股今日 -6%（大面）
        val bars = listOf(
            bar("300750", day.minusDays(1), changePct = BigDecimal("1.00")),
            bar("300750", day, changePct = BigDecimal("-6.00")),
        )

        // when
        val actions = classifier().buildActions(bars, null, emptySet())

        // then
        assertEquals(StockActionLabel.BIG_FACE.label, actions.first { it.code == "300750" }.label, "大面：今日 ≤-5%")
    }

    @Test
    fun `testBuildActions continueFace when limit down persists`() {
        // given: 崩塌股今日继续跌停（isLimitDown + 连板 2 + ≤-5%）
        val bars = listOf(
            bar("000587", day.minusDays(1), isLimitDown = true, limitDownStreak = 1),
            bar("000587", day, isLimitDown = true, limitDownStreak = 2, changePct = BigDecimal("-9.90")),
        )

        // when
        val actions = classifier().buildActions(bars, null, emptySet())

        // then: 继续大面（崩塌池语境，区别于普通大面）
        assertEquals(StockActionLabel.CONTINUE_FACE.label, actions.first { it.code == "000587" }.label, "继续大面：昨日大面名单今日仍 ≤-5%")
    }
}
