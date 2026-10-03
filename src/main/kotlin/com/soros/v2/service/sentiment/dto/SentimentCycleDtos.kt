package com.soros.v2.service.sentiment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate

/**
 * §4.9/§11.1 情绪周期查询接口 DTO（对外 JSON 键 snake_case，全库统一键名，过命名字典）。
 *
 * JSONB 内部键（big_meat_list/big_face_list/collapse_list/dragon_json/followup_json/
 * lists_manual_json）同样受命名字典约束，键名逐字段对齐。
 */

/** GET /api/v1/sentiment-cycle?date= → 单日详情（含 dragon_json/leader_json 展开） */
data class SentimentCycleResponse(
    /** 交易日 */
    @JsonProperty("trade_date") val tradeDate: LocalDate,
    /** 涨停家数 */
    @JsonProperty("limit_up_count") val limitUpCount: Int,
    /** 跌停家数 */
    @JsonProperty("limit_down_count") val limitDownCount: Int,
    /** 连板家数（limit_up_streak>=2） */
    @JsonProperty("lianban_count") val lianbanCount: Int,
    /** 当日最高板 */
    @JsonProperty("max_streak") val maxStreak: Int?,
    /** 高度龙明细 [{code,name,limit_up_streak,board,industry}] */
    @JsonProperty("dragon_json") val dragonJson: List<DragonJsonItem>?,
    /** 强势池家数 */
    @JsonProperty("pool_count") val poolCount: Int,
    /** 大肉数（池内今日>=+5%） */
    @JsonProperty("big_meat_count") val bigMeatCount: Int,
    /** 大面数（池内今日<=-5%） */
    @JsonProperty("big_face_count") val bigFaceCount: Int,
    /** 大肉名单 [{code,name,change_pct,limit_up_streak,industry}] */
    @JsonProperty("big_meat_list") val bigMeatList: List<PoolListItem>?,
    /** 大面名单（结构同上） */
    @JsonProperty("big_face_list") val bigFaceList: List<PoolListItem>?,
    /** 昨日名单今日兑现 [{code,name,src,yest_pct,today_pct,result}] */
    @JsonProperty("followup_json") val followupJson: List<FollowupItem>?,
    /** 名单人工增删留痕 [{side,action,code,name,reason,at}] */
    @JsonProperty("lists_manual_json") val listsManualJson: List<ManualListItem>?,
    /** 龙头前三名 晋级/断板/大面（无固定键枚举，原样透传） */
    @JsonProperty("leader_json") val leaderJson: JsonNode?,
    /** 崩塌池家数 */
    @JsonProperty("collapse_count") val collapseCount: Int,
    /** 崩塌池名单 [{code,name,limit_down_streak,industry}] */
    @JsonProperty("collapse_list") val collapseList: List<CollapseListItem>?,
    /** 崩塌组今日止跌反核数 */
    @JsonProperty("rebound_count") val reboundCount: Int,
    /** 大周期建议值 1-6（规则映射） */
    @JsonProperty("big_cycle_sug") val bigCycleSug: Int?,
    /** 小周期建议值 1-6（规则映射） */
    @JsonProperty("small_cycle_sug") val smallCycleSug: Int?,
    /** 人工确认值（null=未确认，展示取建议值） */
    @JsonProperty("big_cycle") val bigCycle: Int?,
    /** 小周期人工确认值（null=未确认，展示取建议值） */
    @JsonProperty("small_cycle") val smallCycle: Int?,
    /** 冰点/混沌/主升/退潮…（建议标签人工终定） */
    @JsonProperty("status_text") val statusText: String?,
    /** 数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%） */
    @JsonProperty("data_coverage") val dataCoverage: String,
)

/** GET /api/v1/sentiment-cycle/range?from=&to= → 区间序列（画情绪曲线） */
data class SentimentCycleRangeResponse(
    /** 区间序列（升序） */
    @JsonProperty("items") val items: List<SentimentCycleResponse>,
)

/** dragon_json 内元素：高度龙明细 */
data class DragonJsonItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 连板数 */
    @JsonProperty("limit_up_streak") val limitUpStreak: Int,
    /** 市场板（MAIN 主板/GEM 创业板/STAR 科创板） */
    @JsonProperty("board") val board: String,
    /** 行业（JSON 数组，主行业=第一个，展示用） */
    @JsonProperty("industry") val industry: List<String>?,
)

/** big_meat_list / big_face_list 内元素（强势池今日达标名单） */
data class PoolListItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 涨跌幅%（不复权口径） */
    @JsonProperty("change_pct") val changePct: BigDecimal,
    /** 连板数 */
    @JsonProperty("limit_up_streak") val limitUpStreak: Int,
    /** 行业（JSON 数组，主行业=第一个，展示用） */
    @JsonProperty("industry") val industry: List<String>?,
)

/** collapse_list 内元素：崩塌池名单 */
data class CollapseListItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 跌停连板（§4.9 崩塌池，镜像派生） */
    @JsonProperty("limit_down_streak") val limitDownStreak: Int,
    /** 行业（JSON 数组，主行业=第一个，展示用） */
    @JsonProperty("industry") val industry: List<String>?,
)

/** followup_json 内元素：昨日名单今日兑现 */
data class FollowupItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 来源名单（meat=大肉 / face=大面） */
    @JsonProperty("src") val src: String,
    /** 昨日涨跌幅% */
    @JsonProperty("yest_pct") val yestPct: BigDecimal?,
    /** 今日涨跌幅% */
    @JsonProperty("today_pct") val todayPct: BigDecimal?,
    /** 兑现分类（延续/回落/转大面/反核止跌/弱势震荡/继续大面/停牌） */
    @JsonProperty("result") val result: String,
)

/** lists_manual_json 内元素：名单人工增删留痕 */
data class ManualListItem(
    /** 名单侧（MEAT/FACE） */
    @JsonProperty("side") val side: String,
    /** 动作（ADD/REMOVE） */
    @JsonProperty("action") val action: String,
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String?,
    /** 理由 */
    @JsonProperty("reason") val reason: String?,
    /** 操作时间（ISO-8601） */
    @JsonProperty("at") val at: String,
)
