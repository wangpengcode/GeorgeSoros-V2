package com.soros.v2.domain

/**
 * 名单人工增删动作（lists_manual_json.action 值域单点；§11.1 PUT /sentiment-cycle/{date}/lists）。
 */
enum class PoolAction(val apiValue: String) {
    /** 添加个股（兜底系统漏判/误判） */
    ADD("ADD"),

    /** 移除个股（逐股 ✕ 移除） */
    REMOVE("REMOVE"),
    ;

    companion object {
        /** API 字面 → 枚举（请求体 action 解析用） */
        fun fromApi(value: String?): PoolAction? = entries.firstOrNull { it.apiValue == value }
    }
}
