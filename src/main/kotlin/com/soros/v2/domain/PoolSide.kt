package com.soros.v2.domain

/**
 * 名单侧（lists_manual_json.side 值域单点；§11.1 PUT /sentiment-cycle/{date}/lists）。
 *
 * MEAT=大肉名单 / FACE=大面名单（强势池今日 ≥+5% / ≤-5%）。
 */
enum class PoolSide(val apiValue: String) {
    /** 大肉名单 */
    MEAT("MEAT"),

    /** 大面名单 */
    FACE("FACE"),
    ;

    companion object {
        /** API 字面 → 枚举（请求体 side 解析用） */
        fun fromApi(value: String?): PoolSide? = entries.firstOrNull { it.apiValue == value }
    }
}
