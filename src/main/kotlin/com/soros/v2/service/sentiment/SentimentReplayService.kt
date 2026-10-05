package com.soros.v2.service.sentiment

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * §13.5/§19.12 情绪历史回放服务（POST /api/v1/jobs/sentiment-replay）。
 */
interface SentimentReplayService {

    /**
     * 回放区间情绪周期（严格按 trading_calendar 顺序逐日 computeFor；不可并行、不可跳日）。
     *
     * - **force=false（默认，增量补缺断点续跑）**：待算清单 = 交易日历∩[from,to] − sentiment_cycle 已有日期；
     *   自第一个缺日 D0 起内存逐日递推，只落缺日行；已有行跳过（断点续跑天然成立）；
     *   D0 前一交易日断点热启动（prevCycle + 进行中龙头 + calendar 前缀扩展到 min(from, 全部进行中龙头 brokenDate)）；
   *   空洞日内存推进状态但不落库（skippedDays 累计）；数据守卫（非 ST 有效股柱覆盖率 <90%）前缀截止 + deferredDates。
     * - **force=true（全量重建）**：事务0 预清理区间两表后自区间首日冷启动重建（BackfillJob §13.5 钩子传 true）。
     * - 派生口径完全不变（纯 stock_history + trading_calendar + stock_info ST 推导）；完成输出摘要 → 钉钉通知。
     */
    fun replay(from: LocalDate, to: LocalDate, force: Boolean = false): SentimentReplaySummary
}

/** 回放完成摘要（网页左侧历史即刻可看；§19.12 响应结构扩展，向后兼容） */
data class SentimentReplaySummary(
    /** 回放区间起点 */
    @JsonProperty("from") val from: LocalDate,
    /** 回放区间终点 */
    @JsonProperty("to") val to: LocalDate,
    /** 落库 sentiment_cycle 行数 */
    @JsonProperty("sentiment_rows") val sentimentRows: Int,
    /** 龙头周期行数（dragon_cycle 总数，含 BOOT 首日重建） */
    @JsonProperty("dragon_rows") val dragonRows: Int,
    /** 龙头周期清单摘要（code → start_date → cycle_type/status） */
    @JsonProperty("dragon_cycle_list") val dragonCycleList: List<String>,
    /** 回放模式标记（false=增量补缺断点续跑 / true=全量重建；缺省 false，§19.12 决策 4） */
    @JsonProperty("force") val force: Boolean = false,
    /** 本次补算落库交易日数（§19.12 决策 4） */
    @JsonProperty("filled_days") val filledDays: Int = 0,
    /** force=false 跳过日数（已有行跳过 + 空洞日不落库但状态推进，§19.12 决策 4） */
    @JsonProperty("skipped_days") val skippedDays: Int = 0,
    /** 数据守卫拦下留待下次的缺日清单（覆盖率<90% 前缀截止，§19.12 决策 4） */
    @JsonProperty("deferred_dates") val deferredDates: List<LocalDate> = emptyList(),
)
