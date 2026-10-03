package com.soros.v2.service.sentiment

import com.soros.v2.service.sentiment.dto.SentimentConfirmRequest
import com.soros.v2.service.sentiment.dto.SentimentCycleRangeResponse
import com.soros.v2.service.sentiment.dto.SentimentCycleResponse
import com.soros.v2.service.sentiment.dto.SentimentListsRequest
import com.soros.v2.service.sentiment.dto.SentimentTermsResponse
import com.soros.v2.service.sentiment.dto.StockActionsResponse
import java.time.LocalDate

/**
 * §4.9/§11.1 情绪周期查询与人工确认服务（查 sentiment_cycle，口径见各方法 KDoc）。
 */
interface SentimentService {

    /** GET /api/v1/sentiment-cycle?date= → 单日详情（含 dragon_json/leader_json 展开）；无行返回 null */
    fun getCycle(tradeDate: LocalDate): SentimentCycleResponse?

    /** GET /api/v1/sentiment-cycle/range?from=&to= → 区间序列（画情绪曲线） */
    fun getRange(from: LocalDate, to: LocalDate): SentimentCycleRangeResponse

    /** PUT /api/v1/sentiment-cycle/{date}/confirm → 人工确认/修正 大周期/小周期/状态（人工确认值优先于建议值展示） */
    fun confirm(tradeDate: LocalDate, request: SentimentConfirmRequest): SentimentCycleResponse

    /** PUT /api/v1/sentiment-cycle/{date}/lists → 人工增删大肉/大面名单（校验非 ST、重算 count、lists_manual_json 留痕） */
    fun updateLists(tradeDate: LocalDate, request: SentimentListsRequest): SentimentCycleResponse

    /** GET /api/v1/sentiment-cycle/{date}/terms → 当日术语判定汇总（SentimentClassifier 现算，与 Job 落库同实现） */
    fun getTerms(tradeDate: LocalDate): SentimentTermsResponse

    /** GET /api/v1/stocks/{code}/actions?from=&to= → 个股动作标签时间线（反包/晋级/断板/反核止跌/大肉/大面/停牌） */
    fun getStockActions(code: String, from: LocalDate, to: LocalDate): StockActionsResponse
}
