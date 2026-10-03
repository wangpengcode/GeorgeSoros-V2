package com.soros.v2.service.manual.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * §11.4 ManualDataController（V1 webhook 兼容最小集）DTO。
 *
 * 入参键名过命名字典；is_st 仅用于"识别并排除"——手动补数入口若带 is_st=true 直接拒绝（ST 隔离铁律）。
 */

/** POST /api/v1/history/daily 入参（V1 兼容：code 可选，给出则触发手动重跑；恒返 ok） */
data class ManualHistoryDailyRequest(
    /** 证券代码（可选；给出则触发手动重跑） */
    @JsonProperty("code") val code: String? = null,
    /** 重跑起始日（YYYY-MM-DD；缺省=近 10 自然日） */
    @JsonProperty("start_date") val startDate: String? = null,
    /** 重跑结束日（YYYY-MM-DD；缺省=当日） */
    @JsonProperty("end_date") val endDate: String? = null,
)

/** 恒返 ok 响应（V1 兼容：POST /history/daily 不能改成 4xx/5xx，被调用方依赖） */
data class ManualOkResponse(
    @JsonProperty("status") val status: String = "ok",
    @JsonProperty("detail") val detail: String? = null,
)

/** GET /api/v1/history/max/date/{code} 响应（增量锚点，语义原样） */
data class ManualMaxDateResponse(
    @JsonProperty("code") val code: String,
    @JsonProperty("max_trade_date") val maxTradeDate: String?,
)

/** POST /api/v1/info/stock 入参（upsert stock_info；is_st=true 拒绝） */
data class ManualStockInfoRequest(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String? = null,
    /** SH / SZ */
    @JsonProperty("market") val market: String? = null,
    /** 市场板（MAIN/GEM/STAR；北交所/B 股不采集） */
    @JsonProperty("board") val board: String? = null,
    /** 仅用于"识别并排除"，禁止作为业务可选项；true 直接拒绝 */
    @JsonProperty("is_st") val isSt: Boolean = false,
)

/** POST /api/v1/index/info 入参（upsert stock_index，code 带前缀值口径特例 sh000001） */
data class ManualIndexInfoRequest(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String? = null,
)

/** GET /api/v1/info/all 单元素（stock_info 出参） */
data class StockInfoDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String?,
    @JsonProperty("market") val market: String?,
    @JsonProperty("board") val board: String,
    @JsonProperty("is_st") val isSt: Boolean,
    @JsonProperty("delisted") val delisted: Boolean,
    @JsonProperty("ipo_date") val ipoDate: LocalDate?,
    @JsonProperty("industry") val industry: List<String>?,
    @JsonProperty("concept_boards") val conceptBoards: List<String>?,
)

/** GET /api/v1/index/all 单元素（stock_index 出参） */
data class StockIndexDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String?,
)
