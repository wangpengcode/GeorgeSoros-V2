package com.soros.v2.domain

/**
 * 情绪周期六阶段（sentiment_cycle.status_text 建议标签值域单点；schema.sql VARCHAR(50) 无 CHECK，代码 enum 单点）。
 *
 * §4.9 术语表：冰点=连板高度与大肉缩至底部、大面衰竭；退潮=大面激增、高标断板；
 * 混沌=涨跌互现无主线；发酵=梯队扩张；主升/高潮=最高板持续刷新、大肉批量。
 * 落库为中文 label（status_text 列）；建议标签由 SentimentClassifier 规则映射，人工终定。
 */
enum class SentimentStage(val label: String) {
    /** 冰点：连板高度与大肉缩至底部、大面衰竭 */
    ICE("冰点"),

    /** 退潮：大面激增、高标断板 */
    RECEDE("退潮"),

    /** 混沌：涨跌互现无主线 */
    CHAOS("混沌"),

    /** 发酵：梯队扩张 */
    FERMENT("发酵"),

    /** 主升：最高板持续刷新、大肉批量 */
    MAINRISE("主升"),

    /** 高潮：情绪顶格 */
    CLIMAX("高潮"),
    ;

    companion object {
        /** 中文字面 → 枚举（status_text 落库/回显解析用；未知返回 null） */
        fun fromLabel(label: String?): SentimentStage? =
            entries.firstOrNull { it.label == label }
    }
}
