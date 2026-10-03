package com.soros.v2.job

import com.soros.v2.entity.StockFundamentals
import com.soros.v2.exception.PythonClientException
import com.soros.v2.repository.StockFundamentalsRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockListDto
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

/**
 * §11.1 FundamentalsCollectJob 契约测试（Fake Python + Mockito mock 仓储，直调 execute() 不测 cron）。
 *
 * 契约（类 KDoc / §11.1）：
 * - 候选报告期随月份映射：4 月=一季报(当年 03-31)+年报(上年 12-31) 双候选；1/2/3 月=年报；
 *   5 月=一季报；8/9 月=半年报；10/11 月=三季报；6/7/12 月=空（不采集）；
 * - 幂等跳过：已入库行数 ≥ 有效股票数×90% → 跳过不再全量拉取；不足 90% → 拉取并 upsert；
 * - Python 抛异常（SorosBaseException 族）→ 日志降级不中断（披露季"数据没齐"是常态，不告警不抛出）；
 * - execute() 已实现（GREEN）；Python 用 Fake，仓储用 Mockito（非 suspend，mock 安全）。
 *
 * 月份相关用例用 Mockito.mockStatic(LocalDate.now()) 钉死日期，保证任意运行月份确定性；
 * 仓储 stub 用**具体日期值**而非 any() matcher（any() 返回 null 会触发 Kotlin 非空参数 NPE，且污染 matcher 栈）；
 * Job 构造注入 Dispatchers.Unconfined：execute() 内部 runBlocking 在同线程执行 collect()，
 *   mockStatic(LocalDate) 是线程局部的，换成 IO 线程会落到真实今天（2026-10-03）导致断言错乱。
 */
class FundamentalsCollectJobTest {

    /** Fake Python 客户端：记录 fetchFundamentals 调用并支持抛异常 */
    private class FakePythonClient : PythonDataServiceClient {
        var fetchFundamentalsCalls = 0
        var fetchFundamentalsResult: List<FundamentalsStockDto> = emptyList()
        var fetchFundamentalsError: Exception? = null

        override suspend fun healthCheck(): Boolean = true
        override suspend fun fetchStockList(): List<StockListDto> = emptyList()
        override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse =
            DailyBarsBatchResponse("ok")
        override suspend fun fetchTradingCalendar(): List<String> = emptyList()
        override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> {
            fetchFundamentalsCalls++
            fetchFundamentalsError?.let { throw it }
            return fetchFundamentalsResult
        }
        override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> = emptyMap()
        override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
            CrossValidateResponse("ok")
    }

    private fun job(python: FakePythonClient = FakePythonClient()): FundamentalsCollectJob =
        FundamentalsCollectJob(python, Mockito.mock(StockFundamentalsRepository::class.java), Mockito.mock(StockInfoRepository::class.java), Dispatchers.Unconfined)

    /** 钉死 LocalDate.now()，让 execute() 的候选月份确定（MockedStatic.use 自动关闭） */
    private fun pinToday(date: LocalDate, block: () -> Unit) {
        // CALLS_REAL_METHODS：只拦截 now()，LocalDate.of() 等静态方法仍走真实实现
        // （否则 candidateReportDates 里 LocalDate.of(...) 会返回 null，导致 reportDate NPE）
        Mockito.mockStatic(LocalDate::class.java, Mockito.CALLS_REAL_METHODS).use { mocked: MockedStatic<LocalDate> ->
            mocked.`when`<LocalDate> { LocalDate.now() }.thenReturn(date)
            block()
        }
    }

    /** 反射调私有 candidateReportDates（月份→候选报告期映射，非 suspend 可直接 invoke） */
    private fun candidateReportDates(job: FundamentalsCollectJob, today: LocalDate): List<LocalDate> {
        val method = job.javaClass.getDeclaredMethod("candidateReportDates", LocalDate::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(job, today) as List<LocalDate>
    }

    private fun fundamentalsDto(code: String = "600000") = FundamentalsStockDto(
        code = code,
        revenue = BigDecimal("1000000000.00"),   // 元（源亿元 ×1e8，§2.4 fundamentals 单位元）
        netProfit = BigDecimal("100000000.00"),
    )

    /** 4 月钉死后的两个候选报告期（一季报 03-31 + 年报上年 12-31） */
    private val aprilQ1: LocalDate = LocalDate.of(2026, 3, 31)
    private val aprilAnnual: LocalDate = LocalDate.of(2025, 12, 31)

    // ==================== 候选报告期月份映射 ====================

    @Test
    fun `testCandidateReportDates aprilReturnsBothQ1AndAnnual`() {
        // given: 4 月（一季报披露截止月 + 年报披露截止月叠加）
        val job = job()

        // when: 4 月候选报告期
        val candidates = candidateReportDates(job, LocalDate.of(2026, 4, 15))

        // then: 一季报(当年 03-31) + 年报(上年 12-31) 双候选，顺序=当年一季报在前
        assertEquals(
            listOf(aprilQ1, aprilAnnual),
            candidates,
            "4 月候选 = [当年 03-31 一季报, 上年 12-31 年报]",
        )
    }

    @Test
    fun `testCandidateReportDates emptyMonths`() {
        // given: 非披露季月份（6/7/12 月）
        val job = job()

        // when & then: 空候选，跳过
        assertTrue(candidateReportDates(job, LocalDate.of(2026, 6, 15)).isEmpty(), "6 月非披露季无候选")
        assertTrue(candidateReportDates(job, LocalDate.of(2026, 7, 15)).isEmpty(), "7 月非披露季无候选")
        assertTrue(candidateReportDates(job, LocalDate.of(2026, 12, 15)).isEmpty(), "12 月非披露季无候选")
    }

    @Test
    fun `testCandidateReportDates singleCandidateMonths`() {
        // given: 单候选月份（1/2/3=年报、5=一季报、8/9=半年报、10/11=三季报）
        val job = job()

        // then: 各单候选月份映射正确（§11.1 披露季循环）
        assertEquals(listOf(LocalDate.of(2025, 12, 31)), candidateReportDates(job, LocalDate.of(2026, 1, 15)), "1 月=年报")
        assertEquals(listOf(LocalDate.of(2025, 12, 31)), candidateReportDates(job, LocalDate.of(2026, 2, 15)), "2 月=年报")
        assertEquals(listOf(LocalDate.of(2025, 12, 31)), candidateReportDates(job, LocalDate.of(2026, 3, 15)), "3 月=年报")
        assertEquals(listOf(LocalDate.of(2026, 3, 31)), candidateReportDates(job, LocalDate.of(2026, 5, 15)), "5 月=一季报补漏")
        assertEquals(listOf(LocalDate.of(2026, 6, 30)), candidateReportDates(job, LocalDate.of(2026, 8, 15)), "8 月=半年报")
        assertEquals(listOf(LocalDate.of(2026, 6, 30)), candidateReportDates(job, LocalDate.of(2026, 9, 15)), "9 月=半年报")
        assertEquals(listOf(LocalDate.of(2026, 9, 30)), candidateReportDates(job, LocalDate.of(2026, 10, 15)), "10 月=三季报")
        assertEquals(listOf(LocalDate.of(2026, 9, 30)), candidateReportDates(job, LocalDate.of(2026, 11, 15)), "11 月=三季报")
    }

    // ==================== 幂等跳过（已采集≥90%） ====================

    @Test
    fun `testExecute idempotentSkipWhenCollectedRatioHigh`() {
        // given: 4 月 execute；双候选报告期已入库 90 行 / 有效股票 100 → 比例 90% ≥ 阈值 0.9 → 幂等跳过
        val fundamentalsRepository = Mockito.mock(StockFundamentalsRepository::class.java)
        val stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilQ1)).thenReturn(List(90) { StockFundamentals() })
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilAnnual)).thenReturn(List(90) { StockFundamentals() })
        Mockito.`when`(stockInfoRepository.countByIsStFalseAndDelistedFalse()).thenReturn(100L)
        val python = FakePythonClient().apply { fetchFundamentalsResult = listOf(fundamentalsDto()) }

        // when: 4 月 execute（双候选都跳过）
        pinToday(LocalDate.of(2026, 4, 15)) {
            FundamentalsCollectJob(python, fundamentalsRepository, stockInfoRepository, Dispatchers.Unconfined).execute()
        }

        // then: 已采集≥90% → 不拉取不 upsert（幂等跳过，§11.1）
        assertEquals(0, python.fetchFundamentalsCalls, "已采集≥90% 应幂等跳过，不拉取财务")
        Mockito.verify(fundamentalsRepository, Mockito.never()).save(Mockito.any(StockFundamentals::class.java))
    }

    // ==================== 不足拉取 + upsert ====================

    @Test
    fun `testExecute collectedRatioLowTriggersFetchAndUpsert`() {
        // given: 4 月 execute；已入库 0 行 / 有效股票 100 → 比例 0% < 90% → 拉取
        val fundamentalsRepository = Mockito.mock(StockFundamentalsRepository::class.java)
        val stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilQ1)).thenReturn(emptyList())
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilAnnual)).thenReturn(emptyList())
        Mockito.`when`(stockInfoRepository.countByIsStFalseAndDelistedFalse()).thenReturn(100L)
        // upsert 判重（UNIQUE(code, report_date) 至多 1 行）——双候选各自查询
        Mockito.`when`(fundamentalsRepository.findByCodeAndReportDate("600000", aprilQ1)).thenReturn(null)
        Mockito.`when`(fundamentalsRepository.findByCodeAndReportDate("600000", aprilAnnual)).thenReturn(null)
        Mockito.`when`(fundamentalsRepository.save(Mockito.any(StockFundamentals::class.java)))
            .thenAnswer { it.getArgument(0) }
        val python = FakePythonClient().apply { fetchFundamentalsResult = listOf(fundamentalsDto()) }

        // when
        pinToday(LocalDate.of(2026, 4, 15)) {
            FundamentalsCollectJob(python, fundamentalsRepository, stockInfoRepository, Dispatchers.Unconfined).execute()
        }

        // then: 双候选各拉取一次 + 每股 upsert（幂等）
        assertEquals(2, python.fetchFundamentalsCalls, "4 月双候选各拉取一次")
        Mockito.verify(fundamentalsRepository, Mockito.times(2)).save(Mockito.any(StockFundamentals::class.java))
    }

    // ==================== Python 异常降级不中断 ====================

    @Test
    fun `testExecute pythonSorosExceptionDegradesNoInterrupt`() {
        // given: 4 月 execute；已入库 0 行（需拉取）；Python 抛 PythonClientException（SorosBaseException 族）
        val fundamentalsRepository = Mockito.mock(StockFundamentalsRepository::class.java)
        val stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilQ1)).thenReturn(emptyList())
        Mockito.`when`(fundamentalsRepository.findByReportDate(aprilAnnual)).thenReturn(emptyList())
        Mockito.`when`(stockInfoRepository.countByIsStFalseAndDelistedFalse()).thenReturn(100L)
        val python = FakePythonClient().apply { fetchFundamentalsError = PythonClientException("python 服务不可用") }

        // when: 双候选各触发一次降级，必须不抛出、不中断
        pinToday(LocalDate.of(2026, 4, 15)) {
            FundamentalsCollectJob(python, fundamentalsRepository, stockInfoRepository, Dispatchers.Unconfined).execute()
        }

        // then: 异常被 fetchAndUpsert 捕获降级，Job 正常返回
        assertEquals(2, python.fetchFundamentalsCalls, "双候选均尝试拉取（降级前已发请求）")
        Mockito.verify(fundamentalsRepository, Mockito.never()).save(Mockito.any(StockFundamentals::class.java))
    }

    // ==================== 非披露季月份跳过 ====================

    @Test
    fun `testExecute nonDisclosureMonthSkipsFetch`() {
        // given: 6 月非披露季（候选为空）——collect 早退，不触达任何仓储
        val fundamentalsRepository = Mockito.mock(StockFundamentalsRepository::class.java)
        val stockInfoRepository = Mockito.mock(StockInfoRepository::class.java)
        val python = FakePythonClient().apply { fetchFundamentalsResult = listOf(fundamentalsDto()) }

        // when: 6 月 execute
        pinToday(LocalDate.of(2026, 6, 15)) {
            FundamentalsCollectJob(python, fundamentalsRepository, stockInfoRepository, Dispatchers.Unconfined).execute()
        }

        // then: 非披露季月份不拉取财务（§11.1 披露窗口 1-4/4-5/8-9/10-11）
        assertEquals(0, python.fetchFundamentalsCalls, "6 月非披露季应跳过，不拉取财务")
        Mockito.verify(fundamentalsRepository, Mockito.never()).findByReportDate(aprilQ1)
    }
}
