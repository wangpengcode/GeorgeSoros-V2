package com.soros.v2.job

import com.soros.v2.domain.BackfillStatus
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.StockHistory
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.backfill.BackfillService
import com.soros.v2.service.backfill.dto.BackfillRequest
import com.soros.v2.service.backfill.dto.BackfillStatusResponse
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FailedBar
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.util.CollectMetrics
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * CalibrationJob 契约测试（2026-10-04 校准三件套落地；Fake Python/Backfill + Mockito mock 仓储）。
 *
 * 契约（类 KDoc）：
 * - 回填 RUNNING → 整轮跳过（不拉取、不标记——避免与 COPY/merge 竞争写放大）；
 * - 选中未校准 code → 校准窗 = 未校准行的【最新 ≤30 自然日】（确定式推进，分散到多天）；
 * - 逐行对拍（close ±0.1% / volume ±1% / change_pct ±0.02pp，同 CrossValidateJob 容差）：
 *   全部通过 → 仅对【实际比对过】的行置 calibrated=true + calibrated_source/at（read-modify-write 精确标记）；
 *   任一差异 → 全部不标记 + data_quality_log(CALIBRATION_MISMATCH) + 钉钉告警；
 * - Python 空/故障结果 → 跳过不标记（留在候选池，下轮再试），不产生质量噪音。
 */
class CalibrationJobTest {

    /** Fake Python 客户端：记录 fetchDailyBarsBatch 调用与注入响应/故障 */
    private class FakePythonClient : PythonDataServiceClient {
        var batchResponse: DailyBarsBatchResponse = DailyBarsBatchResponse("ok")
        var batchCalls = 0
        val batchRequests = mutableListOf<DailyBarsBatchRequest>()

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
            batchCalls++
            batchRequests.add(request)
            return batchResponse
        }
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    /** Fake 回填服务：仅 status() 有语义 */
    private class FakeBackfillService(private val status: BackfillStatus) : BackfillService {
        override fun start(request: BackfillRequest): BackfillStatusResponse =
            BackfillStatusResponse(status)
        override fun status(): BackfillStatusResponse = BackfillStatusResponse(status)
    }

    /** Fake 钉钉通知：记录告警 */
    private class FakeNotifier : DingTalkNotifier {
        val notified = mutableListOf<Pair<DingTalkEvent, String>>()
        override fun notify(event: DingTalkEvent, title: String, content: String) {
            notified.add(event to content)
        }
        override fun notifyDailyDigest(digest: String) {}
    }

    private val stockHistoryRepository = Mockito.mock(StockHistoryRepository::class.java)
    private val qualityLogRepository = Mockito.mock(DataQualityLogRepository::class.java)
    private val notifier = FakeNotifier()
    private val metrics = Mockito.mock(CollectMetrics::class.java)
    private val python = FakePythonClient()

    private fun job(backfillStatus: BackfillStatus = BackfillStatus.IDLE): CalibrationJob =
        CalibrationJob(
            pythonClient = python,
            backfillService = FakeBackfillService(backfillStatus),
            stockHistoryRepository = stockHistoryRepository,
            dataQualityLogRepository = qualityLogRepository,
            notifier = notifier,
            metrics = metrics,
            sorosIo = Dispatchers.IO,
        )

    /** 构造库内行情行（默认与 freshBar 完全一致 → 对拍通过） */
    private fun storedBar(date: LocalDate, close: String = "10.0000", volume: Long = 1000L,
                          changePct: String = "1.00"): StockHistory =
        StockHistory().apply {
            code = "600000"
            tradeDate = date
            this.close = BigDecimal(close)
            this.volume = volume
            this.changePct = BigDecimal(changePct)
        }

    /** Python 侧新鲜拉取 bar（默认与 storedBar 完全一致） */
    private fun freshBar(date: LocalDate, close: String = "10.0000", volume: Long = 1000L,
                         changePct: String = "1.00"): DailyBar =
        DailyBar(
            date = date, code = "600000",
            open = BigDecimal("9.5000"), high = BigDecimal("10.5000"), low = BigDecimal("9.5000"),
            close = BigDecimal(close), volume = volume, amount = BigDecimal("10000000"),
            changePercent = BigDecimal(changePct), turnover = BigDecimal("1.00"), prevClose = null,
        )

    /** 打桩：600000 未校准区间 [first, last] + 校准窗 [windowStart, last] 库行 + Python 返回 */
    private fun stubHappyPath(first: LocalDate, last: LocalDate,
                              stored: List<StockHistory>, fresh: List<DailyBar>,
                              source: String = "baostock",
                              windowStart: LocalDate = first) {
        Mockito.`when`(stockHistoryRepository.findRandomUncalibratedCodes(Mockito.anyInt())).thenReturn(listOf("600000"))
        Mockito.`when`(stockHistoryRepository.findTopByCodeAndCalibratedFalseOrderByTradeDateAsc(Mockito.anyString()))
            .thenReturn(storedBar(first))
        Mockito.`when`(stockHistoryRepository.findTopByCodeAndCalibratedFalseOrderByTradeDateDesc(Mockito.anyString()))
            .thenReturn(storedBar(last))
        Mockito.`when`(stockHistoryRepository.findByCodeAndTradeDateBetween("600000", windowStart, last))
            .thenReturn(stored)
        python.batchResponse = DailyBarsBatchResponse(
            "ok", results = mapOf("600000" to StockBarsResult(source, fresh.size, fresh)),
        )
    }

    // ==================== 回填互斥 ====================

    @Test
    fun `testSkips whole round while backfill RUNNING`() {
        stubHappyPath(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 30),
            listOf(storedBar(LocalDate.of(2026, 9, 30))),
            listOf(freshBar(LocalDate.of(2026, 9, 30))))

        job(BackfillStatus.RUNNING).execute()

        assertEquals(0, python.batchCalls, "回填运行中不得发起 Python 拉取")
        Mockito.verify(stockHistoryRepository, Mockito.never()).save(Mockito.any(StockHistory::class.java))
    }

    // ==================== 全通过 → 精确标记 ====================

    @Test
    fun `testAllRowsMatch marks compared rows calibrated with source and time`() {
        val d1 = LocalDate.of(2026, 9, 29)
        val d2 = LocalDate.of(2026, 9, 30)
        stubHappyPath(d1, d2, listOf(storedBar(d1), storedBar(d2)), listOf(freshBar(d1), freshBar(d2)))

        job().execute()

        val captor: ArgumentCaptor<StockHistory> = ArgumentCaptor.forClass(StockHistory::class.java)
        Mockito.verify(stockHistoryRepository, Mockito.times(2)).save(captor.capture())
        for (saved in captor.allValues) {
            assertTrue(saved.calibrated, "比对通过的行必须置 calibrated=true")
            assertEquals("BAOSTOCK", saved.calibratedSource, "校准源如实归因（枚举大写）")
            assertTrue(saved.calibratedAt != null && !saved.calibratedAt!!.isAfter(Instant.now()))
        }
        Mockito.verify(qualityLogRepository, Mockito.never()).save(Mockito.any(DataQualityLog::class.java))
        assertTrue(notifier.notified.isEmpty(), "全通过不发告警")
    }

    @Test
    fun `testRowsOutsideComparisonStayUncalibrated`() {
        // 库行 3 根（9-26 周六等 Python 不返回的行），Python 只回 2 根 → 只标记比对过的 2 根
        val d1 = LocalDate.of(2026, 9, 29)
        val d2 = LocalDate.of(2026, 9, 30)
        val d3 = LocalDate.of(2026, 9, 25)
        stubHappyPath(d3, d2,
            listOf(storedBar(d1), storedBar(d2), storedBar(d3)),
            listOf(freshBar(d1), freshBar(d2)))

        job().execute()

        val captor: ArgumentCaptor<StockHistory> = ArgumentCaptor.forClass(StockHistory::class.java)
        Mockito.verify(stockHistoryRepository, Mockito.times(2)).save(captor.capture())
        assertTrue(captor.allValues.all { it.calibrated })
        assertEquals(setOf(d1, d2), captor.allValues.map { it.tradeDate }.toSet(), "未比对行不被标记")
    }

    // ==================== 差异 → 全不标记 + 质量日志 + 告警 ====================

    @Test
    fun `testMismatch leaves uncalibrated and logs quality issue`() {
        val d1 = LocalDate.of(2026, 9, 30)
        // close 10.02 vs 10.00 → 0.2% > 0.1% 容差
        stubHappyPath(d1, d1, listOf(storedBar(d1)), listOf(freshBar(d1, close = "10.0200")))

        job().execute()

        Mockito.verify(stockHistoryRepository, Mockito.never()).save(Mockito.any(StockHistory::class.java))
        val logCaptor: ArgumentCaptor<DataQualityLog> = ArgumentCaptor.forClass(DataQualityLog::class.java)
        Mockito.verify(qualityLogRepository).save(logCaptor.capture())
        assertEquals(QualityIssueType.CALIBRATION_MISMATCH.name, logCaptor.value.issueType)
        assertTrue(logCaptor.value.detail!!.contains("close"))
        assertEquals(1, notifier.notified.size)
        assertEquals(DingTalkEvent.CALIBRATION_MISMATCH, notifier.notified[0].first)
    }

    @Test
    fun `testVolume mismatch over 1 percent detected`() {
        val d1 = LocalDate.of(2026, 9, 30)
        stubHappyPath(d1, d1, listOf(storedBar(d1)), listOf(freshBar(d1, volume = 1020L)))

        job().execute()

        Mockito.verify(stockHistoryRepository, Mockito.never()).save(Mockito.any(StockHistory::class.java))
        val logCaptor: ArgumentCaptor<DataQualityLog> = ArgumentCaptor.forClass(DataQualityLog::class.java)
        Mockito.verify(qualityLogRepository).save(logCaptor.capture())
        assertTrue(logCaptor.value.detail!!.contains("volume"), "volume 超 1% 判差异")
    }

    @Test
    fun `testChangePct within tolerance passes`() {
        val d1 = LocalDate.of(2026, 9, 30)
        // change_pct 差 0.02pp 恰在容差内（>0.02 才判差异）
        stubHappyPath(d1, d1, listOf(storedBar(d1, changePct = "1.00")),
            listOf(freshBar(d1, changePct = "1.02")))

        job().execute()

        val captor: ArgumentCaptor<StockHistory> = ArgumentCaptor.forClass(StockHistory::class.java)
        Mockito.verify(stockHistoryRepository).save(captor.capture())
        assertTrue(captor.value.calibrated)
    }

    // ==================== Python 空/故障 → 跳过不标记 ====================

    @Test
    fun `testEmpty python result skips without marking or noise`() {
        stubHappyPath(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30),
            listOf(storedBar(LocalDate.of(2026, 9, 30))), fresh = emptyList())

        job().execute()

        assertEquals(1, python.batchCalls)
        Mockito.verify(stockHistoryRepository, Mockito.never()).save(Mockito.any(StockHistory::class.java))
        Mockito.verify(qualityLogRepository, Mockito.never()).save(Mockito.any(DataQualityLog::class.java))
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `testPython error result skips without marking`() {
        val d1 = LocalDate.of(2026, 9, 30)
        stubHappyPath(d1, d1, listOf(storedBar(d1)), listOf(freshBar(d1)))
        python.batchResponse = DailyBarsBatchResponse(
            "ok", results = mapOf("600000" to StockBarsResult("akshare", 0, emptyList(), error = "yahoo: 429")),
        )

        job().execute()

        Mockito.verify(stockHistoryRepository, Mockito.never()).save(Mockito.any(StockHistory::class.java))
        Mockito.verify(qualityLogRepository, Mockito.never()).save(Mockito.any(DataQualityLog::class.java))
    }

    // ==================== 校准窗：最新 ≤30 自然日（确定式推进） ====================

    @Test
    fun `testWindow clamped to latest 30 natural days`() {
        // 未校准区间跨 2021-01-01..2026-09-30 → 拉取窗应为 2026-09-01..2026-09-30（30 自然日）
        val end = LocalDate.of(2026, 9, 30)
        stubHappyPath(
            LocalDate.of(2021, 1, 1), end,
            listOf(storedBar(end)), listOf(freshBar(end)),
            windowStart = LocalDate.of(2026, 9, 1),
        )

        job().execute()

        val request = python.batchRequests.single()
        assertEquals(LocalDate.of(2026, 9, 1).toString(), request.startDate)
        assertEquals(end.toString(), request.endDate)
    }

    // ==================== 无候选 / 无未校准行 ====================

    @Test
    fun `testNo uncalibrated codes means no fetch`() {
        Mockito.`when`(stockHistoryRepository.findRandomUncalibratedCodes(Mockito.anyInt())).thenReturn(emptyList())

        job().execute()

        assertEquals(0, python.batchCalls)
        assertFalse(notifier.notified.isNotEmpty())
    }
}
