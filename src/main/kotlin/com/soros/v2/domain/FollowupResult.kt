package com.soros.v2.domain

/**
 * 昨日名单今日兑现结果（followup_json.result 值域单点；JSONB 内部键，落中文 label）。
 *
 * §4.9 术语表 step9：
 * - 大肉名单：延续（今日再 ≥+5% 或涨停）/ 回落（0~+5）/ 转大面（≤-5）/ 停牌
 * - 大面名单：反核止跌（≥+5% 或涨停）/ 弱势震荡 / 继续大面（≤-5）/ 停牌
 */
enum class FollowupResult(val label: String) {
    /** 大肉名单：今日再 ≥+5% 或涨停 */
    CONTINUE("延续"),

    /** 大肉名单：今日 0~+5% */
    RETREAT("回落"),

    /** 大肉名单：今日 ≤-5% */
    TURN_FACE("转大面"),

    /** 大面名单：今日 ≥+5% 或涨停（止跌反核） */
    REBOUND_STOP("反核止跌"),

    /** 大面名单：弱势震荡 */
    WEAK_SWING("弱势震荡"),

    /** 大面名单：今日仍 ≤-5% */
    CONTINUE_FACE("继续大面"),

    /** 停牌：无 bar */
    SUSPENDED("停牌"),
    ;

    companion object {
        /** 中文字面 → 枚举（followup_json 解析用；未知返回 null） */
        fun fromLabel(label: String?): FollowupResult? =
            entries.firstOrNull { it.label == label }
    }
}
