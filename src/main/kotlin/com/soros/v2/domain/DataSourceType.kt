package com.soros.v2.domain

/**
 * 数据来源（stock_history.data_source / index_history.data_source CHECK 约束值域）。
 *
 * Python JSON 的 `source` 字段为小写（baostock/akshare/mootdx），
 * 入库前必须映射到本枚举（大写白名单）——见 fromPython()。
 */
enum class DataSourceType {
    BAOSTOCK,
    AKSHARE,
    MOOTDX,
    UNKNOWN,
    ;

    companion object {
        /** Python source（小写）→ 枚举；未知值降级 UNKNOWN（failover 可见性，不抛异常） */
        fun fromPython(value: String): DataSourceType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
    }
}
