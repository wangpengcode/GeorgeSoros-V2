package com.soros.v2.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §19.13.2 IntradayTokenBucket / TokenBucket 单测（可注入时钟推进时间验证 refill）。
 *
 * 契约（§19.13.2 决策 5 + 外部数据源红线：TokenBucket 限流+抖动，禁止连续猛打）：
 * - 满容量立即可取，取空后立即再取 false；
 * - 时间推进 refill：rate=r 时，每 1/r 秒回补 1 个令牌；
 * - 容量下限 1.0：rate<1（如 spot 0.012）时仍可攒到扣减阈值（与 Python rate_limiter.py 同语义）。
 */
class IntradayTokenBucketTest {

    /** 手工时钟（nanoTime 语义；测试直接推进；实现 () -> Long 便于注入 TokenBucket.clock） */
    private class ManualClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `testTryAcquire fullCapacityImmediateThenExhausted`() {
        val clock = ManualClock()
        val bucket = TokenBucket(rate = 1.0, capacity = 3.0, jitterSeconds = 0.0, clock = clock)
        assertTrue(bucket.tryAcquire(), "满容量立即取 1")
        assertTrue(bucket.tryAcquire(), "满容量立即取 2")
        assertTrue(bucket.tryAcquire(), "满容量立即取 3")
        assertFalse(bucket.tryAcquire(), "容量 3 取空后立即再取 false")
    }

    @Test
    fun `testTryAcquire refillOverTimeAtRate`() {
        val clock = ManualClock()
        val bucket = TokenBucket(rate = 0.5, capacity = 2.0, jitterSeconds = 0.0, clock = clock)
        assertTrue(bucket.tryAcquire(), "满容量 2 取 1")
        assertTrue(bucket.tryAcquire(), "满容量 2 取 2")
        assertFalse(bucket.tryAcquire(), "取空后立即再取 false")

        clock.now += 1_000_000_000L   // 过 1s：rate=0.5 → 回补 0.5 个令牌，仍不足 1
        assertFalse(bucket.tryAcquire(), "只回补 0.5 个令牌仍不足")

        clock.now += 1_000_000_000L   // 再过 1s：累计回补 1.0 → 可取
        assertTrue(bucket.tryAcquire(), "累计回补 1.0 令牌后可取")
    }

    @Test
    fun `testCapacityFloorOneAllowsBurstForSubUnitRate`() {
        // spot 组 rate=0.012、capacity=1：容量下限 max(capacity,1)=1，首令牌立即可取
        val clock = ManualClock()
        val bucket = TokenBucket(rate = 0.012, capacity = 1.0, jitterSeconds = 0.0, clock = clock)
        assertTrue(bucket.tryAcquire(), "容量下限 1.0 首令牌可取")
        assertFalse(bucket.tryAcquire(), "取后立即再取 false（refill 需 ≈83s）")
    }

    @Test
    fun `testReset restoresTokensToCapacity`() {
        val clock = ManualClock()
        val bucket = TokenBucket(rate = 1.0, capacity = 2.0, jitterSeconds = 0.0, clock = clock)
        assertTrue(bucket.tryAcquire())
        assertTrue(bucket.tryAcquire())
        assertFalse(bucket.tryAcquire(), "取空")
        bucket.reset()
        assertTrue(bucket.tryAcquire(), "reset 后满容量立即取")
    }
}
