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
 * 回填重跑计划实现（库内驱动，零外部请求；2026-10-05 语义重写：整窗拉齐，不做洞级拆分）：
 * 1. 防御性 ensureLoaded()（日历是计划的硬依赖，失败 fail-open 用库内日历继续）；
 * 2. stock_info 全量有效股票清单（非 ST/非退市，ST 隔离铁律）→ 每票库内覆盖聚合；
 * 3. 期望窗口计算（起点=max(from, ipo) 吸附首个开市日，终点吸附 ≤to 最后开市日）；
 * 4. 单票判定：未覆盖缺失 = 窗口开市日 − 库内行数 − gap_check 台账覆盖天数；
 *    = 0 → 跳过（全齐或缺失日全部已验证停牌）；> 0 → **一个整窗段**（每票成本=一次外部链，与洞数无关）；
 * 5. 排序（stock_info 驱动，用户口径「先看 stock_info 决定哪些优先拉」）：
 *    有部分数据的票优先（大概率能落库真数据）→ 无数据票垫底；组内按 code 升序。
 * 洞级 islands（findMissingDateIslands）保留供拉齐后检查报告，不再驱动拉取。
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
        val coveredDaysByCode = gapCheckRepository.coveredTradingDaysByCode()
            .associate { it.code to it.coveredDays }

        val dataSegments = mutableListOf<FetchSegment>()   // 有部分数据的缺失票（优先）
        val noDataSegments = mutableListOf<FetchSegment>() // 无数据票（垫底）
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
            if (window == null || window.first > window.second) {
                zeroWindowCodes++
                continue
            }
            val (expectedStart, expectedEnd) = window

            val stockSegments = BackfillClassifier.classifyStock(
                code = stock.code,
                span = span,
                expectedStart = expectedStart,
                expectedEnd = expectedEnd,
                cal = tradingDayLookup,
                verifiedCoveredDays = coveredDaysByCode[stock.code] ?: 0L,
            )
            if (stockSegments.isEmpty()) {
                completeCodes++
            } else if (span.minD != null) {
                dataSegments += stockSegments
            } else {
                noDataSegments += stockSegments
            }
        }
        // stock_info 驱动优先级：有部分数据的缺失票 → 无数据票；组内 code 升序
        val segments = dataSegments.sortedBy { it.code } + noDataSegments.sortedBy { it.code }
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
}
