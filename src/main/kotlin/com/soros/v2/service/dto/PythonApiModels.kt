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
    /** BaoStock query_stock_basic ipoDate（YYYY-MM-DD；§4.8 IPO 首 5 日守卫；Step 5a 增补） */
    @JsonProperty("ipo_date") val ipoDate: String? = null,
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
    @JsonProperty("change_percent") val changePercent: BigDecimal? = null,  // 新股/窗口首行无前收盘 → null（DB change_pct 可空）
    @JsonProperty("turnover") val turnover: BigDecimal? = null,
    @JsonProperty("prev_close") val prevClose: BigDecimal? = null,
)

/** Python /api/v1/daily-bars/batch 请求（§11.1；items 模式=重跑计划逐段窗口，codes+dates 模式=向后兼容） */
data class DailyBarsBatchRequest(
    @JsonProperty("codes") val codes: List<String> = emptyList(),
    @JsonProperty("start_date") val startDate: String? = null,
    @JsonProperty("end_date") val endDate: String? = null,
    @JsonProperty("adjust") val adjust: String = "qfq",
    @JsonProperty("items") val items: List<BatchItem> = emptyList(),
) {
    init {
        require(items.isNotEmpty() || (codes.isNotEmpty() && startDate != null && endDate != null)) {
            "必须提供 items 或 codes+start_date+end_date 二选一"
        }
        require(items.isEmpty() || (codes.isEmpty() && startDate == null && endDate == null)) {
            "items 与 codes+start_date+end_date 二选一，禁止混用"
        }
    }
}

/** items 单段（code + 独立拉取窗口） */
data class BatchItem(
    @JsonProperty("code") val code: String,
    @JsonProperty("start_date") val startDate: String,
    @JsonProperty("end_date") val endDate: String,
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
    /** 源故障文案；null=正常（2026-10-04 部署穿透：Python 侧静默 0 行无法区分「无数据」与「故障」） */
    @JsonProperty("error") val error: String? = null,
    /**
     * 空结果源名单（2026-10-05 均分流量定稿：K=2 验证空）。
     * count=0 占位时 Python 附带：该票历史上返回过空结果的源名去重清单（跨轮累积）。
     * ≥2 个不同源 → 验证空成立；null（旧版 Python）或单源 → pending-empty，保守不推水位。
     */
    @JsonProperty("empty_sources") val emptySources: List<String>? = null,
)

/** 单股失败项（failed[] 元素） */
data class FailedBar(
    @JsonProperty("code") val code: String,
    @JsonProperty("reason") val reason: String,
)

/**
 * Python /api/v1/fundamentals（§11.1：AKShare stock_yjbb_em，report_date=季度末 YYYYMMDD）。
 *
 * - report_date 为 8 位 YYYYMMDD（PLAN §11.1 契约字面量，与 AKShare 输入一致）；
 * - revenue / net_profit 单位=元（Python 侧源亿元 ×1e8）。
 */
data class FundamentalsRequest(
    @JsonProperty("report_date") val reportDate: String,
)

/** Python /api/v1/fundamentals 响应（§11.1：{stocks:[{code,revenue,net_profit}]}） */
data class FundamentalsResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("stocks") val stocks: List<FundamentalsStockDto>,
)

/** 单股财务（revenue/net_profit 单位=元，源亿元 ×1e8；§2.4 fundamentals 单位元） */
data class FundamentalsStockDto(
    @JsonProperty("code") val code: String,
    @JsonProperty("revenue") val revenue: BigDecimal,
    @JsonProperty("net_profit") val netProfit: BigDecimal,
)

/** Python /api/v1/board-members 请求（§4.8：industry 每日 / concept 每周） */
data class BoardMembersRequest(
    @JsonProperty("board_type") val boardType: String,
)

/** Python /api/v1/board-members 响应（§4.8：{boards:{板块名:[codes]}} 覆盖写快照 + 降级标记） */
data class BoardMembersResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("boards") val boards: Map<String, List<String>> = emptyMap(),
    /** 任一板块拉取失败/降级 → true（Python 侧单板块失败跳过，§4.8 防御） */
    @JsonProperty("degraded") val degraded: Boolean = false,
)

/** 板块成分快照（§4.8 覆盖写消费侧：boards + degraded 降级标记，防部分源故障误清全库） */
data class BoardMembersSnapshot(
    @JsonProperty("boards") val boards: Map<String, List<String>> = emptyMap(),
    @JsonProperty("degraded") val degraded: Boolean = false,
)

/** Python /api/v1/daily-bars/cross-validate 请求（§11.2：双源交叉验证，只观测不修正） */
data class CrossValidateRequest(
    @JsonProperty("codes") val codes: List<String>,
    @JsonProperty("start_date") val startDate: String,
    @JsonProperty("end_date") val endDate: String,
    @JsonProperty("adjust") val adjust: String = "qfq",
)

/**
 * Python /api/v1/daily-bars/cross-validate 响应（§11.2）。
 * results: {code: {source: {source,count,data[]}}}；mootdx 永不参与；源无数据也占位 count=0。
 */
data class CrossValidateResponse(
    @JsonProperty("status") val status: String,
    @JsonProperty("results") val results: Map<String, Map<String, StockBarsResult>> = emptyMap(),
    @JsonProperty("failed") val failed: List<FailedBar> = emptyList(),
)
