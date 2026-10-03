package com.soros.v2.job

import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.exception.PythonClientException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.util.CollectMetrics
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * §11.2 CrossValidateJob 契约测试（Fake Python + Mockito mock 仓储，直调 dailyExecute() 不测 cron）。
 *
 * 契约（类 KDoc / §11.2）：
 * - 容差：close ±0.1%（两源同 qfq）、volume ±1%（单位换算探测点）、change_pct ±0.02pp；
 *   ak 为 0 除零保护（close 恒不判差异；volume 仅 ba 非 0 判差异）；
 * - 差异落 data_quality_log(CROSS_VALIDATE_MISMATCH)，只观测不修正（不写 stock_history，Job 无该依赖）；
 * - 单批不一致率 >30% 发钉钉 CROSS_VALIDATE_MISMATCH，≤30% 不发；
 * - Python 异常/空样本 → 日志降级不中断主链。
 *
 * 容差判定走反射调私有 mismatchFields（非 suspend）；告警阈值走 dailyExecute() 全链路。
 */
class CrossValidateJobTest {

    /** Fake Python 客户端：记录 fetchDailyBarsCross 调用并支持抛异常 */
    private class FakePythonClient : PythonDataServiceClient {
        var crossResponse: CrossValidateResponse = CrossValidateResponse("ok")
        var crossCalls = 0
        var crossError: Exception? = null

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse =
            DailyBarsBatchResponse("ok")
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> = emptyList()
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse {
            crossCalls++
            crossError?.let { throw it }
            return crossResponse
        }
    }

    /** Fake 钉钉通知：记录告警（同 DailyCollectJobTest，规避 Kotlin 非空参数 + Mockito matcher 边界） */
    private class FakeNotifier : DingTalkNotifier {
        val notified = mutableListOf<Pair<DingTalkEvent, String>>()

        override fun notify(event: DingTalkEvent, title: String, content: String) {
            notified.add(event to content)
        }

        override fun notifyDailyDigest(digest: String) {
        }
    }

    private val stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
    private val qualityLogRepository = Mockito.mock(DataQualityLogRepository::class.java)
    private val notifier = FakeNotifier()
    private val metrics = Mockito.mock(CollectMetrics::class.java)

    private fun job(python: FakePythonClient = FakePythonClient()): CrossValidateJob =
        CrossValidateJob(python, stockInfoRepository, qualityLogRepository, notifier, metrics, Dispatchers.IO)

    /** 反射调私有 mismatchFields（容差判定，非 suspend） */
    private fun mismatchFields(ba: DailyBar, ak: DailyBar): List<String> {
        val method = job().javaClass.getDeclaredMethod("mismatchFields", DailyBar::class.java, DailyBar::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(job(), ba, ak) as List<String>
    }

    /** 构造一根日 K（供容差边界测试；ba/ak 各字段独立可调） */
    private fun bar(
        close: BigDecimal = BigDecimal("10.0000"),
        volume: Long = 1000L,
        changePercent: BigDecimal = BigDecimal("10.00"),
    ) = DailyBar(
        date = LocalDate.of(2026, 9, 30),
        code = "600000",
        open = BigDecimal("9.5000"),
        high = BigDecimal("10.5000"),
        low = BigDecimal("9.5000"),
        close = close,
        volume = volume,
        amount = BigDecimal("10000000.0000"),
        changePercent = changePercent,
        turnover = BigDecimal("1.00"),
        prevClose = BigDecimal("9.0000"),
    )

    // ==================== 容差边界：close ±0.1% ====================

    @Test
    fun `testMismatchFields closeAtExactToleranceNotMismatch`() {
        // given: ba=10.01 vs ak=10.00 → 比值 0.001 恰在 0.1% 边界（不大于阈值不算差异）
        val fields = mismatchFields(bar(close = BigDecimal("10.0100")), bar(close = BigDecimal("10.0000")))

        // then: 边界值不算 mismatch（容差含等号：>0.001 才判差异）
        assertFalse(fields.contains("close"), "close 恰在 0.1% 边界不判差异")
    }

    @Test
    fun `testMismatchFields closeOverToleranceMismatch`() {
        // given: ba=10.02 vs ak=10.00 → 比值 0.002 > 0.1%
        val fields = mismatchFields(bar(close = BigDecimal("10.0200")), bar(close = BigDecimal("10.0000")))

        // then: 超 0.1% 判 close 差异
        assertTrue(fields.contains("close"), "close 超 0.1% 判差异")
    }

    // ==================== 容差边界：volume ±1% ====================

    @Test
    fun `testMismatchFields volumeAtExactToleranceNotMismatch`() {
        // given: ba=1010 vs ak=1000 → 比值 0.01 恰在 1% 边界
        val fields = mismatchFields(bar(volume = 1010L), bar(volume = 1000L))

        // then: 边界不算差异（>0.01 才判）
        assertFalse(fields.contains("volume"), "volume 恰在 1% 边界不判差异")
    }

    @Test
    fun `testMismatchFields volumeOverToleranceMismatch`() {
        // given: ba=1020 vs ak=1000 → 比值 0.02 > 1%（单位换算探测点）
        val fields = mismatchFields(bar(volume = 1020L), bar(volume = 1000L))

        // then: 超 1% 判 volume 差异
        assertTrue(fields.contains("volume"), "volume 超 1% 判差异（若某源漏 ×100 会触发）")
    }

    // ==================== 容差边界：change_pct ±0.02pp ====================

    @Test
    fun `testMismatchFields changePctAtExactToleranceNotMismatch`() {
        // given: ba=10.02 vs ak=10.00 → 差 0.02pp 恰在边界
        val fields = mismatchFields(
            bar(changePercent = BigDecimal("10.02")),
            bar(changePercent = BigDecimal("10.00")),
        )

        // then: 边界不算差异（>0.02 才判）
        assertFalse(fields.contains("change_pct"), "change_pct 恰在 0.02pp 边界不判差异")
    }

    @Test
    fun `testMismatchFields changePctOverToleranceMismatch`() {
        // given: ba=10.03 vs ak=10.00 → 差 0.03pp > 0.02pp
        val fields = mismatchFields(
            bar(changePercent = BigDecimal("10.03")),
            bar(changePercent = BigDecimal("10.00")),
        )

        // then: 超 0.02pp 判 change_pct 差异
        assertTrue(fields.contains("change_pct"), "change_pct 超 0.02pp 判差异")
    }

    // ==================== ak=0 除零保护 ====================

    @Test
    fun `testMismatchFields akZeroCloseNoMismatchNoDivisionByZero`() {
        // given: ak.close=0（停牌无价），ba.close=10.00 → 除零保护
        val fields = mismatchFields(bar(close = BigDecimal("10.0000")), bar(close = BigDecimal("0.0000")))

        // then: ak=0 不判 close 差异（不除零；停牌无价不算源间差异）
        assertFalse(fields.contains("close"), "ak.close=0 除零保护，不判 close 差异")
    }

    @Test
    fun `testMismatchFields akZeroVolumeOnlyBaNonZeroMismatch`() {
        // given: ak.volume=0 且 ba.volume=1000（某源漏行）
        val fields = mismatchFields(bar(volume = 1000L), bar(volume = 0L))

        // then: 仅 ba 非 0 → 判 volume 差异；两源皆 0 → 不算差异
        assertTrue(fields.contains("volume"), "ak.volume=0 且 ba 非 0 判 volume 差异")
        val bothZero = mismatchFields(bar(volume = 0L), bar(volume = 0L))
        assertFalse(bothZero.contains("volume"), "两源 volume 皆 0 不算差异")
    }

    // ==================== 只观测不修正：差异落 data_quality_log ====================

    @Test
    fun `testDailyExecute mismatchesPersistedToQualityLogOnly`() {
        // given: 2 只有效股样本，baostock 与 akshare 同日 close 不一致（其余字段一致）
        val codes = listOf("600000", "600036")
        Mockito.`when`(stockInfoRepository.findRandomValidCodes(Mockito.anyInt())).thenReturn(codes)
        val python = FakePythonClient().apply {
            crossResponse = cvResponseFor(codes, mismatchCount = 2)
        }
        Mockito.`when`(qualityLogRepository.save(Mockito.any(DataQualityLog::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        job(python).dailyExecute()

        // then: 差异逐条落 data_quality_log（CROSS_VALIDATE_MISMATCH，source=cross-validate）
        val captor = ArgumentCaptor.forClass(DataQualityLog::class.java)
        Mockito.verify(qualityLogRepository, Mockito.times(2)).save(captor.capture())
        val rows = captor.allValues
        assertTrue(rows.all { it.issueType == QualityIssueType.CROSS_VALIDATE_MISMATCH.name }, "issue_type=CROSS_VALIDATE_MISMATCH")
        assertTrue(rows.all { it.source == "cross-validate" }, "source=cross-validate（只观测不修正）")
        assertTrue(rows.all { it.detail!!.contains("close") }, "detail 记录差异字段 close")
        assertEquals(setOf("600000", "600036"), rows.map { it.code }.toSet(), "差异落库覆盖两只股票")
    }

    // ==================== 不一致率 >30% 钉钉告警 ====================

    @Test
    fun `testDailyExecute mismatchRateOver30NotifiesDingTalk`() {
        // given: 4 股样本，2 股不一致 → 不一致率 50% > 30%
        val codes = listOf("600000", "600036", "600050", "600085")
        Mockito.`when`(stockInfoRepository.findRandomValidCodes(Mockito.anyInt())).thenReturn(codes)
        val python = FakePythonClient().apply { crossResponse = cvResponseFor(codes, mismatchCount = 2) }
        Mockito.`when`(qualityLogRepository.save(Mockito.any(DataQualityLog::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        job(python).dailyExecute()

        // then: 50% > 30% → 钉钉告警 CROSS_VALIDATE_MISMATCH（§11.2 / §11.3）
        assertTrue(
            notifier.notified.any { it.first == DingTalkEvent.CROSS_VALIDATE_MISMATCH },
            "不一致率>30% 应告警 CROSS_VALIDATE_MISMATCH",
        )
    }

    @Test
    fun `testDailyExecute mismatchRateAtOrUnder30NoNotify`() {
        // given: 4 股样本，1 股不一致 → 不一致率 25% ≤ 30%
        val codes = listOf("600000", "600036", "600050", "600085")
        Mockito.`when`(stockInfoRepository.findRandomValidCodes(Mockito.anyInt())).thenReturn(codes)
        val python = FakePythonClient().apply { crossResponse = cvResponseFor(codes, mismatchCount = 1) }
        Mockito.`when`(qualityLogRepository.save(Mockito.any(DataQualityLog::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        job(python).dailyExecute()

        // then: 25% ≤ 30% → 不告警
        assertTrue(
            notifier.notified.none { it.first == DingTalkEvent.CROSS_VALIDATE_MISMATCH },
            "不一致率≤30% 不应告警 CROSS_VALIDATE_MISMATCH",
        )
    }

    // ==================== 空样本 / Python 异常降级 ====================

    @Test
    fun `testDailyExecute emptySampleSkipsCrossFetch`() {
        // given: 无有效股票样本（findRandomValidCodes 空）
        Mockito.`when`(stockInfoRepository.findRandomValidCodes(Mockito.anyInt())).thenReturn(emptyList())
        val python = FakePythonClient()

        // when
        job(python).dailyExecute()

        // then: 空样本跳过，不拉交叉验证数据、不落库
        assertEquals(0, python.crossCalls, "空样本不应拉取交叉验证数据")
        Mockito.verify(qualityLogRepository, Mockito.never()).save(Mockito.any(DataQualityLog::class.java))
    }

    @Test
    fun `testDailyExecute pythonExceptionDegradesNoThrow`() {
        // given: 有样本但 Python 拉取抛异常（SorosBaseException 族）
        val codes = listOf("600000")
        Mockito.`when`(stockInfoRepository.findRandomValidCodes(Mockito.anyInt())).thenReturn(codes)
        val python = FakePythonClient().apply { crossError = PythonClientException("python 不可用") }

        // when: 必须不抛出（降级跳过不中断主链）
        job(python).dailyExecute()

        // then: 异常被 runSample 捕获降级
        assertEquals(1, python.crossCalls, "已尝试拉取（降级前发起调用）")
        Mockito.verify(qualityLogRepository, Mockito.never()).save(Mockito.any(DataQualityLog::class.java))
    }

    // ==================== 构造辅助 ====================

    /** 构造交叉验证响应：前 mismatchCount 只股票 ba/ak close 不一致，其余一致 */
    private fun cvResponseFor(codes: List<String>, mismatchCount: Int): CrossValidateResponse {
        val results = codes.mapIndexed { i, code ->
            val ba = stockBar()
            val ak = if (i < mismatchCount) stockBar().copy(close = BigDecimal("20.0000")) else stockBar()
            code to mapOf(
                "baostock" to StockBarsResult("baostock", 1, listOf(ba)),
                "akshare" to StockBarsResult("akshare", 1, listOf(ak)),
            )
        }.toMap()
        return CrossValidateResponse("ok", results)
    }

    /** 单根一致日 K（baostock/akshare 同值 → 不产生差异） */
    private fun stockBar(date: LocalDate = LocalDate.of(2026, 9, 30)) = DailyBar(
        date = date,
        code = "600000",
        open = BigDecimal("10.0000"),
        high = BigDecimal("10.5000"),
        low = BigDecimal("9.5000"),
        close = BigDecimal("10.0000"),
        volume = 1000L,
        amount = BigDecimal("10000000.0000"),
        changePercent = BigDecimal("1.00"),
        turnover = BigDecimal("1.00"),
        prevClose = BigDecimal("9.9000"),
    )
}
