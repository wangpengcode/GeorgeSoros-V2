package com.soros.v2.domain

/**
 * 钉钉告警事件级别（§11.3：ERROR 只留给"数据可能错"；状态类一律 WARN；INFO=每日 digest）。
 */
enum class DingTalkEventLevel { ERROR, WARN, INFO }

/**
 * 钉钉告警事件（§11.3 事件→级别→限频表）。
 *
 * 限频语义（由 DingTalkNotifier 实现，不在此处）：
 * - PYTHON_SERVICE_OFFLINE / BATCH_FAILURE_RATE_HIGH  → 同类 10 分钟 1 条
 * - ADJUSTMENT_DRIFT_UNRESOLVED                        → 每事件 1 条
 * - DELIST_SUSPECT                                     → 每股 1 条
 * - SOURCE_DEGRADED                                    → 同类 10 分钟 1 条
 * - DAILY_COLLECT_SUMMARY                              → 每日 digest
 * - CROSS_VALIDATE_MISMATCH                           → 每交易日 1 条（§11.2 只观测不修正）
 */
enum class DingTalkEvent(val level: DingTalkEventLevel) {
    PYTHON_SERVICE_OFFLINE(DingTalkEventLevel.ERROR),
    BATCH_FAILURE_RATE_HIGH(DingTalkEventLevel.ERROR),
    ADJUSTMENT_DRIFT_UNRESOLVED(DingTalkEventLevel.ERROR),
    DELIST_SUSPECT(DingTalkEventLevel.WARN),
    SOURCE_DEGRADED(DingTalkEventLevel.WARN),
    CROSS_VALIDATE_MISMATCH(DingTalkEventLevel.ERROR),
    CALIBRATION_MISMATCH(DingTalkEventLevel.WARN),
    SENTIMENT_DERIVE_FAILED(DingTalkEventLevel.ERROR),
    SIGNAL_DERIVE_FAILED(DingTalkEventLevel.ERROR),
    DAILY_COLLECT_SUMMARY(DingTalkEventLevel.INFO),
    // ── 盘中监控（§19.13.2 钉钉告警仅 3 类：源停轮 / 高位大面 / 最高板易主）──────
    /** 盘中源连续失败停轮（退避铁律：连续 6 次失败 → 停轮 300s + 告警；§19.13.2） */
    INTRADAY_SOURCE_STOPPED(DingTalkEventLevel.WARN),
    /** 高位股大面（强势池成员现价 ≤-5%，ev_type=DM；§14.5） */
    INTRADAY_BIG_FACE(DingTalkEventLevel.WARN),
    /** 最高板易主（龙头炸板自然触发，ev_type=MAXCHG；§14.5） */
    INTRADAY_MAX_CHANGE(DingTalkEventLevel.WARN),
    ;
}
