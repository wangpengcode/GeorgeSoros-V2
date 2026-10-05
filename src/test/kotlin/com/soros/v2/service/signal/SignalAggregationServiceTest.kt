package com.soros.v2.service.signal

import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.entity.MarketDaily
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §12.4 / §19.11.1 信号聚合服务契约测试（SignalAggregationService；mock Repository 依赖）。
 *
 * 本测试是实现的唯一规格（实现侧为 TODO 空壳，当前红；Implementer 填充后应全绿）。
 * 各用例对应 §19.11.1 / §4.8 公式：
 * - adv/dec 按不复权 change_pct 计：>0 涨、<0 跌、=0 两边不计
 * - zhaban 日线近似 = high ≥ round(昨收×(1+阈值),2) 且非涨停；MAIN=0.10 / GEM、STAR=0.20
 * - yst_promotion：停牌计入分母视为未晋级（决策 4 口径区分）
 * - yst_limit_premium：停牌剔除分母（无价不可算）
 * - yst_face_count 直读 sentiment_cycle(t-1).big_face_count
 * - 梯队排名 = 按 limit_up_streak 降序 dense（并列同名次）
 *
 * Repo 查询契约（实现必须走以下查询，测试据此打桩）：
 * - 当日 bars：StockHistoryRepository.findByTradeDateBetween(date, date)
 * - 昨收：StockHistoryRepository.findByCodeAndTradeDate(code, prevDate)
 * - 板块/市场板：StockInfoRepository.findByCodeIn(codes)
 * - 昨日行情：MarketDailyRepository.findByTradeDate(prevDate)
 * - 昨日情绪：SentimentCycleRepository.findByTradeDate(prevDate)
 * - 当日涨停股：StockHistoryRepository.findByTradeDateAndIsLimitUpTrue(date)
 */
class SignalAggregationServiceTest {

    private val date = LocalDate.of(2026, 9, 30)
    private val prevDate = LocalDate.of(2026, 9, 29)

    private val stockHistoryRepo = Mockito.mock(StockHistoryRepository::class.java)
    private val stockInfoRepo = Mockito.mock(StockInfoRepository::class.java)
    private val sentimentCycleRepo = Mockito.mock(SentimentCycleRepository::class.java)
    private val marketDailyRepo = Mockito.mock(MarketDailyRepository::class.java)
    private val mapper = ObjectMapper()

    private fun service() = SignalAggregationServiceImpl(
        stockHistoryRepository = stockHistoryRepo,
        stockInfoRepository = stockInfoRepo,
        sentimentCycleRepository = sentimentCycleRepo,
        marketDailyRepository = marketDailyRepo,
    )

    // ==================== 构造辅助 ====================

    private fun bar(
        code: String,
        changePct: String,
        high: String = "0.00",
        close: String = "0.00",
        isLimitUp: Boolean = false,
        limitUpStreak: Short = 0,
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.changePct = BigDecimal(changePct)
        this.high = BigDecimal(high)
        this.close = BigDecimal(close)
        this.isLimitUp = isLimitUp
        this.limitUpStreak = limitUpStreak
    }

    private fun prevBar(close: String) = StockHistory().apply {
        this.tradeDate = prevDate
        this.close = BigDecimal(close)
    }

    private fun info(code: String, board: String = "MAIN", industry: String? = "银行") = StockInfo().apply {
        this.code = code
        this.board = board
        this.industry = if (industry == null) null else listOf(industry)
    }

    private fun prevLimitUpListJson(entries: List<Map<String, Any>>): com.fasterxml.jackson.databind.JsonNode =
        mapper.valueToTree(entries)

    private fun limitUpEntry(code: String, streak: Int, changePct: String = "10.00") = mapOf(
        "code" to code,
        "name" to "测试$code",
        "change_pct" to BigDecimal(changePct),
        "limit_up_streak" to streak,
        "industry" to "银行",
    )

    // ==================== 正常流程 ====================

    /** §19.11.1 口径：adv/dec 按不复权 change_pct 计，change_pct=0 两边不计 */
    @Test
    fun `testAggregateMarket advDec counts excludes flat zero`() {
        // given: 6 只，3 涨 1 跌 2 平
        val bars = listOf(
            bar("600001", "+5.00"),
            bar("600002", "+0.01"),
            bar("600003", "0.00"),
            bar("600004", "-2.50"),
            bar("600005", "0.00"),
            bar("600006", "+9.98", isLimitUp = true),
        )
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(bars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            bars.map { info(it.code) },
        )
        // prev 无前文 → yst_* 置空、zhaban 无法判定（无昨收）
        Mockito.`when`(marketDailyRepo.findByTradeDate(prevDate)).thenReturn(null)
        Mockito.`when`(sentimentCycleRepo.findByTradeDate(prevDate)).thenReturn(null)

        // when
        val market = service().aggregateMarket(date, prevDate)

        // then: 涨 3（600001/600002/600006）跌 1（600004）；平 2 两边不计
        assertEquals(3.toShort(), market.advCount, "adv_count=3")
        assertEquals(1.toShort(), market.decCount, "dec_count=1")
        assertEquals(1.toShort(), market.limitUpCount, "limit_up_count=1（600006 涨停）")
        assertEquals(0.toShort(), market.zhabanCount, "无昨收 → zhaban 不可判定=0")
    }

    /** §4.8/§19.11.1 炸板日线近似：high ≥ round(昨收×(1+阈值),2) 且非涨停；主板 0.10 / 双创 0.20 */
    @Test
    fun `testAggregateMarket zhabanCount boardThreshold and limitUpExcluded`() {
        // given: 主板炸板（high≥11.0）、双创不炸（high<12.0）、涨停不炸
        val bars = listOf(
            bar("600001", "+5.00", high = "11.00"),          // MAIN 昨收 10 → round(10×1.10)=11.0 触及 → 炸板
            bar("300001", "+3.00", high = "11.50"),          // GEM 昨收 10 → round(10×1.20)=12.0 未触及 → 不炸
            bar("600002", "+9.98", high = "11.00", isLimitUp = true), // 涨停 → 不炸
        )
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(bars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            listOf(info("600001", "MAIN"), info("300001", "GEM"), info("600002", "MAIN")),
        )
        bars.forEach { Mockito.`when`(stockHistoryRepo.findByCodeAndTradeDate(it.code, prevDate)).thenReturn(prevBar("10.00")) }
        Mockito.`when`(marketDailyRepo.findByTradeDate(prevDate)).thenReturn(null)
        Mockito.`when`(sentimentCycleRepo.findByTradeDate(prevDate)).thenReturn(null)

        // when
        val market = service().aggregateMarket(date, prevDate)

        // then: 仅 600001 计炸板
        assertEquals(1.toShort(), market.zhabanCount, "zhaban_count=1（600001）")
    }

    /** §19.11.1 决策 4：yst_promotion 停牌计入分母视为未晋级 */
    @Test
    fun `testAggregateMarket ystPromotion suspended counts in denominator as not promoted`() {
        // given: 昨日涨停 3 家（600001=1板、600002=2板、600003=1板）；今日 600001 晋级 2板、600002 断板、600003 停牌
        val bars = listOf(
            bar("600001", "+10.00", isLimitUp = true, limitUpStreak = 2),
            bar("600002", "+5.00"),
        )
        val prevMarket = MarketDaily().apply {
            this.tradeDate = prevDate
            this.limitUpList = prevLimitUpListJson(
                listOf(limitUpEntry("600001", 1), limitUpEntry("600002", 2), limitUpEntry("600003", 1)),
            )
        }
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(bars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            listOf(info("600001"), info("600002")),
        )
        Mockito.`when`(marketDailyRepo.findByTradeDate(prevDate)).thenReturn(prevMarket)
        Mockito.`when`(sentimentCycleRepo.findByTradeDate(prevDate)).thenReturn(null)

        // when
        val market = service().aggregateMarket(date, prevDate)

        // then: total = 晋级1/昨日3 = 33.33；1to2 = 600001晋级/(600001+停牌600003) = 50.0；2to3 = 0/1 = 0.0
        val promo = market.ystPromotion
        assertEquals(33.33, promo!!.get("total").asDouble(), 0.5, "yst_promotion.total=33.33")
        assertEquals(50.0, promo.get("by_level").get("1to2").asDouble(), 0.5, "1to2 晋级率 50%（停牌计入分母）")
        assertEquals(0.0, promo.get("by_level").get("2to3").asDouble(), 0.5, "2to3 晋级率 0")
    }

    /** §19.11.1 决策 4：yst_limit_premium 停牌剔除分母（无价不可算，与晋级率口径区分） */
    @Test
    fun `testAggregateMarket ystLimitPremium suspended excluded from denominator`() {
        // given: 昨日涨停 3 家；今日 600001=+10%、600002=+5%、600003 停牌
        val bars = listOf(
            bar("600001", "+10.00"),
            bar("600002", "+5.00"),
        )
        val prevMarket = MarketDaily().apply {
            this.tradeDate = prevDate
            this.limitUpList = prevLimitUpListJson(
                listOf(limitUpEntry("600001", 1), limitUpEntry("600002", 1), limitUpEntry("600003", 1)),
            )
        }
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(bars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            listOf(info("600001"), info("600002")),
        )
        Mockito.`when`(marketDailyRepo.findByTradeDate(prevDate)).thenReturn(prevMarket)
        Mockito.`when`(sentimentCycleRepo.findByTradeDate(prevDate)).thenReturn(null)

        // when
        val market = service().aggregateMarket(date, prevDate)

        // then: (10+5)/2 = 7.50（停牌 600003 剔除分母，不按 0 计）
        assertBigDecimal("7.50", market.ystLimitPremium, "yst_limit_premium=(10+5)/2=7.50")
    }

    /** §19.11.1：yst_face_count 直读 sentiment_cycle(t-1).big_face_count */
    @Test
    fun `testAggregateMarket ystFaceCount reads sentimentCycle prev day bigFaceCount`() {
        // given: 昨日 sentiment_cycle.big_face_count=7
        val prevSentiment = SentimentCycle().apply {
            this.tradeDate = prevDate
            this.bigFaceCount = 7
        }
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(emptyList())
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(emptyList())
        Mockito.`when`(marketDailyRepo.findByTradeDate(prevDate)).thenReturn(null)
        Mockito.`when`(sentimentCycleRepo.findByTradeDate(prevDate)).thenReturn(prevSentiment)

        // when
        val market = service().aggregateMarket(date, prevDate)

        // then: 直读 7
        assertEquals(7.toShort(), market.ystFaceCount, "yst_face_count=7")
    }

    /** §12.4 C 类：ladder_rank / sector_ladder_rank 并列同名次（dense） */
    @Test
    fun `testAssignLadderRanks dense ties share rank both market and sector`() {
        // given: 涨停 5 家（3板×2、2板×2、1板×1），板块 银行{600001,600002,600005} 电子{600003,600004}
        val limitUpBars = listOf(
            bar("600001", "+10.00", isLimitUp = true, limitUpStreak = 3),
            bar("600002", "+10.00", isLimitUp = true, limitUpStreak = 3),
            bar("600003", "+20.00", isLimitUp = true, limitUpStreak = 2),
            bar("600004", "+20.00", isLimitUp = true, limitUpStreak = 2),
            bar("600005", "+10.00", isLimitUp = true, limitUpStreak = 1),
        )
        Mockito.`when`(stockHistoryRepo.findByTradeDateAndIsLimitUpTrue(date)).thenReturn(limitUpBars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            listOf(info("600001", industry = "银行"), info("600002", industry = "银行"),
                info("600003", industry = "电子"), info("600004", industry = "电子"), info("600005", industry = "银行")),
        )

        // when
        val ranks = service().assignLadderRanks(date).associateBy { it.code }

        // then: 全市场 dense 排名（streak 3→1、2→2、1→3）
        assertEquals(1.toShort(), ranks["600001"]!!.ladderRank, "600001 ladder=1")
        assertEquals(1.toShort(), ranks["600002"]!!.ladderRank, "600002 ladder=1（与 600001 并列）")
        assertEquals(2.toShort(), ranks["600003"]!!.ladderRank, "600003 ladder=2")
        assertEquals(2.toShort(), ranks["600004"]!!.ladderRank, "600004 ladder=2（与 600003 并列）")
        assertEquals(3.toShort(), ranks["600005"]!!.ladderRank, "600005 ladder=3")

        // then: 板块内 dense 排名（银行 3板并列第1、1板第2；电子 2板并列第1）
        assertEquals(1.toShort(), ranks["600001"]!!.sectorLadderRank, "600001 银行内第1")
        assertEquals(1.toShort(), ranks["600002"]!!.sectorLadderRank, "600002 银行内第1（并列）")
        assertEquals(2.toShort(), ranks["600005"]!!.sectorLadderRank, "600005 银行内第2")
        assertEquals(1.toShort(), ranks["600003"]!!.sectorLadderRank, "600003 电子内第1")
        assertEquals(1.toShort(), ranks["600004"]!!.sectorLadderRank, "600004 电子内第1（并列）")
    }

    /** §19.11.1 决策 3：sector_daily.avg_chg_pct_all = 板块全成员平均涨幅%（涨停名单 avg_chg_pct 口径不同） */
    @Test
    fun `testAggregateSectors avgChgPctAll includes all members not just limitUp`() {
        // given: 银行{600001=+5涨停2板, 600002=+3未涨停, 600003=+10涨停1板} 电子{600004=−2}
        val bars = listOf(
            bar("600001", "+5.00", isLimitUp = true, limitUpStreak = 2),
            bar("600002", "+3.00"),
            bar("600003", "+10.00", isLimitUp = true, limitUpStreak = 1),
            bar("600004", "-2.00"),
        )
        Mockito.`when`(stockHistoryRepo.findByTradeDateBetween(date, date)).thenReturn(bars)
        Mockito.`when`(stockInfoRepo.findByCodeIn(Mockito.anyCollection())).thenReturn(
            listOf(info("600001", industry = "银行"), info("600002", industry = "银行"),
                info("600003", industry = "银行"), info("600004", industry = "电子")),
        )

        // when
        val sectors = service().aggregateSectors(date).associateBy { it.board }

        // then: 银行 limitUpCount=2、max_streak=2、avg_chg_pct=(5+10)/2=7.5、avg_chg_pct_all=(5+3+10)/3=6.0
        val bank = sectors["银行"]!!
        assertEquals(2.toShort(), bank.limitUpCount, "银行 limit_up_count=2")
        assertEquals(2.toShort(), bank.maxStreak, "银行 max_streak=2")
        assertBigDecimal("7.5", bank.avgChgPct, "银行 avg_chg_pct=(5+10)/2=7.5")
        assertBigDecimal("6.0", bank.avgChgPctAll, "银行 avg_chg_pct_all=(5+3+10)/3=6.0（含未涨停成员）")

        // then: 电子 无涨停 → limit_up_count=0、avg_chg_pct_all=−2.0（全成员均跌也记）
        val elec = sectors["电子"]!!
        assertEquals(0.toShort(), elec.limitUpCount, "电子 limit_up_count=0")
        assertBigDecimal("-2.0", elec.avgChgPctAll, "电子 avg_chg_pct_all=-2.0")
    }

    // ==================== 纯函数 ====================

    /** zhaban 纯函数：主板 0.10 / 双创 0.20 / 涨停不炸 / 无昨收不炸 */
    @Test
    fun `testIsZhaban pure mainBoard 010 gemStar 020 limitUp and nullPrev false`() {
        val svc = service()

        // MAIN 昨收 10 → round(10×1.10,2)=11.00；high=11.00 触及 → 炸板
        assertTrue(svc.isZhaban(bar("600001", "+5.00", high = "11.00"), "MAIN", BigDecimal("10.00")), "主板 high=11.00 触及涨停价 → 炸板")
        // MAIN high=10.99 未触及 → 不炸
        assertFalse(svc.isZhaban(bar("600001", "+5.00", high = "10.99"), "MAIN", BigDecimal("10.00")), "主板 high=10.99 未触及 → 不炸")
        // GEM 昨收 10 → round(10×1.20,2)=12.00；high=11.99 未触及 → 不炸
        assertFalse(svc.isZhaban(bar("300001", "+3.00", high = "11.99"), "GEM", BigDecimal("10.00")), "GEM high=11.99 < 12.00 → 不炸")
        // GEM high=12.00 触及 → 炸板
        assertTrue(svc.isZhaban(bar("300001", "+3.00", high = "12.00"), "GEM", BigDecimal("10.00")), "GEM high=12.00 触及 20% → 炸板")
        // 涨停（is_limit_up=true）即使触及也不炸
        assertFalse(svc.isZhaban(bar("600002", "+9.98", high = "11.00", isLimitUp = true), "MAIN", BigDecimal("10.00")), "涨停不炸")
        // 无昨收 → 不可判定 → 不炸
        assertFalse(svc.isZhaban(bar("600002", "+5.00", high = "11.00"), "MAIN", null), "无昨收 → 不炸")
    }

    /** BigDecimal 数值断言（compareTo 忽略 scale，避免 "7.5" vs "7.50" 假失败） */
    private fun assertBigDecimal(expected: String, actual: BigDecimal?, label: String) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), label)
    }
}
