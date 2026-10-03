package com.soros.v2.service.limitup

import com.soros.v2.service.limitup.dto.LimitUpBoardResponse
import java.time.LocalDate

/**
 * §4.8 涨停梯队查询服务（查库直出，不经过数据源）。
 *
 * - 涨停行：stock_history.is_limit_up=true（限 MAIN/GEM/STAR，IPO 首 5 日已在 saveBatch 强制 false）
 * - 板块归属：join stock_info.industry/concept_boards（BoardCollectJob 覆盖写快照）
 * - 排序：limit_up_streak DESC（最高板=leaderboard[0]，同板数并列全部返回）
 */
interface LimitUpService {

    /** 指定交易日涨停梯队（无涨停行返回空 leaderboard，不视为错误） */
    fun getLimitUpBoard(tradeDate: LocalDate): LimitUpBoardResponse
}
