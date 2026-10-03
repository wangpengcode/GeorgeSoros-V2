package com.soros.v2.testfixtures

import java.math.BigDecimal

/**
 * V1（../GeorgeSoros/soros-data-adaptor）算法支撑模型与工具 —— 1:1 移植进 V2 测试目录。
 *
 * §11.5：V1 源码在 ../GeorgeSoros，已找到。本文件是 V1 算法依赖的 BO/枚举/扩展函数的忠实复刻
 * （Kotlin 2.0 编译 1.4 语法兼容；禁止"顺手修正"任何逻辑，var 可变管道、共享引用原样保留）。
 */

/** V1 Commons（SCALE_OF_SOROS=4） */
object V1Commons {
    const val SCALE_OF_SOROS = 4
}

/** V1 InflectionPointType */
enum class V1InflectionPointType { MAX, MIN }

/** V1 WaveDirectionEnum */
enum class V1WaveDirectionEnum { RISE, FALL, FLAT }

/** V1 TrendMultiType（M=小趋势，S，B=大趋势） */
enum class V1TrendMultiType { M, S, B }

/** V1 StockWaveBo（仅算法消费 code/date/close/high/low，与 V2 qfq 口径天然一致） */
data class V1StockWaveBo(
    val code: String,
    var close: BigDecimal,
    var high: BigDecimal,
    var low: BigDecimal,
    var date: String,
)

/** V1 InflectionPoint */
data class V1InflectionPoint(
    val code: String,
    var date: String,
    var close: BigDecimal? = null,
    var high: BigDecimal? = null,
    var low: BigDecimal? = null,
    var type: V1InflectionPointType,
)

/** V1 StockTrendWaveBo（lastDays 恒 0 —— 已知 bug，复刻保留） */
data class V1StockTrendWaveBo(
    var waveDirectionEnum: V1WaveDirectionEnum,
    var lastDays: Int = 0,
    var range: BigDecimal,
    var trendMultiType: V1TrendMultiType,
    var code: String,
    var startInflectionPoint: V1InflectionPoint? = null,
    var endInflectionPoint: V1InflectionPoint? = null,
)

/** V1 扩展函数（extension/Extensions.kt 中与算法相关的部分） */
fun V1InflectionPoint.isMax(): Boolean = this.type == V1InflectionPointType.MAX

fun V1InflectionPoint.isMin(): Boolean = this.type == V1InflectionPointType.MIN

fun V1InflectionPoint.getValue(): BigDecimal =
    if (this.type == V1InflectionPointType.MAX) this.high ?: BigDecimal.ZERO else this.low ?: BigDecimal.ZERO

fun V1StockTrendWaveBo.isUpTrend(): Boolean = this.waveDirectionEnum == V1WaveDirectionEnum.RISE

fun V1StockTrendWaveBo.isDownTrend(): Boolean = this.waveDirectionEnum == V1WaveDirectionEnum.FALL
