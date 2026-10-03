package com.soros.v2.service.algorithm

import java.math.BigDecimal
import java.time.LocalDate

/**
 * §11.5 SorosAlgorithmV2 —— 清洁版（纯函数，入参 List<Bar> 不可变，管道每步返回新值）。
 *
 * 与 V1 复刻版（testFixtures/SorosUtilsV1.kt）对拍，diff 必须逐条命中 off-by-one 清单：
 *  1. merge 丢末点            → 修正：末点完整纳入
 *  2. peekAndValley 丢尾 2     → 修正：遍历上界放开到尾 2
 *  3. littleTrend 丢末 3 点 + 不落进行中段 → 修正：循环覆盖全段 + 进行中段落库
 *  4. bigTrend 末段 range 不重算 → 修正：末段并入前重算 range
 *  5. lastDays 恒 0            → 修正：start/end 区间交易日数
 *  6. size<20 守卫 &&→||        → 修正：空 或 不足 20 均守卫
 *  7. yyyyMMdd 字典序          → 修正：LocalDate 排序
 *
 * 输出结构兼容 V1 结果表行（stock_inflection_point / BIG_TREND：code/wave_direction/data_type/
 * last_days/st_range 字段名照抄 V1，不"顺手修正"，保下游报表兼容；建表在二期 §12.4）。
 */

/** 日线 bar（仅算法消费 code/date/close/high/low，与 V2 qfq 口径一致；st_change 恒 null 不影响） */
data class Bar(
    val code: String,
    val date: LocalDate,
    val close: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
)

/** 拐点类型（V1 InflectionPointType） */
enum class V2PointType { MAX, MIN }

/** 波浪方向（V1 WaveDirectionEnum；清洁版剔除 FLAT 死值） */
enum class V2WaveDirection { RISE, FALL }

/** 趋势层级（V1 TrendMultiType） */
enum class V2TrendMultiType { M, S, B }

/** 拐点（不可变，对应 stock_inflection_point 行） */
data class V2InflectionPoint(
    val code: String,
    val date: LocalDate,
    val close: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val type: V2PointType,
) {
    /** MAX→high、MIN→low（与 V1 getValue() 语义一致，null 取 ZERO 守卫） */
    fun getValue(): BigDecimal =
        if (type == V2PointType.MAX) high ?: BigDecimal.ZERO else low ?: BigDecimal.ZERO
}

/** 趋势波（不可变，对应 BIG_TREND 行） */
data class V2TrendWave(
    val code: String,
    val waveDirection: V2WaveDirection,
    val lastDays: Int,
    val range: BigDecimal,
    val trendMultiType: V2TrendMultiType,
    val start: V2InflectionPoint?,
    val end: V2InflectionPoint?,
)

/**
 * §11.5 清洁版算法（纯函数单点）。管道同 V1：
 * findInflectionPoint(days) → merge → findPeekAndValley → littleTrend → bigTrend。
 */
object SorosAlgorithmV2 {

    /** 一：拐点（小区间极值点，V1 findInflectionPoint 忠实语义，日期改 LocalDate 排序） */
    fun findInflectionPoint(bars: List<Bar>, days: Int): List<V2InflectionPoint> {
        if (bars.isEmpty() || bars.size < 2 * days + 1) return emptyList()
        val sortedList = bars.sortedBy { it.date }
        val result = mutableListOf(
            V2InflectionPoint(
                code = sortedList[0].code, date = sortedList[0].date, close = sortedList[0].close,
                high = sortedList[0].high, low = sortedList[0].low, type = V2PointType.MAX,
            ),
        )
        var start = 0
        var end = 0
        var endAmend = 0 // 终点修正（V1 语义原样保留）
        while (start < sortedList.size - 2 * days && end < sortedList.size - 1) {
            end = start + days + endAmend
            val currentSegment = sortedList.subList(start, end)
            val startDay = currentSegment[0]
            val endDay = currentSegment[currentSegment.size - 1]
            val max = currentSegment.maxBy { it.high }
            val min = currentSegment.minBy { it.low }
            var maxInflection: V2InflectionPoint? = null
            var minInflection: V2InflectionPoint? = null
            if (max.date != startDay.date && max.date != endDay.date) {
                maxInflection = V2InflectionPoint(max.code, max.date, max.close, max.high, max.low, V2PointType.MAX)
            }
            if (min.date != startDay.date && min.date != endDay.date) {
                minInflection = V2InflectionPoint(min.code, min.date, min.close, min.high, min.low, V2PointType.MIN)
            }
            // 终点修正：段末恰为极值点且其后仍有 bar 时向后探 3 根确认
            if (max.date == endDay.date && end < sortedList.size - 4) {
                val lastMax = sortedList.subList(end, end + 3).maxBy { it.high }
                if (lastMax.high < max.high) {
                    maxInflection = V2InflectionPoint(max.code, max.date, max.close, max.high, max.low, V2PointType.MAX)
                }
            }
            if (max.date == startDay.date && start > 3) {
                val lastMax = sortedList.subList(start - 3, start).maxBy { it.high }
                if (lastMax.high < max.high) {
                    maxInflection = V2InflectionPoint(max.code, max.date, max.close, max.high, max.low, V2PointType.MAX)
                }
            }
            // 终点修正镜像：V1 对 min 用 max(low) 的 quirks 原样保留
            if (min.date == endDay.date && end < sortedList.size - 4) {
                val lastLow = sortedList.subList(end, end + 3).maxBy { it.low }
                if (lastLow.low > min.low) {
                    minInflection = V2InflectionPoint(min.code, min.date, min.close, min.high, min.low, V2PointType.MIN)
                }
            }
            if (min.date == startDay.date && start > 3) {
                val beforeLow = sortedList.subList(start - 3, start).maxBy { it.low }
                if (beforeLow.low > min.low) {
                    minInflection = V2InflectionPoint(min.code, min.date, min.close, min.high, min.low, V2PointType.MIN)
                }
            }
            if (minInflection != null || maxInflection != null) {
                endAmend = 0
                maxInflection?.let { result.add(it) }
                minInflection?.let { result.add(it) }
            } else {
                endAmend += 2
                continue
            }
            start = end
        }
        return result
    }

    /** 二：合并相邻同型拐点（修正 V1 丢末点：循环结束后末点完整纳入，末点与已选末点同型时按同规则去劣） */
    fun merge(points: List<V2InflectionPoint>): List<V2InflectionPoint> {
        if (points.isEmpty()) return emptyList()
        val newList = points.sortedBy { it.date }
        val result = mutableListOf<V2InflectionPoint>()
        var current = 0
        var last = 0
        while (last < newList.size - 1) {
            last = current + 1
            val currentPoint = newList[current]
            val lastPoint = newList[last]
            if (currentPoint.type == lastPoint.type) {
                if (currentPoint.type == V2PointType.MIN && currentPoint.low!! < lastPoint.low!!) {
                    result.add(currentPoint)
                }
                if (currentPoint.type == V2PointType.MAX && currentPoint.high != null && currentPoint.high!! > lastPoint.high!!) {
                    result.add(currentPoint)
                }
                current++
                continue
            } else {
                result.add(currentPoint)
                current++
            }
        }
        // 清单1 修正：V1 循环结束后末点丢失，此处完整纳入——末点无条件保留（保证 merge 末点=输入末点，
        // 下游 littleTrend/bigTrend 的末段与进行中段语义不受丢点影响）；同型去劣仅在循环内对非末点生效。
        if (current < newList.size) {
            result.add(newList[current])
        }
        return result
    }

    /** 三：波峰波谷（修正 V1 丢尾 2：遍历上界放开到尾 2；valleys 守卫 bug 修正） */
    fun findPeekAndValley(points: List<V2InflectionPoint>): List<V2InflectionPoint> {
        if (points.isEmpty() || points.size < 20) return emptyList()
        val rawPeeks = points.filter { it.type == V2PointType.MAX }.sortedBy { it.date }
        var i = 1
        val peeks = mutableListOf<V2InflectionPoint>()
        if (rawPeeks.isNotEmpty()) {
            peeks.add(rawPeeks[0])
        }
        while (i < rawPeeks.size - 1 && rawPeeks.size > 4) {
            if (rawPeeks[i].high!! > rawPeeks[i - 1].high!! && rawPeeks[i].high!! > rawPeeks[i + 1].high!!) {
                peeks.add(rawPeeks[i])
            }
            i++
        }
        val valleyList = points.filter { it.type == V2PointType.MIN }.sortedBy { it.date }
        i = 1
        val valleys = mutableListOf<V2InflectionPoint>()
        if (valleyList.isNotEmpty()) {
            valleys.add(valleyList[0])
        }
        while (i < valleyList.size - 1 && valleyList.size > 4) {
            if (valleyList[i].low!! < valleyList[i - 1].low!! && valleyList[i].low!! < valleyList[i + 1].low!!) {
                valleys.add(valleyList[i])
            }
            i++
        }
        return (peeks + valleys).sortedBy { it.date }
    }

    /** 四：落差法趋势（修正 V1 丢末 3 点 + 不落进行中段；lastDays 按 start/end 交易日数） */
    fun littleTrend(points: List<V2InflectionPoint>): List<V2TrendWave> {
        if (points.isEmpty() || points.size < 3) return emptyList()
        val result = mutableListOf<V2TrendWave>()
        var lastDownTrendValue = BigDecimal.ZERO
        var lastUpTrendValue = BigDecimal.ZERO
        var preTrendWave = V2TrendWave(
            code = points[0].code,
            waveDirection = if (points[1].type == V2PointType.MAX) V2WaveDirection.RISE else V2WaveDirection.FALL,
            lastDays = 0,
            range = BigDecimal.ZERO,
            trendMultiType = V2TrendMultiType.M,
            start = points[0],
            end = null,
        )
        var lastDownTrendAmount: BigDecimal = preTrendWave.start?.getValue() ?: BigDecimal.ZERO
        var lastUpTrendAmount: BigDecimal = BigDecimal.ZERO
        var i = 1
        // 清单3 修正：V1 `i < size-3` 丢末 3 点，此处循环覆盖全段
        while (i < points.size) {
            val pre = points[i - 1]
            val current = points[i]
            if (current.type == V2PointType.MAX && pre.type == V2PointType.MAX) {
                lastUpTrendValue = lastUpTrendValue.add(current.getValue().subtract(pre.getValue()))
                lastUpTrendAmount = current.getValue()
                preTrendWave = preTrendWave.copy(end = current)
                i++
                continue
            } else if (current.type == V2PointType.MIN && pre.type == V2PointType.MIN) {
                lastDownTrendValue = lastDownTrendValue.add(current.getValue().subtract(pre.getValue()))
                lastDownTrendAmount = current.getValue()
                preTrendWave = preTrendWave.copy(end = current)
                i++
                continue
            } else {
                val currentValue = current.getValue().subtract(pre.getValue())
                if (currentValue > BigDecimal.ZERO && preTrendWave.isUpTrend() && current.getValue() > lastUpTrendAmount) {
                    lastUpTrendAmount = current.getValue()
                    preTrendWave = preTrendWave.copy(end = current)
                } else if (currentValue < BigDecimal.ZERO && preTrendWave.isDownTrend() && current.getValue() < lastDownTrendAmount) {
                    lastDownTrendAmount = current.getValue()
                    preTrendWave = preTrendWave.copy(end = current)
                } else {
                    // 趋势反转：旧波定稿入 result，开新波
                    result.add(preTrendWave.finalizeRangeAndLastDays())
                    val newStart = preTrendWave.end ?: preTrendWave.start
                    preTrendWave = V2TrendWave(
                        code = current.code,
                        waveDirection = if (current.type == V2PointType.MAX) V2WaveDirection.RISE else V2WaveDirection.FALL,
                        lastDays = 0,
                        range = BigDecimal.ZERO,
                        trendMultiType = V2TrendMultiType.M,
                        start = newStart,
                        end = current,
                    ).finalizeRangeAndLastDays()
                }
            }
            i++
        }
        // 清单3 修正：进行中段落库（end=段末点），V1 直接丢弃
        if (preTrendWave.start != null && preTrendWave.end != null) {
            result.add(preTrendWave.finalizeRangeAndLastDays())
        }
        return result
    }

    /** 五：大趋势合并（修正 V1 末段 range 不重算：result.add(pre) 前统一重算 range 与 lastDays） */
    fun bigTrend(waves: List<V2TrendWave>): List<V2TrendWave> {
        if (waves.size < 3) return waves
        var i = 0
        var pre = waves[0]
        val result = mutableListOf<V2TrendWave>()
        while (i < waves.size - 2) {
            val first = waves[i]
            val third = waves[i + 2]
            if (first.waveDirection == V2WaveDirection.RISE && third.end!!.getValue() > first.end!!.getValue()) {
                // 上涨趋势继续
                pre = pre.copy(
                    waveDirection = V2WaveDirection.RISE,
                    end = third.end,
                    range = computeRange(pre.start!!, third.end!!),
                    lastDays = computeLastDays(pre.start!!, third.end!!),
                )
                i += 2
                continue
            } else if (first.waveDirection == V2WaveDirection.FALL && third.end!!.getValue() < first.end!!.getValue()) {
                // 下跌趋势继续
                pre = pre.copy(
                    waveDirection = V2WaveDirection.FALL,
                    end = third.end,
                    range = computeRange(pre.start!!, third.end!!),
                    lastDays = computeLastDays(pre.start!!, third.end!!),
                )
                i += 2
                continue
            } else {
                if (first.waveDirection == V2WaveDirection.RISE) {
                    result.add(
                        pre.copy(
                            range = computeRange(pre.start!!, pre.end!!).abs(),
                            lastDays = computeLastDays(pre.start!!, pre.end!!),
                        ),
                    )
                    pre = waves[i + 1].copy(waveDirection = V2WaveDirection.FALL)
                    i++
                } else {
                    result.add(
                        pre.copy(
                            range = computeRange(pre.start!!, pre.end!!),
                            lastDays = computeLastDays(pre.start!!, pre.end!!),
                        ),
                    )
                    pre = waves[i + 1].copy(waveDirection = V2WaveDirection.RISE)
                    i++
                }
                continue
            }
        }
        // 清单4 修正：末段 pre 入 result 前按最终 start/end 重算 range（V1 沿用陈旧 range）
        result.add(
            pre.copy(
                range = computeRange(pre.start!!, pre.end!!),
                lastDays = computeLastDays(pre.start!!, pre.end!!),
            ),
        )
        return result
    }

    /** 波幅（V1 SCALE_OF_SOROS=4，HALF_EVEN；RISE 为正 / FALL 为负，符号由方向自洽） */
    private fun computeRange(start: V2InflectionPoint, end: V2InflectionPoint): BigDecimal =
        end.getValue().subtract(start.getValue()).divide(start.getValue(), SCALE_OF_SOROS, java.math.RoundingMode.HALF_EVEN)

    /** 区间交易日数（start/end 日历差+1，清单5 修正：V1 lastDays 恒 0） */
    private fun computeLastDays(start: V2InflectionPoint, end: V2InflectionPoint): Int =
        java.time.temporal.ChronoUnit.DAYS.between(start.date, end.date).toInt() + 1

    /** 定稿：按最终 start/end 重算 range 与 lastDays（进行中段落库前调用） */
    private fun V2TrendWave.finalizeRangeAndLastDays(): V2TrendWave {
        val s = start ?: return this
        val e = end ?: return this
        return copy(range = computeRange(s, e), lastDays = computeLastDays(s, e))
    }

    private fun V2TrendWave.isUpTrend(): Boolean = waveDirection == V2WaveDirection.RISE

    private fun V2TrendWave.isDownTrend(): Boolean = waveDirection == V2WaveDirection.FALL

    private const val SCALE_OF_SOROS = 4
}
