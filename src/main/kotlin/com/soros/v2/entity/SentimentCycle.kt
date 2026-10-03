package com.soros.v2.entity

import com.fasterxml.jackson.databind.JsonNode
import com.soros.v2.domain.DataCoverage
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * sentiment_cycle —— 情绪周期表（每日一行，SentimentCycleJob 派生，§4.9）
 */
@Entity
@Table(name = "sentiment_cycle")
class SentimentCycle(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,

    /** 交易日 */
    @Column(name = "trade_date", nullable = false, unique = true)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 涨停家数 */
    @Column(name = "limit_up_count", nullable = false)
    var limitUpCount: Int = 0,

    /** 跌停家数 */
    @Column(name = "limit_down_count", nullable = false)
    var limitDownCount: Int = 0,

    /** 连板家数（limit_up_streak>=2） */
    @Column(name = "lianban_count", nullable = false)
    var lianbanCount: Int = 0,

    /** 当日最高板 */
    @Column(name = "max_streak")
    var maxStreak: Short? = null,

    /** 高度龙明细 [{code,name,limit_up_streak,board,industry}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dragon_json")
    var dragonJson: JsonNode? = null,

    /** 强势池家数 */
    @Column(name = "pool_count", nullable = false)
    var poolCount: Int = 0,

    /** 大肉数（池内今日>=+5%） */
    @Column(name = "big_meat_count", nullable = false)
    var bigMeatCount: Int = 0,

    /** 大面数（池内今日<=-5%） */
    @Column(name = "big_face_count", nullable = false)
    var bigFaceCount: Int = 0,

    /** [{code,name,change_pct,limit_up_streak,industry}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "big_meat_list")
    var bigMeatList: JsonNode? = null,

    /** 结构同上 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "big_face_list")
    var bigFaceList: JsonNode? = null,

    /** 昨日名单今日兑现 [{code,name,src,yest_pct,today_pct,result}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "followup_json")
    var followupJson: JsonNode? = null,

    /** 名单人工增删留痕 [{side,action,code,name,reason,at}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lists_manual_json")
    var listsManualJson: JsonNode? = null,

    /** 龙头前三名 晋级/断板/大面 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "leader_json")
    var leaderJson: JsonNode? = null,

    /** 崩塌池家数 */
    @Column(name = "collapse_count", nullable = false)
    var collapseCount: Int = 0,

    /** 崩塌池名单 [{code,name,limit_down_streak,industry}]（§17.5 C1，与大肉/大面名单同构） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collapse_list")
    var collapseList: JsonNode? = null,

    /** 崩塌组今日止跌反核数 */
    @Column(name = "rebound_count", nullable = false)
    var reboundCount: Int = 0,

    /** 大周期建议值 1-6（规则映射） */
    @Column(name = "big_cycle_sug")
    var bigCycleSug: Short? = null,

    /** 小周期建议值 1-6（规则映射） */
    @Column(name = "small_cycle_sug")
    var smallCycleSug: Short? = null,

    /** 人工确认值（null=未确认，展示取建议值） */
    @Column(name = "big_cycle")
    var bigCycle: Short? = null,

    /** 小周期人工确认值（null=未确认，展示取建议值） */
    @Column(name = "small_cycle")
    var smallCycle: Short? = null,

    /** 冰点/混沌/主升/退潮…（建议标签人工终定） */
    @Column(name = "status_text")
    var statusText: String? = null,

    /** 数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%，§13.4） */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_coverage", nullable = false)
    var dataCoverage: DataCoverage = DataCoverage.FULL,

    /** 行创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
