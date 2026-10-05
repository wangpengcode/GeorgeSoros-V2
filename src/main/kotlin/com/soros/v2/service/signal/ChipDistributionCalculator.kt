package com.soros.v2.service.signal

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 筹码分布递推纯函数（路径 A，§12.4.1 / §19.11.1 穿透定稿）。
 *
 * 递推模型：`D_t = D_{t-1}×(1−tr) + tr×triangular(qfq_high, qfq_low, qfq_close)`，qfq 坐标全程、180 桶。
 * 口径纪律：
 * - 成本偏离 cost_dev = (close−avg_cost)/avg_cost×100（PLAN 版符号，qfq 重对基免疫）；avg_cost 为分布均值，不落库。
 * - 分位端点：c90=p5/p95、c70=p15/p85；集中度 = (high−low)/(high+low)×100。
 * - 边界：一字板 ±0.5% 扁平兜底、换手率 clamp ≤1、停牌无 bar 筹码冻结、上市首日全量换手。
 * - warm-up：前 60 个交易日筹码 8 列=NULL（宁可标空不用不准的数，§17.1 B7）。
 *
 * 纯函数接口（无 DB/无状态），实现留待 implementer；桶状态驻留内存不持久化。
 */
interface ChipDistributionCalculator {

    /**
     * 全历史递推（SignalPrecomputeJob 主路径：每票全历史只读递推，只写当日行）。
     *
     * @param bars 该股 qfq 日线序列（按 tradeDate 升序，停牌无 bar）
     * @return 逐日筹码 8 列结果（与 bars 一一对应；warm-up 前 60 日各项全 null）
     */
    fun computeForHistory(bars: List<ChipBarInput>): List<ChipDayResult>

    /**
     * 单日增量推进（在昨日分布状态上推一日；幂等，除权漂移由外层触发全历史重算）。
     *
     * @param prev 昨日分布状态（180 桶驻留内存复用；null=warm-up 起点 D_0 单峰近似）
     * @param bar  今日 qfq bar
     * @return 今日筹码 8 列（warm-up 前 60 日各项为 null）
     */
    fun advanceOneDay(prev: ChipDistributionState?, bar: ChipBarInput): ChipDayResult
}

/** 递推输入：单根 qfq 日线 bar（仅消耗递推所需字段） */
data class ChipBarInput(
    /** 交易日 */
    val tradeDate: LocalDate,
    /** qfq 最高价 */
    val high: BigDecimal,
    /** qfq 最低价 */
    val low: BigDecimal,
    /** qfq 收盘价（分布峰位） */
    val close: BigDecimal,
    /** 换手率小数（0-1；调用方负责 DB「换手率%」÷100 转换。实现内 clamp ≤1，tr=0 冻结分布） */
    val turnoverRate: BigDecimal,
)

/** 分布状态（180 桶 qfq 价格分布；驻留内存不持久化） */
data class ChipDistributionState(
    /** 180 桶权重 */
    val buckets: DoubleArray,
    /** 总权重（桶归一化基线） */
    val totalWeight: Double,
)

/** 单日筹码结果（signal_daily 8 列；warm-up 前 60 日全 null） */
data class ChipDayResult(
    /** 获利盘% */
    val profitRatio: BigDecimal?,
    /** 成本偏离% = (close−avg_cost)/avg_cost×100（qfq 重对基免疫） */
    val costDev: BigDecimal?,
    /** 90% 成本区间下沿（p5 分位，qfq 坐标） */
    val c90Low: BigDecimal?,
    /** 90% 成本区间上沿（p95 分位，qfq 坐标） */
    val c90High: BigDecimal?,
    /** 90% 集中度（东财口径 (p95−p5)/(p95+p5)×100，qfq 坐标） */
    val c90Conc: BigDecimal?,
    /** 70% 成本区间下沿（p15 分位，qfq 坐标） */
    val c70Low: BigDecimal?,
    /** 70% 成本区间上沿（p85 分位，qfq 坐标） */
    val c70High: BigDecimal?,
    /** 70% 集中度（(p85−p15)/(p85+p15)×100，qfq 坐标） */
    val c70Conc: BigDecimal?,
)

/** 分布分位求解结果（从桶分布求分位端点/均值/获利盘；avg_cost 现算派生不落库，§12.4.1） */
data class ChipQuantiles(
    /** p5 分位（qfq 坐标） */
    val p5: BigDecimal,
    /** p15 分位（qfq 坐标） */
    val p15: BigDecimal,
    /** p85 分位（qfq 坐标） */
    val p85: BigDecimal,
    /** p95 分位（qfq 坐标） */
    val p95: BigDecimal,
    /** 分布均值（avg_cost，qfq 绝对坐标；前端反解 close/(1+cost_dev/100) 同值） */
    val avgCost: BigDecimal,
    /** 获利盘% = CDF(现价)，即现价下方筹码权重占比（成本低于现价的持仓比例；2026-10-05 定稿，测试即规格） */
    val profitRatio: BigDecimal,
)
