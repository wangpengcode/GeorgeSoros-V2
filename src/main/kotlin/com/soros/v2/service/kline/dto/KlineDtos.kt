package com.soros.v2.service.kline.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

/**
 * §19.13.1 K线复盘端点 DTO（GET /api/v1/kline 响应）。
 *
 * 响应键 snake_case 逐字段对齐命名字典（§17.6）：trade_date/open/high/low/close/volume/amount/
 * change_pct/turnover_rate + chip 8 键（键名=signal_daily DDL 一字不差）。
 * bars 键已增册（命名字典 §19.13.1 落定行）；chip 无 signal_daily 行 → null（warm-up 前 60 日）。
 */

/** GET /api/v1/kline → 200 响应体 */
data class KlineResponse(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称（stock_info.name，联想下拉选中即带） */
    @JsonProperty("name") val name: String,
    /** K线 bars 数组（升序区间，页面消费名；语义与 items 同族） */
    @JsonProperty("bars") val bars: List<KlineBar>,
)

/** bars[] 元素（9 字段全非 null；chip 无 signal_daily 行 → null） */
data class KlineBar(
    /** 交易日（ISO 字符串；@JsonFormat 强制——standalone MockMvc 的 ObjectMapper 默认 WRITE_DATES_AS_TIMESTAMPS=true） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonProperty("trade_date") val tradeDate: LocalDate,
    /** 开盘价（qfq） */
    @JsonProperty("open") val open: BigDecimal,
    /** 最高价（qfq） */
    @JsonProperty("high") val high: BigDecimal,
    /** 最低价（qfq） */
    @JsonProperty("low") val low: BigDecimal,
    /** 收盘价（qfq） */
    @JsonProperty("close") val close: BigDecimal,
    /** 成交量（股） */
    @JsonProperty("volume") val volume: Long,
    /** 成交额（元） */
    @JsonProperty("amount") val amount: BigDecimal,
    /** 涨跌幅%（不复权口径） */
    @JsonProperty("change_pct") val changePct: BigDecimal,
    /** 换手率%（DB 原值透出，§19.13.1 决策 3 直读 stock_history.turnover_rate） */
    @JsonProperty("turnover_rate") val turnoverRate: BigDecimal,
    /** 筹码对象（signal_daily 8 列；无行=null，warm-up 段） */
    @JsonProperty("chip") val chip: KlineChip?,
)

/** chip 对象（signal_daily 8 列，键名=DDL 一字不差；8 字段全非 null） */
data class KlineChip(
    /** 获利盘% */
    @JsonProperty("profit_ratio") val profitRatio: BigDecimal,
    /** 成本偏离% = (close−avg_cost)/avg_cost×100（qfq 重对基免疫） */
    @JsonProperty("cost_dev") val costDev: BigDecimal,
    /** 90% 成本区间下沿（p5 分位，qfq 坐标） */
    @JsonProperty("c90_low") val c90Low: BigDecimal,
    /** 90% 成本区间上沿（p95 分位，qfq 坐标） */
    @JsonProperty("c90_high") val c90High: BigDecimal,
    /** 90% 集中度（东财口径 (p95−p5)/(p95+p5)×100，qfq 坐标） */
    @JsonProperty("c90_conc") val c90Conc: BigDecimal,
    /** 70% 成本区间下沿（p15 分位，qfq 坐标） */
    @JsonProperty("c70_low") val c70Low: BigDecimal,
    /** 70% 成本区间上沿（p85 分位，qfq 坐标） */
    @JsonProperty("c70_high") val c70High: BigDecimal,
    /** 70% 集中度（(p85−p15)/(p85+p15)×100，qfq 坐标） */
    @JsonProperty("c70_conc") val c70Conc: BigDecimal,
)
