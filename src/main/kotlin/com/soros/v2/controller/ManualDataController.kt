package com.soros.v2.controller

import com.soros.v2.service.StockInfoService
import com.soros.v2.service.manual.ManualDataService
import com.soros.v2.service.manual.dto.ManualHistoryDailyRequest
import com.soros.v2.service.manual.dto.ManualIndexInfoRequest
import com.soros.v2.service.manual.dto.ManualMaxDateResponse
import com.soros.v2.service.manual.dto.ManualOkResponse
import com.soros.v2.service.manual.dto.ManualStockInfoRequest
import com.soros.v2.service.manual.dto.ManualStockListRefreshResponse
import com.soros.v2.service.manual.dto.StockIndexDto
import com.soros.v2.service.manual.dto.StockInfoDto
import com.soros.v2.service.manual.mapper.toDto
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * §11.4 ManualDataController（V1 webhook 兼容最小集 + 手动重跑）。
 *
 * 保留 6 端点（V1 真实调用方依赖）：
 * - POST /history/daily：恒返 ok（语义不能改成 4xx/5xx）；code 可选，给出则触发手动重跑
 * - GET /history/max/date/{code}：增量锚点（语义原样）
 * - POST /info/stock、GET /info/all
 * - POST /index/info、GET /index/all
 * 新增（§11.4）：手动重跑（按 code+区间）；失败任务列表/重试后置（本期不落）。
 * 砍：V1 TestController 4 个 /test 端点。
 */
@RestController
@RequestMapping("/api/v1")
class ManualDataController(
    private val manualDataService: ManualDataService,
    private val stockInfoService: StockInfoService,
) {

    private val logger = LoggerFactory.getLogger(ManualDataController::class.java)

    /** POST /api/v1/history/daily：恒返 ok；code 给出时触发手动重跑（失败也只降级 detail，不改变 ok 状态） */
    @PostMapping("/history/daily")
    suspend fun historyDaily(@RequestBody(required = false) request: ManualHistoryDailyRequest?): ManualOkResponse {
        val code = request?.code ?: return ManualOkResponse()
        val start = request.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now().minusDays(10)
        val end = request.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        return try {
            val result = manualDataService.replayDaily(code, start, end)
            if (result == null) {
                ManualOkResponse("ok", "replay skipped: 无该股或无数据 code=$code")
            } else {
                ManualOkResponse("ok", "replay ok code=$code rows=${result.totalRows} drift=${result.driftDetected}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("[manual] 手动重跑失败 code={} error={}", code, e.message)
            ManualOkResponse("ok", "replay failed code=$code: ${e.message}")
        }
    }

    /** GET /api/v1/history/max/date/{code}：增量锚点（无数据返回 null 字段） */
    @GetMapping("/history/max/date/{code}")
    fun historyMaxDate(@PathVariable code: String): ManualMaxDateResponse {
        val max = manualDataService.findMaxTradeDate(code)
        return ManualMaxDateResponse(code, max?.toString())
    }

    /** POST /api/v1/info/stock：单条 upsert（is_st=true 拒绝，ST 隔离铁律） */
    @PostMapping("/info/stock")
    fun infoStock(@RequestBody request: ManualStockInfoRequest): StockInfoDto =
        manualDataService.upsertStockInfo(request).toDto()

    /**
     * POST /api/v1/info/refresh：全量刷新股票清单（BaoStock query_stock_basic，含
     * board/market/search_key/ipo_date 回填，ST/退市/北交所隔离）——回填与采集的前置。
     * 空库直接回填会被防御拒绝（BackfillJob 空候选 FAILED），先调本端点。
     */
    @PostMapping("/info/refresh")
    suspend fun refreshStockList(): ManualStockListRefreshResponse {
        val stocks = stockInfoService.refreshStockList()
        logger.info("[manual] 股票清单手动刷新完成 count={}", stocks.size)
        return ManualStockListRefreshResponse(stockCount = stocks.size)
    }

    /** GET /api/v1/info/all：全量 stock_info */
    @GetMapping("/info/all")
    fun infoAll(): List<StockInfoDto> = manualDataService.listAllStockInfo().map { it.toDto() }

    /** POST /api/v1/index/info：单条 upsert stock_index（code 带前缀值口径特例 sh000001） */
    @PostMapping("/index/info")
    fun indexInfo(@RequestBody request: ManualIndexInfoRequest): StockIndexDto =
        manualDataService.upsertStockIndex(request.code, request.name ?: "").toDto()

    /** GET /api/v1/index/all：全量 stock_index */
    @GetMapping("/index/all")
    fun indexAll(): List<StockIndexDto> = manualDataService.listAllStockIndex().map { it.toDto() }
}
