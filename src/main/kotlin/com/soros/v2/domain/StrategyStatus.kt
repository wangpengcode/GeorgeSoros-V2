package com.soros.v2.domain

/**
 * 策略配置状态值域单点（schema.sql strategy_config.status CHECK IN ('DRAFT','ACTIVE','RETIRED')）。
 *
 * 服务层新建策略用 [DRAFT]（§12.9 决策 1 无自由 YAML 文本，落库即权威）。
 */
enum class StrategyStatus(val label: String) {
    /** 草稿（新建默认） */
    DRAFT("DRAFT"),

    /** 启用 */
    ACTIVE("ACTIVE"),

    /** 退役 */
    RETIRED("RETIRED"),
    ;
}
