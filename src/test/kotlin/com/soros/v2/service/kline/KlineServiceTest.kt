package com.soros.v2.service.kline

import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.StockHistory
import com.soros.v2.entity.StockInfo
import com.soros.v2.entity.TradingCalendar
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * §19.13.1 KlineServiceImpl 窗口计算/吸附/截断/chip 联查纯逻辑测试（mock repo，无 TestContainers）。
 *
 * 契约（§19.13.1 决策 2 行为矩阵 + 端点契约）：
 * - 仅 code：to=≤today 最近交易日（吸附口径 A），from=to−249 交易日（近 250 交易日窗口）；
 * - code+date：to=≤date 最近交易日吸附，from=to−249；
 * - code+from+to：显式区间（含两端）；date 与 from/to 同时传时 date 作废；
 * - code+from（无 to）→ to=最近交易日；code+to（无 from）→ from=to−249；
 * - from 早于最早 bar → 自然截断（只返回库内实际行，无幻影行）；
 * - chip 按 code+trade_date 联查 signal_daily，无行 → chip=null；
 * - code 不存在（stock_info 无此码）→ BusinessException("不存在")（→404）；from>to → BusinessException（→422）。
 *
 * 依赖注入（KlineServiceImpl 构造器，落码清单 §19.13.1）：
 *   KlineServiceImpl(stockHistoryRepository, signalDailyRepository, stockInfoRepository, tradingCalendarRepository)
 *
 * ⚠️ 空壳：KlineServiceImpl / KlineDtos / KlineMappers 尚未创建（TDD 红阶段），本文件编译失败即预期红。
 */
class KlineServiceTest {

    private lateinit var stockHistoryRepository: StockHistoryRepository
    private lateinit var signalDailyRepository: SignalDailyRepository
    private lateinit var stockInfoRepository: StockInfoRepository
    private lateinit var tradingCalendarRepository: TradingCalendarRepository
    private lateinit var service: KlineServiceImpl

    @BeforeEach
    fun setUp() {
        stockHistoryRepository = Mockito.mock(StockHistoryRepository::class.java)
        signalDailyRepository = Mockito.mock(SignalDailyRepository::class.java)
        stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        tradingCalendarRepository = Mockito.mock(TradingCalendarRepository::class.java)
        service = KlineServiceImpl(
            stockHistoryRepository,
            signalDailyRepository,
            stockInfoRepository,
            tradingCalendarRepository,
        )
    }

    // ==================== 正常流程：默认近 250 交易日窗口 ====================

    @Test
    fun `testGetKline onlyCodeResolvesDefault250TradingDayWindow`() {
        // given: 300 个交易日（升序，末位=2026-10-05），最近交易日 to=2026-10-05
        val today = LocalDate.of(2026, 10, 5)
        val days = (0 until 300).map { i -> today.minusDays((299 - i).toLong()) } // [today-299 .. today]
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(today))
        Mockito.`when`(tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(Mockito.any(), Mockito.any()))
            .thenReturn(days.map { TradingCalendar(it) })
        val expectedFrom = days[days.size - 250] // to 往前数第 250 个交易日（含 to → 250 日窗口）
        val bars = days.takeLast(250).map { d -> historyBar("600000", d) }
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.any(), Mockito.eq(today),
        )).thenReturn(bars)
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.any(), Mockito.eq(today),
        )).thenReturn(emptyList())

        // when: 仅 code → 服务按默认窗口处理
        val resp = service.getKline("600000", null, null, null)

        // then: 窗口 [today-249, today]，250 根 bar 升序
        assertEquals("600000", resp.code, "code 回传")
        assertEquals("浦发银行", resp.name, "name 来自 stock_info")
        assertEquals(250, resp.bars.size, "默认近 250 交易日窗口")
        assertEquals(days.takeLast(250).map { it }, resp.bars.map { it.tradeDate }, "bars[] 升序且贴合 250 窗口")

        // then: 落库查询以吸附后的 from=to−249 交易日、to=最近交易日
        val fromCaptor = ArgumentCaptor.forClass(LocalDate::class.java)
        Mockito.verify(stockHistoryRepository).findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), fromCaptor.capture(), Mockito.eq(today),
        )
        assertEquals(expectedFrom, fromCaptor.value, "from = to − 249 交易日（250 日窗口）")
    }

    // ==================== 正常流程：date= 终点吸附 ====================

    @Test
    fun `testGetKline dateAdsorbsToNearestTradingDayAsTo`() {
        // given: date=2026-10-03（周六，非交易日），≤date 最近交易日=2026-09-30
        val dateParam = LocalDate.of(2026, 10, 3)
        val adsorbsTo = LocalDate.of(2026, 9, 30)
        val days = (0 until 300).map { i -> adsorbsTo.minusDays((299 - i).toLong()) } // 末位=2026-09-30
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(dateParam))
            .thenReturn(TradingCalendar(adsorbsTo))
        Mockito.`when`(tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(Mockito.any(), Mockito.any()))
            .thenReturn(days.map { TradingCalendar(it) })
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.any(), Mockito.eq(adsorbsTo),
        )).thenReturn(days.takeLast(250).map { d -> historyBar("600000", d) })
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.any(), Mockito.eq(adsorbsTo),
        )).thenReturn(emptyList())

        // when: date 语义=该日为终点 → to=≤date 最近交易日吸附
        val resp = service.getKline("600000", null, null, dateParam)

        // then: 吸附到 2026-09-30，窗口 [expectedFrom, 2026-09-30]
        assertEquals(250, resp.bars.size, "近 250 日窗口")
        val toCaptor = ArgumentCaptor.forClass(LocalDate::class.java)
        Mockito.verify(tradingCalendarRepository).findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(dateParam)
        Mockito.verify(stockHistoryRepository).findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.any(), toCaptor.capture(),
        )
        assertEquals(adsorbsTo, toCaptor.value, "to = ≤date 最近交易日（吸附口径 A）")
    }

    // ==================== 正常流程：from 早于最早 bar 自然截断 ====================

    @Test
    fun `testGetKline fromEarlierThanEarliestBarTruncatedNaturally`() {
        // given: from=2021-01-01（全量按钮语义）显式，库内最早 bar=2021-10-08
        val fromParam = LocalDate.of(2021, 1, 1)
        val toParam = LocalDate.of(2026, 9, 30)
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(toParam))
        val actualBars = listOf(
            historyBar("600000", LocalDate.of(2021, 10, 8)),
            historyBar("600000", LocalDate.of(2021, 10, 11)),
        )
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(fromParam), Mockito.eq(toParam),
        )).thenReturn(actualBars)
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(fromParam), Mockito.eq(toParam),
        )).thenReturn(emptyList())

        // when: 显式 from 早于最早 bar
        val resp = service.getKline("600000", fromParam, toParam, null)

        // then: 自然截断——只含库内实际行，无幻影/补零行，bar 起始=库内最早
        assertEquals(2, resp.bars.size, "截断到实际存在 bars")
        assertEquals(
            listOf(LocalDate.of(2021, 10, 8), LocalDate.of(2021, 10, 11)),
            resp.bars.map { it.tradeDate },
            "响应 bars 从库内最早交易日开始",
        )
    }

    // ==================== 正常流程：chip 联查与 chip=null ====================

    @Test
    fun `testGetKline chipJoinedByTradeDateAndNullWhenNoSignalRow`() {
        // given: 3 根 bar，signal_daily 仅有前 2 日行（第 3 日=warm-up 前 60 日无行）
        val d1 = LocalDate.of(2026, 9, 28)
        val d2 = LocalDate.of(2026, 9, 29)
        val d3 = LocalDate.of(2026, 9, 30)
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(d3))
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(d1), Mockito.eq(d3),
        )).thenReturn(listOf(historyBar("600000", d1), historyBar("600000", d2), historyBar("600000", d3)))
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(d1), Mockito.eq(d3),
        )).thenReturn(listOf(signalChip("600000", d1), signalChip("600000", d2)))

        // when
        val resp = service.getKline("600000", d1, d3, null)

        // then: 按 trade_date join——有行 → chip 8 列映射；无行 → chip=null
        assertEquals(3, resp.bars.size, "bars 数=history 行数")
        assertNotNull(resp.bars[0].chip, "d1 有 signal_daily 行 → chip 非空")
        assertEquals(BigDecimal("92.90"), resp.bars[0].chip!!.profitRatio, "profit_ratio 映射")
        assertEquals(BigDecimal("-1.20"), resp.bars[0].chip!!.costDev, "cost_dev 映射")
        assertEquals(BigDecimal("8.10"), resp.bars[0].chip!!.c90Low, "c90_low 映射")
        assertEquals(BigDecimal("9.55"), resp.bars[0].chip!!.c90High, "c90_high 映射")
        assertEquals(BigDecimal("61.20"), resp.bars[0].chip!!.c90Conc, "c90_conc 映射")
        assertEquals(BigDecimal("8.65"), resp.bars[0].chip!!.c70Low, "c70_low 映射")
        assertEquals(BigDecimal("9.40"), resp.bars[0].chip!!.c70High, "c70_high 映射")
        assertEquals(BigDecimal("43.80"), resp.bars[0].chip!!.c70Conc, "c70_conc 映射")
        assertNotNull(resp.bars[1].chip, "d2 有 signal_daily 行 → chip 非空")
        assertNull(resp.bars[2].chip, "d3 无 signal_daily 行 → chip=null（warm-up 段）")
    }

    // ==================== 边界：date 与 from/to 同时传时 date 作废 ====================

    @Test
    fun `testGetKline dateIgnoredWhenFromToExplicit`() {
        // given: date + from + to 三参同传 → §19.13.1 裁定 date 作废 + WARN
        val fromParam = LocalDate.of(2026, 9, 1)
        val toParam = LocalDate.of(2026, 9, 30)
        val dateParam = LocalDate.of(2026, 12, 31) // 若被使用会拉一个远离 from/to 的窗口
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(toParam))
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(fromParam), Mockito.eq(toParam),
        )).thenReturn(listOf(historyBar("600000", toParam)))
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(fromParam), Mockito.eq(toParam),
        )).thenReturn(emptyList())

        // when
        val resp = service.getKline("600000", fromParam, toParam, dateParam)

        // then: 用显式 from/to，date 不参与吸附/窗口
        assertEquals(listOf(toParam), resp.bars.map { it.tradeDate }, "窗口由 from/to 决定，date 作废")
        Mockito.verify(tradingCalendarRepository, Mockito.never())
            .findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(dateParam)
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testGetKline unknownCodeThrowsBusinessException404`() {
        // given: stock_info 无此码（stock_history 也无行）→ 404 语义
        // 防御：无论实现先查存在性还是先算窗口，日历桩都不致 NPE（findByCode 为 null 才是 404 主判定）
        Mockito.`when`(stockInfoRepository.findByCode("999999")).thenReturn(null)
        Mockito.`when`(stockHistoryRepository.findTopByCodeOrderByTradeDateDesc("999999")).thenReturn(null)
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(LocalDate.of(2026, 9, 30)))
        Mockito.`when`(tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(Mockito.any(), Mockito.any()))
            .thenReturn(listOf(TradingCalendar(LocalDate.of(2026, 9, 30))))

        // when & then: BusinessException 含"不存在"（GlobalExceptionHandler → 404）
        val ex = assertThrows(BusinessException::class.java) {
            service.getKline("999999", null, null, null)
        }
        assertTrue(ex.message!!.contains("不存在"), "未知 code → 含「不存在」的 BusinessException（→404）")
    }

    @Test
    fun `testGetKline fromGreaterThanToThrowsBusinessException422`() {
        // given: 显式 from>to；防御：实现若先吸附 to 再校验，日历桩不致 NPE
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(LocalDate.of(2026, 9, 1)))

        // when & then: BusinessException（不含"不存在"→ GlobalExceptionHandler → 422）
        val ex = assertThrows(BusinessException::class.java) {
            service.getKline("600000", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1), null)
        }
        assertTrue(ex.message!!.contains("from") && ex.message!!.contains("to"), "from>to 消息指明 from/to")
    }

    // ==================== 下游契约测试（DTO 传播完整性，回归防护） ====================

    @Test
    fun `testGetKline downstreamContractBarAndChipFieldsNonNull`() {
        // given: 全字段源数据（OHLC/volume/amount/change_pct/turnover_rate + chip 8 列）
        val d = LocalDate.of(2026, 9, 30)
        Mockito.`when`(stockInfoRepository.findByCode("600000"))
            .thenReturn(stockInfo("600000", "浦发银行"))
        Mockito.`when`(tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(Mockito.any()))
            .thenReturn(TradingCalendar(d))
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(d), Mockito.eq(d),
        )).thenReturn(listOf(fullHistoryBar("600000", d)))
        Mockito.`when`(signalDailyRepository.findByCodeAndTradeDateBetween(
            Mockito.eq("600000"), Mockito.eq(d), Mockito.eq(d),
        )).thenReturn(listOf(fullSignalChip("600000", d)))

        // when: 服务构建 KlineResponse 传给下游 HTTP JSON 消费者
        val resp = service.getKline("600000", d, d, null)

        // then: 下游消费者（KlineController → 页面）必需的字段全部非 null——
        // 断言集合=下游必需字段集合，不是源 DTO 已有字段集合（防 mapper 漏设字段回归）
        val bar = resp.bars.single()
        assertNotNull(bar.tradeDate, "trade_date 为 null 会导致页面 x 轴断点")
        assertNotNull(bar.open, "open 为 null 会导致蜡烛图缺口")
        assertNotNull(bar.high, "high 为 null 会导致蜡烛图缺口")
        assertNotNull(bar.low, "low 为 null 会导致蜡烛图缺口")
        assertNotNull(bar.close, "close 为 null 会导致蜡烛图缺口")
        assertNotNull(bar.volume, "volume 为 null 会导致量能副图空白")
        assertNotNull(bar.amount, "amount 为 null 会导致金额卡空白")
        assertNotNull(bar.changePct, "change_pct 为 null 会导致涨跌着色失效")
        assertNotNull(bar.turnoverRate, "turnover_rate 为 null 会导致筹码曲线前端递推中断")
        val chip = bar.chip
        assertNotNull(chip, "有 signal_daily 行时 chip 必须非 null")
        assertNotNull(chip!!.profitRatio, "profit_ratio 为 null 会导致获利盘卡空白")
        assertNotNull(chip.costDev, "cost_dev 为 null 会导致 avg_cost 派生失效")
        assertNotNull(chip.c90Low, "c90_low 为 null 会导致 90% 带下沿缺失")
        assertNotNull(chip.c90High, "c90_high 为 null 会导致 90% 带上沿缺失")
        assertNotNull(chip.c90Conc, "c90_conc 为 null 会导致 90% 集中度卡空白")
        assertNotNull(chip.c70Low, "c70_low 为 null 会导致 70% 带下沿缺失")
        assertNotNull(chip.c70High, "c70_high 为 null 会导致 70% 带上沿缺失")
        assertNotNull(chip.c70Conc, "c70_conc 为 null 会导致 70% 集中度卡空白")
    }

    // ==================== 测试数据构造 ====================

    private fun stockInfo(code: String, name: String): StockInfo = StockInfo().apply {
        this.code = code
        this.name = name
        this.board = "MAIN"
        this.isSt = false
        this.delisted = false
    }

    private fun historyBar(code: String, tradeDate: LocalDate): StockHistory = StockHistory().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.open = BigDecimal("10.00")
        this.high = BigDecimal("10.50")
        this.low = BigDecimal("9.90")
        this.close = BigDecimal("10.20")
        this.volume = 1_000_000L
        this.amount = BigDecimal("10200000.00")
        this.changePct = BigDecimal("2.00")
        this.turnoverRate = BigDecimal("0.50")
    }

    private fun fullHistoryBar(code: String, tradeDate: LocalDate): StockHistory = StockHistory().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.open = BigDecimal("9.22")
        this.high = BigDecimal("9.49")
        this.low = BigDecimal("9.16")
        this.close = BigDecimal("9.48")
        this.volume = 147_484_820L
        this.amount = BigDecimal("1386209937.38")
        this.changePct = BigDecimal("1.28")
        this.turnoverRate = BigDecimal("0.42")
    }

    private fun signalChip(code: String, tradeDate: LocalDate): SignalDaily = SignalDaily().apply {
        this.code = code
        this.tradeDate = tradeDate
        this.profitRatio = BigDecimal("92.90")
        this.costDev = BigDecimal("-1.20")
        this.c90Low = BigDecimal("8.10")
        this.c90High = BigDecimal("9.55")
        this.c90Conc = BigDecimal("61.20")
        this.c70Low = BigDecimal("8.65")
        this.c70High = BigDecimal("9.40")
        this.c70Conc = BigDecimal("43.80")
    }

    private fun fullSignalChip(code: String, tradeDate: LocalDate): SignalDaily = signalChip(code, tradeDate)
}
