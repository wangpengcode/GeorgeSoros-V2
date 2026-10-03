package com.soros.v2.controller

import com.soros.v2.service.limitup.LimitUpService
import com.soros.v2.service.limitup.dto.LimitUpBoardResponse
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §4.8 LimitUpController：涨停梯队/当日最高板查询（Kotlin 查库直出，不经过数据源）。
 *
 * GET /api/v1/limit-up-board?trade_date=2026-10-02
 * → { trade_date, limit_up_count, leaderboard:[{code,name,limit_up_streak,board,change_pct,industry,concept_boards}] }
 *   按 limit_up_streak DESC；最高板=leaderboard[0]（同板数并列全部返回）。
 *
 * 入参/响应字段名过命名字典（trade_date 而非 PLAN §4.8 字面 "date"，见 LimitUpDtos KDoc）。
 */
@RestController
@RequestMapping("/api/v1/limit-up-board")
class LimitUpController(
    private val limitUpService: LimitUpService,
) {

    @GetMapping
    fun getLimitUpBoard(
        @RequestParam("trade_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) tradeDate: LocalDate,
    ): LimitUpBoardResponse = limitUpService.getLimitUpBoard(tradeDate)
}
