package com.soros.v2.service.strategy

import com.soros.v2.domain.StockActionLabel
import com.soros.v2.entity.MarketDaily
import com.soros.v2.entity.SectorDaily
import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.StockHistory
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SectorDailyRepository
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.repository.StockHistoryRepository
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §19.13.3 ConditionEvaluator 双路径求值纯函数契约测试（mock 聚合 repo，无 TestContainers）。
 *
 * 求值路径（§19.13.3「ConditionEvaluator 扩展」）：
 * - 路径 A（聚合表直查）：limit_up_streak/ladder_rank/is_zhaban/sector_ladder_rank + chip 4 组 直读 signal_daily；
 *   adv/dec/yst_limit_premium/yst_face_count/limit_up_count 直读 market_daily；limit_up_count/avg_chg_pct_all 直读 sector_daily。
 *   零候选依赖，条件逐日判真。窗口 = 近 30 交易日，每条件逐日判真 → last_fired/fired_30d/firedDays。
 *   （注：limit_up_streak 列实存于 stock_history（schema §五），SignalWindow 携带 stockHistory 供路径 A 读取；
 *    §19.13.3 行「直读 signal_daily」为计划简写，schema 为准。）
 * - 路径 L（标签候选池扫描现算，G5）：LabelEvaluationService.getLabels 返回 `Map<code, Map<trade_date, label>>`（
 *   内部 = 五池并集∪进行中龙头 候选集 逐票复用 SentimentClassifier.labelFor 现算，不落库）；
 *   条件逐日判真 = ∃ code ∈ universe∩候选集 当日命中目标标签（within_days N 放宽至 [D−N+1, D]）。
 * - B/C 类：本期只出 data_state（表存在性+latest+coverage），ready=PENDING，不求值不误导。
 * - LabelEvaluationCache：ConcurrentHashMap<窗口末日, LabelResult>，同日多次请求不重复扫（compute 只调一次）；
 *   失效：①窗口末日变化 ②signal_daily 最新日变化 ③显式 invalidate。
 *
 * ⚠️ 空壳（TDD 红阶段）：ConditionEvaluator / ConditionEvaluatorImpl / SignalWindow / ParsedCondition /
 * ConditionDtos / LabelEvaluationService / LabelEvaluationCache / LabelDtos 尚未创建，本文件编译失败即预期红。
 *
 * 签名契约（Implementer 创建时对齐，包 com.soros.v2.service.strategy 与 .dto）：
 * - ConditionEvaluator.evaluate(cond: ParsedCondition, ctx: EvalContext): ConditionResult
 * - data class EvalContext(window: List<LocalDate>, signalTables: SignalWindow, universe: Set<String>,
 *                          labelService: LabelEvaluationService)
 * - data class ConditionResult(cls: ConditionClass, ready: ReadyState, lastFired: LocalDate?, fired30d: Int,
 *                              firedDays: List<LocalDate>, dataState: Map<String, Any>)
 * - enum ConditionClass { A, L, B, C }；enum ReadyState { READY, PARTIAL, PENDING }
 * - data class ParsedCondition(condId, side, source, op, value: Any, cls: ConditionClass, withinDays: Int = 0)
 * - data class SignalWindow(signalDaily: Map<String, List<SignalDaily>>, marketDaily: Map<LocalDate, MarketDaily>,
 *                           sectorDaily: List<SectorDaily>, stockHistory: Map<String, List<StockHistory>>)
 * - interface LabelEvaluationService { fun getLabels(endDate: LocalDate, windowDays: Int): Map<String, Map<LocalDate, StockActionLabel>> }
 * - class LabelEvaluationCache { fun getOrLoad(endDate: LocalDate, loader: (LocalDate) -> LabelResult): LabelResult;
 *                                fun invalidate(endDate: LocalDate) }
 * - data class LabelResult(endDate: LocalDate, labels: Map<String, Map<LocalDate, StockActionLabel>>, scanScope: CandidateScope)
 * - data class CandidateScope(scanScope: String, candidateCount: Int, coveredDays: Int, stockHistoryLatest: LocalDate?,
 *                             dragonCycleLatest: LocalDate?, archiveSince: LocalDate?)
 */
class ConditionEvaluatorTest {

    private val d1 = LocalDate.of(2026, 9, 24)
    private val d2 = LocalDate.of(2026, 9, 25)
    private val d3 = LocalDate.of(2026, 9, 28)
    private val d4 = LocalDate.of(2026, 9, 29)
    private val d5 = LocalDate.of(2026, 9, 30)
    private val window = listOf(d1, d2, d3, d4, d5)

    private val evaluator = ConditionEvaluatorImpl()

    // ==================== 测试数据构造 ====================

    private fun bar(
        code: String,
        date: LocalDate,
        limitUpStreak: Short = 0,
        isLimitUp: Boolean = false,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.close = BigDecimal("10.00")
        this.changePct = BigDecimal("0.00")
        this.isLimitUp = isLimitUp
        this.limitUpStreak = limitUpStreak
        this.isLimitDown = false
        this.limitDownStreak = 0
    }

    private fun marketRow(date: LocalDate): MarketDaily = MarketDaily().apply {
        this.tradeDate = date
        this.advCount = 2000
        this.decCount = 800
        this.limitUpCount = 40
    }

    private fun sectorRow(date: LocalDate): SectorDaily = SectorDaily().apply {
        this.tradeDate = date
        this.board = "半导体"
        this.limitUpCount = 6
        this.avgChgPctAll = BigDecimal("3.5")
    }

    private fun signalRow(code: String, date: LocalDate, ladderRank: Short = 1): SignalDaily = SignalDaily().apply {
        this.code = code
        this.tradeDate = date
        this.ladderRank = ladderRank
    }

    /**
     * mock 聚合 repo → 组装 SignalWindow（生产侧由 StrategyService/条件装配器做同构组装，EvalContext 携带进 evaluate）。
     * 路径 A 的「mock 聚合 repo」语义在此体现：window 数据来自聚合 repo 查询结果。
     */
    private fun assembleWindow(
        historyRepo: StockHistoryRepository,
        signalRepo: SignalDailyRepository,
        marketRepo: MarketDailyRepository,
        sectorRepo: SectorDailyRepository,
        universe: List<String>,
    ): SignalWindow {
        val stockHistory = universe.associateWith { code ->
            historyRepo.findByCodeAndTradeDateBetween(code, window.first(), window.last())
        }
        val signalDaily = universe.associateWith { code ->
            signalRepo.findByCodeAndTradeDateBetween(code, window.first(), window.last())
        }
        val marketDaily = window.mapNotNull { d -> marketRepo.findByTradeDate(d)?.let { it.tradeDate to it } }.toMap()
        val sectorDaily = window.flatMap { d -> sectorRepo.findByTradeDateOrderByBoardAsc(d) }
        return SignalWindow(signalDaily, marketDaily, sectorDaily, stockHistory)
    }

    private fun ctx(
        signalTables: SignalWindow,
        universe: Set<String> = setOf("600000"),
        labelService: LabelEvaluationService = Mockito.mock(LabelEvaluationService::class.java),
    ) = EvalContext(
        window = window,
        signalTables = signalTables,
        universe = universe,
        labelService = labelService,
    )

    private fun mockLabelService(labels: Map<String, Map<LocalDate, StockActionLabel>>): LabelEvaluationService {
        val svc = Mockito.mock(LabelEvaluationService::class.java)
        Mockito.`when`(svc.getLabels(Mockito.any(), Mockito.anyInt())).thenReturn(labels)
        return svc
    }

    // ==================== 路径 A：正常流程 ====================

    @Test
    fun `testEvaluate AClassLimitUpStreakBetweenTrue`() {
        // given: 聚合 repo（stock_history）返回 600000 近 5 日 limit_up_streak=[0,3,4,5,0]
        // → d2/d3/d4 落在 [3,7]，条件逐日判真 3 天
        val historyRepo = Mockito.mock(StockHistoryRepository::class.java)
        val signalRepo = Mockito.mock(SignalDailyRepository::class.java)
        val marketRepo = Mockito.mock(MarketDailyRepository::class.java)
        val sectorRepo = Mockito.mock(SectorDailyRepository::class.java)
        Mockito.`when`(historyRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(
            listOf(
                bar("600000", d1, 0),
                bar("600000", d2, 3, isLimitUp = true),
                bar("600000", d3, 4, isLimitUp = true),
                bar("600000", d4, 5, isLimitUp = true),
                bar("600000", d5, 0),
            ),
        )
        Mockito.`when`(signalRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(emptyList())
        Mockito.`when`(marketRepo.findByTradeDate(Mockito.any())).thenReturn(null)
        Mockito.`when`(sectorRepo.findByTradeDateOrderByBoardAsc(Mockito.any())).thenReturn(emptyList())

        // when: A 类 limit_up_streak between [3,7]
        val cond = ParsedCondition(
            condId = "buy_0",
            side = "BUY",
            source = "limit_up_streak",
            op = "between",
            value = listOf(3, 7),
            cls = ConditionClass.A,
        )
        val result = evaluator.evaluate(
            cond,
            ctx(assembleWindow(historyRepo, signalRepo, marketRepo, sectorRepo, listOf("600000"))),
        )

        // then: READY + 真实求值 last_fired/fired_30d/firedDays
        assertEquals(ConditionClass.A, result.cls, "A 类标识")
        assertEquals(ReadyState.READY, result.ready, "数据齐可求值")
        assertEquals(d4, result.lastFired, "最近触发日=窗口内最后命中日 d4")
        assertEquals(3, result.fired30d, "近 30 日触发次数=3（d2/d3/d4）")
        assertEquals(listOf(d2, d3, d4), result.firedDays, "触发流水日期（flow 数据源）")
    }

    @Test
    fun `testEvaluate AClassLimitUpStreakBetweenFalse`() {
        // given: 聚合 repo 返回 limit_up_streak 全 0/1（无 [3,7] 命中）
        val historyRepo = Mockito.mock(StockHistoryRepository::class.java)
        val signalRepo = Mockito.mock(SignalDailyRepository::class.java)
        val marketRepo = Mockito.mock(MarketDailyRepository::class.java)
        val sectorRepo = Mockito.mock(SectorDailyRepository::class.java)
        Mockito.`when`(historyRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(
            listOf(
                bar("600000", d1, 0),
                bar("600000", d2, 1),
                bar("600000", d3, 2),
                bar("600000", d4, 0),
                bar("600000", d5, 1),
            ),
        )
        Mockito.`when`(signalRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(emptyList())
        Mockito.`when`(marketRepo.findByTradeDate(Mockito.any())).thenReturn(null)
        Mockito.`when`(sectorRepo.findByTradeDateOrderByBoardAsc(Mockito.any())).thenReturn(emptyList())

        // when
        val cond = ParsedCondition(
            condId = "buy_0",
            side = "BUY",
            source = "limit_up_streak",
            op = "between",
            value = listOf(3, 7),
            cls = ConditionClass.A,
        )
        val result = evaluator.evaluate(
            cond,
            ctx(assembleWindow(historyRepo, signalRepo, marketRepo, sectorRepo, listOf("600000"))),
        )

        // then: READY + 零命中（last_fired null / fired_30d 0 / firedDays 空）
        assertEquals(ReadyState.READY, result.ready, "数据齐可求值（但无命中）")
        assertNull(result.lastFired, "无命中 → last_fired null")
        assertEquals(0, result.fired30d, "无命中 → fired_30d 0")
        assertTrue(result.firedDays.isEmpty(), "无命中 → firedDays 空")
    }

    // ==================== 路径 L：标签候选池扫描现算 ====================

    @Test
    fun `testEvaluate LClassLabelTrueWithinWindow`() {
        // given: 候选池（五池并集∪龙头，LabelEvaluationService 现算结果）含 600000 于 d3 命中「反包」(REBREAK)
        // → within_days=3 放宽 [D−2, D]：D=d3/d4/d5 均判真，D=d1/d2 不判真
        val labels = mapOf("600000" to mapOf(d3 to StockActionLabel.REBREAK))
        val signalWindow = SignalWindow(emptyMap(), emptyMap(), emptyList(), emptyMap())

        // when: L 类 sentiment_cycle op=label value=反包 within_days=3
        val cond = ParsedCondition(
            condId = "buy_0",
            side = "BUY",
            source = "sentiment_cycle",
            op = "label",
            value = "反包",
            cls = ConditionClass.L,
            withinDays = 3,
        )
        val result = evaluator.evaluate(cond, ctx(signalWindow, universe = setOf("600000"), labelService = mockLabelService(labels)))

        // then: READY + 命中（labelFor 结果经候选池映射回条件）
        assertEquals(ConditionClass.L, result.cls, "L 类标识")
        assertEquals(ReadyState.READY, result.ready, "候选池扫描现算 → READY")
        assertEquals(d5, result.lastFired, "最近触发日=放宽窗口内最后命中日 d5（[d3,d5] 内 d3 命中）")
        assertEquals(3, result.fired30d, "d3/d4/d5 三日判真")
        assertEquals(listOf(d3, d4, d5), result.firedDays, "触发流水（flow 数据源）")
    }

    @Test
    fun `testEvaluate LClassUniverseStockNotInCandidatePoolNeverFires`() {
        // given: universe 含 600000/600001，候选集（labels map）仅 600000 且当日标签=大肉（≠反包）
        // → 600001 候选集外永不触发（L5），600000 无目标标签 → 全窗口零命中
        val labels = mapOf("600000" to mapOf(d3 to StockActionLabel.BIG_MEAT))
        val signalWindow = SignalWindow(emptyMap(), emptyMap(), emptyList(), emptyMap())
        val cond = ParsedCondition(
            condId = "buy_0",
            side = "BUY",
            source = "sentiment_cycle",
            op = "label",
            value = "反包",
            cls = ConditionClass.L,
            withinDays = 3,
        )

        // when
        val result = evaluator.evaluate(
            cond,
            ctx(signalWindow, universe = setOf("600000", "600001"), labelService = mockLabelService(labels)),
        )

        // then: READY（候选集外票该条件就绪但永不触发，不误导）+ 零命中
        assertEquals(ReadyState.READY, result.ready, "L 类候选集外票 ready=READY 但永不触发（L5）")
        assertNull(result.lastFired, "无目标标签命中 → last_fired null")
        assertEquals(0, result.fired30d, "零命中")
        assertTrue(result.firedDays.isEmpty(), "零命中")
    }

    // ==================== B/C 类：ready=PENDING 只出 data_state ====================

    @Test
    fun `testEvaluate BClassReadyPendingOnly`() {
        // given: B 类（单股时序现算，price_action/volume 等）——挂 b 期，本期只标记
        val cond = ParsedCondition(
            condId = "buy_1",
            side = "BUY",
            source = "price_action",
            op = "new_high",
            value = 20,
            cls = ConditionClass.B,
        )
        val signalWindow = SignalWindow(emptyMap(), emptyMap(), emptyList(), emptyMap())

        // when
        val result = evaluator.evaluate(cond, ctx(signalWindow))

        // then: PENDING + 不求值（last_fired null / fired_30d 0 / firedDays 空）
        assertEquals(ConditionClass.B, result.cls, "B 类标识")
        assertEquals(ReadyState.PENDING, result.ready, "B 类本期只出标记，不求值不误导")
        assertNull(result.lastFired, "B 类不求值 → last_fired null")
        assertEquals(0, result.fired30d, "B 类不求值 → fired_30d 0")
        assertTrue(result.firedDays.isEmpty(), "B 类不产 flow 行")
    }

    @Test
    fun `testEvaluate CClassReadyPendingDataStateOnly`() {
        // given: C 类（跨股聚合，limit_ecology 梯队排名/板块聚合）——本期只出数据就绪度
        val signalWindow = SignalWindow(
            signalDaily = mapOf("600000" to listOf(signalRow("600000", d5, 2))),
            marketDaily = mapOf(d5 to marketRow(d5)),
            sectorDaily = listOf(sectorRow(d5)),
            stockHistory = mapOf(),
        )
        val cond = ParsedCondition(
            condId = "sell_0",
            side = "SELL",
            source = "limit_ecology",
            op = "yst_premium",
            value = -2,
            cls = ConditionClass.C,
        )

        // when
        val result = evaluator.evaluate(cond, ctx(signalWindow))

        // then: PENDING + data_state（表存在性+latest+coverage，C 类本期唯一有意义输出）
        assertEquals(ConditionClass.C, result.cls, "C 类标识")
        assertEquals(ReadyState.PENDING, result.ready, "C 类本期只标记不就绪")
        assertNull(result.lastFired, "C 类不求值 → last_fired null")
        assertEquals(0, result.fired30d, "C 类不求值 → fired_30d 0")
        assertTrue(result.firedDays.isEmpty(), "C 类不产 flow 行")
        assertTrue(result.dataState.containsKey("signal_daily"), "data_state 含 signal_daily 就绪度")
        assertTrue(result.dataState.containsKey("market_daily"), "data_state 含 market_daily 就绪度")
        assertTrue(result.dataState.containsKey("sector_daily"), "data_state 含 sector_daily 就绪度")
    }

    // ==================== LabelEvaluationCache：同日复用（compute 只调一次） ====================

    @Test
    fun `testLabelCache sameDayReuseComputeOnlyOnce`() {
        // given: 真实缓存（ConcurrentHashMap 窗口末日为 key）
        val cache = LabelEvaluationCache()
        val endDate = d5
        var computeCalls = 0
        val loader: (LocalDate) -> LabelResult = {
            computeCalls++
            LabelResult(it, emptyMap(), CandidateScope("五池并集∪龙头", 0, 0, null, null, null))
        }

        // when: 同日两次请求（多策略复用同一份 / 同日多次刷新控制台）
        cache.getOrLoad(endDate, loader)
        cache.getOrLoad(endDate, loader)

        // then: compute 只调一次（读 3 表窗口 + 候选集派生的昂贵计算不做第二次）
        assertEquals(1, computeCalls, "同日复用：compute 只调一次，不重复扫")
    }

    @Test
    fun `testLabelCache differentEndDateRecompute`() {
        // given: 真实缓存
        val cache = LabelEvaluationCache()
        var computeCalls = 0
        val loader: (LocalDate) -> LabelResult = {
            computeCalls++
            LabelResult(it, emptyMap(), CandidateScope("五池并集∪龙头", 0, 0, null, null, null))
        }

        // when: 窗口末日变化（跨交易日）→ 失效重算
        cache.getOrLoad(d5, loader)
        cache.getOrLoad(d4, loader)

        // then: compute 两次（不同窗口末日 → 各自构建）
        assertEquals(2, computeCalls, "跨交易日窗口末日变化 → 重算")
    }

    @Test
    fun `testLabelCache invalidateForcesRecompute`() {
        // given: 真实缓存
        val cache = LabelEvaluationCache()
        var computeCalls = 0
        val loader: (LocalDate) -> LabelResult = {
            computeCalls++
            LabelResult(it, emptyMap(), CandidateScope("五池并集∪龙头", 0, 0, null, null, null))
        }

        // when: 显式 invalidate（盘中 archive 归档后 / SignalPrecomputeJob 增量后）
        cache.getOrLoad(d5, loader)
        cache.invalidate(d5)
        cache.getOrLoad(d5, loader)

        // then: invalidate 后同日重算
        assertEquals(2, computeCalls, "显式 invalidate → 重算")
    }

    // ==================== 下游契约测试（ConditionResult → GET /conditions、GET /flow 传播完整性） ====================

    @Test
    fun `testEvaluate downstreamContractResultRequiredFieldsPropagated`() {
        // given: A 类命中（window 内 d2 命中）
        val historyRepo = Mockito.mock(StockHistoryRepository::class.java)
        val signalRepo = Mockito.mock(SignalDailyRepository::class.java)
        val marketRepo = Mockito.mock(MarketDailyRepository::class.java)
        val sectorRepo = Mockito.mock(SectorDailyRepository::class.java)
        Mockito.`when`(historyRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(
            listOf(bar("600000", d1, 0), bar("600000", d2, 3, isLimitUp = true), bar("600000", d3, 0)),
        )
        Mockito.`when`(signalRepo.findByCodeAndTradeDateBetween("600000", d1, d5)).thenReturn(emptyList())
        Mockito.`when`(marketRepo.findByTradeDate(Mockito.any())).thenReturn(null)
        Mockito.`when`(sectorRepo.findByTradeDateOrderByBoardAsc(Mockito.any())).thenReturn(emptyList())
        val cond = ParsedCondition("buy_0", "BUY", "limit_up_streak", "between", listOf(3, 7), ConditionClass.A)

        // when
        val result = evaluator.evaluate(cond, ctx(assembleWindow(historyRepo, signalRepo, marketRepo, sectorRepo, listOf("600000"))))

        // then: 下游消费者（GET /conditions 条件表 + GET /flow 流水）必需的字段全部有确定值——
        // 断言集合=下游必需字段集合（cls/ready/firedDays/dataState 必填有值；lastFired 语义 null 允许）
        assertEquals(ConditionClass.A, result.cls, "cls 供条件表类别徽标（A/L/B/C）")
        assertEquals(ReadyState.READY, result.ready, "ready 供数据就绪度展示")
        assertEquals(d2, result.lastFired, "lastFired 供最近触发日")
        assertEquals(listOf(d2), result.firedDays, "firedDays 供 GET /flow 流水数据源")
        assertTrue(result.dataState.containsKey("signal_daily") || result.dataState.isEmpty(), "dataState 供数据就绪度面板")
    }
}
