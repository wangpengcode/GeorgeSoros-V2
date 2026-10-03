package com.soros.v2.util

import com.soros.v2.service.dto.DailyBar
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.7 防线② DataValidator 行内自洽校验（纯函数单测，无 Spring）。
 *
 * 校验规则（PLAN §4.7 防线②）：
 * - high ≥ max(open, close)、low ≤ min(open, close)
 * - 四价均 > 0 且 close ∈ [low, high]
 * - volume ≥ 0
 * - 持有 prev_close 时交叉校验 |change_pct − (close−prev_close)/prev_close×100| ≤ 0.2%
 *   （除权日豁免：§4.6 已识别漂移的 bar 不参与本交叉校验——由 saveBatch 层保证，不在本对象）
 *
 * 非法行由 saveBatch 拦截：不落库 + 记日志；不抛出中断整批（防御性降级）。
 */
class DataValidatorTest {

    /** 构造一根合法 bar（open=10.00 / high=10.50 / low=9.50 / close=10.20，change=+2.0%，prev_close=10.00） */
    private fun bar(
        open: BigDecimal = BigDecimal("10.00"),
        high: BigDecimal = BigDecimal("10.50"),
        low: BigDecimal = BigDecimal("9.50"),
        close: BigDecimal = BigDecimal("10.20"),
        volume: Long = 1_000_000L,
        changePct: BigDecimal = BigDecimal("2.00"),
        prevClose: BigDecimal? = BigDecimal("10.00"),
    ) = DailyBar(
        date = LocalDate.of(2026, 9, 30),
        code = "600000",
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        amount = BigDecimal("10200000.00"),
        changePercent = changePct,
        turnover = BigDecimal("1.50"),
        prevClose = prevClose,
    )

    // ==================== 正常流程 ====================

    @Test
    fun `testValidate wellFormedBar valid`() {
        // given: 四价自洽 + 量>0 + prev_close 交叉校验一致
        val result = DataValidator.validate(bar())
        // then
        assertTrue(result.valid, "自洽行应通过校验")
        assertTrue(result.violations.isEmpty(), "无违规项，violations 应为空")
    }

    @Test
    fun `testValidate prevCloseCrossCheckWithinTolerance`() {
        // given: (10.20-10.00)/10.00×100 = 2.00，change_pct=2.00 → |2.00-2.00|=0 ≤ 0.2
        val result = DataValidator.validate(bar())
        assertTrue(result.valid, "交叉校验差 0.00 ≤ 0.2%，通过")
    }

    @Test
    fun `testValidate prevCloseNull skipsCrossCheck`() {
        // given: prev_close=null（AKShare 无昨收列，§4.6 降级：跳过涨跌幅对拍，链式校验仍独立有效）
        val result = DataValidator.validate(bar(prevClose = null))
        // then: 交叉校验被跳过，行仍有效
        assertTrue(result.valid, "prev_close=null 时交叉校验跳过，行内自洽即可通过")
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testValidate highLessThanMaxOpenClose invalid`() {
        // given: high=9.00 < max(open=10.00, close=10.20)=10.20
        val result = DataValidator.validate(bar(high = BigDecimal("9.00")))
        // then
        assertFalse(result.valid, "high < max(open,close) 必须判非法（violations 含 high 违规）")
        assertTrue(result.violations.any { it.contains("high") }, "violations 应点名 high 字段")
    }

    @Test
    fun `testValidate lowGreaterThanMinOpenClose invalid`() {
        // given: low=10.30 > min(open=10.00, close=10.20)=10.00
        val result = DataValidator.validate(bar(low = BigDecimal("10.30")))
        // then
        assertFalse(result.valid, "low > min(open,close) 必须判非法（violations 含 low 违规）")
        assertTrue(result.violations.any { it.contains("low") }, "violations 应点名 low 字段")
    }

    @Test
    fun `testValidate closeOutsideLowHigh invalid`() {
        // given: close=10.50 > high=10.50? 用 close=10.60 超出 [low, high]
        val result = DataValidator.validate(bar(close = BigDecimal("10.60")))
        // then: close 必须在 [low, high] 区间内
        assertFalse(result.valid, "close 超出 [low,high] 区间必须判非法")
        assertTrue(result.violations.any { it.contains("close") }, "violations 应点名 close 字段")
    }

    @Test
    fun `testValidate nonPositiveOpen invalid`() {
        // given: open=0（四价均需 >0）
        val result = DataValidator.validate(bar(open = BigDecimal("0.00")))
        // then
        assertFalse(result.valid, "open=0 违反四价均>0，判非法")
        assertTrue(result.violations.any { it.contains("open") }, "violations 应点名 open 字段")
    }

    @Test
    fun `testValidate negativeVolume invalid`() {
        // given: volume=-1
        val result = DataValidator.validate(bar(volume = -1L))
        // then
        assertFalse(result.valid, "volume<0 判非法")
        assertTrue(result.violations.any { it.contains("volume") }, "violations 应点名 volume 字段")
    }

    @Test
    fun `testValidate prevCloseCrossCheckOutOfTolerance invalid`() {
        // given: change_pct=2.50 vs 实际 2.00 → |2.50-2.00|=0.50 > 0.2
        val result = DataValidator.validate(bar(changePct = BigDecimal("2.50")))
        // then
        assertFalse(result.valid, "交叉校验差 0.50 > 0.2%，判非法（prev_close 对拍防线）")
        assertTrue(result.violations.any { it.contains("change") || it.contains("prev") }, "violations 应点名涨跌幅/昨收交叉项")
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testValidate multipleViolations allCollected`() {
        // given: 同时违反 high 与 close 两条（violations 需收集全部违规项，不中断）
        val result = DataValidator.validate(
            bar(high = BigDecimal("9.00"), close = BigDecimal("10.60")),
        )
        // then: valid=false，且两条违规都在 violations 中
        assertFalse(result.valid, "多违规行应判非法")
        assertEquals(
            result.violations.size,
            result.violations.filter { it.contains("high") || it.contains("close") }.size,
            "violations 必须收集全部违规项（不得只报第一条）",
        )
        assertTrue(result.violations.any { it.contains("high") }, "high 违规在列")
        assertTrue(result.violations.any { it.contains("close") }, "close 违规在列")
    }

    @Test
    fun `testValidate crossCheck boundary exactlyTolerance`() {
        // given: 差恰好 0.2%（容差边界，<= 0.2 通过）
        // 实际涨跌 = 2.00，change_pct=2.20 → |2.20-2.00|=0.20 == 0.2
        val result = DataValidator.validate(bar(changePct = BigDecimal("2.20")))
        // then: 容差边界 <= 0.2 应通过（不误伤）
        assertTrue(result.valid, "交叉校验差恰好 0.20 == 容差 0.2%，应通过")
    }

    @Test
    fun `testValidate description joinsViolations`() {
        // given: 高开 + 低破 同时违规
        val result = DataValidator.validate(
            bar(open = BigDecimal("0.00"), high = BigDecimal("9.00")),
        )
        // then: description 为 violations 的分号拼接（供日志/质量日志 detail 使用）
        assertFalse(result.valid, "双违规判非法")
        assertEquals(result.violations.joinToString("; "), result.description, "description 应为 violations 分号拼接")
    }
}
