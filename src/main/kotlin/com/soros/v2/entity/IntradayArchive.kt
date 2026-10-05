package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDate

/**
 * intraday_archive —— 收盘权威归档（盘后权威表，词表升级的数据源；15:10 用 date=当日 重拉 5 池接口权威归档）
 */
@Entity
@Table(name = "intraday_archive")
class IntradayArchive(
    @EmbeddedId
    var id: IntradayArchiveId = IntradayArchiveId(),

    /** HH:MM:SS（词表升级：早封/晚封板） */
    @Column(name = "first_seal_time", length = 8)
    var firstSealTime: String? = null,

    /** 最后封板时间（HH:MM:SS） */
    @Column(name = "last_seal_time", length = 8)
    var lastSealTime: String? = null,

    /** 炸板次数 */
    @Column(name = "zhaban_count")
    var zhabanCount: Short? = null,

    /** 封板资金（元） */
    @Column(name = "seal_amount", precision = 16, scale = 2)
    var sealAmount: BigDecimal? = null,

    /** 连板数（首板=1，源接口'连板数'） */
    @Column(name = "limit_up_streak")
    var limitUpStreak: Short? = null,

    /** 池标识（ZT/ZB/DT/STRONG/PREV；schema CHAR(6) bpchar，JDBC CHAR 对齐 ddl-auto:validate） */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pool", length = 6)
    var pool: String? = null,
)

/**
 * intraday_archive 复合主键（UNIQUE(trade_date, code) 自然键）。
 */
@Embeddable
data class IntradayArchiveId(
    /** 交易日 */
    @Column(name = "trade_date", nullable = false)
    var tradeDate: LocalDate = LocalDate.EPOCH,

    /** 证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history） */
    @Column(name = "code", nullable = false, length = 20)
    var code: String = "",
) : Serializable
