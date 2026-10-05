package com.soros.v2.service.intraday

import com.soros.v2.service.intraday.dto.IntradayBidAskResponse
import com.soros.v2.service.intraday.dto.IntradayPoolsResponse
import com.soros.v2.service.intraday.dto.IntradaySpotResponse

/**
 * §19.13.2 盘中 Python 客户端（Kotlin→Python 盘中三端点唯一链路，独立故障域）。
 *
 * 与日 K [com.soros.v2.service.PythonDataServiceClient] 分桶分故障域（决策 5「分家」）：
 * - 走独立 [IntradayTokenBucket]（3 参数组：pools 0.10 / spot 0.012 / bid-ask 0.5 rps）；
 * - 不做日 K 共享熔断（盘中退避由 [IntradayPollState] 承担，§19.13.2 退避铁律）；
 * - 端点失败抛 [com.soros.v2.exception.PythonClientException]，由轮询服务降级记 WARN 不炸循环；
 * - 令牌不足返回 null（本轮 defer，不计为源失败）。
 */
interface IntradayPythonClient {

    /**
     * GET /api/v1/intraday/pools?date= → 三池一响应（{status,date,limit_up,limit_down,broken}）。
     * date 为 YYYYMMDD；令牌不足返回 null。
     */
    suspend fun fetchPools(dateYyyymmdd: String): IntradayPoolsResponse?

    /** GET /api/v1/intraday/spot → 全市场实时快照（{status,count,stocks[]}；令牌不足返回 null） */
    suspend fun fetchSpot(): IntradaySpotResponse?

    /** GET /api/v1/intraday/bid-ask?code= → 单票五档（{status,code,bids,asks}；令牌不足返回 null） */
    suspend fun fetchBidAsk(code: String): IntradayBidAskResponse?
}
