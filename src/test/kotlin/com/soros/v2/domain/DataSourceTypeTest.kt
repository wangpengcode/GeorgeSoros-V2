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
    fun `testEnum contains new values for CHECK constraint V5`() {
        // V5 CHECK 白名单值域（DB 层同步扩展；顺序无关）
        assert(DataSourceType.entries.map { it.name }.containsAll(listOf("AKSHARE_SINA", "YAHOO")))
    }
}
