package com.soros.v2.service.backfill

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
 * 回填重跑计划实现（库内驱动，零外部请求；2026-10-05 用户定稿：水位线断点续传 + 板块优先级）：
 * 1. 防御性 ensureLoaded()（日历是计划的硬依赖，失败 fail-open 用库内日历继续）；
 * 2. stock_info 全量有效股票清单（非 ST/非退市，ST 隔离铁律）→ 每票库内覆盖聚合（仅供 reason 判定）；
 * 3. 期望窗口计算（起点=max(from, ipo) 吸附首个开市日，终点吸附 ≤to 最后开市日）；
 * 4. 单票判定（BackfillClassifier.classifyStock）：input_data_last_day ≥ expectedEnd → 跳过；
 *    否则恰一段，起点=水位线后首个开市日（断点续传），水位线为空整窗起拉；
 * 5. 排序（用户定稿「688 300 先导入，然后 600 000」）：板块组 688→300→600→000→其他，
 *    组内按 code 升序。
 * gap_check 台账由 Job 落库（验证空记录），不再参与计划判定；islands 查询保留供拉齐后检查报告。
 */
@Service
class BackfillPlanServiceImpl(
    private val stockInfoRepository: StockInfoRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val calendarService: TradingCalendarService,
    private val tradingDayLookup: TradingDayLookup,
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
            if (window == null || window.first > window.second) {
                zeroWindowCodes++
                continue
            }
            val (expectedStart, expectedEnd) = window

            val stockSegments = BackfillClassifier.classifyStock(
                code = stock.code,
                watermark = stock.inputDataLastDay,
                span = span,
                expectedStart = expectedStart,
                expectedEnd = expectedEnd,
                cal = tradingDayLookup,
            )
            if (stockSegments.isEmpty()) {
                completeCodes++
            } else {
                segments += stockSegments
            }
        }
        // 板块优先级：688→300→600→000→其他，组内 code 升序（用户定稿导入顺序）
        val ordered = segments.sortedWith(compareBy({ BackfillClassifier.boardGroup(it.code) }, { it.code }))
        logger.info(
            "[backfill-plan] 构建完成：from={} to={} codes={} complete={} zeroWindow={} segments={}",
            from, to, stocks.size, completeCodes, zeroWindowCodes, ordered.size,
        )
        return RerunPlan(
            from = from,
            to = to,
            totalCodes = stocks.size,
            completeCodes = completeCodes,
            zeroWindowCodes = zeroWindowCodes,
            segments = ordered,
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
