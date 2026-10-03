package com.soros.v2.service.limitup.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

/**
 * §4.8 涨停梯队接口 DTO。
 *
 * 字段名对齐命名字典：trade_date / limit_up_count / limit_up_streak / board / change_pct /
 * industry / concept_boards（全库统一键名；PLAN §4.8 字面 "date" 经调度确认改过命名字典 trade_date）。
 */

/** GET /api/v1/limit-up-board → 涨停梯队 + 当日最高板（leaderboard[0]，同板数并列全部返回） */
data class LimitUpBoardResponse(
    /** 交易日 */
    @JsonProperty("trade_date") val tradeDate: LocalDate,
    /** 涨停家数（§4.8 当日涨停总数，限 MAIN/GEM/STAR 且非 IPO 首 5 日） */
    @JsonProperty("limit_up_count") val limitUpCount: Int,
    /** 涨停梯队明细（按 limit_up_streak DESC；最高板=首个元素） */
    @JsonProperty("leaderboard") val leaderboard: List<LimitUpBoardItem>,
)

/** 单只涨停股明细（§4.8 leaderboard[] 元素） */
data class LimitUpBoardItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String?,
    /** 连板数（首板=1，0=非涨停/断板；Kotlin 派生） */
    @JsonProperty("limit_up_streak") val limitUpStreak: Int,
    /** 市场板（MAIN 主板/GEM 创业板/STAR 科创板） */
    @JsonProperty("board") val board: String,
    /** 涨跌幅%（不复权口径） */
    @JsonProperty("change_pct") val changePct: BigDecimal,
    /** 行业（JSON 数组，主行业=第一个，展示用） */
    @JsonProperty("industry") val industry: List<String>?,
    /** 概念板块（JSON 数组） */
    @JsonProperty("concept_boards") val conceptBoards: List<String>?,
)
