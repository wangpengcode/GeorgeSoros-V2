package com.soros.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * DataSourceType.fromPython 契约测试（2026-10-04 第四源落地）。
 *
 * 背景（30,255 行 UNKNOWN 根因）：Python 新浪 failover 归因 "akshare-sina"（连字符），
 * 旧实现 `it.name.equals(value, ignoreCase=true)` 严格比对枚举名（下划线）→ 降级 UNKNOWN。
 * 修复后归一化：'-' → '_'；新增 AKSHARE_SINA / YAHOO 值域。
 */
class DataSourceTypeTest {

    @Test
    fun `testFromPython akshare-sina hyphen normalizes to AKSHARE_SINA`() {
        // 连字符（Python serving_source 实际值）→ 枚举下划线名
        assertEquals(DataSourceType.AKSHARE_SINA, DataSourceType.fromPython("akshare-sina"))
    }

    @Test
    fun `testFromPython akshare_sina underscore also matches`() {
        assertEquals(DataSourceType.AKSHARE_SINA, DataSourceType.fromPython("akshare_sina"))
    }

    @Test
    fun `testFromPython yahoo maps YAHOO`() {
        assertEquals(DataSourceType.YAHOO, DataSourceType.fromPython("yahoo"))
    }

    @Test
    fun `testFromPython core sources case-insensitive`() {
        assertEquals(DataSourceType.BAOSTOCK, DataSourceType.fromPython("baostock"))
        assertEquals(DataSourceType.AKSHARE, DataSourceType.fromPython("AKSHARE"))
        assertEquals(DataSourceType.MOOTDX, DataSourceType.fromPython("Mootdx"))
    }

    @Test
    fun `testFromPython unknown value falls back UNKNOWN`() {
        assertEquals(DataSourceType.UNKNOWN, DataSourceType.fromPython("weird-source"))
        assertEquals(DataSourceType.UNKNOWN, DataSourceType.fromPython(""))
    }

    @Test
    fun `testFromPython tencent maps TENCENT`() {
        assertEquals(DataSourceType.TENCENT, DataSourceType.fromPython("tencent"))
        assertEquals(DataSourceType.TENCENT, DataSourceType.fromPython("TENCENT"))
    }

    @Test
    fun `testFromPython sse maps SSE`() {
        assertEquals(DataSourceType.SSE, DataSourceType.fromPython("sse"))
        assertEquals(DataSourceType.SSE, DataSourceType.fromPython("SSE"))
    }

    @Test
    fun `testFromPython v6 value domain full round-trip`() {
        // V6 值域完整断言：六数据源 + AKSHARE_SINA 子源标签 + UNKNOWN 全覆盖 fromPython 往返
        // （与 V6 迁移文件 stock_history_data_source_check 白名单 8 值逐一对拍）
        val domain = mapOf(
            "baostock" to DataSourceType.BAOSTOCK,
            "akshare" to DataSourceType.AKSHARE,
            "akshare-sina" to DataSourceType.AKSHARE_SINA,
            "mootdx" to DataSourceType.MOOTDX,
            "yahoo" to DataSourceType.YAHOO,
            "tencent" to DataSourceType.TENCENT,
            "sse" to DataSourceType.SSE,
            "unknown" to DataSourceType.UNKNOWN,
        )
        domain.forEach { (pythonValue, expected) ->
            assertEquals(expected, DataSourceType.fromPython(pythonValue), "fromPython('$pythonValue')")
        }
    }

    @Test
    fun `testEnum contains all values for CHECK constraint V6`() {
        // V6 CHECK 白名单值域 8 值全覆盖（DB 层同步扩展；顺序无关）
        assert(DataSourceType.entries.map { it.name }.containsAll(
            listOf("BAOSTOCK", "AKSHARE", "AKSHARE_SINA", "MOOTDX",
                   "YAHOO", "TENCENT", "SSE", "UNKNOWN")
        ))
    }

    @Test
    fun `testEnum contains new values for CHECK constraint V5`() {
        // V5 CHECK 白名单值域（DB 层同步扩展；顺序无关）
        assert(DataSourceType.entries.map { it.name }.containsAll(listOf("AKSHARE_SINA", "YAHOO")))
    }
}
