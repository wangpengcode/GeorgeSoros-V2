package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * stock_info —— 股票基础信息（数据底座层一期采集；is_st 仅用于识别排除）
 */
@Entity
@Table(
    name = "stock_info",
    uniqueConstraints = [UniqueConstraint(name = "stock_info_code_key", columnNames = ["code"])],
)
class StockInfo(
    /** 行主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Int = 0,

    /** 600000 */
    @Column(name = "code", nullable = false)
    var code: String = "",

    /** 名称 */
    @Column(name = "name")
    var name: String? = null,

    /** SH / SZ */
    @Column(name = "market")
    var market: String? = null,

    /** 市场板（MAIN 主板/GEM 创业板/STAR 科创板） */
    @Column(name = "board")
    var board: String = "MAIN",

    /** 仅用于"识别并排除"，禁止作为业务可选项 */
    @Column(name = "is_st")
    var isSt: Boolean = false,

    /** 缺失≠退市：人工确认才置 true */
    @Column(name = "delisted")
    var delisted: Boolean = false,

    /** BaoStock ipoDate；§4.8 IPO 首 5 日守卫 */
    @Column(name = "ipo_date")
    var ipoDate: LocalDate? = null,

    /** JSON 数组（一股可属多行业，主行业=第一个，展示用） */
    @Convert(converter = StringListJsonConverter::class)
    @Column(name = "industry")
    var industry: List<String>? = null,

    /** JSON 数组 */
    @Convert(converter = StringListJsonConverter::class)
    @Column(name = "concept_boards")
    var conceptBoards: List<String>? = null,

    /** 小写 name+全拼+拼音首字母，pg_trgm 模糊搜索 */
    @Column(name = "search_key")
    var searchKey: String? = null,

    /** 流通股本（股）；东财快照流通市值÷收盘价反推，BaoStock profit 季度对拍（§17.1 B1） */
    @Column(name = "float_shares")
    var floatShares: Long? = null,

    /** 总股本（股）；市值=股本×当日收盘价，条件求值时现算不落市值列 */
    @Column(name = "total_shares")
    var totalShares: Long? = null,

    /** 股本刷新时间 */
    @Column(name = "shares_updated_at")
    var sharesUpdatedAt: LocalDateTime? = null,

    /** 除权处理水位（§17.1 B6）：AdjustCheckStep 重算完该股筹码后更新，扫描时水位覆盖即跳过 */
    @Column(name = "adj_processed_until")
    var adjProcessedUntil: LocalDate? = null,

    /**
     * 数据导入水位（2026-10-05 用户定稿）：该票数据已核对/导入到的最近交易日。
     * 判定口径：= 最新开市日 → 跳过导入；落后 → 从次日断点续传导入。
     * 成功落库或整段验证空后推进到段终点（单调不减防御，禁止回拨）；NULL=从未导入。
     */
    @Column(name = "input_data_last_day")
    var inputDataLastDay: LocalDate? = null,

    /** 行更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
