package com.soros.v2.service

import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.BoardMembersSnapshot
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
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

    /** POST /api/v1/fundamentals → 全市场财务（report_date YYYYMMDD 季度末；单位=元） */
    suspend fun fetchFundamentals(request: FundamentalsRequest): List<FundamentalsStockDto>

    /** POST /api/v1/board-members → 板块成分覆盖写快照 {板块名:[codes]}（industry/concept） */
    suspend fun fetchBoardMembers(request: BoardMembersRequest): Map<String, List<String>>

    /**
     * POST /api/v1/board-members → 板块成分快照（含降级标记）。
     *
     * [BoardMembersSnapshot.degraded] = 任一板块拉取失败/降级时 true；
     * 消费侧（refreshBoardSnapshot）据此跳过清空，防止部分源故障误清全库。
     */
    suspend fun fetchBoardMembersSnapshot(request: BoardMembersRequest): BoardMembersSnapshot =
        BoardMembersSnapshot(boards = fetchBoardMembers(request))

    /** POST /api/v1/daily-bars/cross-validate → 双源交叉验证数据（baostock+akshare，mootdx 不参与） */
    suspend fun fetchDailyBarsCross(request: CrossValidateRequest): CrossValidateResponse
}
