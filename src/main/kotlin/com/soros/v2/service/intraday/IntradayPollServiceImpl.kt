package com.soros.v2.service.intraday

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.soros.v2.config.IntradayProperties
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.IntradayEventType
import com.soros.v2.domain.PoolId
import com.soros.v2.entity.IntradayEvent
import com.soros.v2.entity.IntradayPoolSnap
import com.soros.v2.entity.IntradayReplay
import com.soros.v2.exception.PythonClientException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.IntradayEventRepository
import com.soros.v2.repository.IntradayPoolSnapRepository
import com.soros.v2.repository.IntradayReplayRepository
import com.soros.v2.service.intraday.dto.IntradayPageDto
import com.soros.v2.service.intraday.dto.IntradayPoolRowDto
import com.soros.v2.service.intraday.dto.IntradayKpiPoint
import com.soros.v2.service.intraday.mapper.advDecRatio
import com.soros.v2.service.intraday.mapper.toEventDetail
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * §19.13.2 IntradayPollServiceImpl：盘中轮询落表时序实现。
 *
 * - 幂等 upsert：同 snap_at+pool 不重复（existsBySnapAtAndPool 判重，同秒重跑跳过）；
 * - 防御性设计：Python 端点失败降级记 WARN（[guardedCall] 退避/停轮 + 钉钉源停轮告警），不炸 Job 循环；
 * - 首轮无基线跳过 diff（防启动灌入海量事件）；
 * - ST 隔离铁律：池/快照数据为外部源已过滤产物，Kotlin 侧不做 ST 展开（识别并排除语义由采集层承担）。
 */
@Service
class IntradayPollServiceImpl(
    private val client: IntradayPythonClient,
    private val snapRepo: IntradayPoolSnapRepository,
    private val eventRepo: IntradayEventRepository,
    private val replayRepo: IntradayReplayRepository,
    private val pollState: IntradayPollState,
    private val notifier: DingTalkNotifier,
    private val properties: IntradayProperties,
    private val objectMapper: ObjectMapper,
) : IntradayPollService {

    private val logger = LoggerFactory.getLogger(IntradayPollServiceImpl::class.java)

    override suspend fun pollRound() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val dateYmd = today.format(DateTimeFormatter.BASIC_ISO_DATE)

        // 1) 先读上轮快照（diff 基线；必须先于写本轮快照，避免读到本轮自身）
        val prevZt = snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(PoolId.ZT.code, now)
        val prevZb = snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(PoolId.ZB.code, now)

        // 2) 池：拉三池（一响应）→ 写 3 帧 snap → diff 事件 → 钉钉（MAXCHG）
        val poolsResp = guardedCall(IntradayPollState.Source.POOLS, now) { client.fetchPools(dateYmd) }
        if (poolsResp != null) {
            writeSnaps(now, poolsResp.limitUp, poolsResp.broken, poolsResp.limitDown)
            val diff = diffEvents(prevZt, prevZb, poolsResp.limitUp, poolsResp.broken, now, today)
            persistAndPush(diff, now, today)
        }

        // 3) spot：adv/dec + 高位股大面（强势池成员现价 ≤-5%）
        val spotResp = guardedCall(IntradayPollState.Source.SPOT, now) { client.fetchSpot() }
        if (spotResp != null) {
            val adv = spotResp.stocks.count { (it.changePct ?: BigDecimal.ZERO) > BigDecimal.ZERO }
            val dec = spotResp.stocks.count { (it.changePct ?: BigDecimal.ZERO) < BigDecimal.ZERO }
            val dm = detectBigFace(poolsResp?.limitUp ?: emptyList(), spotResp.stocks, now, today)
            persistAndPush(dm, now, today)
            // 4) kpi_series 采样点（池+spot 双齐才记，缺一不记 0 点误导）
            if (poolsResp != null) {
                appendKpi(now, today, poolsResp.limitUp.size, poolsResp.broken.size, poolsResp.limitDown.size, adv, dec)
            }
        }

        // 5) bid-ask 五档：候选名单（梯队∪炸板∪跌停，单轮 ≤15 次）健康跟踪，不入库
        val candidates = buildCandidates(poolsResp?.limitUp ?: emptyList(), poolsResp?.broken ?: emptyList(), poolsResp?.limitDown ?: emptyList())
        candidates.take(properties.bidAskMaxPerRound).forEach { code ->
            guardedCall(IntradayPollState.Source.BID_ASK, now) { client.fetchBidAsk(code) }
        }
    }

    // ==================== 池快照 + diff ====================

    /** 写 3 帧 snap（ZT/ZB/DT；同 snap_at 幂等：同 poll_time+pool 不重复） */
    private fun writeSnaps(now: LocalDateTime, zt: List<IntradayPoolRowDto>, zb: List<IntradayPoolRowDto>, dt: List<IntradayPoolRowDto>) {
        writeSnap(now, PoolId.ZT, zt)
        writeSnap(now, PoolId.ZB, zb)
        writeSnap(now, PoolId.DT, dt)
    }

    private fun writeSnap(now: LocalDateTime, pool: PoolId, rows: List<IntradayPoolRowDto>) {
        if (snapRepo.existsBySnapAtAndPool(now, pool.code)) {
            logger.debug("[Step Intraday] snap 幂等跳过：snapAt={} pool={}", now, pool.code)
            return
        }
        snapRepo.save(
            IntradayPoolSnap(
                snapAt = now,
                pool = pool.code,
                payload = objectMapper.valueToTree(rows),
            ),
        )
        logger.debug("[Step Intraday] snap 写入：snapAt={} pool={} rows={}", now, pool.code, rows.size)
    }

    /** 上轮 snap 行反序列化（payload=英文 DTO 行数组） */
    private fun latestRows(snap: IntradayPoolSnap?): Map<String, IntradayPoolRowDto> {
        if (snap?.payload == null || snap.payload.isNull) return emptyMap()
        return objectMapper.convertValue(snap.payload, object : TypeReference<List<IntradayPoolRowDto>>() {})
            .associateBy { it.code }
    }

    /**
     * 状态 diff（本轮 vs 上轮；首轮无基线返回空）。
     *
     * 值域遵守 schema ev_type CHECK（ZT/ZB/HF/DM/MAXCHG/OPEN/ALERT）：跌停不做事件（DT 非合法 ev_type），
     * 仅入 snap（intraday_pool_snap.pool=DT）+ kpi_series.dt 计数 + 归档（intraday_archive.pool=DT）。
     */
    private fun diffEvents(
        prevZt: IntradayPoolSnap?, prevZb: IntradayPoolSnap?,
        currentZt: List<IntradayPoolRowDto>, currentZb: List<IntradayPoolRowDto>,
        now: LocalDateTime, today: LocalDate,
    ): List<IntradayEvent> {
        if (prevZt == null && prevZb == null) {
            logger.info("[Step Intraday] 首轮无基线，跳过 diff（防启动灌入海量事件）")
            return emptyList()
        }
        val pZt = latestRows(prevZt)
        val pZb = latestRows(prevZb)
        val cZt = currentZt.associateBy { it.code }
        val cZb = currentZb.associateBy { it.code }

        val events = mutableListOf<IntradayEvent>()
        // ZT 新封板 / HF 回封（上轮炸板本轮封住）
        for (row in currentZt) {
            when {
                pZb[row.code] != null -> events += eventOf(now, today, IntradayEventType.HF, row)
                pZt[row.code] == null -> events += eventOf(now, today, IntradayEventType.ZT, row)
            }
        }
        // ZB 炸板（上轮封住本轮在炸板池）
        for (row in currentZb) {
            if (pZt[row.code] != null) events += eventOf(now, today, IntradayEventType.ZB, row)
        }
        // OPEN 开板（上轮封住，本轮既不在涨停池也不在炸板池）
        for ((code, prevRow) in pZt) {
            if (cZt[code] == null && cZb[code] == null) events += eventOf(now, today, IntradayEventType.OPEN, prevRow)
        }
        // MAXCHG 最高板易主（最高连板 code 变化；龙头炸板自然触发易主）
        val prevMax = pZt.values.maxByOrNull { it.lianban ?: 0 }
        val curMax = currentZt.maxByOrNull { it.lianban ?: 0 }
        if (prevMax != null && curMax != null && prevMax.code != curMax.code) {
            events += eventOf(now, today, IntradayEventType.MAXCHG, curMax)
        }
        return events
    }

    private fun eventOf(now: LocalDateTime, today: LocalDate, type: IntradayEventType, row: IntradayPoolRowDto): IntradayEvent =
        IntradayEvent(
            tradeDate = today,
            evTime = now,
            evType = type,
            code = row.code,
            name = row.name,
            detail = objectMapper.valueToTree(row.toEventDetail()),
        )

    /** 事件落库 + 钉钉推送（§14.5：仅 MAXCHG/DM 推；pushed_dd 标记已推） */
    private fun persistAndPush(events: List<IntradayEvent>, now: LocalDateTime, today: LocalDate) {
        if (events.isEmpty()) return
        for (ev in events) {
            val pushed = pushAlert(ev, now)
            if (pushed) ev.pushedDd = true
        }
        eventRepo.saveAll(events)
        logger.info("[Step Intraday] 事件落库：date={} n={} types={}", today, events.size, events.map { it.evType.name }.distinct())
    }

    /** 单事件钉钉推送（§14.5：最高板易主/高位大面推；普通涨停不推防刷屏） */
    private fun pushAlert(ev: IntradayEvent, now: LocalDateTime): Boolean = when (ev.evType) {
        IntradayEventType.MAXCHG -> {
            notifier.notify(
                DingTalkEvent.INTRADAY_MAX_CHANGE,
                "最高板易主",
                "【盘中预警，收盘确认】${ev.name}(ev.code) ${ev.detailStreak()}板 登顶最高板，时间 ${now.format(TIME_FMT)}",
            )
            true
        }
        IntradayEventType.DM -> {
            notifier.notify(
                DingTalkEvent.INTRADAY_BIG_FACE,
                "高位股大面",
                "【盘中预警，收盘确认】${ev.name}(ev.code) 现价 ${ev.detailChangePct()}%（强势池成员现价 ≤-5%），时间 ${now.format(TIME_FMT)}",
            )
            true
        }
        else -> false
    }

    // ==================== spot 大面 ====================

    /** 高位股大面检测：强势池成员（无源时退化 = ZT 池连板 ≥3 近似口径）现价 ≤-5% */
    private fun detectBigFace(
        ztRows: List<IntradayPoolRowDto>,
        spotStocks: List<com.soros.v2.service.intraday.dto.IntradaySpotStockDto>,
        now: LocalDateTime, today: LocalDate,
    ): List<IntradayEvent> {
        val candidates = ztRows.filter { (it.lianban ?: 0) >= BIG_FACE_MIN_STREAK }.associateBy { it.code }
        if (candidates.isEmpty()) return emptyList()
        val spotByCode = spotStocks.associateBy { it.code }
        return candidates.mapNotNull { (code, poolRow) ->
            val chg = spotByCode[code]?.changePct ?: return@mapNotNull null
            if (chg <= BIG_FACE_THRESHOLD) {
                eventOf(now, today, IntradayEventType.DM, poolRow.copy(changePct = chg))
            } else {
                null
            }
        }
    }

    /** bid-ask 候选名单（梯队∪炸板∪跌停，单轮上限 [properties.bidAskMaxPerRound]） */
    private fun buildCandidates(zt: List<IntradayPoolRowDto>, zb: List<IntradayPoolRowDto>, dt: List<IntradayPoolRowDto>): List<String> {
        val codes = LinkedHashSet<String>()
        zt.sortedByDescending { it.lianban ?: 0 }.forEach { codes += it.code }
        zb.forEach { codes += it.code }
        dt.take(5).forEach { codes += it.code }
        return codes.toList()
    }

    // ==================== kpi_series ====================

    /** intraday_replay 当日行追加 kpi_series 采样点（90s 采样；进程重启曲线不丢） */
    private fun appendKpi(now: LocalDateTime, today: LocalDate, zt: Int, zb: Int, dt: Int, adv: Int, dec: Int) {
        val point = IntradayKpiPoint(
            t = now.format(TIME_FMT),
            zt = zt, zb = zb, dt = dt,
            prem = null,
            adr = advDecRatio(adv, dec),
            adv = adv, dec = dec,
            maxStreak = null,
            gapPct = null,
        )
        val existing = replayRepo.findByTradeDate(today)
        val page: IntradayPageDto = if (existing?.page != null) {
            objectMapper.convertValue(existing.page, IntradayPageDto::class.java).let { it.copy(kpiSeries = it.kpiSeries + point) }
        } else {
            IntradayPageDto(kpiSeries = listOf(point))
        }
        val replay = existing ?: IntradayReplay(tradeDate = today)
        replay.page = objectMapper.valueToTree(page)
        replayRepo.save(replay)
        logger.debug("[Step Intraday] replay kpi_series 追加：date={} t={}", today, point.t)
    }

    // ==================== 防御性降级封装 ====================

    /**
     * 外部源调用防护：退避/停轮跳过 → 令牌不足 defer → 失败降级（记 WARN/ERROR + 连续失败计数 +
     * 跨 [stopThreshold] 推一次钉钉源停轮告警），不炸 Job 循环。
     */
    private suspend fun <T> guardedCall(
        source: IntradayPollState.Source,
        now: LocalDateTime,
        block: suspend () -> T?,
    ): T? {
        if (pollState.beginRound(source, now)) {
            logger.debug("[Step Intraday] {} 退避/停轮中跳过本轮", source.key)
            return null
        }
        return try {
            val result = block()
            if (result != null) {
                pollState.recordSuccess(source, now)
            } else {
                logger.debug("[Step Intraday] {} 令牌不足，本轮 defer", source.key)
            }
            result
        } catch (e: CancellationException) {
            // 协程取消必须上抛（不静默取消），否则退避状态被取消打脏、Job 停不下来
            throw e
        } catch (e: PythonClientException) {
            degrade(source, now, e)
            null
        } catch (e: Exception) {
            degrade(source, now, e)
            null
        }
    }

    private fun degrade(source: IntradayPollState.Source, now: LocalDateTime, e: Exception) {
        val crossed = pollState.recordFailure(source, now)
        logger.warn("[Step Intraday] {} 拉取失败（已降级不阻塞循环）：{}", source.key, e.message)
        if (crossed) {
            notifier.notify(
                DingTalkEvent.INTRADAY_SOURCE_STOPPED,
                "盘中数据源连续失败停轮",
                "源组=${source.key} 连续失败 ${properties.stopThreshold} 次，停轮 ${properties.stopCooldownSeconds}s（${e.message}）",
            )
        }
    }

    private fun IntradayEvent.detailStreak(): String = (detail?.get("limit_up_streak")?.asInt())?.toString() ?: "-"
    private fun IntradayEvent.detailChangePct(): String = (detail?.get("change_pct")?.asText()) ?: "-"

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        /** 高位股近似口径：强势池无源，退化 = ZT 池连板 ≥3（mock 样稿口径） */
        const val BIG_FACE_MIN_STREAK = 3
        /** 高位大面阈值：现价 ≤-5%（§14.5 定稿；SentimentProperties.bigFaceThreshold 同源口径） */
        val BIG_FACE_THRESHOLD: BigDecimal = BigDecimal("-5.00")
    }
}
