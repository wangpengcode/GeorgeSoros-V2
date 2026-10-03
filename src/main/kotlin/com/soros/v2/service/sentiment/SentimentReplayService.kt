package com.soros.v2.service.sentiment

import java.time.LocalDate

/**
 * §13.5 情绪历史冷启动回放服务（POST /api/v1/jobs/sentiment-replay）。
 */
interface SentimentReplayService {

    /**
     * 回放区间情绪周期（严格按 trading_calendar 顺序逐日 computeFor；不可并行、不可跳日）。
     *
     * - 回放前删除区间内 sentiment_cycle / dragon_cycle（重放语义，防新旧混杂）
     * - dragon_cycle 自区间首日重建：首日无"前文"，龙头取区间开始时最高板、status 标 BOOT
     * - 全量 ~1300 交易日，单日计算秒级，总耗时分钟级
     * - 完成输出摘要 → 钉钉通知
     */
    fun replay(from: LocalDate, to: LocalDate): SentimentReplaySummary
}

/** 回放完成摘要（网页左侧历史即刻可看） */
data class SentimentReplaySummary(
    /** 回放区间起点 */
    val from: LocalDate,
    /** 回放区间终点 */
    val to: LocalDate,
    /** 落库 sentiment_cycle 行数 */
    val sentimentRows: Int,
    /** 龙头周期行数（dragon_cycle 总数，含 BOOT 首日重建） */
    val dragonRows: Int,
    /** 龙头周期清单摘要（code → start_date → cycle_type/status） */
    val dragonCycleList: List<String>,
)
