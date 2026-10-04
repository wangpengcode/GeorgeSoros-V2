package com.soros.v2.domain

/**
 * 数据来源（stock_history.data_source CHECK 约束值域，V6 起含 AKSHARE_SINA/YAHOO/TENCENT/SSE）。
 *
 * Python JSON 的 `source` 字段为小写（baostock/akshare/mootdx/yahoo/tencent/sse），
 * 且存在连字符子源标签（"akshare-sina" = akshare 内部 EM→新浪 failover 归因），
 * 入库前必须映射到本枚举（大写白名单）——见 fromPython()。
 *
 * V6（2026-10-04 第五/第六渠道落地）：TENCENT（独立转发商，qfq/hfq 服务端自算）、
 * SSE（上交所行情云，源头级校准腿，仅 raw）。枚举与 CHECK 同批变更纪律见 V6 迁移文件头。
 */
enum class DataSourceType {
    BAOSTOCK,
    AKSHARE,
    AKSHARE_SINA,
    MOOTDX,
    YAHOO,
    TENCENT,
    SSE,
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
