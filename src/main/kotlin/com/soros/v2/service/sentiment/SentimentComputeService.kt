package com.soros.v2.service.sentiment

import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import com.soros.v2.entity.StockHistory
import java.time.LocalDate

/**
 * §4.9/§13.5 情绪周期计算服务（单点口径）。
 *
 * computeFor 是纯函数：给定确定输入 → 确定输出（回放与增量同路径，§13.5）。
 * 依赖数据全部显式经 [SentimentComputeContext] 传入，函数体内不直接访问 Repository，
 * 保证同一输入在 Job 每日派生与冷启动回放下结果完全一致。
 */
interface SentimentComputeService {

    /**
     * 计算单日情绪周期（纯函数）。
     *
     * 输出 = 当日 sentiment_cycle 行（含全部派生列 + big_cycle_sug/small_cycle_sug/status_text 建议）
     *       + 龙头状态机逐日推进结果（晋级/断板/反包/上位/停牌/BOOT，§4.9）。
     */
    fun computeFor(date: LocalDate, ctx: SentimentComputeContext): SentimentComputeResult
}

/**
 * 计算输入（数据全量显式注入，保证确定性；Implementer 按 §4.9 对象池/状态机消费）。
 *
 * @property date           计算交易日
 * @property prevCycle      前一日 sentiment_cycle（followup 兑现依赖；null=回放首日）
 * @property barsByCode     当日及近 5 日行情窗口（key=code；含 limit_up_streak/limit_down_streak/
 *                          change_pct/qfq 收盘，限 MAIN/GEM/STAR 非 ST —— 采集侧已隔离）
 * @property calendar       交易日历（截止当日，升序；停牌检测/观察期推进依赖）
 * @property activeDragonCycles 进行中龙头周期（end_date IS NULL，每股至多 1 条，uq_dragon_active）
 */
data class SentimentComputeContext(
    val date: LocalDate,
    val prevCycle: SentimentCycle?,
    val barsByCode: Map<String, List<StockHistory>>,
    val calendar: List<LocalDate>,
    val activeDragonCycles: List<DragonCycle>,
)

/**
 * 计算输出（确定性；Job 落库 + 状态机写 dragon_cycle）。
 */
data class SentimentComputeResult(
    /** 当日情绪周期行（未持久化，含建议值；id 恒 0） */
    val sentiment: SentimentCycle,
    /** 龙头周期变更（新建/更新；由状态机逐日推进产出，未持久化） */
    val dragonUpdates: List<DragonCycle>,
    /** 龙头周期结束标记（end_date 刚定性，供日报"今日阵亡"汇总） */
    val deadDragonCodes: List<String>,
)
