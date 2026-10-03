package com.soros.v2.service.limitup

import com.soros.v2.domain.Board
import com.soros.v2.entity.StockHistory
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.limitup.dto.LimitUpBoardItem
import com.soros.v2.service.limitup.dto.LimitUpBoardResponse
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * §4.8 LimitUpService 实现：查 stock_history 当日涨停行 + join stock_info 板块归属，装配 leaderboard。
 */
@Service
class LimitUpServiceImpl(
    private val stockHistoryRepository: StockHistoryRepository,
    private val stockInfoRepository: StockInfoRepository,
) : LimitUpService {

    override fun getLimitUpBoard(tradeDate: LocalDate): LimitUpBoardResponse {
        val limitUpRows = stockHistoryRepository.findByTradeDateAndIsLimitUpTrue(tradeDate)
            .sortedWith(compareByDescending<StockHistory> { it.limitUpStreak }.thenBy { it.code })
        if (limitUpRows.isEmpty()) {
            return LimitUpBoardResponse(tradeDate, 0, emptyList())
        }
        val infoByCode = stockInfoRepository.findByCodeIn(limitUpRows.map { it.code }).associateBy { it.code }
        val leaderboard = limitUpRows.map { row ->
            val info = infoByCode[row.code]
            LimitUpBoardItem(
                code = row.code,
                name = info?.name,
                limitUpStreak = row.limitUpStreak.toInt(),
                board = info?.board ?: Board.MAIN.name,
                changePct = row.changePct ?: BigDecimal.ZERO,
                industry = info?.industry,
                conceptBoards = info?.conceptBoards,
            )
        }
        return LimitUpBoardResponse(tradeDate, limitUpRows.size, leaderboard)
    }
}
