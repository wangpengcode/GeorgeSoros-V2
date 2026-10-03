package com.soros.v2.domain

/**
 * 龙头周期类型（dragon_cycle.cycle_type 值域单点；schema.sql CHECK IN ('BIG','SMALL')；null=进行中未定性）。
 *
 * §4.9 用户口径：小周期=龙头 5-7 板断板且不反包；大周期=断板后反包再涨停创新高。
 */
enum class CycleType {
    /** 大周期：断板反包再涨停创新高，多打好几个板 */
    BIG,

    /** 小周期：5-7 板断板且不反包，周期随之结束 */
    SMALL,
    ;
}
