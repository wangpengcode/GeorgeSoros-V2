package com.soros.v2.domain

/**
 * 数据质量问题类型（data_quality_log.issue_type 值域单点；schema.sql 无 CHECK，代码 enum 单点）。
 *
 * §2.4 枚举允许值 + §17.1 B2 扩展：
 * - ADJUSTMENT_DRIFT            除权漂移（§4.6 检测命中，触发单股全量重拉）
 * - ADJUSTMENT_DRIFT_UNRESOLVED 漂移重拉后仍不一致（人工介入）
 * - RAW_FALLBACK                mootdx 不复权降级源写入
 * - CROSS_VALIDATE_MISMATCH     双源交叉验证差异（§11.2 只观测不修正）
 * - DELIST_SUSPECT              连续 20 交易日无行，疑似退市（人工确认才置 delisted）
 * - NO_BAR_TODAY                当日无行（停牌正常现象，不告警不置位）
 * - CONDITION_SKIP              策略条件输入为 null 时跳过（§17.1 B2）
 * - VALIDATION_REJECTED         DataValidator.validate() 校验失败行拒绝入库（§4.7 防线②，Kotlin 侧质量日志）
 */
enum class QualityIssueType {
    ADJUSTMENT_DRIFT,
    ADJUSTMENT_DRIFT_UNRESOLVED,
    RAW_FALLBACK,
    CROSS_VALIDATE_MISMATCH,
    CALIBRATION_MISMATCH,
    DELIST_SUSPECT,
    NO_BAR_TODAY,
    CONDITION_SKIP,
    VALIDATION_REJECTED,
    ;
}
