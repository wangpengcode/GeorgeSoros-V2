package com.soros.v2.repository

import com.soros.v2.entity.StockHistory
import java.math.BigDecimal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * stock_history 数据访问接口（数据底座层）。
 */
interface StockHistoryRepository : JpaRepository<StockHistory, Long> {

    /** 按 代码+交易日 查单行（UNIQUE(code, trade_date) 至多 1 行） */
    fun findByCodeAndTradeDate(code: String, tradeDate: LocalDate): StockHistory?

    /** 按 代码+交易日区间 查列表（升序） */
    fun findByCodeAndTradeDateBetween(code: String, start: LocalDate, end: LocalDate): List<StockHistory>

    /** 按 交易日区间 查全部行情（升序；§13.4 情绪派生窗口 / §13.5 回放 barsByCode 数据源） */
    fun findByTradeDateBetween(start: LocalDate, end: LocalDate): List<StockHistory>

    /**
     * §19.12 情绪回放瘦身投影批量加载（只取回放推导实际消费的 8 字段）。
     *
     * **投影消费约束**：回放推导（SentimentComputeService.computeFor 全链）仅消费
     * code/tradeDate/close/changePct/isLimitUp/isLimitDown/limitUpStreak/limitDownStreak 这 8 字段；
     * open/high/low/volume/amount/turnoverRate 在回放推导路径零消费、不进投影。
     * **未来 computeFor 新增字段消费须同步扩本投影**（口径漂移防线，§19.12 不变式①）。
     */
    @Query(
        """
        SELECT h.code AS code, h.tradeDate AS tradeDate, h.close AS close, h.changePct AS changePct,
               h.isLimitUp AS isLimitUp, h.isLimitDown AS isLimitDown,
               h.limitUpStreak AS limitUpStreak, h.limitDownStreak AS limitDownStreak
        FROM StockHistory h
        WHERE h.tradeDate BETWEEN :start AND :end
        ORDER BY h.code, h.tradeDate
        """,
    )
    fun findReplayBars(@Param("start") start: LocalDate, @Param("end") end: LocalDate): List<ReplayBarProjection>

    /** 是否已存在 代码+交易日 行（增量 upsert 幂等判重） */
    fun existsByCodeAndTradeDate(code: String, tradeDate: LocalDate): Boolean

    /** 最近一个交易日行（除权漂移检测 / 连板数派生前置） */
    fun findTopByCodeOrderByTradeDateDesc(code: String): StockHistory?

    /** 该股最大交易日（采集水位 / 对账） */
    @Query("SELECT MAX(h.tradeDate) FROM StockHistory h WHERE h.code = :code")
    fun findMaxTradeDateByCode(@Param("code") code: String): LocalDate?

    /** 区间内有行的代码清单（去重升序，§六.6⑤ 派生列抽查对拍候选池，确定性抽样） */
    @Query("SELECT DISTINCT h.code FROM StockHistory h WHERE h.tradeDate BETWEEN :start AND :end ORDER BY h.code")
    fun findDistinctCodesByTradeDateBetween(@Param("start") start: LocalDate, @Param("end") end: LocalDate): List<String>

    /** 指定交易日全部涨停行（§4.8 涨停梯队 / 最高板：is_limit_up 前置判定列） */
    fun findByTradeDateAndIsLimitUpTrue(tradeDate: LocalDate): List<StockHistory>

    /** 随机抽 N 只含未校准行的代码（CalibrationJob 低频分批候选池；calibrated=false 常驻） */
    @Query(
        value = """
            SELECT DISTINCT code FROM stock_history
            WHERE calibrated = false
            ORDER BY random()
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRandomUncalibratedCodes(@Param("limit") limit: Int): List<String>

    /** 该股最早未校准行（CalibrationJob 校准窗起点） */
    fun findTopByCodeAndCalibratedFalseOrderByTradeDateAsc(code: String): StockHistory?

    /** 该股最新未校准行（CalibrationJob 校准窗终点；null=全部已校准） */
    fun findTopByCodeAndCalibratedFalseOrderByTradeDateDesc(code: String): StockHistory?

    /**
     * 每票覆盖聚合（重跑计划分类输入；仅统计库内行，min/max 为全量 span）。
     * 返回 List 空 = 该码无数据（NO_DATA）。注意：只返回有行的 code，无行 code 不在结果集里。
     */
    @Query(
        value = """
            SELECT h.code AS code, MIN(h.trade_date) AS min_d, MAX(h.trade_date) AS max_d, COUNT(*) AS n_rows
            FROM stock_history h
            WHERE h.code IN :codes
            GROUP BY h.code
        """,
        nativeQuery = true,
    )
    fun aggregateCoverageByCodes(@Param("codes") codes: Collection<String>): List<StockSpanProjection>

    /**
     * 单票缺失开市日 gaps-and-islands（calendar 反连接；仅对 n_rows < 开市日数 的票执行）。
     * 输出升序的 [seg_from, seg_to] islands = 缺失交易日连续段。
     */
    @Query(
        value = """
            WITH span AS (
                SELECT MIN(trade_date) AS min_d, MAX(trade_date) AS max_d
                FROM stock_history WHERE code = :code
            ),
            missing AS (
                SELECT c.trade_date AS d
                FROM trading_calendar c, span s
                WHERE c.trade_date BETWEEN s.min_d AND s.max_d
                  AND NOT EXISTS (SELECT 1 FROM stock_history h
                                  WHERE h.code = :code AND h.trade_date = c.trade_date)
            ),
            islands AS (
                SELECT d, d - (ROW_NUMBER() OVER (ORDER BY d))::int AS grp
                FROM missing
            )
            SELECT MIN(d) AS seg_from, MAX(d) AS seg_to
            FROM islands
            GROUP BY grp
            ORDER BY seg_from
        """,
        nativeQuery = true,
    )
    fun findMissingDateIslands(@Param("code") code: String): List<GapIslandProjection>

    /** 每票覆盖聚合投影（别名=字段名；minD/maxD 非空——只有有行的 code 才返回） */
    interface StockSpanProjection {
        val code: String
        val min_d: LocalDate?
        val max_d: LocalDate?
        val n_rows: Long
    }

    /** gaps-and-islands 输出投影 */
    interface GapIslandProjection {
        val seg_from: LocalDate?
        val seg_to: LocalDate?
    }

    /**
     * §19.12 情绪回放瘦身投影（8 字段接口投影，防实体 hydration）。
     * 回放推导仅消费此 8 字段；未来 computeFor 新增字段消费须同步扩本接口与 [findReplayBars]。
     */
    interface ReplayBarProjection {
        /** 证券代码 */
        val code: String

        /** 交易日 */
        val tradeDate: LocalDate

        /** 收盘价（qfq） */
        val close: BigDecimal?

        /** 涨跌幅%（不复权口径） */
        val changePct: BigDecimal?

        /** 涨停（按原始 change_pct + board 阈值判定） */
        val isLimitUp: Boolean

        /** 跌停 */
        val isLimitDown: Boolean

        /** 连板数（首板=1） */
        val limitUpStreak: Short

        /** 跌停连板 */
        val limitDownStreak: Short
    }
}
