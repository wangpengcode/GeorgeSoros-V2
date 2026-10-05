package com.soros.v2.util

import com.soros.v2.config.IntradayProperties
import org.springframework.stereotype.Component

/**
 * §19.13.2 决策 5 盘中独立 TokenBucket（与日 K 桶分家，回填高峰不饿死盘中、盘中节奏不叠加进日 K 桶）。
 *
 * 3 参数组独立限流（配置化 + 抖动，外部数据源红线：TokenBucket 限流+抖动，禁止连续猛打）：
 * - [Group.POOLS]   池接口（ZT/ZB/DT）：0.10 rps、容量 3、抖动 5s（每池间隔 ≥15s，每分钟 ≤6 次池请求）
 * - [Group.SPOT]   全市场快照：0.012 rps（≈1/85s）、容量 1、抖动 30s（90s 一轮 + 随机抖动）
 * - [Group.BID_ASK] 五档：0.5 rps（30/min 上限）、容量 10、抖动 0.2s（候选名单 30s 一轮分片轮询，单轮 ≤15 次）
 *
 * [tryAcquire] 非阻塞：有令牌立即扣减返回 true，否则 false（调用方本轮 defer，不阻塞轮询循环）。
 * 抖动由调用方在取令牌成功后按 [jitterMillis] 应用（协程内 `delay`，不打同步线程）。
 */
@Component
class IntradayTokenBucket(private val properties: IntradayProperties) {

    enum class Group { POOLS, SPOT, BID_ASK }

    private val pools = TokenBucket(properties.poolsRate, properties.poolsCapacity.toDouble(), properties.poolsJitterSeconds)
    private val spot = TokenBucket(properties.spotRate, properties.spotCapacity.toDouble(), properties.spotJitterSeconds)
    private val bidAsk = TokenBucket(properties.bidAskRate, properties.bidAskCapacity.toDouble(), properties.bidAskJitterSeconds)

    /** 非阻塞取令牌（无令牌返回 false；调用方本轮 defer，不计数为源失败） */
    fun tryAcquire(group: Group): Boolean = bucket(group).tryAcquire()

    /** 取令牌成功后应施加的抖动（毫秒；调用方协程 `delay` 应用） */
    fun jitterMillis(group: Group): Long = (bucket(group).jitterSeconds * 1000).toLong()

    /** 重置桶（测试 / 运维恢复） */
    fun reset(group: Group) = bucket(group).reset()

    private fun bucket(group: Group): TokenBucket = when (group) {
        Group.POOLS -> pools
        Group.SPOT -> spot
        Group.BID_ASK -> bidAsk
    }
}

/**
 * 令牌桶核心（单调时钟 refill，线程安全）。
 *
 * - 容量下限 1.0：rate<1（如 spot 0.012）时令牌仍可攒到扣减阈值（与 Python rate_limiter.py 同语义）；
 * - [clock] 注入便于测试推进时间验证 refill。
 */
class TokenBucket(
    val rate: Double,
    val capacity: Double,
    val jitterSeconds: Double = 0.0,
    private val clock: () -> Long = System::nanoTime,
) {
    private var tokens: Double = maxOf(capacity, 1.0)
    private var last: Long = clock()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val elapsedSec = (now - last) / 1e9
        tokens = minOf(maxOf(capacity, 1.0), tokens + elapsedSec * rate)
        last = now
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        tokens = maxOf(capacity, 1.0)
        last = clock()
    }
}
