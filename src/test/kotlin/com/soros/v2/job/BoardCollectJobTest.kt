package com.soros.v2.job

import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.service.StockInfoService
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.CrossValidateRequest
import com.soros.v2.service.dto.CrossValidateResponse
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.DailyBarsBatchResponse
import com.soros.v2.service.dto.FundamentalsRequest
import com.soros.v2.service.dto.FundamentalsStockDto
import com.soros.v2.service.dto.StockListDto
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.8 BoardCollectJob 契约测试（Fake StockInfoService 直调 cron 入口，不依赖真实 cron/网络）。
 *
 * 契约（类 KDoc / §4.8）：
 * - 行业板块：**每日**全量（collectIndustryDaily）→ refreshBoardSnapshot(INDUSTRY)；
 * - 概念板块：**每周**全量（collectConceptWeekly）→ refreshBoardSnapshot(CONCEPT)；
 * - 行业每日开跑前先 backfillIpoDates（§4.8 IPO 首 5 日守卫前置），概念每周不做；
 * - 失败降级：板块快照失败（SorosBaseException 族）只日志不中断（涨停梯队只少板块归属展示）。
 *
 * 快照反转（{板块:[codes]}→code→[板块名] 覆盖写）本身在 StockInfoServiceImpl，本测试只验 Job 编排。
 */
class BoardCollectJobTest {

    /** Fake StockInfoService：记录 backfillIpoDates / refreshBoardSnapshot 调用并支持失败注入 */
    private class FakeStockInfoService : StockInfoService {
        var backfillIpoDatesCalls = 0
        var backfillError: Exception? = null
        var refreshBoardCalls = mutableListOf<BoardType>()
        var refreshBoardError: Exception? = null

        override suspend fun refreshStockList(): List<StockInfo> = emptyList()
        override suspend fun backfillIpoDates(): Int {
            backfillIpoDatesCalls++
            backfillError?.let { throw it }
            return 1
        }
        override suspend fun refreshBoardSnapshot(boardType: BoardType): Int {
            refreshBoardCalls.add(boardType)
            refreshBoardError?.let { throw it }
            return 1
        }
        override fun findByCode(code: String): StockInfo? = null
        override fun saveBenchmarkIndices(): Int = 0
    }

    private fun job(infoService: StockInfoService): BoardCollectJob =
        BoardCollectJob(infoService, Dispatchers.IO)

    // ==================== 行业每日 ====================

    @Test
    fun `testCollectIndustryDaily backfillsIpoDatesThenRefreshesIndustry`() {
        // given: 行业每日入口（§4.8：晚于日采 21:30）
        val info = FakeStockInfoService()

        // when
        job(info).collectIndustryDaily()

        // then: 开跑先 backfillIpoDates（IPO 守卫水位最新），再 refreshBoardSnapshot(INDUSTRY)
        assertEquals(1, info.backfillIpoDatesCalls, "行业每日开跑前先回填 ipo_date")
        assertEquals(listOf(BoardType.INDUSTRY), info.refreshBoardCalls, "行业每日触发 INDUSTRY 板块快照")
    }

    // ==================== 概念每周 ====================

    @Test
    fun `testCollectConceptWeekly refreshesConceptOnly`() {
        // given: 概念每周入口（§4.8：周日 21:30）
        val info = FakeStockInfoService()

        // when
        job(info).collectConceptWeekly()

        // then: 只刷新 CONCEPT 快照，不触发 ipo_date 回填（每周前置已在行业每日做过）
        assertEquals(listOf(BoardType.CONCEPT), info.refreshBoardCalls, "概念每周触发 CONCEPT 板块快照")
        assertEquals(0, info.backfillIpoDatesCalls, "概念每周不重复回填 ipo_date")
    }

    // ==================== 失败降级 ====================

    @Test
    fun `testCollectBoard snapshotFailureDegradesNoThrow`() {
        // given: 板块成分拉取失败（SorosBaseException 族 → 降级跳过）
        val info = FakeStockInfoService().apply {
            refreshBoardError = BusinessException("板块成分接口限流失败")
        }

        // when: 行业每日执行（backfill 正常 + 快照失败），必须不抛出
        job(info).collectIndustryDaily()

        // then: backfill 不受影响 + 快照失败仅日志降级（涨停梯队只少板块归属展示，不中断）
        assertEquals(1, info.backfillIpoDatesCalls, "快照失败不影响 ipo_date 回填")
        assertEquals(1, info.refreshBoardCalls.size, "快照已尝试（降级前发起了调用）")
        assertEquals(BoardType.INDUSTRY, info.refreshBoardCalls.first(), "失败的是 INDUSTRY 快照")
    }

    // ==================== 边界：backfill 失败不阻塞快照 ====================

    @Test
    fun `testCollectIndustryDaily backfillFailureDoesNotBlockSnapshot`() {
        // given: backfillIpoDates 抛异常（runCatching 兜底）+ 快照正常
        val info = FakeStockInfoService().apply {
            backfillError = BusinessException("ipo 回填源异常")
        }

        // when
        job(info).collectIndustryDaily()

        // then: backfill 失败被 runCatching 吞掉，快照照常执行（§4.8 不阻塞主链）
        assertEquals(1, info.backfillIpoDatesCalls, "backfill 已尝试（虽失败）")
        assertEquals(listOf(BoardType.INDUSTRY), info.refreshBoardCalls, "backfill 失败不阻塞 INDUSTRY 快照")
    }
}
