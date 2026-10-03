package com.soros.v2.job

import com.soros.v2.domain.BoardType
import com.soros.v2.exception.SorosBaseException
import com.soros.v2.service.StockInfoService
import com.soros.v2.util.MdcSupport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * §4.8 BoardCollectJob：板块成分覆盖写快照（stock_info.industry / concept_boards）。
 *
 * - 行业板块：**每日**全量（stock_board_industry_* ≈86 个）
 * - 概念板块：**每周**全量（stock_board_concept_* ≈400+ 个；TokenBucket 2rps 下限速约 3-4 分钟）
 * - 开跑前先 backfillIpoDates（§4.8 IPO 首 5 日守卫前置，BaoStock query_stock_basic 一次拉取含 ipo_date）
 * - 只关注当前成分快照：覆盖写、不保留成分历史（§4.8 用户确认）
 * - 失败降级：板块快照失败不影响主链（涨停梯队只少板块归属展示，不中断）
 */
@Component
class BoardCollectJob(
    private val infoService: StockInfoService,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(BoardCollectJob::class.java)

    /** §4.8 行业每日（21:30，晚于日采 20:00，等日采回落再打板块接口） */
    @Scheduled(cron = "\${soros.board.industry-cron:0 30 21 * * MON-FRI}")
    fun collectIndustryDaily() {
        runBlocking(sorosIo) {
            MdcSupport.withMdcSuspend(
                MdcSupport.JOB to "board-collect",
                MdcSupport.PHASE to "ipo-backfill",
            ) {
                runCatching { infoService.backfillIpoDates() }
                    .onFailure { logger.warn("[board-collect] ipo_date 回填失败（不阻塞板块快照）：{}", it.message) }
            }
            collectBoard(BoardType.INDUSTRY)
        }
    }

    /** §4.8 概念每周（周日 21:30） */
    @Scheduled(cron = "\${soros.board.concept-cron:0 30 21 * * SUN}")
    fun collectConceptWeekly() {
        runBlocking(sorosIo) {
            collectBoard(BoardType.CONCEPT)
        }
    }

    private suspend fun collectBoard(boardType: BoardType) {
        MdcSupport.withMdcSuspend(
            MdcSupport.JOB to "board-collect",
            MdcSupport.PHASE to "board-snapshot",
            MdcSupport.DATE_RANGE to boardType.name,
        ) {
            try {
                val updated = infoService.refreshBoardSnapshot(boardType)
                logger.info("[board-collect] 板块快照完成 boardType={} 板块数已覆盖，更新 {} 行", boardType, updated)
            } catch (e: SorosBaseException) {
                logger.warn("[board-collect] 板块成分拉取失败 boardType={}（降级跳过）：{}", boardType, e.message)
            }
        }
    }
}
