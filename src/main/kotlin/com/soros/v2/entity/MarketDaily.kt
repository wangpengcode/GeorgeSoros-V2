package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.domain.DataCoverage
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDate

/**
 * market_daily —— 全市场温度计（一日一行，SignalPrecomputeJob 派生，§12.4）
 */
@Entity
@Table(name = "market_daily")
class MarketDaily(
    /** 交易日 */
    @Id
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 上涨家数 */
    @Column(name = "adv_count")
    var advCount: Short? = null,

    /** 下跌家数 */
    @Column(name = "dec_count")
    var decCount: Short? = null,

    /** 冗余 = jsonb_array_length(limit_up_list)，同源校验 */
    @Column(name = "limit_up_count")
    var limitUpCount: Short? = null,

    /** 同上 */
    @Column(name = "limit_down_count")
    var limitDownCount: Short? = null,

    /** 涨停名单 [{code,name,change_pct,limit_up_streak,industry,reason}]；reason=LLM 归因（AttributionStep，仅复盘展示不进条件） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limit_up_list")
    var limitUpList: JsonNode? = null,

    /** 跌停名单（同构） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limit_down_list")
    var limitDownList: JsonNode? = null,

    /** 炸板家数（日线近似口径） */
    @Column(name = "zhaban_count")
    var zhabanCount: Short? = null,

    /** 昨涨停今溢价 = 昨名单 ∘ 今行情（表自算自洽） */
    @Column(name = "yst_limit_premium")
    var ystLimitPremium: BigDecimal? = null,

    /** 分级晋级率 {"total":21.05,"by_level":{"1to2":33.3,...}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "yst_promotion")
    var ystPromotion: JsonNode? = null,

    /** 昨日大面家数 */
    @Column(name = "yst_face_count")
    var ystFaceCount: Short? = null,

    /** 数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%，§13.4） */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_coverage", nullable = false)
    var dataCoverage: DataCoverage = DataCoverage.FULL,
)
