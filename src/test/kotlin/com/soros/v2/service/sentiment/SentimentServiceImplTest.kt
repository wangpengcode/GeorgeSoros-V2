package com.soros.v2.service.sentiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.soros.v2.config.SentimentProperties
import com.soros.v2.domain.PoolAction
import com.soros.v2.domain.PoolSide
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.sentiment.dto.SentimentConfirmRequest
import com.soros.v2.service.sentiment.dto.SentimentListsRequest
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * §4.9/§11.1 SentimentServiceImpl 业务契约测试（确认优先 / 名单校验与留痕 / 单日映射）。
 *
 * 契约（类 KDoc / §4.9 / §11.1）：
 * - confirm：人工确认值优先于建议值展示（big_cycle 覆盖 big_cycle_sug 展示；建议值保留对照）；
 *   周期不存在 → BusinessException（404 语义）；
 * - updateLists：非 ST 校验（stock_info.is_st=true 直接 BusinessException，ST 隔离铁律）；
 *   增删后 lists_manual_json 留痕 {side,action,code,name,reason,at} + 重算 big_meat_count/big_face_count；
 * - getCycle：Entity → SentimentCycleResponse 全字段映射（JSONB 数组解析为显式 DTO）。
 *
 * 构造：注入 mock Repository（断言即业务规格，未改），SentimentClassifier 用真实组件（口径单点）。
 */
class SentimentServiceImplTest {

    private val day = LocalDate.of(2026, 9, 30)
    private val mapper = ObjectMapper()

    private lateinit var sentimentRepo: SentimentCycleRepository
    private lateinit var stockInfoRepo: StockInfoRepository
    private lateinit var stockHistoryRepo: StockHistoryRepository
    private lateinit var dragonRepo: DragonCycleRepository
    private lateinit var service: SentimentServiceImpl

    @BeforeEach
    fun setUp() {
        sentimentRepo = Mockito.mock(SentimentCycleRepository::class.java)
        stockInfoRepo = Mockito.mock(StockInfoRepository::class.java)
        stockHistoryRepo = Mockito.mock(StockHistoryRepository::class.java)
        dragonRepo = Mockito.mock(DragonCycleRepository::class.java)
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(null)
        service = SentimentServiceImpl(
            sentimentCycleRepository = sentimentRepo,
            stockInfoRepository = stockInfoRepo,
            stockHistoryRepository = stockHistoryRepo,
            dragonCycleRepository = dragonRepo,
            classifier = SentimentClassifier(SentimentProperties()),
        )
    }

    /** 最小业务行（派生列默认值，各测试覆盖写关键字段） */
    private fun row(tradeDate: LocalDate = day): SentimentCycle = SentimentCycle().apply {
        this.tradeDate = tradeDate
    }

    /** 大肉名单 JSON 数组（change_pct 用 BigDecimal 保精度，命名字典 §17.6 键名） */
    private fun meatJson(vararg codes: String): ArrayNode {
        val arr = mapper.createArrayNode()
        for (code in codes) {
            arr.add(
                mapper.createObjectNode()
                    .put("code", code)
                    .put("name", "测试股")
                    .put("change_pct", BigDecimal("6.50"))
                    .put("limit_up_streak", 2),
            )
        }
        return arr
    }

    private fun nonStStock(code: String, name: String) = StockInfo().apply {
        this.code = code
        this.name = name
        this.isSt = false
    }

    /** 崩塌池名单 JSON 数组（键过命名字典：code/name/limit_down_streak） */
    private fun collapseJson(vararg codes: String): ArrayNode {
        val arr = mapper.createArrayNode()
        for (code in codes) {
            arr.add(
                mapper.createObjectNode()
                    .put("code", code)
                    .put("name", "测试股")
                    .put("limit_down_streak", 2),
            )
        }
        return arr
    }

    /** 个股日线 bar（动作标签时间线用） */
    private fun bar(
        code: String,
        date: LocalDate,
        isLimitUp: Boolean = false,
        changePct: BigDecimal = BigDecimal("0.00"),
    ) = StockHistory().apply {
        this.code = code
        this.tradeDate = date
        this.close = BigDecimal("10.0000")
        this.changePct = changePct
        this.isLimitUp = isLimitUp
        this.limitUpStreak = 0
        this.isLimitDown = false
        this.limitDownStreak = 0
    }

    // ==================== confirm：人工值优先 ====================

    @Test
    fun `testConfirm manualValueOverridesSuggestionInResponse`() {
        // given: 已存在行 big_cycle_sug=4（建议值）；人工确认 big_cycle=3
        val existing = row().apply {
            bigCycleSug = 4
            smallCycleSug = 3
        }
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(existing)

        // when: confirm
        val resp = service.confirm(day, SentimentConfirmRequest(bigCycle = 3, smallCycle = 2, statusText = "主升"))

        // then: 展示取人工值 3/2（优先于建议值），status_text 人工终定
        assertEquals(3, resp.bigCycle, "big_cycle 展示=人工确认值 3")
        assertEquals(2, resp.smallCycle, "small_cycle 展示=人工确认值 2")
        assertEquals("主升", resp.statusText, "status_text 人工终定")
        assertEquals(4, resp.bigCycleSug, "big_cycle_sug 建议值保留对照")
    }

    @Test
    fun `testConfirm cycleNotFoundThrowsBusinessException`() {
        // given: 无该交易日行（404 语义）
        // when & then: BusinessException 向上（HTTP 404 由全局处理器映射）
        assertThrows(BusinessException::class.java) {
            service.confirm(day, SentimentConfirmRequest(bigCycle = 3))
        }
    }

    @Test
    fun `testConfirm nullRequestFieldsLeaveUnchanged`() {
        // given: 已有行 big_cycle=null；body 全 null（不修改任何字段）
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(row().apply { bigCycle = null; smallCycle = null })

        // when
        val resp = service.confirm(day, SentimentConfirmRequest())

        // then: 不覆盖既有值（null=不修改语义）
        assertNull(resp.bigCycle, "big_cycle 未修改保持 null")
        assertNull(resp.smallCycle, "small_cycle 未修改保持 null")
    }

    // ==================== updateLists：非 ST 校验 + 留痕 + 重算 count ====================

    @Test
    fun `testUpdateLists addStCodeRejectedWithBusinessException`() {
        // given: 000587 为 ST（stock_info.is_st=true，ST 隔离铁律）
        val st = StockInfo().apply { code = "000587"; isSt = true }
        Mockito.`when`(stockInfoRepo.findByCode("000587")).thenReturn(st)

        // when & then: 直接拒绝，不落留痕
        assertThrows(BusinessException::class.java) {
            service.updateLists(day, SentimentListsRequest(side = PoolSide.MEAT, action = PoolAction.ADD, code = "000587"))
        }
    }

    @Test
    fun `testUpdateLists addToMeatLeavesManualTraceAndRecounts`() {
        // given: 已有行 big_meat_list 2 只（big_meat_count=2）；人工补入 600000（非 ST）
        val existing = row().apply {
            bigMeatList = meatJson("600000", "600036")
            bigMeatCount = 2
        }
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(existing)
        Mockito.`when`(stockInfoRepo.findByCode("600000")).thenReturn(nonStStock("600000", "浦发银行"))

        // when
        val resp = service.updateLists(day, SentimentListsRequest(side = PoolSide.MEAT, action = PoolAction.ADD, code = "600000", name = "浦发银行", reason = "系统漏判"))

        // then: big_meat_count 重算 +1；lists_manual_json 留痕 {side,action,code,name,reason,at}
        assertEquals(3, resp.bigMeatCount, "big_meat_count 重算=3（名单长度同源）")
        assertNotNull(resp.listsManualJson, "lists_manual_json 留痕非空")
        val trace = resp.listsManualJson!!.first()
        assertEquals("MEAT", trace.side, "留痕 side")
        assertEquals("ADD", trace.action, "留痕 action")
        assertEquals("600000", trace.code, "留痕 code")
        assertEquals("系统漏判", trace.reason, "留痕 reason")
        assertNotNull(trace.at, "留痕 at 时间戳非空")
    }

    @Test
    fun `testUpdateLists removeFromMeatRecountsAndTraces`() {
        // given: 已有行 big_meat_list 3 只（big_meat_count=3）；人工移除 600036
        val existing = row().apply {
            bigMeatList = meatJson("600000", "600036", "600050")
            bigMeatCount = 3
        }
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(existing)
        Mockito.`when`(stockInfoRepo.findByCode("600036")).thenReturn(nonStStock("600036", "测试股"))

        // when
        val resp = service.updateLists(day, SentimentListsRequest(side = PoolSide.MEAT, action = PoolAction.REMOVE, code = "600036", reason = "误判"))

        // then: big_meat_count 重算 -1；留痕 action=REMOVE
        assertEquals(2, resp.bigMeatCount, "big_meat_count 重算=2")
        assertEquals("REMOVE", resp.listsManualJson!!.first().action, "留痕 action=REMOVE")
    }

    // ==================== getCycle：Entity → DTO 映射 ====================

    @Test
    fun `testGetCycle mapsEntityFieldsToDto`() {
        // given: 已存在完整行（含 dragon_json/大肉名单）
        val existing = row().apply {
            maxStreak = 6
            val dragonObj = mapper.createObjectNode().apply {
                put("code", "600000")
                put("name", "测试龙")
                put("limit_up_streak", 6)
                put("board", "MAIN")
                putArray("industry").add("软件服务")
            }
            dragonJson = mapper.createArrayNode().add(dragonObj)
            bigMeatList = meatJson("600000")
            bigMeatCount = 1
            dataCoverage = com.soros.v2.domain.DataCoverage.FULL
        }
        Mockito.`when`(sentimentRepo.findByTradeDate(day)).thenReturn(existing)

        // when
        val resp = service.getCycle(day)

        // then: 全字段映射 + JSONB 数组解析为显式 DTO（键过命名字典 §17.6）
        assertNotNull(resp, "有行返回 DTO")
        assertEquals(day, resp!!.tradeDate, "trade_date")
        assertEquals(6, resp.maxStreak, "max_streak Short→Int")
        assertEquals("600000", resp.dragonJson!!.first().code, "dragon_json 数组解析")
        assertEquals(BigDecimal("6.50"), resp.bigMeatList!!.first().changePct, "big_meat_list 解析 change_pct")
        assertEquals("FULL", resp.dataCoverage, "data_coverage 字符串")
    }

    @Test
    fun `testGetCycle noRowReturnsNull`() {
        // given: 无该交易日行
        // when & then
        assertNull(service.getCycle(day), "无行返回 null")
    }

    // ==================== getStockActions：反核止跌口径（M3 复用 collapseReboundCodes） ====================

    @Test
    fun `testGetStockActions collapseLimitUpLabeledReboundStop`() {
        // given: 崩塌池含 600000（当日 collapse_list），该股当日涨停 → 反核止跌（与 /terms 同口径）
        val prevDay = day.minusDays(1)
        val bars = listOf(
            bar("600000", prevDay, isLimitUp = false),
            bar("600000", day, isLimitUp = true, changePct = BigDecimal("9.98")),
        )
        Mockito.`when`(stockHistoryRepo.findByCodeAndTradeDateBetween("600000", prevDay, day)).thenReturn(bars)
        Mockito.`when`(dragonRepo.findByCodeAndEndDateIsNull("600000")).thenReturn(null)
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(prevDay, day))
            .thenReturn(
                listOf(
                    row().apply { tradeDate = prevDay },
                    row().apply { tradeDate = day; collapseList = collapseJson("600000") },
                ),
            )

        // when
        val resp = service.getStockActions("600000", prevDay, day)

        // then: 崩塌池∩今日涨停 → 反核止跌（label 值域单点 StockActionLabel.REBOUND_STOP.label）
        assertEquals(1, resp.actions.size, "仅当日涨停 bar 标注")
        assertEquals("反核止跌", resp.actions.first().label, "崩塌池涨停 → 反核止跌")
        assertEquals(day, resp.actions.first().tradeDate, "trade_date=涨停日")
    }

    @Test
    fun `testGetStockActions collapseNotLimitUpNoReboundStop`() {
        // given: 崩塌池含 600000，但当日未涨停（非反核）→ 不标反核止跌（口径漂移防线）
        val prevDay = day.minusDays(1)
        val bars = listOf(
            bar("600000", prevDay, isLimitUp = false),
            bar("600000", day, isLimitUp = false, changePct = BigDecimal("-3.00")),
        )
        Mockito.`when`(stockHistoryRepo.findByCodeAndTradeDateBetween("600000", prevDay, day)).thenReturn(bars)
        Mockito.`when`(dragonRepo.findByCodeAndEndDateIsNull("600000")).thenReturn(null)
        Mockito.`when`(sentimentRepo.findByTradeDateBetweenOrderByTradeDateAsc(prevDay, day))
            .thenReturn(
                listOf(
                    row().apply { tradeDate = prevDay },
                    row().apply { tradeDate = day; collapseList = collapseJson("600000") },
                ),
            )

        // when
        val resp = service.getStockActions("600000", prevDay, day)

        // then: 未涨停不入反核集合 → 无反核止跌标签（也非其他标签，change_pct 未达大肉/大面阈值）
        assertTrue(resp.actions.isEmpty(), "非涨停不标反核止跌（与 /terms 同口径）")
    }
}
