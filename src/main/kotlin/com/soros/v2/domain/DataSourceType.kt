package com.soros.v2.domain

/**
 * 数据来源（stock_history.data_source CHECK 约束值域，V5 起含 AKSHARE_SINA/YAHOO）。
 *
 * Python JSON 的 `source` 字段为小写（baostock/akshare/mootdx/yahoo），
 * 且存在连字符子源标签（"akshare-sina" = akshare 内部 EM→新浪 failover 归因），
 * 入库前必须映射到本枚举（大写白名单）——见 fromPython()。
 */
enum class DataSourceType {
    BAOSTOCK,
    AKSHARE,
    AKSHARE_SINA,
    MOOTDX,
    YAHOO,
    UNKNOWN,
    ;

    companion object {
        /**
         * Python source → 枚举；未知值降级 UNKNOWN（failover 可见性，不抛异常）。
         * 归一化：忽略大小写 + 连字符→下划线（"akshare-sina" → AKSHARE_SINA）。
         * 2026-10-04 修复：旧实现严格比对枚举名，30,255 行 akshare-sina 数据降级 UNKNOWN。
         */
        fun fromPython(value: String): DataSourceType {
            val normalized = value.trim().replace('-', '_')
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}
