package com.soros.v2.job

import com.soros.v2.config.DataCollectionProperties
import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.StockHistoryService
import com.soros.v2.service.StockInfoService
import com.soros.v2.service.TradingCalendarService
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.DailyCollectCompleted
import com.soros.v2.util.BenchmarkIndices
import com.soros.v2.util.CollectMetrics
import com.soros.v2.util.MdcSupport
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §4.4/§4.7 DailyCollectJob：@Scheduled 20:00 MON-FRI + coroutines（sorosIo dispatcher 收口）。
 *
 * 链路（§4.4/§4.7/§4.8/§4.6）：
 * 0. ensureLoaded（幂等）→ isTradingDay 守卫（§17.2；fail-open：日历不可判定不阻塞采集）
 * 1. healthCheck → 失败快速失败 + PYTHON_SERVICE_OFFLINE 钉钉告警（§11.3）
 *    → StockInfoService.refreshStockList()（ST/退市/北交所隔离，仅 MAIN/GEM/STAR）
 * 2. 分批 fetchDailyBarsBatch（协程并发 ≤5）→ StockHistoryService.saveBatch
 *    （DataValidator/涨停/漂移重拉/连板派生/IPO 守卫，见 service 接口）；0 行入库不计 success
 * 3. 滚动重拉窗口（§4.7 防线④）：起点 = min(findMaxDate+1, 10 自然日前)
 * 4. 5 个基准指数日 K 采集（index_history，code 带前缀特例 sh000001）
 * 5. 完成发 DailyCollectCompleted 事件（§13.4 握手）→ 失败率>10% 钉钉告警 → 钉钉 digest
 *
 * 单飞防重入：@Scheduled 默认单线程调度器串行，天然单飞；
 * 错过窗口补偿 = §17.2 21:30 兜底 cron + 次日滚动窗口自动补采。
 */
@Component
class DailyCollectJob(
    private val pythonClient: PythonDataServiceClient,
    private val historyService: StockHistoryService,
    private val infoService: StockInfoService,
    private val calendarService: TradingCalendarService,
    private val properties: DataCollectionProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val notifier: DingTalkNotifier,
    private val metrics: CollectMetrics,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(DailyCollectJob::class.java)

    private companion object {
        /** §4.4 协程并发上限（Python 侧限流配合，调用方并发度 ≤ 5） */
        const val MAX_FETCH_CONCURRENCY = 5
        /** §11.3 批次失败率告警阈值（%） */
        val FAILURE_RATE_ALERT_THRESHOLD = BigDecimal("10")
    }

    /** §4.7 防线① 采集时序 cron：MON-FRI（§4.4；周末非交易日不空转） */
    @Scheduled(cron = "\${soros.collect.cron:0 0 20 * * MON-FRI}")
    fun execute() {
        runBlocking(sorosIo) {
            collect()
        }
    }

    /** §4.4/§4.7 编排：交易日守卫 → 健康检查 → 列表 → 分批行情入库 → 基准指数 → 完成事件（不含业务逻辑） */
    private suspend fun collect() {
        val startMs = System.currentTimeMillis()
        if (!guardTradingDay()) return

        MdcSupport.withMdcSuspend(MdcSupport.JOB to "daily-collect", MdcSupport.ATTEMPT to "1") {
            val healthOk = MdcSupport.withMdcSuspend(MdcSupport.PHASE to "health-check") { pythonClient.healthCheck() }
            if (!healthOk) {
                logger.error("[Step 1] Python 健康检查失败，快速失败跳过本次采集")
                notifier.notify(DingTalkEvent.PYTHON_SERVICE_OFFLINE, "Python 服务离线", "健康检查失败，本次采集跳过")
                return@withMdcSuspend
            }
            logger.info("[Step 1] Python 健康检查通过，开始采集")

            val stocks = MdcSupport.withMdcSuspend(MdcSupport.PHASE to "stock-list") { infoService.refreshStockList() }
            logger.info("[Step 2] 有效股票 {} 只（ST/退市/北交所已隔离）", stocks.size)

            val (success, failed) = MdcSupport.withMdcSuspend(MdcSupport.PHASE to "collect-daily-bars") {
                collectDailyBars(stocks)
            }
            MdcSupport.withMdcSuspend(MdcSupport.PHASE to "benchmark-indices") { collectBenchmarkIndices() }

            val durationMs = System.currentTimeMillis() - startMs
            logger.info("[Step 5] 采集完成 success={} failed={} durationMs={}", success.size, failed.size, durationMs)
            notifyFailureRateIfHigh(success, failed)
            eventPublisher.publishEvent(DailyCollectCompleted(success, failed, durationMs))
            notifier.notifyDailyDigest("日度采集完成：成功 ${success.size} 只，失败 ${failed.size} 只，耗时 ${durationMs}ms")
        }
    }

    /** §17.2 交易日守卫（fail-open）：ensureLoaded 失败/不可判定不阻塞采集；仅明确非交易日才跳过 */
    private suspend fun guardTradingDay(): Boolean = MdcSupport.withMdcSuspend(
        MdcSupport.JOB to "daily-collect",
        MdcSupport.PHASE to "guard",
        MdcSupport.ATTEMPT to "1",
    ) {
        val calendarReady = try {
            calendarService.ensureLoaded()
            true
        } catch (e: Exception) {
            logger.warn("[Step 0] 交易日历加载失败（fail-open 不阻塞采集）：{}", e.message)
            false
        }
        val today = LocalDate.now()
        val trading = if (calendarReady) calendarService.isTradingDay(today) else true
        if (!trading) logger.info("[Step 0] 非交易日 {} 跳过", today)
        trading
    }

    /** 分批拉取 + 入库（并发 ≤ 5）；返回 (successCodes, failedCodes) */
    private suspend fun collectDailyBars(stocks: List<StockInfo>): Pair<Set<String>, Set<String>> {
        val success = Collections.synchronizedSet(mutableSetOf<String>())
        val failed = Collections.synchronizedSet(mutableSetOf<String>())
        val semaphore = Semaphore(MAX_FETCH_CONCURRENCY)
        coroutineScope {
            for (chunk in stocks.chunked(properties.batchSize)) {
                async(sorosIo) {
                    semaphore.withPermit { fetchAndSaveChunk(chunk, success, failed) }
                }
            }
        }
        return success.toSet() to failed.toSet()
    }

    /** 单批：拉取批量行情 → 逐股 saveBatch（单股失败记 failed 不炸整批） */
    private suspend fun fetchAndSaveChunk(
        chunk: List<StockInfo>,
        success: MutableSet<String>,
        failed: MutableSet<String>,
    ) {
        val request = DailyBarsBatchRequest(
            codes = chunk.map { it.code },
            startDate = chunk.minOf { windowStartFor(it.code) }.toString(),
            endDate = LocalDate.now().toString(),
            adjust = "qfq",
        )
        MdcSupport.withMdcSuspend(
            MdcSupport.JOB to "daily-collect",
            MdcSupport.DATE_RANGE to "${request.startDate}..${request.endDate}",
            MdcSupport.PHASE to "fetch",
            MdcSupport.ATTEMPT to "1",
        ) {
            val fetchStartMs = System.currentTimeMillis()
            val response = try {
                pythonClient.fetchDailyBarsBatch(request)
            } catch (e: SorosBaseException) {
                logger.error("[Step 3x] 批量行情拉取失败 codes={} error={}", request.codes, e.message)
                metrics.incrementFailedCodes()
                failed.addAll(request.codes)
                return@withMdcSuspend
            }
            metrics.recordBatchDuration(System.currentTimeMillis() - fetchStartMs)
            for (stock in chunk) {
                persistStockResult(stock, response, success, failed)
            }
        }
    }

    /** 逐股结果落库：有数据且入库行数>0 → success；无数据/0 行（重拉空或全被校验拒绝）→ failed */
    private suspend fun persistStockResult(
        stock: StockInfo,
        response: DailyBarsBatchResponse,
        success: MutableSet<String>,
        failed: MutableSet<String>,
    ) {
        MdcSupport.withMdcSuspend(
            MdcSupport.JOB to "daily-collect",
            MdcSupport.CODE to stock.code,
            MdcSupport.PHASE to "save-batch",
            MdcSupport.ATTEMPT to "1",
        ) {
            val result = response.results[stock.code]
            if (result == null || result.data.isEmpty()) {
                val reason = response.failed.firstOrNull { it.code == stock.code }?.reason ?: "无数据"
                logger.warn("[Step 3x] 单股无数据 code={} reason={}", stock.code, reason)
                failed.add(stock.code)
                metrics.incrementFailedCodes()
                return@withMdcSuspend
            }
            try {
                val board = Board.fromPython(stock.board)
                val source = DataSourceType.fromPython(result.source)
                val saveResult = historyService.saveBatch(stock.code, result.data, source, board)
                if (saveResult.totalRows > 0) {
                    success.add(stock.code)
                } else {
                    logger.warn("[Step 3x] 单股入库 0 行（重拉空/全被校验拒绝，不计 success）code={}", stock.code)
                    failed.add(stock.code)
                    metrics.incrementFailedCodes()
                }
            } catch (e: SorosBaseException) {
                logger.error("[Step 3x] 单股入库失败 code={} error={}", stock.code, e.message)
                failed.add(stock.code)
                metrics.incrementFailedCodes()
            }
        }
    }

    /**
     * §4.7 防线④ 滚动重拉窗口：起点 = min(findMaxDate+1, now-rollingWindowDays)；
     * 无水位（新股）从 defaultStartDate 回填。指数 code 不参与 failedCodes（顺带采集）。
     */
    private fun windowStartFor(code: String): LocalDate {
        val maxDate = historyService.findMaxDate(code) ?: return properties.defaultStartDate
        val incremental = maxDate.plusDays(1)
        val rollingStart = LocalDate.now().minusDays(properties.rollingWindowDays.toLong())
        return minOf(incremental, rollingStart)
    }

    /** 5 个基准指数日 K（index_history；Python 尚未支持指数代码时为 no-op，失败不阻塞主流程） */
    private suspend fun collectBenchmarkIndices() {
        for (index in BenchmarkIndices.INDICES) {
            try {
                val response = pythonClient.fetchDailyBarsBatch(
                    DailyBarsBatchRequest(
                        codes = listOf(index.code),
                        startDate = properties.defaultStartDate.toString(),
                        endDate = LocalDate.now().toString(),
                        adjust = "qfq",
                    ),
                )
                val bars = response.results[index.code]?.data ?: emptyList()
                if (bars.isNotEmpty()) {
                    historyService.saveIndexBatch(index.code, bars, DataSourceType.AKSHARE)
                } else {
                    logger.debug("[Step 4x] 基准指数暂无数据 code={}", index.code)
                }
            } catch (e: SorosBaseException) {
                logger.error("[Step 4x] 基准指数采集失败 code={} error={}", index.code, e.message)
            }
        }
    }

    /** §11.3 批次失败率 >10% → BATCH_FAILURE_RATE_HIGH（ERROR，同类 10 分钟 1 条） */
    private fun notifyFailureRateIfHigh(success: Set<String>, failed: Set<String>) {
        val total = success.size + failed.size
        if (total == 0) return
        val rate = BigDecimal(failed.size).multiply(BigDecimal("100"))
            .divide(BigDecimal(total), 2, RoundingMode.HALF_UP)
        if (rate > FAILURE_RATE_ALERT_THRESHOLD) {
            logger.warn("[Step 5] 批次失败率超过 10%（{}%），触发钉钉告警", rate)
            notifier.notify(
                DingTalkEvent.BATCH_FAILURE_RATE_HIGH,
                "批次失败率超过 10%",
                "成功 ${success.size} 只，失败 ${failed.size} 只，失败率 $rate%",
            )
        }
    }
}
