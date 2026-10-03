package com.soros.v2.util

import com.soros.v2.domain.Board
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.5 涨停/跌停检测（纯函数单测，无 Spring）。
 *
 * 口径铁律（PLAN §4.5 / §17.6）：
 * - 判定只用数据源提供的**不复权** change_pct（真实涨跌幅），不从 qfq OHLC 自行计算；
 * - 阈值按 board：主板 10%（容差 9.9）、创业板/科创板 20%（容差 19.9）；
 * - ST 一律不在采集范围（§2.2 隔离原则）——本 Detector 不得出现任何 ST 分支（禁止引入 ST 业务可选项）。
 */
class LimitUpDetectorTest {

    // ==================== 正常流程 ====================

    @Test
    fun `testDetect main board limitUp at threshold`() {
        // given: 主板 change_pct = 9.9（阈值下限，>= 即涨停）
        val (up, down) = LimitUpDetector.detect(BigDecimal("9.90"), Board.MAIN)
        // then: 涨停 true / 跌停 false
        assertTrue(up, "主板 9.9% 应为涨停（容差 9.9，>= 阈值）")
        assertFalse(down, "9.9% 不是跌停")
    }

    @Test
    fun `testDetect main board limitDown at negative threshold`() {
        // given: 主板 change_pct = -9.9
        val (up, down) = LimitUpDetector.detect(BigDecimal("-9.90"), Board.MAIN)
        // then
        assertFalse(up, "-9.9% 不是涨停")
        assertTrue(down, "主板 -9.9% 应为跌停（<= -阈值）")
    }

    @Test
    fun `testDetect main board belowThreshold notLimit`() {
        // given: 主板 9.5% < 9.9
        val (up, down) = LimitUpDetector.detect(BigDecimal("9.50"), Board.MAIN)
        // then: 既不涨停也不跌停
        assertFalse(up, "9.5% 未达主板 9.9 阈值，非涨停")
        assertFalse(down, "9.5% 非跌停")
    }

    @Test
    fun `testDetect gem board twentyPercent limitUp`() {
        // given: 创业板 change_pct = 19.9（20cm 阈值下限）
        val (up, down) = LimitUpDetector.detect(BigDecimal("19.90"), Board.GEM)
        // then
        assertTrue(up, "创业板 19.9% 应为涨停（20cm 容差 19.9）")
        assertFalse(down, "19.9% 非跌停")
    }

    @Test
    fun `testDetect star board twentyPercent limitDown`() {
        // given: 科创板 -19.9
        val (up, down) = LimitUpDetector.detect(BigDecimal("-19.90"), Board.STAR)
        // then
        assertFalse(up, "-19.9% 非涨停")
        assertTrue(down, "科创板 -19.9% 应为跌停（20cm）")
    }

    @Test
    fun `testDetect gem board midRange notLimit`() {
        // given: 创业板 10% 远低于 20cm 阈值
        val (up, down) = LimitUpDetector.detect(BigDecimal("10.00"), Board.GEM)
        // then
        assertFalse(up, "创业板 10% 未达 20cm 阈值，非涨停（主板 10cm 阈值不适用于双创）")
        assertFalse(down, "10% 非跌停")
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testDetect main justBelowThreshold notLimit`() {
        // given: 主板 9.89 仅差 0.01 未达阈值
        val (up, _) = LimitUpDetector.detect(BigDecimal("9.89"), Board.MAIN)
        // then: 差 0.01 不得误判涨停（容差边界）
        assertFalse(up, "9.89% 未达 9.9 阈值，不得误标涨停")
    }

    @Test
    fun `testDetect main largeOverLimit bothLimitUp`() {
        // given: 主板 11% 远超阈值（新股/异常场景，Detector 不做拦截——IPO 守卫在 saveBatch 层）
        val (up, down) = LimitUpDetector.detect(BigDecimal("11.00"), Board.MAIN)
        // then: 仍是涨停
        assertTrue(up, "11% 超过主板 10cm 阈值，判定涨停")
        assertFalse(down, "11% 非跌停")
    }

    @Test
    fun `testDetect zero change notLimit`() {
        // given: 平盘
        val (up, down) = LimitUpDetector.detect(BigDecimal("0.00"), Board.MAIN)
        // then
        assertFalse(up, "平盘非涨停")
        assertFalse(down, "平盘非跌停")
    }
}
