package com.soros.v2.service.intraday

import com.soros.v2.config.IntradayProperties
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * §19.13.2 盘中轮询运行时态（内存态，不入库，重启清零可接受——§17.6 定稿）。
 *
 * 每个源组（pools/spot/bid_ask）维护：最近成功时间 last_ok_at / 连续失败计数 / 退避状态。
 * 退避铁律（§19.13.2，§14.3 同款）：
 * - 连续 [backoffThreshold]=3 次失败 → BACKOFF 降频 2 倍（奇数轮跳过）；
 * - 连续 [stopThreshold]=6 次失败 → STOPPED 停轮 [stopCooldownSeconds]=300s + 钉钉告警（recordFailure 返回值标识跨阈值）。
 */
@Component
class IntradayPollState(private val properties: IntradayProperties) {

    enum class Source(val key: String) {
        /** 池接口（/intraday/pools） */
        POOLS("pools"),

        /** 全市场快照（/intraday/spot） */
        SPOT("spot"),

        /** 五档（/intraday/bid-ask） */
        BID_ASK("bid_ask"),
        ;

        companion object {
            /** 字面 → 枚举（overview 回读） */
            fun fromKey(value: String): Source? = entries.firstOrNull { it.key == value }
        }
    }

    enum class RateState { NORMAL, BACKOFF, STOPPED }

    /** 单源组运行时态（内存；rounds=attempt+skip 计数，BACKOFF 按奇偶降频） */
    data class SourceState(
        var ok: Boolean = true,
        var lastOkAt: LocalDateTime? = null,
        var consecutiveFailures: Int = 0,
        var lastStopAt: LocalDateTime? = null,
        var rounds: Long = 0L,
    )

    private val states = ConcurrentHashMap<Source, SourceState>()

    /**
     * 轮询入口：推进 rounds 并返回本轮是否应跳过（true=跳过）。
     * - NORMAL → 不跳；BACKOFF → 奇数轮跳过（降频 2 倍）；STOPPED → 冷却窗口内跳过。
     */
    fun beginRound(source: Source, now: LocalDateTime): Boolean {
        val s = states.computeIfAbsent(source) { SourceState() }
        s.rounds++
        return when (rateState(source)) {
            RateState.STOPPED -> {
                val lastStop = s.lastStopAt
                lastStop != null && now.isBefore(lastStop.plusSeconds(properties.stopCooldownSeconds))
            }
            RateState.BACKOFF -> s.rounds % 2 == 1L
            RateState.NORMAL -> false
        }
    }

    /** 当前退避状态（consecutiveFailures ≥ stopThreshold → STOPPED；≥ backoffThreshold → BACKOFF） */
    fun rateState(source: Source): RateState {
        val failures = states[source]?.consecutiveFailures ?: 0
        return when {
            failures >= properties.stopThreshold -> RateState.STOPPED
            failures >= properties.backoffThreshold -> RateState.BACKOFF
            else -> RateState.NORMAL
        }
    }

    /** 调用成功登记：ok=true、连续失败清零、lastOkAt=now */
    fun recordSuccess(source: Source, now: LocalDateTime) {
        val s = states.computeIfAbsent(source) { SourceState() }
        s.ok = true
        s.consecutiveFailures = 0
        s.lastOkAt = now
    }

    /**
     * 调用失败登记：连续失败 +1，ok=false。
     * @return true 表示本次跨过 [stopThreshold]（应推一次钉钉停轮告警，去重语义）
     */
    fun recordFailure(source: Source, now: LocalDateTime): Boolean {
        val s = states.computeIfAbsent(source) { SourceState() }
        s.ok = false
        s.consecutiveFailures++
        val crossed = s.consecutiveFailures == properties.stopThreshold
        if (crossed) {
            s.lastStopAt = now
        }
        return crossed
    }

    /** 运行时态快照（overview / replay.panels 数据源；Map 转拷贝防并发修改） */
    fun snapshot(): Map<Source, SourceState> = states.toMap()
}
