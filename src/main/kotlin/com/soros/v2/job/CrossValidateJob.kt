package com.soros.v2.job

import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.StockBarsResult
import com.soros.v2.util.CollectMetrics
import com.soros.v2.util.MdcSupport
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §11.2 CrossValidateJob：双源交叉验证（baostock vs akshare，mootdx 永不参与），只观测不修正。
 *
 * - 每周日：抽 50 股近 20 交易日（近 20 交易日 ≈ 28 自然日）
 * - 每日：随机 10 股当日（MON-FRI，晚于日采）
 * - 容差：close ±0.1%（两源同 qfq）、volume ±1%（单位换算探测点）、change_pct ±0.02pp
 * - 差异落 data_quality_log(CROSS_VALIDATE_MISMATCH)；单批 >30% 不一致 → 钉钉告警
 * - 修正永远走 §4.6/§4.7 正道，交叉验证只观测（防"用错误源覆盖正确源"）
 * - 失败不影响主链：Python 异常/空样本只日志降级，不抛出中断
 */
@Component
class CrossValidateJob(
    private val pythonClient: PythonDataServiceClient,
    private val stockInfoRepository: StockInfoRepository,
    private val qualityLogRepository: DataQualityLogRepository,
    private val notifier: DingTalkNotifier,
    private val metrics: CollectMetrics,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(CrossValidateJob::class.java)

    private companion object {
        /** §11.2 每周日样本参数 */
        const val WEEKLY_SAMPLE_SIZE = 50
        const val WEEKLY_LOOKBACK_DAYS = 20
        /** §11.2 每日小样本参数 */
        const val DAILY_SAMPLE_SIZE = 10
        const val DAILY_LOOKBACK_DAYS = 1
        /** 近 N 交易日 ≈ N×1.4 自然日 */
        const val TRADING_TO_NATURAL_RATIO = 1.4
        /** §11.2 单批不一致率告警阈值（%） */
        val MISMATCH_ALERT_PCT = BigDecimal("30")
    }

    /** §11.2 每周日全量抽查（50 股 × 近 20 交易日） */
    @Scheduled(cron = "\${soros.cross-validate.weekly-cron:0 15 22 * * SUN}")
    fun weeklyExecute() {
        runBlocking(sorosIo) {
            runSample(WEEKLY_SAMPLE_SIZE, WEEKLY_LOOKBACK_DAYS)
        }
    }

    /** §11.2 每日小样本（10 股 × 当日） */
    @Scheduled(cron = "\${soros.cross-validate.daily-cron:0 15 22 * * MON-FRI}")
    fun dailyExecute() {
        runBlocking(sorosIo) {
            runSample(DAILY_SAMPLE_SIZE, DAILY_LOOKBACK_DAYS)
        }
    }

    private suspend fun runSample(sampleSize: Int, lookbackTradingDays: Int) {
        MdcSupport.withMdcSuspend(
            MdcSupport.JOB to "cross-validate",
            MdcSupport.PHASE to "cross-validate",
            MdcSupport.DATE_RANGE to "${sampleSize}股/${lookbackTradingDays}交易日",
        ) {
            try {
                val codes = stockInfoRepository.findRandomValidCodes(sampleSize)
                if (codes.isEmpty()) {
                    logger.info("[cross-validate] 无有效股票样本，跳过")
                    return@withMdcSuspend
                }
                val today = LocalDate.now()
                val start = today.minusDays((lookbackTradingDays * TRADING_TO_NATURAL_RATIO).toLong())
                val response = pythonClient.fetchDailyBarsCross(
                    CrossValidateRequest(codes, start.toString(), today.toString(), "qfq"),
                )
                val compared = compareSources(response.results)
                persistAndAlert(compared)
            } catch (e: SorosBaseException) {
                logger.warn("[cross-validate] 交叉验证拉取失败（降级跳过不中断主链）：{}", e.message)
            }
        }
    }

    /** 两源逐 bar 对比（baostock 为基准，akshare 为对照）；返回差异明细 + 实际比较对数 */
    private fun compareSources(
        results: Map<String, Map<String, StockBarsResult>>,
    ): CrossValidateResult {
        val mismatches = mutableListOf<CrossValidateMismatch>()
        var comparedPairs = 0
        for ((code, bySource) in results) {
            val baostock = bySource["baostock"]?.data ?: emptyList()
            val akshare = bySource["akshare"]?.data ?: emptyList()
            if (baostock.isEmpty() || akshare.isEmpty()) continue
            val baByDate = baostock.associateBy { it.date }
            for (akBar in akshare) {
                val baBar = baByDate[akBar.date] ?: continue
                comparedPairs++
                val fields = mismatchFields(baBar, akBar)
                if (fields.isNotEmpty()) {
                    mismatches.add(
                        CrossValidateMismatch(
                            code = code,
                            tradeDate = akBar.date,
                            detail = fields.joinToString("; "),
                        ),
                    )
                }
            }
        }
        return CrossValidateResult(comparedPairs, mismatches)
    }

    /** 容差判定：close ±0.1% / volume ±1% / change_pct ±0.02pp；返回差异字段名集合 */
    private fun mismatchFields(ba: DailyBar, ak: DailyBar): List<String> {
        val fields = mutableListOf<String>()
        if (closeMismatch(ba.close, ak.close)) fields.add("close")
        if (volumeMismatch(ba.volume, ak.volume)) fields.add("volume")
        if (changePctMismatch(ba.changePercent, ak.changePercent)) fields.add("change_pct")
        return fields
    }

    /** close ±0.1%（两源同 qfq）；ak 为 0（停牌无价）不判差异 */
    private fun closeMismatch(ba: BigDecimal, ak: BigDecimal): Boolean {
        if (ak.signum() == 0) return false
        val ratio = ba.subtract(ak).abs().divide(ak, 8, RoundingMode.HALF_UP)
        return ratio > BigDecimal("0.001")
    }

    /** volume ±1%（单位换算探测点：若某源漏 ×100，比值会触发）；ak 为 0 时仅 ba 非 0 判差异 */
    private fun volumeMismatch(ba: Long, ak: Long): Boolean {
        if (ak == 0L) return ba != 0L
        val ratio = BigDecimal(ba - ak).abs().divide(BigDecimal(ak), 8, RoundingMode.HALF_UP)
        return ratio > BigDecimal("0.01")
    }

    /** change_pct ±0.02pp（百分点） */
    // changePercent 可空（新股/窗口首行无前收盘）：双 null 视为一致，单 null 视为不一致
    private fun changePctMismatch(ba: BigDecimal?, ak: BigDecimal?): Boolean = when {
        ba == null && ak == null -> false
        ba == null || ak == null -> true
        else -> ba.subtract(ak).abs() > BigDecimal("0.02")
    }

    /** 差异落库 + 单批不一致率 >30% 钉钉告警（不一致率 = 差异条数 / 实际比较对数） */
    private fun persistAndAlert(result: CrossValidateResult) {
        val today = LocalDate.now()
        for (m in result.mismatches) {
            qualityLogRepository.save(
                DataQualityLog().apply {
                    checkDate = today
                    code = m.code
                    issueType = QualityIssueType.CROSS_VALIDATE_MISMATCH.name
                    detail = "双源交叉验证差异：${m.detail}"
                    source = "cross-validate"
                },
            )
            metrics.incrementQualityLog(QualityIssueType.CROSS_VALIDATE_MISMATCH)
        }
        logger.info("[cross-validate] 比较 {} 对，差异 {} 条", result.comparedPairs, result.mismatches.size)
        if (result.comparedPairs == 0) return
        val rate = BigDecimal(result.mismatches.size)
            .divide(BigDecimal(result.comparedPairs), 2, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
        if (rate > MISMATCH_ALERT_PCT) {
            logger.warn("[cross-validate] 单批不一致率 {}% 超过 30%，触发钉钉告警", rate)
            notifier.notify(
                DingTalkEvent.CROSS_VALIDATE_MISMATCH,
                "双源交叉验证差异超过 30%",
                "比较 ${result.comparedPairs} 对，差异 ${result.mismatches.size} 条，不一致率 $rate%",
            )
        }
    }

    /** 交叉验证结果（比较对数 + 差异明细） */
    data class CrossValidateResult(
        val comparedPairs: Int,
        val mismatches: List<CrossValidateMismatch>,
    )

    /** 单条差异明细（供落库 + 告警） */
    data class CrossValidateMismatch(
        val code: String,
        val tradeDate: LocalDate,
        val detail: String,
    )
}
