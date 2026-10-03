package com.soros.v2.service

import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.StockListDto

/**
 * §4.3 Python 数据服务客户端（Kotlin→Python 唯一上游链路，§13.2 责任边界）。
 *
 * 契约：
 * - 超时双 profile（§13.2）：default 日采/查询 connect 2s + response 10s、retry 2×500ms；
 *   backfill 历史区间 response 60s、retry 1 次。
 * - Kotlin 侧仅对 Python 整体熔断（PythonCircuitBreaker：连续 20 失败 → open 60s），
 *   不做"换数据源重试"（多源 failover 全在 Python Router 内部完成，避免双重点燃）。
 * - 间歇性获取由 Python 侧限流承担，本客户端不并发打满（调用方并发度 ≤ 5）。
 */
interface PythonDataServiceClient {

    /** GET /api/v1/health → 是否可用（Python 离线返回 false，不抛异常） */
    suspend fun healthCheck(): Boolean

    /** GET /api/v1/stock-list?market=all&board=all → 全市场股票列表 */
    suspend fun fetchStockList(): List<StockListDto>

    /** POST /api/v1/daily-bars/batch → 批量日 K（results 按 code 分组 + failed[]） */
    suspend fun fetchDailyBarsBatch(request: DailyBarsBatchRequest): DailyBarsBatchResponse

    /** GET /api/v1/trading-calendar → 一次全量交易日（YYYY-MM-DD） */
    suspend fun fetchTradingCalendar(): List<String>
}
