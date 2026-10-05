package com.soros.v2.service.strategy

import com.soros.v2.domain.StockActionLabel
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * §19.13.3 LabelEvaluationCache：路径 L 当日缓存（ConcurrentHashMap<窗口末日, LabelResult>）。
 *
 * 同日多次请求（多策略复用同一份 / 同日多次刷新控制台）不重复扫；失效时机：
 * ① 窗口末日变化（跨交易日）② signal_daily 最新日变化（SignalPrecomputeJob 增量后）③ 显式 [invalidate]。
 * 仅驻内存，重启重算可接受（数据 ≤9,000 行，重算毫秒级）。
 */
class LabelEvaluationCache {

    private val store = ConcurrentHashMap<LocalDate, LabelResult>()

    /** 取或载（computeIfAbsent：同日 compute 只调一次） */
    fun getOrLoad(endDate: LocalDate, loader: (LocalDate) -> LabelResult): LabelResult =
        store.computeIfAbsent(endDate) { loader(it) }

    /** 显式失效（盘中 archive 归档后 / SignalPrecomputeJob 增量后） */
    fun invalidate(endDate: LocalDate) {
        store.remove(endDate)
    }
}

/** 路径 L 标签求值结果（含扫描范围元数据） */
data class LabelResult(
    /** 窗口末日（缓存 key） */
    val endDate: LocalDate,

    /** 候选票 → 逐日标签 */
    val labels: Map<String, Map<LocalDate, StockActionLabel>>,

    /** 候选集扫描范围元数据 */
    val scanScope: CandidateScope,
)

/** 候选集扫描范围元数据（data_state 标注源，G5/L1-L5 口径） */
data class CandidateScope(
    /** 扫描范围描述（五池并集∪进行中龙头） */
    val scanScope: String,

    /** 候选集数量（典型 50-150 只，极端高潮日≈300） */
    val candidateCount: Int,

    /** 候选集覆盖交易日数 */
    val coveredDays: Int,

    /** stock_history 窗口数据最新日 */
    val stockHistoryLatest: LocalDate?,

    /** dragon_cycle 窗口数据最新日 */
    val dragonCycleLatest: LocalDate?,

    /** 盘中上线日（L1 分段口径边界） */
    val archiveSince: LocalDate?,
)
