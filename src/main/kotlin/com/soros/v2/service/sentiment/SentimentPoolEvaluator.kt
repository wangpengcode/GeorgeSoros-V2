package com.soros.v2.service.sentiment

import com.soros.v2.config.SentimentProperties
import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.stereotype.Component

/**
 * §4.9 强势池/崩塌池判定（对象池定义，阈值全部配置化 soros.sentiment.pool 与 collapse）。
 *
 * 强势池（满足任一即入池，大肉/大面统计对象）：
 *   P1. 近 3 个交易日内最高 limit_up_streak >= pool.min-streak（默认 3）
 *   P2. 近 5 个交易日内涨停次数 >= pool.min-limit-ups（默认 2）
 *   P3. 近 5 个交易日累计涨幅 >= pool.min-5d-gain（默认 50%，qfq 收盘价窗口计算）
 *
 * 崩塌池（观察大面延续还是止跌）：
 *   C1. limit_down_streak >= collapse.min-streak（默认 2，连续跌停）
 *   C2. 近 5 个交易日累计跌幅 <= -collapse.max-5d-drop（默认 30%，高位崩下来）
 *
 * 判定为纯函数（入参确定 → 结果确定），SentimentCycleJob 落库与回放共用。
 */
@Component
class SentimentPoolEvaluator(
    private val properties: SentimentProperties,
) {

    /** 强势池判定：入参为该股近 5 交易日 bar 窗口（含当日，升序）；满足 P1/P2/P3 任一返回 true */
    fun isInStrongPool(bars: List<StockHistory>): Boolean {
        if (bars.isEmpty()) return false
        val window = bars.sortedBy { it.tradeDate }.takeLast(POOL_WINDOW)
        if (window.isEmpty()) return false
        // P1：近 3 个交易日内最高 limit_up_streak >= pool.min-streak
        val recent3 = window.takeLast(3)
        val p1 = recent3.maxOfOrNull { it.limitUpStreak.toInt() } ?: 0 >= properties.pool.minStreak
        // P2：近 5 个交易日内涨停次数 >= pool.min-limit-ups
        val p2 = window.count { it.isLimitUp } >= properties.pool.minLimitUps
        // P3：近 5 个交易日 qfq 收盘累计涨幅 = (close_last - close_first) / close_first * 100 >= pool.min-5d-gain
        val p3 = cumulativePctChange(window)?.compareTo(properties.pool.min5dGain)?.let { it >= 0 } ?: false
        return p1 || p2 || p3
    }

    /** 崩塌池判定：入参为该股近 5 交易日 bar 窗口（含当日，升序）；满足 C1/C2 任一返回 true */
    fun isInCollapsePool(bars: List<StockHistory>): Boolean {
        if (bars.isEmpty()) return false
        val window = bars.sortedBy { it.tradeDate }.takeLast(POOL_WINDOW)
        if (window.isEmpty()) return false
        // C1：当日 limit_down_streak >= collapse.min-streak（连续跌停）
        val c1 = window.last().limitDownStreak.toInt() >= properties.collapse.minStreak
        // C2：近 5 个交易日累计跌幅 <= -collapse.max-5d-drop（qfq 收盘价窗口）
        val c2 = cumulativePctChange(window)?.let { it <= properties.collapse.max5dDrop.negate() } ?: false
        return c1 || c2
    }

    /** 近 N 日 qfq 收盘累计涨跌幅 %（窗口首个 bar 为分母；close 缺失降级不命中） */
    private fun cumulativePctChange(window: List<StockHistory>): BigDecimal? {
        val first = window.first().close ?: return null
        val last = window.last().close ?: return null
        if (first.compareTo(BigDecimal.ZERO) == 0) return null
        return last.subtract(first)
            .multiply(BigDecimal("100"))
            .divide(first, PCT_SCALE, RoundingMode.HALF_UP)
    }

    private companion object {
        const val POOL_WINDOW = 5
        const val PCT_SCALE = 4
    }
}
