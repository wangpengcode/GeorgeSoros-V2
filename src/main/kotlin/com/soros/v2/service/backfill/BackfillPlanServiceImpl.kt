package com.soros.v2.service.backfill

import com.soros.v2.repository.StockHistoryGapCheckRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.RerunPlan
import com.soros.v2.service.backfill.dto.StockSpan
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 回填重跑计划实现（库内驱动，零外部请求）：
 * 1. 防御性 ensureLoaded()（日历是计划的硬依赖，失败 → BusinessException，不再 fail-open）；
 * 2. 全量有效股票清单（非 ST/非退市，ST 隔离铁律）→ 每票库内覆盖聚合（aggregateCoverageByCodes）；
 * 3. 期望窗口 A 计算（起点=max(from, ipo) 吸附首个开市日，终点吸附 ≤to 最后开市日）→ BackfillClassifier 分类；
 * 4. MID 中间洞由 findMissingDateIslands 产出，与 stock_history_gap_check 完全重合的段被排除（exact-match）。
 */
@Service
class BackfillPlanServiceImpl(
    private val stockInfoRepository: StockInfoRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val calendarService: TradingCalendarService,
    private val tradingDayLookup: TradingDayLookup,
    private val gapCheckRepository: StockHistoryGapCheckRepository,
) : BackfillPlanService {

    private val logger = LoggerFactory.getLogger(BackfillPlanServiceImpl::class.java)

    override suspend fun buildRerunPlan(from: LocalDate, to: LocalDate): RerunPlan {
        ensureCalendarLoaded()
        val stocks = stockInfoRepository.findByIsStFalseAndDelistedFalse()
        if (stocks.isEmpty()) {
            return RerunPlan(from, to, totalCodes = 0, completeCodes = 0, zeroWindowCodes = 0, segments = emptyList())
        }

        val coverageByCode = stockHistoryRepository.aggregateCoverageByCodes(stocks.map { it.code })
            .associateBy { it.code }

        val segments = mutableListOf<FetchSegment>()
        var completeCodes = 0
        var zeroWindowCodes = 0
        for (stock in stocks) {
            val span = coverageByCode[stock.code]?.let {
                StockSpan(it.code, it.min_d, it.max_d, it.n_rows)
            } ?: StockSpan(stock.code, null, null, 0L)

            val window = BackfillClassifier.expectedWindow(
                ipoDate = stock.ipoDate,
                defaultStartDate = from,
                to = to,
                cal = tradingDayLookup,
            )
            if (window == null) {
                zeroWindowCodes++
                continue
            }
            val (expectedStart, expectedEnd) = window
            if (expectedStart > expectedEnd) {
                zeroWindowCodes++
                continue
            }

            val stockSegments = BackfillClassifier.classifyStock(
                code = stock.code,
                span = span,
                expectedStart = expectedStart,
                expectedEnd = expectedEnd,
                cal = tradingDayLookup,
                midSegments = { code, minD, maxD ->
                    findMissingIslands(code, minD, maxD)
                },
                isVerifiedEmpty = { code, segFrom, segTo ->
                    gapCheckRepository.existsByCodeAndRange(code, segFrom, segTo)
                },
            )
            if (stockSegments.isEmpty()) {
                completeCodes++
            } else {
                segments += stockSegments
            }
        }
        logger.info(
            "[backfill-plan] 构建完成：from={} to={} codes={} complete={} zeroWindow={} segments={}",
            from, to, stocks.size, completeCodes, zeroWindowCodes, segments.size,
        )
        return RerunPlan(
            from = from,
            to = to,
            totalCodes = stocks.size,
            completeCodes = completeCodes,
            zeroWindowCodes = zeroWindowCodes,
            segments = segments,
        )
    }

    /**
     * 防御性日历加载（fail-open，铁律 8：外部数据缺失/异常降级不崩）。
     * 硬保证在 BackfillJob.run 的 prepareCalendar（亦 fail-open）；此处再确保一次——
     * 加载失败仅告警，用库内既有日历行继续（窗口吸附取不到开市日 → zero-window 零段，降级不炸）。
     */
    private suspend fun ensureCalendarLoaded() {
        try {
            calendarService.ensureLoaded()
        } catch (e: Exception) {
            logger.warn("[backfill-plan] 交易日历加载失败（fail-open 用库内既有日历继续）：{}", e.message)
        }
    }

    /** 中间洞 gaps-and-islands（calendar 反连接）；仅当 n_rows < 开市日数 时由分类器调用 */
    private fun findMissingIslands(code: String, minD: LocalDate, maxD: LocalDate): List<Pair<LocalDate, LocalDate>> =
        stockHistoryRepository.findMissingDateIslands(code)
            .mapNotNull { island ->
                val from = island.seg_from
                val to = island.seg_to
                if (from != null && to != null) Pair(from, to) else null
            }
            .filter { (f, t) -> !f.isBefore(minD) && !t.isAfter(maxD) }
}
