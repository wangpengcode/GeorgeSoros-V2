package com.soros.v2.job

import com.soros.v2.entity.StockFundamentals
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.repository.StockFundamentalsRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.util.MdcSupport
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §11.1 FundamentalsCollectJob：披露季财务采集（stock_fundamentals upsert，UNIQUE(code, report_date) 幂等）。
 *
 * 披露季循环（年报/一季报/半年报/三季报披露截止后每日尝试 + 幂等跳过）：
 * - 一季报：报告期 03-31，披露截止 4 月末 → 4-5 月每日尝试
 * - 半年报：报告期 06-30，披露截止 8 月末 → 8-9 月每日尝试
 * - 三季报：报告期 09-30，披露截止 10 月末 → 10-11 月每日尝试
 * - 年报：报告期 12-31，披露截止次年 4 月末 → 1-4 月每日尝试（4 月与一季报叠加）
 *
 * 幂等跳过：已入库行数 ≥ 有效股票数×90% 视为本次披露季已采集，跳过不再全量拉取；
 * Python 失败只日志降级（披露季"数据还没齐"是常态，绝不把采集失败提升为告警/中断）。
 */
@Component
class FundamentalsCollectJob(
    private val pythonClient: PythonDataServiceClient,
    private val fundamentalsRepository: StockFundamentalsRepository,
    private val stockInfoRepository: StockInfoRepository,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(FundamentalsCollectJob::class.java)

    private companion object {
        /** §11.1 幂等跳过阈值：已采集行数 ≥ 有效股票数×90% 即视为本报告期已齐 */
        val COLLECTED_RATIO_THRESHOLD = BigDecimal("0.9")
        val REPORT_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
    }

    /** §11.1 披露季每日尝试（MON-FRI，晚于日采，避免与 Python 限流叠峰） */
    @Scheduled(cron = "\${soros.fundamentals.cron:0 40 20 * * MON-FRI}")
    fun execute() {
        runBlocking(sorosIo) {
            collect()
        }
    }

    private suspend fun collect() {
        val today = LocalDate.now()
        val candidates = candidateReportDates(today)
        if (candidates.isEmpty()) {
            logger.info("[fundamentals] 非披露季月份 {} 月，跳过（披露窗口：1-4/4-5/8-9/10-11）", today.monthValue)
            return
        }
        for (reportDate in candidates) {
            MdcSupport.withMdcSuspend(
                MdcSupport.JOB to "fundamentals-collect",
                MdcSupport.PHASE to "fundamentals",
                MdcSupport.DATE_RANGE to reportDate.toString(),
            ) {
                if (alreadyCollected(reportDate)) {
                    logger.info("[fundamentals] 报告期 {} 已采集（幂等跳过）", reportDate)
                    return@withMdcSuspend
                }
                fetchAndUpsert(reportDate)
            }
        }
    }

    /** 当前月份对应的待采集报告期（4 月叠加一季报+年报） */
    private fun candidateReportDates(today: LocalDate): List<LocalDate> {
        val year = today.year
        return when (today.monthValue) {
            1, 2, 3 -> listOf(LocalDate.of(year - 1, 12, 31))            // 年报
            4 -> listOf(LocalDate.of(year, 3, 31), LocalDate.of(year - 1, 12, 31)) // 一季报 + 年报
            5 -> listOf(LocalDate.of(year, 3, 31))                        // 一季报补漏
            8, 9 -> listOf(LocalDate.of(year, 6, 30))                     // 半年报
            10, 11 -> listOf(LocalDate.of(year, 9, 30))                   // 三季报
            else -> emptyList()
        }
    }

    /** 幂等跳过：已入库行数 ≥ 有效股票数×90% */
    private fun alreadyCollected(reportDate: LocalDate): Boolean {
        val existing = fundamentalsRepository.findByReportDate(reportDate).size
        val expected = stockInfoRepository.countByIsStFalseAndDelistedFalse()
        if (expected == 0L) return false
        val ratio = BigDecimal(existing).divide(BigDecimal(expected), 4, RoundingMode.HALF_UP)
        return ratio >= COLLECTED_RATIO_THRESHOLD
    }

    /** 拉取全市场财务 → upsert（亿元×1e8 已在 Python 侧转元；UNIQUE(code, report_date) 幂等） */
    private suspend fun fetchAndUpsert(reportDate: LocalDate) {
        try {
            val stocks = pythonClient.fetchFundamentals(
                FundamentalsRequest(reportDate.format(REPORT_DATE_FORMAT)),
            )
            var upserted = 0
            for (stock in stocks) {
                val entity = fundamentalsRepository.findByCodeAndReportDate(stock.code, reportDate)
                    ?: StockFundamentals().also { it.code = stock.code; it.reportDate = reportDate }
                entity.revenue = stock.revenue
                entity.netProfit = stock.netProfit
                fundamentalsRepository.save(entity)
                upserted++
            }
            logger.info("[fundamentals] 报告期 {} 采集入库 {} 只", reportDate, upserted)
        } catch (e: SorosBaseException) {
            logger.warn("[fundamentals] 报告期 {} 拉取失败（披露季无数据/源异常，降级跳过不报错）：{}", reportDate, e.message)
        }
    }
}
