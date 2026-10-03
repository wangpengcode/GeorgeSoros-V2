package com.soros.v2.domain

/**
 * 个股动作标签（GET /api/v1/stocks/{code}/actions 与 /terms 的 label 值域单点）。
 *
 * §4.9 术语表 + 术语判定接口：actions: [{code, name, label: 反包|晋级|断板|反核止跌|继续大面|大肉|大面|停牌, evidence}]。
 * 该词表同时是二期 L2 DSL 信号源词汇（§12.7.1：YAML 可写 `label: 反包 within_days: 3`）。
 */
enum class StockActionLabel(val label: String) {
    /** 反包：连板股断板后（1-3 日观察期内）再次涨停、包住断板日实体 */
    REBREAK("反包"),

    /** 晋级：连板股今日继续涨停，n 板 → n+1 板 */
    PROMOTE("晋级"),

    /** 断板：连板股今日未涨停，连板中断 */
    BROKEN("断板"),

    /** 反核止跌：崩塌股被资金逆势承接拉回止跌甚至涨停（≥+5% 或涨停） */
    REBOUND_STOP("反核止跌"),

    /** 继续大面：昨日大面名单今日仍 ≤-5% */
    CONTINUE_FACE("继续大面"),

    /** 大肉：高位强势股今日大涨（≥+5% 或涨停） */
    BIG_MEAT("大肉"),

    /** 大面：高位股今日大跌（≤-5%） */
    BIG_FACE("大面"),

    /** 停牌：交易日历开市但无 bar */
    SUSPENDED("停牌"),
    ;

    companion object {
        /** 中文字面 → 枚举（evidence 解析用；未知返回 null） */
        fun fromLabel(label: String?): StockActionLabel? =
            entries.firstOrNull { it.label == label }
    }
}
