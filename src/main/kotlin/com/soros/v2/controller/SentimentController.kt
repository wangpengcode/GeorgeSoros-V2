package com.soros.v2.controller

import com.soros.v2.domain.CycleStatus
import com.soros.v2.service.sentiment.DragonCycleService
import com.soros.v2.service.sentiment.SentimentService
import com.soros.v2.service.sentiment.dto.DragonCycleConfirmRequest
import com.soros.v2.service.sentiment.dto.DragonCycleListResponse
import com.soros.v2.service.sentiment.dto.SentimentConfirmRequest
import com.soros.v2.service.sentiment.dto.SentimentCycleRangeResponse
import com.soros.v2.service.sentiment.dto.SentimentCycleResponse
import com.soros.v2.service.sentiment.dto.SentimentListsRequest
import com.soros.v2.service.sentiment.dto.SentimentTermsResponse
import com.soros.v2.service.sentiment.dto.StockActionsResponse
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §4.9/§11.1 SentimentController：情绪周期查询/人工确认 + 龙头生命周期接口。
 *
 * 端点（对外 JSON 键 snake_case，过命名字典）：
 * - GET /api/v1/sentiment-cycle?date=         单日详情
 * - GET /api/v1/sentiment-cycle/range?from=&to=  区间序列
 * - PUT /api/v1/sentiment-cycle/{date}/confirm   人工确认/修正 大周期/小周期/状态
 * - PUT /api/v1/sentiment-cycle/{date}/lists     人工增删大肉/大面名单
 * - GET /api/v1/sentiment-cycle/{date}/terms     当日术语判定汇总（SentimentClassifier 现算）
 * - GET /api/v1/stocks/{code}/actions?from=&to=  个股动作标签时间线
 * - GET /api/v1/dragon-cycle?status=&limit=      龙头生命周期列表
 * - PUT /api/v1/dragon-cycle/{id}/confirm        人工改判龙头周期大小/备注
 */
@RestController
@RequestMapping("/api/v1")
class SentimentController(
    private val sentimentService: SentimentService,
    private val dragonCycleService: DragonCycleService,
) {

    /** GET /api/v1/sentiment-cycle?date= → 单日详情（无行返回 200 null body） */
    @GetMapping("/sentiment-cycle")
    fun getCycle(
        @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) tradeDate: LocalDate,
    ): SentimentCycleResponse? = sentimentService.getCycle(tradeDate)

    /** GET /api/v1/sentiment-cycle/range?from=&to= → 区间序列 */
    @GetMapping("/sentiment-cycle/range")
    fun getRange(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): SentimentCycleRangeResponse = sentimentService.getRange(from, to)

    /** PUT /api/v1/sentiment-cycle/{date}/confirm → 人工确认情绪评级 */
    @PutMapping("/sentiment-cycle/{date}/confirm")
    fun confirm(
        @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) tradeDate: LocalDate,
        @RequestBody request: SentimentConfirmRequest,
    ): SentimentCycleResponse = sentimentService.confirm(tradeDate, request)

    /** PUT /api/v1/sentiment-cycle/{date}/lists → 人工增删大肉/大面名单（校验非 ST + 留痕） */
    @PutMapping("/sentiment-cycle/{date}/lists")
    fun updateLists(
        @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) tradeDate: LocalDate,
        @RequestBody request: SentimentListsRequest,
    ): SentimentCycleResponse = sentimentService.updateLists(tradeDate, request)

    /** GET /api/v1/sentiment-cycle/{date}/terms → 当日术语判定汇总（SentimentClassifier 现算，与 Job 同实现） */
    @GetMapping("/sentiment-cycle/{date}/terms")
    fun getTerms(
        @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) tradeDate: LocalDate,
    ): SentimentTermsResponse = sentimentService.getTerms(tradeDate)

    /** GET /api/v1/stocks/{code}/actions?from=&to= → 个股动作标签时间线 */
    @GetMapping("/stocks/{code}/actions")
    fun getStockActions(
        @PathVariable code: String,
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): StockActionsResponse = sentimentService.getStockActions(code, from, to)

    /** GET /api/v1/dragon-cycle?status=&limit= → 龙头生命周期列表（网页时间轴数据源） */
    @GetMapping("/dragon-cycle")
    fun listDragonCycles(
        @RequestParam("status") status: CycleStatus? = null,
        @RequestParam("limit") limit: Int = 50,
    ): DragonCycleListResponse = dragonCycleService.list(status, limit)

    /** PUT /api/v1/dragon-cycle/{id}/confirm → 人工改判龙头周期大小/备注 */
    @PutMapping("/dragon-cycle/{id}/confirm")
    fun confirmDragonCycle(
        @PathVariable id: Long,
        @RequestBody request: DragonCycleConfirmRequest,
    ) = dragonCycleService.confirm(id, request)
}
