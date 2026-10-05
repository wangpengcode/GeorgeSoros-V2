package com.soros.v2.service.kline

import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.SignalDailyRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.repository.TradingCalendarRepository
import com.soros.v2.service.kline.dto.KlineResponse
import com.soros.v2.service.kline.mapper.toKlineBar
import com.soros.v2.service.kline.mapper.toKlineChip
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * §19.13.1 KlineServiceImpl：窗口解析（trading_calendar 吸附）+ bars∘chip join + 存在性校验。
 *
 * - 默认窗口 = 近 250 交易日（仅 code / code+date 缺省 from）；
 * - date 语义=该日为终点（to=≤date 最近交易日吸附）；date 与 from/to 同传时作废 + WARN；
 * - chip 按 code+trade_date 区间一次查询后 Map 分组 join（禁止 N+1）；
 * - turnover_rate DB % 原样透出（K线页面，非筹码递推，不 ÷100）。
 */
@Service
class KlineServiceImpl(
    private val stockHistoryRepository: StockHistoryRepository,
    private val signalDailyRepository: SignalDailyRepository,
    private val stockInfoRepository: StockInfoRepository,
    private val tradingCalendarRepository: TradingCalendarRepository,
) : KlineService {

    private val logger = LoggerFactory.getLogger(KlineServiceImpl::class.java)

    override fun getKline(code: String?, from: LocalDate?, to: LocalDate?, date: LocalDate?): KlineResponse {
        val safeCode = code ?: throw BusinessException("股票代码不能为空")
        val info = stockInfoRepository.findByCode(safeCode)
            ?: throw BusinessException("股票代码 $safeCode 不存在")

        val (effFrom, effTo) = resolveWindow(safeCode, from, to, date)
        logger.info("[Step 1] 窗口解析完成：code={} from={} to={}", safeCode, effFrom, effTo)

        val bars = stockHistoryRepository.findByCodeAndTradeDateBetween(safeCode, effFrom, effTo)
        val chipsByDate = signalDailyRepository.findByCodeAndTradeDateBetween(safeCode, effFrom, effTo)
            .associateBy { it.tradeDate }
        logger.info("[Step 2] 拉取完成：code={} bars={} chips={}", safeCode, bars.size, chipsByDate.size)

        return KlineResponse(
            code = safeCode,
            name = info.name ?: "",
            bars = bars.map { it.toKlineBar(chipsByDate[it.tradeDate]?.toKlineChip()) },
        )
    }

    /** 窗口解析：date > 显式 to；from 缺省 250 日窗口；from 显式即全量；所有分支末尾统一校验 from≤to */
    private fun resolveWindow(code: String, from: LocalDate?, to: LocalDate?, date: LocalDate?): Pair<LocalDate, LocalDate> {
        val effFrom: LocalDate
        val effTo: LocalDate
        when {
            from != null && to != null -> {
                if (date != null) {
                    logger.warn("[Step 1] date 与 from/to 同传，date 作废：code={} date={}", code, date)
                }
                effFrom = from
                effTo = to
            }
            date != null -> {
                effTo = nearestTradingDay(date)
                effFrom = from ?: computeDefaultFrom(effTo)
            }
            from != null -> {
                effTo = nearestTradingDay(LocalDate.now())
                effFrom = from
            }
            to != null -> {
                effTo = to
                effFrom = computeDefaultFrom(to)
            }
            else -> {
                effTo = nearestTradingDay(LocalDate.now())
                effFrom = computeDefaultFrom(effTo)
            }
        }
        if (effFrom > effTo) {
            throw BusinessException("from 不能大于 to")
        }
        return Pair(effFrom, effTo)
    }

    /** ≤ anchor 最近交易日（吸附口径 A；日历空/异常降级回退 anchor 本身，不崩） */
    private fun nearestTradingDay(anchor: LocalDate): LocalDate =
        tradingCalendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(anchor)?.tradeDate ?: anchor

    /** 默认窗口起点 = to 往前数第 249 个交易日（含 to → 250 日窗口；日历不足时降级到最早日历日） */
    private fun computeDefaultFrom(to: LocalDate): LocalDate {
        val days = tradingCalendarRepository.findByTradeDateBetweenOrderByTradeDateAsc(to.minusYears(LOOKBACK_YEARS), to)
        return days.takeLast(DEFAULT_WINDOW).firstOrNull()?.tradeDate ?: to
    }

    private companion object {
        /** 默认窗口（近 250 交易日，§19.13.1 决策 2） */
        const val DEFAULT_WINDOW = 250

        /** 日历查询回看年数（覆盖回填起点 2021-10-01 之后全量；5 年含约 1211 交易日 > 250 窗口） */
        const val LOOKBACK_YEARS = 5L
    }
}
