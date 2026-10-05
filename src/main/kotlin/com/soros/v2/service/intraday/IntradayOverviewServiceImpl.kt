package com.soros.v2.service.intraday

import com.soros.v2.repository.IntradayEventRepository
import com.soros.v2.repository.IntradayPoolSnapRepository
import com.soros.v2.service.intraday.dto.IntradayOverviewDto
import com.soros.v2.service.intraday.dto.IntradaySourceStatusDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Service

/**
 * §19.13.2 IntradayOverviewServiceImpl：overview 快照组装。
 *
 * - status 值域（API 契约字面，非 DB 枚举）：OK=全部源 NORMAL 且有快照 / DEGRADED=任一源退避或停轮 /
 *   INACTIVE=尚无任何快照（首轮前）；
 * - last_poll_at：内存运行时态最近成功轮询（重启清零可接受，§17.6 定稿；兜底最新快照时间）；
 * - 全部读操作零副作用，IntradayJob/轮询循环不依赖本服务。
 */
@Service
class IntradayOverviewServiceImpl(
    private val snapRepo: IntradayPoolSnapRepository,
    private val eventRepo: IntradayEventRepository,
    private val pollState: IntradayPollState,
) : IntradayOverviewService {

    override fun overview(): IntradayOverviewDto {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val latestSnap = snapRepo.findFirstByOrderBySnapAtDesc()
        val snapshot = pollState.snapshot()
        val status = computeStatus(latestSnap != null, snapshot)
        return IntradayOverviewDto(
            status = status.name,
            pollTime = latestSnap?.snapAt?.format(TIME_FMT),
            alertCount = eventRepo.countByTradeDate(today),
            lastPollAt = lastPollAt(snapshot, latestSnap?.snapAt),
            sources = IntradayPollState.Source.entries.map { source ->
                val state = snapshot[source] ?: IntradayPollState.SourceState()
                IntradaySourceStatusDto(
                    source = source.key,
                    ok = state.ok,
                    lastOkAt = state.lastOkAt?.toString(),
                    rateState = pollState.rateState(source).name,
                )
            },
        )
    }

    /** 最近一次成功轮询（内存运行时态优先，兜底最新快照时间） */
    private fun lastPollAt(snapshot: Map<IntradayPollState.Source, IntradayPollState.SourceState>, latestSnapAt: LocalDateTime?): String? =
        snapshot.values.filter { it.ok }.mapNotNull { it.lastOkAt }.maxOrNull()?.toString()
            ?: latestSnapAt?.toString()

    private fun computeStatus(hasSnap: Boolean, snapshot: Map<IntradayPollState.Source, IntradayPollState.SourceState>): Status {
        if (!hasSnap) return Status.INACTIVE
        val degraded = snapshot.any { (source, _) -> pollState.rateState(source) != IntradayPollState.RateState.NORMAL }
        return if (degraded) Status.DEGRADED else Status.OK
    }

    /** overview.status 值域（API 契约字面，非 DB 枚举；单点映射防散落硬编码） */
    enum class Status {
        /** 全部源 NORMAL 且有快照 */
        OK,

        /** 任一源退避/停轮 */
        DEGRADED,

        /** 尚无任何快照（首轮前） */
        INACTIVE,
    }

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
