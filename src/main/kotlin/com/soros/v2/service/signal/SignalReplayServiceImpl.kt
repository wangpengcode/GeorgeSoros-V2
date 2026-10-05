package com.soros.v2.service.signal

import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.StockHistory
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.MarketDailyRepository
import com.soros.v2.repository.SectorDailyRepository
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.TradingCalendarService
import java.math.BigDecimal
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 信号预计算回放服务实现（SignalPrecomputeJob 全历史补算 + 增量单日，§19.11.1 / §13.5）。
 *
 * 纪律（§19.11.1 定稿）：
 * - 全历史回放按股分批 **200 股/事务**（TransactionTemplate；禁止 @Transactional 整区间，§13.5 反面教训）
 * - 每 500 股 INFO 进度日志；批次失败（毒票=有行情无 stock_info）整批回滚 + 日志，断点续跑下轮补齐
 * - 每票全历史只读递推（ChipDistributionCalculator）只写区间行；除权检测内置（全历史重算天然免疫）+ 更新 adj_processed_until
 * - 增量 replayDay 幂等：同日已存在行跳过；市场/板块聚合按日各自独立事务
 */
@Service
class SignalReplayServiceImpl(
    private val chipCalculator: ChipDistributionCalculator,
    private val aggregationService: SignalAggregationService,
    private val signalDailyRepository: SignalDailyRepository,
    private val marketDailyRepository: MarketDailyRepository,
    private val sectorDailyRepository: SectorDailyRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val stockInfoRepository: StockInfoRepository,
    private val tradingCalendarService: TradingCalendarService,
    private val tradingCalendarRepository: TradingCalendarRepository,
    private val transactionManager: PlatformTransactionManager,
) : SignalReplayService {

    private val logger = LoggerFactory.getLogger(SignalReplayServiceImpl::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /** §19.11.1：全历史回放，按股分批 200 股/事务，断点续跑 */
    override fun replay(from: LocalDate, to: LocalDate): SignalReplaySummary {
        if (from.isAfter(to)) throw BusinessException("回放区间非法：from($from) > to($to)")
        logger.info("[Step Replay] 信号全历史回放启动：from={} to={}（按股分批 200 股/事务）", from, to)
        val allCodes = stockHistoryRepository.findDistinctCodesByTradeDateBetween(from, to)
        if (allCodes.isEmpty()) {
            logger.warn("[Step Replay] 区间内无行情股：from={} to={}（空回放）", from, to)
            return SignalReplaySummary(from, to, 0, 0, 0, 0)
        }
        var signalRows = 0
        var codesProcessed = 0
        var failedBatches = 0
        for ((batchIndex, batch) in allCodes.chunked(BATCH_SIZE).withIndex()) {
            val batchRows = runBatch(batch, from, to, batchIndex + 1)
            signalRows += batchRows
            if (batchRows > 0) codesProcessed += batch.size else failedBatches++
            if (codesProcessed % PROGRESS_LOG_STEP < batch.size) {
                logger.info(
                    "[Step Replay] 进度：成功处理 {} 股（共 {}），signal_daily 累计 {} 行，失败批 {} 个",
                    codesProcessed, allCodes.size, signalRows, failedBatches,
                )
            }
        }
        val (marketRows, sectorRows) = aggregateDailyMarketAndSectors(from, to)
        val summary = SignalReplaySummary(from, to, signalRows, marketRows, sectorRows, codesProcessed)
        logger.info(
            "[Step Replay] 回放完成：from={} to={} signal={} market={} sector={} codes={} 失败批={}",
            from, to, signalRows, marketRows, sectorRows, codesProcessed, failedBatches,
        )
        return summary
    }

    /** §19.11.1：增量单日（幂等：同日已存在行跳过；事件监听与 21:30 兜底共用） */
    override fun replayDay(date: LocalDate): SignalReplaySummary {
        logger.info("[Step ReplayDay] 增量单日启动：date={}", date)
        val bars = stockHistoryRepository.findByTradeDateBetween(date, date)
        if (bars.isEmpty()) {
            logger.warn("[Step ReplayDay] 当日无行情：date={}", date)
            return SignalReplaySummary(date, date, 0, 0, 0, 0)
        }
        var signalRows = 0
        var codesProcessed = 0
        transactionTemplate.execute {
            for (code in bars.map { it.code }.distinct()) {
                if (signalDailyRepository.existsByCodeAndTradeDate(code, date)) continue
                val rows = try {
                    processStock(code, date, date)
                } catch (e: RuntimeException) {
                    logger.warn("[Step ReplayDay] 单股毒票/异常跳过：code={} date={} error={}", code, date, e.message)
                    0
                }
                signalRows += rows
                if (rows > 0) codesProcessed++
            }
        }
        val (marketRows, sectorRows) = aggregateDailyMarketAndSectors(date, date)
        logger.info("[Step ReplayDay] 完成：date={} signal={} codes={}", date, signalRows, codesProcessed)
        return SignalReplaySummary(date, date, signalRows, marketRows, sectorRows, codesProcessed)
    }

    /**
     * 单批事务执行（200 股/事务）：整批提交或整批回滚；失败（毒票/异常）记日志并返回 0 行。
     */
    private fun runBatch(batch: List<String>, from: LocalDate, to: LocalDate, batchIndex: Int): Int =
        try {
            transactionTemplate.execute { batch.sumOf { code -> processStock(code, from, to) } } ?: 0
        } catch (e: RuntimeException) {
            logger.warn(
                "[Step Replay] 批次失败已回滚（毒票/异常），留待断点续跑补齐：批号={} 本批={} 股 首码={} error={}",
                batchIndex, batch.size, batch.firstOrNull(), e.message,
            )
            0
        }

    /**
     * 区间内每日市场/板块聚合 + 梯队排名（各自独立事务，非整区间单事务，§19.11.1）。
     * 返回 (market_daily 行数, sector_daily 行数)。
     */
    private fun aggregateDailyMarketAndSectors(from: LocalDate, to: LocalDate): Pair<Int, Int> {
        val tradingDays = tradingCalendarRepository
            .findByTradeDateBetweenOrderByTradeDateAsc(from, to)
            .map { it.tradeDate }
            .ifEmpty { stockHistoryRepository.findByTradeDateBetween(from, to).map { it.tradeDate }.distinct().sorted() }
        var marketRows = 0
        var sectorRows = 0
        for (day in tradingDays) {
            val prevDate = tradingCalendarService.previousTradingDay(day)
            val (m, s) = transactionTemplate.execute {
                val market = aggregationService.aggregateMarket(day, prevDate)
                marketDailyRepository.save(market)
                sectorDailyRepository.deleteByTradeDate(day)
                val sectors = aggregationService.aggregateSectors(day)
                sectorDailyRepository.saveAll(sectors)
                aggregationService.assignLadderRanks(day).forEach { rank ->
                    signalDailyRepository.findByCodeAndTradeDate(rank.code, day)?.let {
                        it.ladderRank = rank.ladderRank
                        it.sectorLadderRank = rank.sectorLadderRank
                        signalDailyRepository.save(it)
                    }
                }
                1 to sectors.size
            } ?: (0 to 0)
            marketRows += m
            sectorRows += s
        }
        return marketRows to sectorRows
    }

    /**
     * 单股处理：全历史只读递推 → 只写区间行（删段重建幂等）+ 除权水位更新。
     * 毒票（有行情无 stock_info）抛 [BusinessException] → 整批事务回滚（§19.11.1 断点续跑语义）。
     */
    private fun processStock(code: String, from: LocalDate, to: LocalDate): Int {
        val info = stockInfoRepository.findByCode(code)
            ?: throw BusinessException("毒票（有行情无 stock_info，无法派生 board/industry）：code=$code")
        val history = stockHistoryRepository.findByCodeAndTradeDateBetween(code, HISTORY_START, to)
            .sortedBy { it.tradeDate }
        if (history.isEmpty()) return 0
        val chipResults = chipCalculator.computeForHistory(history.map { it.toChipBarInput() })
        // 除权检测内置：本实现每票全历史重算，天然覆盖隐含昨收≠前日 close 场景；先删后插幂等
        signalDailyRepository.deleteByCodeAndTradeDateBetween(code, from, to)
        val board = info.board
        val rows = mutableListOf<SignalDaily>()
        history.forEachIndexed { idx, bar ->
            if (!bar.tradeDate.isBefore(from) && !bar.tradeDate.isAfter(to)) {
                val prevClose = if (idx > 0) history[idx - 1].close else null
                val chip = chipResults[idx]
                rows += SignalDaily().apply {
                    this.code = code
                    this.tradeDate = bar.tradeDate
                    this.isZhaban = aggregationService.isZhaban(bar, board, prevClose)
                    this.profitRatio = chip.profitRatio
                    this.costDev = chip.costDev
                    this.c90Low = chip.c90Low
                    this.c90High = chip.c90High
                    this.c90Conc = chip.c90Conc
                    this.c70Low = chip.c70Low
                    this.c70High = chip.c70High
                    this.c70Conc = chip.c70Conc
                }
            }
        }
        signalDailyRepository.saveAll(rows)
        val maxBarDate = history.last().tradeDate
        if (info.adjProcessedUntil == null || info.adjProcessedUntil!!.isBefore(maxBarDate)) {
            info.adjProcessedUntil = maxBarDate
            stockInfoRepository.save(info)
        }
        return rows.size
    }

    /**
     * StockHistory → ChipBarInput（OHLC=qfq 口径同源，缺列兜底为 close）。
     * 单位换算：DB turnover_rate 口径为「换手率%」（命名字典 §92），递推 tr 按小数（0-1）消费 → 此处 ÷100；
     * 漏除将使换手率 ≥1% 的交易日被 clamp 成全量换手，筹码分布退化为单日三角（单位铁律，2026-10-05 验证修复）。
     */
    private fun StockHistory.toChipBarInput(): ChipBarInput {
        val close = this.close ?: BigDecimal.ZERO
        return ChipBarInput(
            tradeDate = this.tradeDate,
            high = this.high ?: close,
            low = this.low ?: close,
            close = close,
            turnoverRate = (this.turnoverRate ?: BigDecimal.ZERO).divide(BigDecimal(100)),
        )
    }

    private companion object {
        const val BATCH_SIZE = 200
        const val PROGRESS_LOG_STEP = 500
        val HISTORY_START: LocalDate = LocalDate.of(1990, 1, 1)
    }
}
