package com.soros.v2.service

import com.soros.v2.config.PythonClientProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §13.2 Kotlin 侧 Python 整体熔断状态机（纯单测，无 Spring）。
 *
 * 契约（§13.2 / 类 KDoc）：
 * - CLOSED（全放行）→ OPEN（拒绝 + 计时）→ HALF_OPEN（单探测请求）→ CLOSED / OPEN；
 * - allowRequest：CLOSED→true；OPEN 未超时→false；OPEN 超时→转 HALF_OPEN 放行单探测；HALF_OPEN 已有探测→false；
 * - recordFailure：CLOSED 连续失败 ≥ failureThreshold → OPEN；HALF_OPEN 失败 → 回 OPEN；
 * - recordSuccess：计数清零；HALF_OPEN 成功 → CLOSED。
 *
 * 红先行说明：当前为骨架（allowRequest/recordFailure/recordSuccess 为 TODO），本测试定义状态机契约；
 * Implementer 填充实现后即绿。时间推进用 openSeconds=1 的真实小步 sleep（~1s，不真等 60s）。
 */
class PythonCircuitBreakerTest {

    /** 构造小阈值小窗口熔断配置（threshold=2、open=1s），避免等真实 20 次失败/60s */
    private fun breaker(threshold: Int = 2, openSeconds: Int = 1): PythonCircuitBreaker {
        val props = PythonClientProperties().apply {
            circuitBreakerFailureThreshold = threshold
            circuitBreakerOpenSeconds = openSeconds
        }
        return PythonCircuitBreaker(props)
    }

    // ==================== 正常流程 ====================

    @Test
    fun `testAllowRequest initialClosed allowsAll`() {
        // given: 初始 CLOSED
        val cb = breaker()
        // when & then: CLOSED 全放行
        assertTrue(cb.allowRequest(), "CLOSED 态允许所有请求")
    }

    @Test
    fun `testCircuitBreaker opensAfterFailureThreshold`() {
        // given: threshold=2
        val cb = breaker(threshold = 2)

        // when: 1 次失败未达阈值
        cb.recordFailure()
        assertTrue(cb.allowRequest(), "连续失败 1 次 < 阈值 2，仍放行")

        // when: 第 2 次失败达阈值
        cb.recordFailure()
        assertFalse(cb.allowRequest(), "连续失败达阈值后 OPEN，拒绝请求（未超时）")
    }

    @Test
    fun `testAllowRequest afterOpenTimeout halfOpen singleProbe`() {
        // given: 熔断已 OPEN（threshold=2，open=1s）
        val cb = breaker(threshold = 2)
        cb.recordFailure()
        cb.recordFailure()
        assertFalse(cb.allowRequest(), "OPEN 未超时拒绝")

        // when: 等待 openMillis 过后
        Thread.sleep(1100)

        // then: 转 HALF_OPEN 放行单探测
        assertTrue(cb.allowRequest(), "OPEN 超时转 HALF_OPEN 放行单探测")
        // then: HALF_OPEN 已有探测，后续请求拒绝（防并发打满）
        assertFalse(cb.allowRequest(), "HALF_OPEN 单探测进行中，其余请求拒绝")
    }

    @Test
    fun `testRecordSuccess halfOpenCloses`() {
        // given: HALF_OPEN（单探测已放行）
        val cb = breaker(threshold = 2)
        cb.recordFailure()
        cb.recordFailure()
        Thread.sleep(1100)
        assertTrue(cb.allowRequest(), "探测请求放行")

        // when: 探测成功
        cb.recordSuccess()

        // then: 回 CLOSED，请求恢复全放行
        assertTrue(cb.allowRequest(), "HALF_OPEN 成功→CLOSED，恢复全放行")
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testRecordFailure halfOpenReopens`() {
        // given: HALF_OPEN
        val cb = breaker(threshold = 2)
        cb.recordFailure()
        cb.recordFailure()
        Thread.sleep(1100)
        assertTrue(cb.allowRequest(), "探测请求放行")

        // when: 探测失败
        cb.recordFailure()

        // then: 回 OPEN（未超时）拒绝
        assertFalse(cb.allowRequest(), "HALF_OPEN 失败回 OPEN，拒绝请求")
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testRecordSuccess closedResetsFailureCount`() {
        // given: 接近阈值（1 次失败）
        val cb = breaker(threshold = 2)
        cb.recordFailure()

        // when: 成功复位
        cb.recordSuccess()
        cb.recordFailure()
        // then: 复位后需重新累计（1+1=2 才 OPEN，若未复位则 1 次失败已达旧累计 2）
        assertTrue(cb.allowRequest(), "recordSuccess 计数清零，CLOSED 下需重新累计满阈值才 OPEN")
        cb.recordFailure()
        assertFalse(cb.allowRequest(), "清零后重新累计达阈值 OPEN")
    }

    @Test
    fun `testAllowRequest neverOpensOnSuccessStreak`() {
        // given: threshold=1（任何一次失败即 OPEN）
        val cb = breaker(threshold = 1)
        // when: 成功夹杂失败
        cb.recordSuccess()
        cb.recordSuccess()
        // then: 仍 CLOSED
        assertTrue(cb.allowRequest(), "只有 recordFailure 才累计，recordSuccess 恒清零")
    }
}
