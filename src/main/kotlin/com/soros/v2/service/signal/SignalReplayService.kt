package com.soros.v2.service.signal

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * 信号预计算回放服务（SignalPrecomputeJob 全历史补算 + 增量单日，§19.11.1 / §13.5）。
 *
 * 职责：每票全历史只读递推（ChipDistributionCalculator）→ 当日聚合（SignalAggregationService）
 * → 落库 signal_daily / market_daily / sector_daily 三表。
 *
 * 纪律（§19.11.1 定稿）：
 * - 全历史回放按股分批 **200 股/事务**（禁止照抄 SentimentReplay 单事务整区间），每 500 股进度日志
 * - 断点续跑：以已落库 signal_daily 行判处理水位，重跑只补缺失批次（删段重建幂等语义，§17.2）
 * - 增量=监听 SentimentCycleCompleted（新事件）+21:30 兜底，每票全历史只读递推只写当日行（幂等+除权免疫）
 * - 除权检测内置：隐含昨收 ≠ 前日 close → 该票全日期重算重写 + 更新 stock_info.adj_processed_until
 */
interface SignalReplayService {

    /**
     * 全历史回放（按股分批 200 股/事务；断点续跑）。
     *
     * @param from 回放区间起点
     * @param to   回放区间终点
     * @return 处理摘要（三表落库行数 + 处理股票数）
     */
    fun replay(from: LocalDate, to: LocalDate): SignalReplaySummary

    /**
     * 增量单日（幂等：同日已存在行则跳过；事件监听与 21:30 兜底共用）。
     *
     * @param date 交易日
     * @return 处理摘要
     */
    fun replayDay(date: LocalDate): SignalReplaySummary
}

/** 回放/增量处理摘要（§17.6 snake_case 响应键：signal_rows/market_rows/sector_rows/codes_processed，先增册再用名） */
data class SignalReplaySummary(
    /** 处理区间起点 */
    @JsonProperty("from") val from: LocalDate,
    /** 处理区间终点 */
    @JsonProperty("to") val to: LocalDate,
    /** 落库 signal_daily 行数 */
    @JsonProperty("signal_rows") val signalRows: Int,
    /** 落库 market_daily 行数 */
    @JsonProperty("market_rows") val marketRows: Int,
    /** 落库 sector_daily 行数 */
    @JsonProperty("sector_rows") val sectorRows: Int,
    /** 处理股票数 */
    @JsonProperty("codes_processed") val codesProcessed: Int,
)
