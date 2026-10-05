package com.soros.v2.service.intraday

import com.soros.v2.exception.PythonClientException
import com.soros.v2.service.intraday.dto.IntradayBidAskResponse
import com.soros.v2.service.intraday.dto.IntradayPoolsResponse
import com.soros.v2.service.intraday.dto.IntradaySpotResponse
import com.soros.v2.util.IntradayTokenBucket
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * 盘中 Python 客户端实现（WebClient + [IntradayTokenBucket] 3 参数组限流 + 抖动）。
 *
 * - 令牌不足 → 返回 null（调用方 defer 本轮，**不**计为源失败）；
 * - 取令牌成功后按组抖动（协程 delay，不打同步线程；§19.13.2 决策 5）；
 * - HTTP 非 2xx / 传输异常 → 统一 [PythonClientException]（轮询服务记失败降级）；
 * - spot 大响应（全市场 ~5500 行）走 backfill profile（60s 超时），pools/bid-ask 走 default profile。
 */
@Service
class IntradayPythonClientImpl(
    @Qualifier("pythonWebClient") private val webClient: WebClient,
    @Qualifier("pythonBackfillWebClient") private val backfillWebClient: WebClient? = null,
    private val tokenBucket: IntradayTokenBucket,
) : IntradayPythonClient {

    private val logger = LoggerFactory.getLogger(IntradayPythonClientImpl::class.java)

    override suspend fun fetchPools(dateYyyymmdd: String): IntradayPoolsResponse? {
        if (!tokenBucket.tryAcquire(IntradayTokenBucket.Group.POOLS)) {
            logger.debug("[intraday] pools 令牌不足，本轮 defer")
            return null
        }
        jitter(IntradayTokenBucket.Group.POOLS)
        return callApi(webClient, "/api/v1/intraday/pools?date=$dateYyyymmdd", IntradayPoolsResponse::class.java)
    }

    override suspend fun fetchSpot(): IntradaySpotResponse? {
        if (!tokenBucket.tryAcquire(IntradayTokenBucket.Group.SPOT)) {
            logger.debug("[intraday] spot 令牌不足，本轮 defer")
            return null
        }
        jitter(IntradayTokenBucket.Group.SPOT)
        // 全市场大响应 → backfill profile（response 60s；§13.2 同款双 profile）
        val target = backfillWebClient ?: webClient
        return callApi(target, "/api/v1/intraday/spot", IntradaySpotResponse::class.java)
    }

    override suspend fun fetchBidAsk(code: String): IntradayBidAskResponse? {
        if (!tokenBucket.tryAcquire(IntradayTokenBucket.Group.BID_ASK)) {
            logger.debug("[intraday] bid-ask({}) 令牌不足，本轮 defer", code)
            return null
        }
        jitter(IntradayTokenBucket.Group.BID_ASK)
        return callApi(webClient, "/api/v1/intraday/bid-ask?code=$code", IntradayBidAskResponse::class.java)
    }

    private suspend fun jitter(group: IntradayTokenBucket.Group) {
        val millis = tokenBucket.jitterMillis(group)
        if (millis > 0) delay(millis)
    }

    private suspend fun <T> callApi(client: WebClient, uri: String, bodyType: Class<T>): T =
        client.get()
            .uri(uri)
            .retrieve()
            .onStatus({ status -> !status.is2xxSuccessful }) { resp ->
                Mono.error(PythonClientException("Python 盘中 HTTP ${resp.statusCode().value()}: $uri"))
            }
            .bodyToMono(bodyType)
            .awaitSingle()
}
