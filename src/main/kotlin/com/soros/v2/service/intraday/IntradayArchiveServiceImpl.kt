package com.soros.v2.service.intraday

import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.config.IntradayProperties
import com.soros.v2.domain.PoolId
import com.soros.v2.entity.IntradayArchive
import com.soros.v2.entity.IntradayReplay
import com.soros.v2.repository.IntradayArchiveRepository
import com.soros.v2.repository.IntradayEventRepository
import com.soros.v2.repository.IntradayPoolSnapRepository
import com.soros.v2.repository.IntradayPoolStateRepository
import com.soros.v2.repository.IntradayReplayRepository
import com.soros.v2.service.intraday.dto.IntradayPageDto
import com.soros.v2.service.intraday.dto.IntradayPanelsDto
import com.soros.v2.service.intraday.dto.IntradayPoolPanelDto
import com.soros.v2.service.intraday.dto.IntradayReplayEvent
import com.soros.v2.service.intraday.mapper.toArchive
import com.soros.v2.service.intraday.mapper.toLadderItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * §19.13.2 IntradayArchiveServiceImpl：15:10 收盘权威归档实现。
 *
 * - 幂等：先清当日归档行再重写（UNIQUE(trade_date, code) 天然防重）；
 * - 防御性：重拉失败 → 记 ERROR + 直接返回（不写半成品归档、不误置 complete=true）；
 * - 缺口诚实：Python 契约三池一响应（limit_up/limit_down/broken）→ 仅归档 ZT/ZB/DT 三池。
 */
@Service
class IntradayArchiveServiceImpl(
    private val client: IntradayPythonClient,
    private val archiveRepo: IntradayArchiveRepository,
    private val replayRepo: IntradayReplayRepository,
    private val eventRepo: IntradayEventRepository,
    private val poolStateRepo: IntradayPoolStateRepository,
    private val snapRepo: IntradayPoolSnapRepository,
    private val pollState: IntradayPollState,
    private val properties: IntradayProperties,
    private val objectMapper: ObjectMapper,
) : IntradayArchiveService {

    private val logger = LoggerFactory.getLogger(IntradayArchiveServiceImpl::class.java)

    override suspend fun archiveDay(tradeDate: LocalDate) {
        val now = LocalDateTime.now()
        val dateYmd = tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE)
        val pools = try {
            client.fetchPools(dateYmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[Step Intraday] 归档重拉失败（不写半成品归档，complete 保持 false）：date={} error={}", tradeDate, e.message)
            return
        }
        if (pools == null) {
            logger.error("[Step Intraday] 归档重拉令牌不足（不写半成品归档，complete 保持 false）：date={}", tradeDate)
            return
        }

        // 1) 幂等重写归档行（先清当日再插入）
        archiveRepo.deleteByTradeDate(tradeDate)
        val rows = mutableListOf<IntradayArchive>()
        rows += pools.limitUp.map { it.toArchive(tradeDate, PoolId.ZT.code) }
        rows += pools.broken.map { it.toArchive(tradeDate, PoolId.ZB.code) }
        rows += pools.limitDown.map { it.toArchive(tradeDate, PoolId.DT.code) }
        archiveRepo.saveAll(rows)
        logger.info("[Step Intraday] 归档写入：date={} rows={}", tradeDate, rows.size)

        // 2) 构建整页快照（ladder=归档 ZT 行；kpi_series 保留盘中采样点）
        val page = buildPage(tradeDate)
        val existing = replayRepo.findByTradeDate(tradeDate)
        val replay = existing ?: IntradayReplay(tradeDate = tradeDate)
        replay.page = objectMapper.valueToTree(page)
        replay.complete = true
        replayRepo.save(replay)
        logger.info("[Step Intraday] 归档整页快照落库：date={} complete=true", tradeDate)

        // 3) 清理过期快照（保留 snapRetentionDays 天供回溯调试）
        val threshold = now.minusDays(properties.snapRetentionDays.toLong())
        val deleted = snapRepo.deleteBySnapAtBefore(threshold)
        logger.info("[Step Intraday] 过期快照清理：threshold={} deleted={}", threshold, deleted)
    }

    /** 整页渲染（§14.4 schema：{kpi_series, ladder, events, panels}；ladder/events 现拼，kpi_series 保留盘中采样） */
    private fun buildPage(tradeDate: LocalDate): IntradayPageDto {
        val kpiSeries = replayRepo.findByTradeDate(tradeDate)?.page
            ?.let { page -> objectMapper.convertValue(page, IntradayPageDto::class.java) }
            ?.kpiSeries
            ?: emptyList()
        val ladder = archiveRepo.findLadderByTradeDate(tradeDate).map { it.toLadderItem() }
        val events = eventRepo.findByTradeDate(tradeDate).map { it.toReplayEvent() }
        val panels = buildPanels(tradeDate)
        return IntradayPageDto(
            kpiSeries = kpiSeries,
            ladder = ladder,
            events = events,
            panels = panels,
        )
    }

    /** panels 运行时态（big_face=DM 事件 / ding_talk=已推钉钉事件 / pools=池开关+健康态） */
    private fun buildPanels(tradeDate: LocalDate): IntradayPanelsDto {
        val poolsSourceState = pollState.snapshot()[IntradayPollState.Source.POOLS]
        return IntradayPanelsDto(
            bigFace = eventRepo.findByTradeDateAndEvType(tradeDate, com.soros.v2.domain.IntradayEventType.DM).map { it.toReplayEvent() },
            dingTalk = eventRepo.findByTradeDateAndPushedDdTrue(tradeDate).map { it.toReplayEvent() },
            pools = PoolId.entries.map { pool ->
                val state = poolStateRepo.findByPool(pool.code)
                // 三池同源一响应（/intraday/pools）→ 池健康态统一取 POOLS 端点运行时（诚实缺口：STRONG/PREV 无源但占位）
                IntradayPoolPanelDto(
                    pool = pool.code,
                    enabled = state?.enabled ?: true,
                    lastOkAt = poolsSourceState?.lastOkAt?.toString(),
                    rateState = pollState.rateState(IntradayPollState.Source.POOLS).name,
                )
            },
        )
    }

    private fun com.soros.v2.entity.IntradayEvent.toReplayEvent(): IntradayReplayEvent = IntradayReplayEvent(
        time = evTime.format(TIME_FMT),
        code = code,
        name = name,
        tg = evType.code,
        txt = eventText(),
    )

    /** 事件流文案（面板展示；钉钉正文与 panels.ding_talk 不混入） */
    private fun com.soros.v2.entity.IntradayEvent.eventText(): String {
        val streak = detail?.get("limit_up_streak")?.asText()
        val chg = detail?.get("change_pct")?.asText()
        return when (evType) {
            com.soros.v2.domain.IntradayEventType.ZT -> "涨停 ${streak ?: "1"}板"
            com.soros.v2.domain.IntradayEventType.ZB -> "炸板"
            com.soros.v2.domain.IntradayEventType.HF -> "回封"
            com.soros.v2.domain.IntradayEventType.DM -> "大面 ${chg ?: "-"}%"
            com.soros.v2.domain.IntradayEventType.MAXCHG -> "最高板易主 ${streak ?: "-"}板"
            com.soros.v2.domain.IntradayEventType.OPEN -> "开板"
            com.soros.v2.domain.IntradayEventType.ALERT -> "策略开仓预警"
        }
    }

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
