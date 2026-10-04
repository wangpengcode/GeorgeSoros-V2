package com.soros.v2.job

import com.soros.v2.domain.BackfillStatus
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.util.MdcSupport
import com.soros.v2.domain.QualityIssueType
import com.soros.v2.entity.DataQualityLog
import com.soros.v2.entity.StockHistory
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.DataQualityLogRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.backfill.BackfillService
import com.soros.v2.util.CollectMetrics
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * CalibrationJob —— stock_history 低频校准对拍（2026-10-04 第四源落地四件套之一）。
 *
 * 职责（用户拍板「校准是间歇性 job，分散到多天，对每个数据源都不是很频繁」）：
 * - 每 30 分钟（非整点分钟）抽 ≤4 只含未校准行的代码；回填 RUNNING 时整轮跳过（互斥写竞争）
 * - 校准窗 = 该股未校准行的【最新 ≤30 自然日】（确定式推进：本轮校掉尾部一段，
 *   下轮候选窗自动前移，天然分散到多天；无需随机数，进度可预期）
 * - 经 Python Router 拉取窗内 qfq 日 K（分片路由天然分压多源），逐行与库值对拍：
 *   close ±0.1% / volume ±1% / change_pct ±0.02pp（与 CrossValidateJob 同容差）
 * - 全部比对通过 → 仅对【实际比对过】的行置 calibrated=true + calibrated_source/at
 *   （逐行 read-modify-write，Python 未返回的行保持未校准留待后续，绝不虚标）；
 *   任一差异 → 全部不标记 + data_quality_log(CALIBRATION_MISMATCH) + 钉钉 WARN
 * - Python 空/故障结果 → 跳过不标记（留在候选池下轮再试），不产生质量噪音
 *
 * 与 CrossValidateJob 的分工：交叉验证是「多源对拍观测」（不写 stock_history）；
 * 校准是「库值 vs 最新源」的收敛闭环（写校准标记，是数据可信度的水位线）。
 */
@Component
class CalibrationJob(
    private val pythonClient: PythonDataServiceClient,
    private val backfillService: BackfillService,
    private val stockHistoryRepository: StockHistoryRepository,
    private val dataQualityLogRepository: DataQualityLogRepository,
    private val notifier: DingTalkNotifier,
    private val metrics: CollectMetrics,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {

    private val logger = LoggerFactory.getLogger(CalibrationJob::class.java)

    /** 每轮抽取的未校准 code 数（低频：4 只 × ≤30 日 ≈ ≤120 行/30min） */
    val sampleCodes: Int = 4

    /** 校准窗最大自然日跨度 */
    val maxWindowDays: Long = 30

    /** 间歇性校准（非整点分钟，避开整点任务群； soros.calibration.cron 可覆盖） */
    @Scheduled(cron = "\${soros.calibration.cron:0 13,43 * * * *}")
    fun execute() {
        runBlocking(sorosIo) {
            // 回填互斥：RUNNING 期间 COPY/merge 与 streak 重算并发写主表，校准对拍让行
            if (backfillService.status().status == BackfillStatus.RUNNING) {
                logger.info("[calibration] 回填运行中，本轮跳过")
                return@runBlocking
            }
            MdcSupport.withMdcSuspend(
                MdcSupport.JOB to "calibration",
                MdcSupport.PHASE to "calibration",
            ) {
                runRound()
            }
        }
    }

    private suspend fun runRound() {
        val codes = stockHistoryRepository.findRandomUncalibratedCodes(sampleCodes)
        if (codes.isEmpty()) {
            logger.info("[calibration] 无未校准候选，跳过")
            return
        }
        var marked = 0
        var mismatched = 0
        for (code in codes) {
            try {
                val outcome = calibrateOne(code)
                marked += outcome.marked
                mismatched += outcome.mismatched
            } catch (e: Exception) {
                // 单股失败不炸整轮（与 Router 单股失败语义一致）；留在候选池下轮再试
                logger.warn("[calibration] {} 校准失败（留待下轮）：{}", code, e.message)
            }
        }
        logger.info("[calibration] 候选 {} 只，标记 {} 行，差异 {} 行", codes.size, marked, mismatched)
    }

    /** 单股校准：窗口选择 → 拉取 → 对拍 → 标记/告警；返回 (marked, mismatched) */
    private suspend fun calibrateOne(code: String): CalibrateOutcome {
        val firstDate = stockHistoryRepository
            .findTopByCodeAndCalibratedFalseOrderByTradeDateAsc(code)?.tradeDate ?: return CalibrateOutcome.EMPTY
        val lastDate = stockHistoryRepository
            .findTopByCodeAndCalibratedFalseOrderByTradeDateDesc(code)?.tradeDate ?: return CalibrateOutcome.EMPTY
        // 校准窗 = 最新 ≤30 自然日（end 固定为最新未校准行，start 收缩；确定式尾部推进）
        val start = if (ChronoUnit.DAYS.between(firstDate, lastDate) >= maxWindowDays) {
            lastDate.minusDays(maxWindowDays - 1)
        } else {
            firstDate
        }

        val response = pythonClient.fetchDailyBarsBatch(
            DailyBarsBatchRequest(codes = listOf(code), startDate = start.toString(), endDate = lastDate.toString(), adjust = "qfq"),
        )
        val result = response.results[code]
        if (result == null || result.data.isEmpty() || result.error != null) {
            // 空/故障：跳过不标记（留候选池），不产生质量噪音（源故障已在 Python 侧 error 文案可观测）
            logger.info("[calibration] {} 拉取空/故障（{}），跳过", code, result?.error ?: "empty")
            return CalibrateOutcome.EMPTY
        }

        val source = DataSourceType.fromPython(result.source)
        val storedByDate = stockHistoryRepository
            .findByCodeAndTradeDateBetween(code, start, lastDate)
            .associateBy { it.tradeDate }

        val mismatches = mutableListOf<Pair<LocalDate, List<String>>>()
        val compared = mutableListOf<StockHistory>()
        for (bar in result.data) {
            val row = storedByDate[bar.date] ?: continue // Python 多出行不参与标记
            compared.add(row)
            val fields = mismatchFields(row, bar)
            if (fields.isNotEmpty()) mismatches.add(bar.date to fields)
        }
        if (compared.isEmpty()) {
            logger.info("[calibration] {} 窗口 [{}..{}] 无可比行，跳过", code, start, lastDate)
            return CalibrateOutcome.EMPTY
        }

        if (mismatches.isNotEmpty()) {
            persistAndAlert(code, source, mismatches)
            return CalibrateOutcome(marked = 0, mismatched = mismatches.size)
        }

        // 全部通过：逐行 read-modify-write 精确标记（Python 未返回的行保持未校准）
        for (row in compared) {
            row.calibrated = true
            row.calibratedSource = source.name
            row.calibratedAt = Instant.now()
            stockHistoryRepository.save(row)
        }
        return CalibrateOutcome(marked = compared.size, mismatched = 0)
    }

    /** 容差判定（与 CrossValidateJob 同口径）：close ±0.1% / volume ±1% / change_pct ±0.02pp */
    private fun mismatchFields(row: StockHistory, bar: DailyBar): List<String> {
        val fields = mutableListOf<String>()
        val close = row.close
        if (close != null && close.signum() != 0 && bar.close.signum() != 0) {
            val ratio = close.subtract(bar.close).abs().divide(bar.close, 8, RoundingMode.HALF_UP)
            if (ratio > CLOSE_TOLERANCE) fields.add("close")
        } else if (close != null && close.signum() != 0 && bar.close.signum() == 0) {
            fields.add("close") // 库有值而源回 0：异常而非容差
        }
        val volume = row.volume
        if (volume != null && bar.volume == 0L) {
            if (volume != 0L) fields.add("volume")
        } else if (volume != null && bar.volume != 0L) {
            val ratio = BigDecimal(volume - bar.volume).abs()
                .divide(BigDecimal(bar.volume), 8, RoundingMode.HALF_UP)
            if (ratio > VOLUME_TOLERANCE) fields.add("volume")
        }
        val changePct = row.changePct
        if (changePct != null && bar.changePercent.subtract(changePct).abs() > CHANGE_PCT_TOLERANCE_PP) {
            fields.add("change_pct")
        }
        return fields
    }

    /** 差异落库（逐行明细）+ 钉钉 WARN（单轮内聚合一条；限频由 Notifier 层实现） */
    private fun persistAndAlert(
        code: String,
        source: DataSourceType,
        mismatches: List<Pair<LocalDate, List<String>>>,
    ) {
        val today = LocalDate.now()
        for ((date, fields) in mismatches) {
            dataQualityLogRepository.save(
                DataQualityLog().apply {
                    checkDate = today
                    this.code = code
                    issueType = QualityIssueType.CALIBRATION_MISMATCH.name
                    detail = "校准对拍差异($date)：${fields.joinToString("; ")}；源=${source.name}"
                    this.source = "calibration"
                },
            )
            metrics.incrementQualityLog(QualityIssueType.CALIBRATION_MISMATCH)
        }
        logger.warn("[calibration] {} 校准差异 {} 行，不标记", code, mismatches.size)
        notifier.notify(
            DingTalkEvent.CALIBRATION_MISMATCH,
            "校准对拍发现差异",
            "$code 窗口内 ${mismatches.size} 行与源(${source.name})不一致，已留待人工核查（不自动标记）",
        )
    }

    /** 单股校准结果（标记行数 / 差异行数；EMPTY=跳过不动作） */
    private data class CalibrateOutcome(val marked: Int, val mismatched: Int) {
        companion object {
            val EMPTY = CalibrateOutcome(0, 0)
        }
    }

    companion object {
        private val CLOSE_TOLERANCE = BigDecimal("0.001")
        private val VOLUME_TOLERANCE = BigDecimal("0.01")
        private val CHANGE_PCT_TOLERANCE_PP = BigDecimal("0.02")
    }
}
