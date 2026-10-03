package com.soros.v2.service.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Python 数据服务 API 契约 DTO（PLAN §11.1 / §5.6；Jackson fail-on-unknown 严格模式）。
 *
 * 键名对齐 Python JSON（§2.4 四端映射）：change_percent / turnover / prev_close 等
 * 与 DB 列名（change_pct / turnover_rate / —）有意不同，靠 @JsonProperty 显式映射，禁止"顺手统一"。
 */

/** Python /api/v1/health 响应（§11.1：{status, sources:{baostock/akshare/mootdx: ok|degraded|down}}） */
data class PythonHealthResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("sources") val sources: Map<String, String>,
)

/** Python /api/v1/stock-list 响应（§11.1：{status, stocks[]}） */
data class StockListResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("stocks") val stocks: List<StockListDto>,
)

/** 单只股票（/stock-list 元素：AKShare 列表 + st_em/stop_em 合并；is_st 仅用于识别排除） */
data class StockListDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("market") val market: String,
    @JsonProperty("board") val board: String,
    @JsonProperty("is_st") val isSt: Boolean,
    @JsonProperty("delisted") val delisted: Boolean,
)

/** Python /api/v1/trading-calendar 响应（§11.1：一次全量 dates[]，YYYY-MM-DD） */
data class TradingCalendarResponse(
    @JsonProperty("dates") val dates: List<String>,
)

/**
 * 单根日 K 线（Python Bar，§5.6 11 字段契约）。
 * 口径：OHLC=qfq、volume=股、amount=元、change_percent=不复权真实涨跌幅、prev_close=传输字段不落库。
 */
data class DailyBar(
    @JsonProperty("date") val date: LocalDate,
    @JsonProperty("code") val code: String,
    @JsonProperty("open") val open: BigDecimal,
    @JsonProperty("high") val high: BigDecimal,
    @JsonProperty("low") val low: BigDecimal,
    @JsonProperty("close") val close: BigDecimal,
    @JsonProperty("volume") val volume: Long,
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("change_percent") val changePercent: BigDecimal,
    @JsonProperty("turnover") val turnover: BigDecimal,
    @JsonProperty("prev_close") val prevClose: BigDecimal? = null,
)

/** Python /api/v1/daily-bars/batch 请求（§11.1：codes 裸数字、dates YYYY-MM-DD、adjust=qfq） */
data class DailyBarsBatchRequest(
    @JsonProperty("codes") val codes: List<String>,
    @JsonProperty("start_date") val startDate: String,
    @JsonProperty("end_date") val endDate: String,
    @JsonProperty("adjust") val adjust: String = "qfq",
)

/** Python /api/v1/daily-bars/batch 响应（§11.1：results 按 code 分组，failed[] 单股失败不炸整批） */
data class DailyBarsBatchResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("results") val results: Map<String, StockBarsResult> = emptyMap(),
    @JsonProperty("failed") val failed: List<FailedBar> = emptyList(),
)

/** 单股批量结果（results[code]：source 为小写 baostock/akshare/mootdx） */
data class StockBarsResult(
    @JsonProperty("source") val source: String,
    @JsonProperty("count") val count: Int,
    @JsonProperty("data") val data: List<DailyBar>,
)

/** 单股失败项（failed[] 元素） */
data class FailedBar(
    @JsonProperty("code") val code: String,
    @JsonProperty("reason") val reason: String,
)
