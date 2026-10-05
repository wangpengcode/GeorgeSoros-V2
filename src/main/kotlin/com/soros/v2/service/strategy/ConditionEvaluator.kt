package com.soros.v2.service.strategy

import com.fasterxml.jackson.annotation.JsonProperty
import com.soros.v2.entity.MarketDaily
import com.soros.v2.entity.SectorDaily
import com.soros.v2.entity.SignalDaily
import com.soros.v2.entity.StockHistory
import java.time.LocalDate

/**
 * §19.13.3 ConditionEvaluator：条件求值双路径纯函数契约。
 *
 * - 路径 A（聚合表直查）：limit_up_streak/ladder_rank/is_zhaban/sector_ladder_rank + chip 4 组直读
 *   signal_daily（注：limit_up_streak 列实存于 stock_history，SignalWindow 携带 stockHistory 供路径 A 读取，
 *   §19.13.3 行「直读 signal_daily」为计划简写，schema 为准）；adv/dec/yst_limit_premium/yst_face_count/
 *   limit_up_count 直读 market_daily；limit_up_count/avg_chg_pct_all 直读 sector_daily。零候选依赖，逐日判真。
 * - 路径 L（标签候选池扫描现算，G5）：LabelEvaluationService.getLabels 返回 `Map<code, Map<trade_date, label>>`，
 *   条件逐日判真 = ∃ code ∈ universe∩候选集 当日命中目标标签（within_days N 放宽至 [D−N+1, D]）。
 * - B/C 类：本期只出 data_state（表存在性+latest+coverage），ready=PENDING，不求值不误导。
 */
interface ConditionEvaluator {

    /** 按条件 source/op 派发路径 A / 路径 L；B/C 类只产 data_state 不就绪 */
    fun evaluate(cond: ParsedCondition, ctx: EvalContext): ConditionResult
}

/** 条件类别（A=聚合表直查 / L=标签候选池扫描现算 / B=单股现算挂b期 / C=跨股聚合挂b期） */
enum class ConditionClass {
    /** 聚合表直查（signal/market/sector_daily），本期真实求值 */
    A,

    /** 标签候选池扫描现算（G5 定稿，本期真实求值） */
    L,

    /** 单股时序现算（price_action/volume 等，挂 b 期） */
    B,

    /** 跨股聚合（limit_ecology 等，本期只出数据就绪标记） */
    C,
}

/** 条件数据就绪态（READY=数据齐可求值 / PARTIAL=表缺行 / PENDING=挂b期只标记） */
enum class ReadyState {
    /** 数据齐可求值 */
    READY,

    /** 表缺行 */
    PARTIAL,

    /** 挂 b 期只标记 */
    PENDING,
}

/** 解析后的条件（DTO 层结构化条件 → 求值输入） */
data class ParsedCondition(
    /** 条件 ID（buy_0/sell_0，策略内唯一） */
    val condId: String,

    /** 买卖侧 BUY/SELL */
    val side: String,

    /** 条件信号源（§12.7.1 白名单） */
    val source: String,

    /** 条件运算符（equals/between/gte/lte/label…） */
    val op: String,

    /** 条件值（数值/区间/标签，Any 语义随 source/op） */
    val value: Any,

    /** 条件类别 A/L/B/C */
    val cls: ConditionClass,

    /** 标签类条件 within_days 放宽天数（N，[D−N+1, D]） */
    val withinDays: Int = 0,
)

/** 路径 A 聚合窗口数据（mock 聚合 repo / 生产侧条件装配器同构组装） */
data class SignalWindow(
    /** signal_daily 窗口（code → 升序行） */
    val signalDaily: Map<String, List<SignalDaily>>,

    /** market_daily 窗口（trade_date → 行） */
    val marketDaily: Map<LocalDate, MarketDaily>,

    /** sector_daily 窗口（行列表） */
    val sectorDaily: List<SectorDaily>,

    /** stock_history 窗口（code → 升序行；limit_up_streak 读取，schema §五） */
    val stockHistory: Map<String, List<StockHistory>>,
)

/** 求值上下文（窗口 + 聚合数据 + universe + 标签服务） */
data class EvalContext(
    /** 求值窗口（近 30 交易日，trading_calendar） */
    val window: List<LocalDate>,

    /** signal/market/sector_daily 窗口数据（路径 A 直查） */
    val signalTables: SignalWindow,

    /** 策略 universe（watchlist 并集 or 全市场） */
    val universe: Set<String>,

    /** 标签服务（路径 L，内含当日缓存） */
    val labelService: LabelEvaluationService,
)

/** 条件求值结果（flow 数据源 + 数据就绪度） */
data class ConditionResult(
    /** 条件类别 A/L/B/C（JSON 键 class，Kotlin 字段名 cls） */
    @JsonProperty("class")
    val cls: ConditionClass,

    /** 数据就绪态 READY/PARTIAL/PENDING */
    val ready: ReadyState,

    /** 最近触发日（A/L 真实；C/B null） */
    val lastFired: LocalDate?,

    /** 近 30 交易日触发次数（A/L 真实；C/B 0） */
    val fired30d: Int,

    /** 触发流水日期（flow 数据源，近 30 交易日升序） */
    val firedDays: List<LocalDate>,

    /** 数据就绪度（C 类本期唯一有意义输出；A 类空；L 类含 scan_scope） */
    val dataState: Map<String, Any>,
)
