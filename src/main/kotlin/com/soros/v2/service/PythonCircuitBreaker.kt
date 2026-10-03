package com.soros.v2.service

import com.soros.v2.config.PythonClientProperties
import org.springframework.stereotype.Component

/**
 * §13.2 Kotlin 侧 Python 整体熔断：连续 20 次失败 → open 60s → half-open 单探测。
 *
 * 状态机：CLOSED（全放行）→ OPEN（拒绝 + 计时）→ HALF_OPEN（单探测请求）→ CLOSED / OPEN。
 * 线程安全：所有状态变更 @Synchronized。
 */
@Component
class PythonCircuitBreaker(
    private val properties: PythonClientProperties,
) {

    private val failureThreshold: Int = properties.circuitBreakerFailureThreshold
    private val openMillis: Long = properties.circuitBreakerOpenSeconds * 1000L

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state: State = State.CLOSED
    private var failureCount: Int = 0
    private var openedAtMillis: Long = 0L

    /**
     * 请求前询问：是否放行本次调用。
     * - CLOSED → true；OPEN 且超时 → 转 HALF_OPEN 放行单探测；OPEN 未超时 → false；HALF_OPEN 已有探测 → false。
     */
    @Synchronized
    fun allowRequest(): Boolean = when (state) {
        State.CLOSED -> true
        State.OPEN -> {
            if (System.currentTimeMillis() - openedAtMillis >= openMillis) {
                state = State.HALF_OPEN
                true
            } else {
                false
            }
        }
        State.HALF_OPEN -> false
    }

    /** 调用失败登记：CLOSED 连续失败 ≥ failureThreshold → OPEN；HALF_OPEN 失败 → 回 OPEN */
    @Synchronized
    fun recordFailure() {
        when (state) {
            State.CLOSED -> {
                failureCount++
                if (failureCount >= failureThreshold) {
                    state = State.OPEN
                    openedAtMillis = System.currentTimeMillis()
                }
            }
            State.HALF_OPEN -> {
                state = State.OPEN
                failureCount = 0
                openedAtMillis = System.currentTimeMillis()
            }
            State.OPEN -> Unit // 保持 OPEN，计时不重置（等 allowRequest 超时转 HALF_OPEN 放行单探测）
        }
    }

    /** 调用成功登记：计数清零；HALF_OPEN 成功 → CLOSED */
    @Synchronized
    fun recordSuccess() {
        failureCount = 0
        if (state == State.HALF_OPEN) {
            state = State.CLOSED
        }
    }
}
