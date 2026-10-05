package com.soros.v2.service.intraday.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

// =====================================================================
// Python /api/v1/intraday/* 契约 DTO（router.py 实锤；Jackson fail-on-unknown 严格模式）
// =====================================================================

/**
 * Python GET /api/v1/intraday/pools 响应（§19.13.2 P1：三池一响应 {status, date, limit_up, limit_down, broken}）。
 * date 为 YYYYMMDD（akshare 池接口格式）。
 */
data class IntradayPoolsResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("date") val date: String,
    @JsonProperty("limit_up") val limitUp: List<IntradayPoolRowDto> = emptyList(),
    @JsonProperty("limit_down") val limitDown: List<IntradayPoolRowDto> = emptyList(),
    @JsonProperty("broken") val broken: List<IntradayPoolRowDto> = emptyList(),
)

/**
 * 池行（Python _norm_pool_row 中→英 DTO：code/name/change_pct/latest_price/amount/turnover_rate/
 * lianban/first_time/broken_count/industry；缺列 NULL，不造默认值，§19.13.2 P1）。
 */
data class IntradayPoolRowDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("change_pct") val changePct: BigDecimal? = null,
    @JsonProperty("latest_price") val latestPrice: BigDecimal? = null,
    @JsonProperty("amount") val amount: BigDecimal? = null,
    @JsonProperty("turnover_rate") val turnoverRate: BigDecimal? = null,
    /** 连板数（源接口'连板数'；跌停池=连续跌停） */
    @JsonProperty("lianban") val lianban: Int? = null,
    /** 首次封板时间（HHMMSS 或 HH:MM:SS；跌停/炸板池可能缺省） */
    @JsonProperty("first_time") val firstTime: String? = null,
    /** 炸板次数（源接口'炸板次数'/'开板次数'） */
    @JsonProperty("broken_count") val brokenCount: Int? = null,
    @JsonProperty("industry") val industry: String? = null,
)

/**
 * Python GET /api/v1/intraday/spot 响应（§19.13.2 P2：{status, count, stocks[]}；
 * stocks 元素 code/name/latest_price/change_pct/volume/amount/turnover_rate，volume=股）。
 */
data class IntradaySpotResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("count") val count: Int,
    @JsonProperty("stocks") val stocks: List<IntradaySpotStockDto> = emptyList(),
)

/** 全市场快照单票 */
data class IntradaySpotStockDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("latest_price") val latestPrice: BigDecimal? = null,
    @JsonProperty("change_pct") val changePct: BigDecimal? = null,
    @JsonProperty("volume") val volume: Long? = null,
    @JsonProperty("amount") val amount: BigDecimal? = null,
    @JsonProperty("turnover_rate") val turnoverRate: BigDecimal? = null,
)

/**
 * Python GET /api/v1/intraday/bid-ask 响应（§19.13.2 P3：{status, code, bids, asks}；
 * bids/asks 各 ≤5 档 [价, 量]，买1→买5 / 卖1→卖5，停牌/异常票空档补 0.0）。
 */
data class IntradayBidAskResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("code") val code: String,
    @JsonProperty("bids") val bids: List<List<Double>> = emptyList(),
    @JsonProperty("asks") val asks: List<List<Double>> = emptyList(),
)

// =====================================================================
// GET /api/v1/intraday/overview 契约 DTO（§19.13.2 端点契约 + 任务定稿）
// =====================================================================

/**
 * 盘中监控 overview（intraday.html 消费；snake_case 信封与既有 controller 一致）。
 * - status：OK（全部源正常且有快照）/ DEGRADED（任一源退避/停轮）/ INACTIVE（尚无快照）
 * - poll_time：最新池快照采样时间（HH:mm:ss）
 * - alert_count：当日 intraday_event 行数
 * - last_poll_at：最近一次成功轮询时间（ISO；内存运行时态，重启清零可接受）
 * - sources：3 源组健康（pools/spot/bid_ask；last_ok_at/rate_state 为内存运行时键，§17.6 已定稿）
 */
data class IntradayOverviewDto(
    val status: String,
    @JsonProperty("poll_time") val pollTime: String?,
    @JsonProperty("alert_count") val alertCount: Long,
    @JsonProperty("last_poll_at") val lastPollAt: String?,
    val sources: List<IntradaySourceStatusDto>,
)

/** 单源组状态（§19.13.2 数据池状态面板 + overview.sources） */
data class IntradaySourceStatusDto(
    val source: String,
    val ok: Boolean,
    @JsonProperty("last_ok_at") val lastOkAt: String?,
    @JsonProperty("rate_state") val rateState: String,
)

// =====================================================================
// intraday_replay.page JSON 契约 DTO（§14.4 整页渲染 schema；同一 DTO 序列化）
// =====================================================================

/** kpi_series 单采样点（§14.4：t,zt,zb,dt,prem,adr,adv,dec,max_streak,gap_pct；adr=涨跌家数比） */
data class IntradayKpiPoint(
    /** 采样时间 HH:mm:ss */
    @JsonProperty("t") val t: String,
    /** 涨停家数 */
    @JsonProperty("zt") val zt: Int,
    /** 炸板家数 */
    @JsonProperty("zb") val zb: Int,
    /** 跌停家数 */
    @JsonProperty("dt") val dt: Int,
    /** 昨涨停溢价（PREV 池现拼；无源时 null） */
    @JsonProperty("prem") val prem: BigDecimal? = null,
    /** 涨跌家数比 adv/dec（§17.6 定稿口径；dec=0 时 null 防除零） */
    @JsonProperty("adr") val adr: BigDecimal? = null,
    /** 上涨家数 */
    @JsonProperty("adv") val adv: Int,
    /** 下跌家数 */
    @JsonProperty("dec") val dec: Int,
    /** 最高板 */
    @JsonProperty("max_streak") val maxStreak: Int? = null,
    /** 竞价缺口%（竞价方案落地盘中时穿透定稿，本期 null） */
    @JsonProperty("gap_pct") val gapPct: BigDecimal? = null,
)

/** 连板梯队行（§14.4 ladder 全名：code,name,limit_up_streak,change_pct,seal_amount,first_seal_time,zhaban_count,limit_stat） */
data class IntradayLadderItem(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("limit_up_streak") val limitUpStreak: Int? = null,
    @JsonProperty("change_pct") val changePct: BigDecimal? = null,
    @JsonProperty("seal_amount") val sealAmount: BigDecimal? = null,
    @JsonProperty("first_seal_time") val firstSealTime: String? = null,
    @JsonProperty("zhaban_count") val zhabanCount: Int? = null,
    @JsonProperty("limit_stat") val limitStat: String? = null,
)

/** 事件流行（§14.4：time,code,name,tg,txt；tg=事件类型标签，钉钉记录走独立 panels.ding_talk 不混入） */
data class IntradayReplayEvent(
    /** HH:mm:ss */
    @JsonProperty("time") val time: String,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("name") val name: String? = null,
    /** 事件类型标签（ZT/ZB/HF/DM/MAXCHG/OPEN/ALERT） */
    @JsonProperty("tg") val tg: String,
    @JsonProperty("txt") val txt: String,
)

/** panels（§14.4：big_face / ding_talk / strategy_alerts / pools） */
data class IntradayPanelsDto(
    @JsonProperty("big_face") val bigFace: List<IntradayReplayEvent> = emptyList(),
    @JsonProperty("ding_talk") val dingTalk: List<IntradayReplayEvent> = emptyList(),
    @JsonProperty("strategy_alerts") val strategyAlerts: List<IntradayReplayEvent> = emptyList(),
    @JsonProperty("pools") val pools: List<IntradayPoolPanelDto> = emptyList(),
)

/** pools 池状态面板运行时态（§14.4：enabled ∘ intraday_pool_state；last_ok_at/rate_state 内存态不入库） */
data class IntradayPoolPanelDto(
    @JsonProperty("pool") val pool: String,
    @JsonProperty("enabled") val enabled: Boolean,
    @JsonProperty("last_ok_at") val lastOkAt: String? = null,
    @JsonProperty("rate_state") val rateState: String = "NORMAL",
)

/** intraday_replay.page 整页（§14.4：{kpi_series, ladder, events, panels}） */
data class IntradayPageDto(
    @JsonProperty("kpi_series") val kpiSeries: List<IntradayKpiPoint> = emptyList(),
    @JsonProperty("ladder") val ladder: List<IntradayLadderItem> = emptyList(),
    @JsonProperty("events") val events: List<IntradayReplayEvent> = emptyList(),
    @JsonProperty("panels") val panels: IntradayPanelsDto = IntradayPanelsDto(),
)

/** 事件详情 JSONB（§14.4：{limit_up_streak,seal_amount,zhaban_count,change_pct,kelly{...}}） */
data class IntradayEventDetail(
    @JsonProperty("limit_up_streak") val limitUpStreak: Int? = null,
    @JsonProperty("seal_amount") val sealAmount: BigDecimal? = null,
    @JsonProperty("zhaban_count") val zhabanCount: Int? = null,
    @JsonProperty("change_pct") val changePct: BigDecimal? = null,
)
