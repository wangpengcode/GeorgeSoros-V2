package com.soros.v2.util

/**
 * §4.4 DailyCollectJob 顺带采集的 5 个基准指数（index_history；code 带前缀值口径特例 sh000001）。
 *
 * 说明：PLAN 正文仅定稿"5 个基准指数（sh000001 等）"，未逐一列码；
 * 采用 A 股标准五基准（上证/深成/沪深300/中证500/创业板指），增删在本单点维护。
 *
 * 依赖缺口（实证）：Python /api/v1/daily-bars/batch 的 codes 校验仅接受裸数字 6 位（models.py
 * DailyBarsBatchRequest._validate_codes），带 sh/sz 前缀的指数代码会被 422 拒绝——本阶段 Kotlin 不直连数据源
 * （§外部调用全部走 Python 服务），指数日 K 采集需 Python 侧支持指数代码（Step 3 增补）后方可端到端打通。
 */
object BenchmarkIndices {

    data class IndexDef(val code: String, val name: String)

    val INDICES: List<IndexDef> = listOf(
        IndexDef("sh000001", "上证指数"),
        IndexDef("sz399001", "深证成指"),
        IndexDef("sh000300", "沪深300"),
        IndexDef("sh000905", "中证500"),
        IndexDef("sz399006", "创业板指"),
    )
}
