package com.soros.v2.service.signal

import java.math.BigDecimal
import org.springframework.stereotype.Service

/**
 * 筹码分布递推纯函数实现（路径 A，§12.4.1 / §19.11.1 穿透定稿）。
 *
 * 递推模型：`D_t = D_{t-1}×(1−tr) + tr×triangular(qfq_high, qfq_low, qfq_close)`，qfq 坐标全程、180 桶。
 * - 三角分布按桶精确积分（CDF 差分解桶，比逐桶采样更准且测试容差内）
 * - 一字板（high==low）→ 均匀带 [close×0.995, close×1.005] 扁平兜底
 * - 换手率 clamp ≤1；tr=0（停牌/零换手）原样冻结分布
 * - 分位 = 累计权重线性插值；avg_cost = Σ(桶中心价×桶权重)；cost_dev = (close−avg_cost)/avg_cost×100（PLAN 版符号）
 * - 获利盘 = CDF(现价)（成本 ≤ 现价的筹码占比）
 * - warm-up：前 60 交易日 8 列全 NULL（宁可标空不用不准的数，§17.1 B7）
 */
@Service
class ChipDistributionCalculatorImpl : ChipDistributionCalculator {

    override fun computeForHistory(bars: List<ChipBarInput>): List<ChipDayResult> {
        if (bars.isEmpty()) return emptyList()
        var prev: ChipDistributionState? = null
        var prevGrid: DayGrid? = null
        return bars.mapIndexed { index, bar ->
            val grid = dayGrid(bar)
            val newWeights = newChipWeights(bar, grid)
            val state = mix(prev, prevGrid, newWeights, grid, bar.turnoverRate.toDouble())
            prev = state
            prevGrid = grid
            if (index < WARM_UP_DAYS) emptyResult() else toChipResult(state, grid, bar)
        }
    }

    override fun advanceOneDay(prev: ChipDistributionState?, bar: ChipBarInput): ChipDayResult {
        if (prev == null) return emptyResult()
        val grid = dayGrid(bar)
        val newWeights = newChipWeights(bar, grid)
        // 增量路径 prev 网格与当日同构（驻留内存 180 桶复用，§19.11.1），不做跨网格重采样
        val state = mix(prev, null, newWeights, grid, bar.turnoverRate.toDouble())
        return toChipResult(state, grid, bar)
    }

    /** 当日价格网格：一字板 → ±0.5% 扁平带，否则 [low, high] */
    private fun dayGrid(bar: ChipBarInput): DayGrid {
        val low = bar.low.toDouble()
        val high = bar.high.toDouble()
        return if (high == low) {
            val close = bar.close.toDouble()
            DayGrid(close * FLAT_BAND_LOW, close * FLAT_BAND_HIGH, isFlat = true)
        } else {
            DayGrid(low, high, isFlat = false)
        }
    }

    /** 当日新增筹码分布（180 桶权重） */
    private fun newChipWeights(bar: ChipBarInput, grid: DayGrid): DoubleArray {
        if (grid.isFlat) {
            // 一字板扁平兜底：均匀带（等宽桶权重相等）
            return DoubleArray(BUCKETS) { 1.0 / BUCKETS }
        }
        return triangularWeights(grid.low, grid.high, bar.close.toDouble())
    }

    /**
     * 递推合并：D_t = D_{t-1}×(1−tr) + tr×D_new，tr clamp≤1。
     * - prev==null 或 tr≥1.0 → 全量重置为当日新增分布（上市首日全量换手语义）
     * - tr==0.0 → 原样冻结 prev（停牌/零换手；防止浮点归一化引入微差）
     * - 网格漂移时先把 prev 重采样到当日网格（除权/价格区间变化免疫）
     */
    private fun mix(
        prev: ChipDistributionState?,
        prevGrid: DayGrid?,
        newWeights: DoubleArray,
        grid: DayGrid,
        turnoverRate: Double,
    ): ChipDistributionState {
        val tr = turnoverRate.coerceIn(0.0, 1.0)
        return when {
            prev == null || tr >= 1.0 -> normalized(newWeights)
            tr == 0.0 -> prev
            else -> {
                val prevBuckets =
                    if (prevGrid != null && prevGrid != grid) remapBuckets(prev.buckets, prevGrid, grid) else prev.buckets
                val mixed = DoubleArray(BUCKETS) { i -> prevBuckets[i] * (1.0 - tr) + newWeights[i] * tr }
                normalized(mixed)
            }
        }
    }

    /** 三角分布按桶精确积分（CDF 差分解桶；mode=close 峰位，§19.11.1 递推公式） */
    private fun triangularWeights(low: Double, high: Double, mode: Double): DoubleArray {
        val a = low
        val b = high
        val c = mode.coerceIn(a, b)
        val step = (b - a) / BUCKETS
        fun cdf(x: Double): Double {
            if (x <= a) return 0.0
            if (x >= b) return 1.0
            return if (x <= c) (x - a) * (x - a) / ((b - a) * (c - a))
            else 1.0 - (b - x) * (b - x) / ((b - a) * (b - c))
        }
        return DoubleArray(BUCKETS) { i ->
            val x0 = a + i * step
            (cdf(x0 + step) - cdf(x0)).coerceIn(0.0, 1.0)
        }
    }

    /** 网格重采样：把 prev 分布（fromGrid）按累计权重线性插值映射到 toGrid */
    private fun remapBuckets(from: DoubleArray, fromGrid: DayGrid, toGrid: DayGrid): DoubleArray {
        val out = DoubleArray(BUCKETS)
        val cum = DoubleArray(BUCKETS + 1)
        for (i in 0 until BUCKETS) cum[i + 1] = cum[i] + from[i]
        val total = cum[BUCKETS]
        if (total <= 0.0) return out
        val stepF = (fromGrid.high - fromGrid.low) / BUCKETS
        val stepT = (toGrid.high - toGrid.low) / BUCKETS
        fun cumAt(price: Double): Double {
            if (price <= fromGrid.low) return 0.0
            if (price >= fromGrid.high) return total
            val pos = (price - fromGrid.low) / stepF
            val idx = pos.toInt().coerceIn(0, BUCKETS - 1)
            val frac = (pos - idx).coerceIn(0.0, 1.0)
            return cum[idx] + (cum[idx + 1] - cum[idx]) * frac
        }
        for (i in 0 until BUCKETS) {
            val t0 = toGrid.low + i * stepT
            out[i] = (cumAt(t0 + stepT) - cumAt(t0)).coerceIn(0.0, total)
        }
        return out
    }

    /** 归一化到总权重 1.0 */
    private fun normalized(buckets: DoubleArray): ChipDistributionState {
        val sum = buckets.sum()
        if (sum <= 0.0) return ChipDistributionState(DoubleArray(BUCKETS), 0.0)
        return ChipDistributionState(DoubleArray(BUCKETS) { i -> buckets[i] / sum }, 1.0)
    }

    /** 桶分布 → signal_daily 筹码 8 列（分位累计权重线性插值，§19.11.1 决策 1/2） */
    private fun toChipResult(state: ChipDistributionState, grid: DayGrid, bar: ChipBarInput): ChipDayResult {
        val buckets = state.buckets
        val cum = DoubleArray(BUCKETS + 1)
        for (i in 0 until BUCKETS) cum[i + 1] = cum[i] + buckets[i]
        val total = cum[BUCKETS]
        val low = grid.low
        val step = (grid.high - grid.low) / BUCKETS
        val centers = DoubleArray(BUCKETS) { i -> low + (i + 0.5) * step }
        var weightedSum = 0.0
        for (i in 0 until BUCKETS) weightedSum += centers[i] * buckets[i]
        val avgCost = if (total > 0.0) weightedSum / total else 0.0
        val p5 = quantile(0.05, cum, total, low, step)
        val p15 = quantile(0.15, cum, total, low, step)
        val p85 = quantile(0.85, cum, total, low, step)
        val p95 = quantile(0.95, cum, total, low, step)
        val close = bar.close.toDouble()
        val profit = cdfAt(close, cum, total, low, step) * 100.0
        val c90Conc = if (p95 + p5 != 0.0) (p95 - p5) / (p95 + p5) * 100.0 else 0.0
        val c70Conc = if (p85 + p15 != 0.0) (p85 - p15) / (p85 + p15) * 100.0 else 0.0
        val costDev = if (avgCost != 0.0) (close - avgCost) / avgCost * 100.0 else 0.0
        return ChipDayResult(
            profitRatio = toDecimal(profit),
            costDev = toDecimal(costDev),
            c90Low = toDecimal(p5),
            c90High = toDecimal(p95),
            c90Conc = toDecimal(c90Conc),
            c70Low = toDecimal(p15),
            c70High = toDecimal(p85),
            c70Conc = toDecimal(c70Conc),
        )
    }

    private fun quantile(q: Double, cum: DoubleArray, total: Double, low: Double, step: Double): Double {
        if (total <= 0.0) return low
        val target = q * total
        if (target <= 0.0) return low
        if (target >= cum[BUCKETS]) return low + BUCKETS * step
        var idx = 0
        while (idx < BUCKETS - 1 && cum[idx + 1] < target) idx++
        val weight = cum[idx + 1] - cum[idx]
        val frac = if (weight > 0.0) (target - cum[idx]) / weight else 0.0
        return low + (idx + frac) * step
    }

    /** 现价处累计权重（获利盘 = CDF(现价)，§19.11.1 决策 1 同源） */
    private fun cdfAt(price: Double, cum: DoubleArray, total: Double, low: Double, step: Double): Double {
        if (price <= low) return 0.0
        if (price >= low + BUCKETS * step) return total
        val pos = (price - low) / step
        val idx = pos.toInt().coerceIn(0, BUCKETS - 1)
        val frac = (pos - idx).coerceIn(0.0, 1.0)
        return cum[idx] + (cum[idx + 1] - cum[idx]) * frac
    }

    private fun emptyResult() = ChipDayResult(null, null, null, null, null, null, null, null)

    private fun toDecimal(value: Double): BigDecimal = BigDecimal.valueOf(value)

    /** 当日桶网格（价格下沿/上沿/是否一字板扁平带） */
    private data class DayGrid(val low: Double, val high: Double, val isFlat: Boolean)

    private companion object {
        const val BUCKETS = 180
        const val WARM_UP_DAYS = 60
        const val FLAT_BAND_LOW = 0.995
        const val FLAT_BAND_HIGH = 1.005
    }
}
