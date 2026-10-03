package com.soros.v2.domain

/**
 * 龙头生命周期状态（dragon_cycle.status 值域单点；schema.sql CHECK IN ('RISING','BROKEN','SUSPENDED','DEAD')）。
 *
 * §4.9 状态机：RISING 上升（连板延续）→ BROKEN 断板（观察期）→ [反包→RISING | 阵亡→DEAD | 停牌→SUSPENDED]。
 * 停牌不结束周期：复牌后再走断板/反包判定。
 */
enum class CycleStatus {
    /** 上升：龙头今日涨停，连板延续，max_streak 刷新 */
    RISING,

    /** 断板：龙头今日未涨停，broken_date 起观察期（默认 3 交易日，反包窗口） */
    BROKEN,

    /** 停牌：交易日历开市但龙头无 bar，周期延续，suspend_json 记录区间 */
    SUSPENDED,

    /** 阵亡：观察期内无反包，周期结束（±定性 cycle_type） */
    DEAD,
    ;
}
