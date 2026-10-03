package com.soros.v2.domain

import com.soros.v2.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 领域枚举（PLAN §2.4 枚举表）纯单测：
 * - Board.fromPython：未知值抛 BusinessException（契约漂移视为业务异常）；
 * - DataSourceType.fromPython：小写→大写白名单、未知→UNKNOWN 降级不抛（failover 可见性）；
 * - DingTalkEvent 事件→级别映射（§11.3 表：ERROR 只留给"数据可能错"，状态类 WARN，INFO=每日 digest）。
 */
class EnumsTest {

    // ==================== Board ====================

    @Test
    fun `testBoard fromPython knownValues`() {
        // given: Python /stock-list board 值已与 §2.4 枚举对齐
        // when & then
        assertEquals(Board.MAIN, Board.fromPython("MAIN"), "MAIN→主板")
        assertEquals(Board.GEM, Board.fromPython("GEM"), "GEM→创业板")
        assertEquals(Board.STAR, Board.fromPython("STAR"), "STAR→科创板")
    }

    @Test
    fun `testBoard fromPython unknownThrowsBusinessException`() {
        // given: 北交所/B 股/未知值
        // when & then: 视为契约漂移抛业务异常（不静默降级——board 决定涨停阈值，错了会错判）
        assertThrows(BusinessException::class.java) {
            Board.fromPython("BJ")
        }
        assertThrows(BusinessException::class.java) {
            Board.fromPython("UNKNOWN")
        }
        assertThrows(BusinessException::class.java) {
            Board.fromPython("")
        }
    }

    // ==================== DataSourceType ====================

    @Test
    fun `testDataSourceType fromPython lowercase maps to uppercase`() {
        // given: Python JSON 的 source 为小写（baostock/akshare/mootdx）
        // when & then
        assertEquals(DataSourceType.BAOSTOCK, DataSourceType.fromPython("baostock"), "baostock→BAOSTOCK")
        assertEquals(DataSourceType.AKSHARE, DataSourceType.fromPython("akshare"), "akshare→AKSHARE")
        assertEquals(DataSourceType.MOOTDX, DataSourceType.fromPython("mootdx"), "mootdx→MOOTDX")
        // ignoreCase 容错
        assertEquals(DataSourceType.AKSHARE, DataSourceType.fromPython("AKSHARE"), "大写 AKSHARE 同样映射")
        assertEquals(DataSourceType.MOOTDX, DataSourceType.fromPython("MootDx"), "混合大小写同样映射")
    }

    @Test
    fun `testDataSourceType fromPython unknownDegradesToUNKNOWN`() {
        // given: 未知源（Python 侧新增数据源时的 failover 可见性）
        // when & then: 降级 UNKNOWN 不抛异常（区别于 Board——source 只做记录，不做业务分支）
        assertEquals(DataSourceType.UNKNOWN, DataSourceType.fromPython("new-source"), "未知源→UNKNOWN")
        assertEquals(DataSourceType.UNKNOWN, DataSourceType.fromPython(""), "空串→UNKNOWN")
    }

    // ==================== DingTalkEvent 级别 ====================

    @Test
    fun `testDingTalkEvent levelMapping follows section113 table`() {
        // given: §11.3 事件→级别→限频表
        // then: ERROR 只留给"数据可能错"
        assertEquals(DingTalkEventLevel.ERROR, DingTalkEvent.PYTHON_SERVICE_OFFLINE.level, "Python 离线=ERROR")
        assertEquals(DingTalkEventLevel.ERROR, DingTalkEvent.BATCH_FAILURE_RATE_HIGH.level, "批次失败率高=ERROR")
        assertEquals(DingTalkEventLevel.ERROR, DingTalkEvent.ADJUSTMENT_DRIFT_UNRESOLVED.level, "漂移未解决=ERROR")
        // 状态类一律 WARN
        assertEquals(DingTalkEventLevel.WARN, DingTalkEvent.DELIST_SUSPECT.level, "疑似退市=WARN（人工确认）")
        assertEquals(DingTalkEventLevel.WARN, DingTalkEvent.SOURCE_DEGRADED.level, "源降级=WARN")
        // 每日 digest=INFO
        assertEquals(DingTalkEventLevel.INFO, DingTalkEvent.DAILY_COLLECT_SUMMARY.level, "每日摘要=INFO")
    }
}
