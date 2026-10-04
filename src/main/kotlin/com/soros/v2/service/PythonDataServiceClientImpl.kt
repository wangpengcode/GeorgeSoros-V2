package com.soros.v2.service

import com.soros.v2.exception.PythonClientException
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.BoardMembersResponse
import com.soros.v2.service.dto.BoardMembersSnapshot
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsResponse
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.PythonHealthResponse
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.service.dto.StockListResponse
import com.soros.v2.service.dto.TradingCalendarResponse
import java.net.ConnectException
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

/**
 * §4.3 Python 客户端实现（WebClient + §13.2 超时/重试 + 熔断）。
 *
 * - 熔断接线（M-2）：每次调用前 allowRequest()（open → 抛 PythonClientException 不打 HTTP）；
 *   成功 recordSuccess()（清零计数）/ 失败 recordFailure() —— 半开探测语义同步。
 * - retryWhen：仅对 5xx / ConnectException / WebClientRequestException / timeout 触发（拉取只读，幂等安全）；
 *   最终失败统一转 [PythonClientException]，并触发 PythonCircuitBreaker.recordFailure()（熔断失败计数）。
 * - backfill profile（response 60s / retry 1）在 fetchDailyBarsBatch 内按请求区间大小（> 366 自然日）切换：
 *   响应超时由生产 WebClientConfig 的 backfill WebClient（Reactor Netty HttpClient）承担，本类只选客户端 + 重试次数。
 * - 响应 DTO 解析依赖 Jackson fail-on-unknown 严格模式（生产 WebClientConfig 注入 Spring ObjectMapper）。
 */
@Service
class PythonDataServiceClientImpl(
    @Qualifier("pythonWebClient") private val webClient: WebClient,
    private val circuitBreaker: PythonCircuitBreaker,
    @Qualifier("pythonBackfillWebClient") private val backfillWebClient: WebClient? = null,
) : PythonDataServiceClient {

    private val logger = LoggerFactory.getLogger(PythonDataServiceClientImpl::class.java)

    private companion object {
        const val DEFAULT_RETRY_COUNT = 2
        const val BACKFILL_RETRY_COUNT = 1
        const val RETRY_BACKOFF_MILLIS = 500L
        const val BACKFILL_DAY_THRESHOLD = 366L
    }

    /** 5xx 传输态标记异常（仅用于 retry 分流，最终统一转 [PythonClientException]） */
    private class TransientFailure(val statusCode: Int) : RuntimeException("Python transient HTTP $statusCode")

    override suspend fun healthCheck(): Boolean {
        if (!circuitBreaker.allowRequest()) {
            logger.warn("[health] 熔断 OPEN，Python 视为离线")
            return false
        }
        return try {
            val response = webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(PythonHealthResponse::class.java)
                .awaitSingle()
            circuitBreaker.recordSuccess()
            logger.debug("[health] Python status={}", response.status)
            true
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            logger.warn("[health] Python 健康检查失败：{}", e.message)
            false
        }
    }

    override suspend fun fetchStockList(): List<StockListDto> {
        // 全量清单实测 40s+（akshare ST 列表 + baostock 退市/ipo 一次拉全市场），
        // 超出 default profile 10s response timeout（重试必失败）→ 走 backfill profile（60s）
        val target = backfillWebClient ?: webClient
        return executeWithBreaker("stock-list") {
            callApi(
                target,
                { target.get().uri("/api/v1/stock-list?market=all&board=all").retrieve() },
                StockListResponse::class.java,
                BACKFILL_RETRY_COUNT,
            ).stocks
        }
    }

    override suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse {
        val isBackfill = requestRangeExceeds(request, BACKFILL_DAY_THRESHOLD)
        val retryCount = if (isBackfill) BACKFILL_RETRY_COUNT else DEFAULT_RETRY_COUNT
        val target = if (isBackfill) (backfillWebClient ?: webClient) else webClient
        logger.debug("[daily-bars-batch] isBackfill={} codes={} start={} end={}", isBackfill, request.codes, request.startDate, request.endDate)
        return executeWithBreaker("daily-bars-batch") {
            callApi(
                target,
                { target.post().uri("/api/v1/daily-bars/batch").bodyValue(request).retrieve() },
                DailyBarsBatchResponse::class.java,
                retryCount,
            )
        }
    }

    override suspend fun fetchTradingCalendar(): List<String> = executeWithBreaker("trading-calendar") {
        callApi(
            webClient,
            { webClient.get().uri("/api/v1/trading-calendar").retrieve() },
            TradingCalendarResponse::class.java,
            DEFAULT_RETRY_COUNT,
        ).dates
    }

    override suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto> =
        executeWithBreaker("fundamentals") {
            callApi(
                webClient,
                { webClient.post().uri("/api/v1/fundamentals").bodyValue(request).retrieve() },
                FundamentalsResponse::class.java,
                DEFAULT_RETRY_COUNT,
            ).stocks
        }

    override suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>> =
        fetchBoardMembersSnapshot(request).boards

    override suspend fun fetchBoardMembersSnapshot(request: BoardMembersRequest): BoardMembersSnapshot =
        executeWithBreaker("board-members") {
            callApi(
                webClient,
                { webClient.post().uri("/api/v1/board-members").bodyValue(request).retrieve() },
                BoardMembersResponse::class.java,
                DEFAULT_RETRY_COUNT,
            ).let { BoardMembersSnapshot(boards = it.boards, degraded = it.degraded) }
        }

    override suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse =
        executeWithBreaker("daily-bars-cross-validate") {
            callApi(
                webClient,
                { webClient.post().uri("/api/v1/daily-bars/cross-validate").bodyValue(request).retrieve() },
                CrossValidateResponse::class.java,
                DEFAULT_RETRY_COUNT,
            )
        }

    /** 请求区间是否超阈值（backfill profile 判定；internal 供单测直接验证边界语义） */
    internal fun requestRangeExceeds(request: DailyBarsBatchRequest, days: Long): Boolean = try {
        val start = LocalDate.parse(request.startDate)
        val end = LocalDate.parse(request.endDate)
        ChronoUnit.DAYS.between(start, end) > days
    } catch (e: Exception) {
        logger.warn("[daily-bars-batch] 日期解析失败按默认 profile：start={} end={} error={}", request.startDate, request.endDate, e.message)
        false
    }

    /** 统一入口：调用前熔断询问（open→不打 HTTP 抛异常），成功后 recordSuccess，失败 recordFailure */
    private suspend fun <T> executeWithBreaker(operation: String, block: suspend () -> T): T {
        if (!circuitBreaker.allowRequest()) {
            throw PythonClientException("Python 熔断 OPEN，拒绝调用 $operation")
        }
        return try {
            val result = block()
            circuitBreaker.recordSuccess()
            result
        } catch (e: SorosBaseException) {
            circuitBreaker.recordFailure()
            logger.warn("[{}] Python 调用失败：{}", operation, e.message)
            throw e
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            val wrapped = PythonClientException("Python $operation 调用异常：${e.message}", e)
            logger.warn("[{}] Python 调用异常：{}", operation, e.message)
            throw wrapped
        }
    }

    private suspend fun <T> callApi(
        client: WebClient,
        request: () -> WebClient.ResponseSpec,
        bodyType: Class<T>,
        retryCount: Int,
    ): T = request()
        .onStatus({ status -> status.is5xxServerError }) { resp: ClientResponse ->
            Mono.error(TransientFailure(resp.statusCode().value()))
        }
        .onStatus({ status -> !status.is2xxSuccessful }) { resp: ClientResponse ->
            Mono.error(PythonClientException("Python HTTP ${resp.statusCode().value()} 非 2xx"))
        }
        .bodyToMono(bodyType)
        .retryWhen(
            Retry.backoff(retryCount.toLong(), Duration.ofMillis(RETRY_BACKOFF_MILLIS))
                .filter { throwable ->
                    throwable is TransientFailure ||
                        throwable is WebClientRequestException ||
                        throwable is ConnectException ||
                        throwable is TimeoutException
                }
                .onRetryExhaustedThrow { _, signal ->
                    PythonClientException(
                        "Python 瞬时故障重试 $retryCount 次后仍失败：${signal.failure().message}",
                    )
                },
        )
        .awaitSingle()
}
