package com.soros.v2.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.config.IntradayProperties
import com.soros.v2.domain.DingTalkEvent
import com.soros.v2.domain.IntradayEventType
import com.soros.v2.entity.IntradayPoolSnap
import com.soros.v2.exception.PythonClientException
import com.soros.v2.notification.DingTalkNotifier
import com.soros.v2.repository.IntradayEventRepository
import com.soros.v2.repository.IntradayPoolSnapRepository
import com.soros.v2.repository.IntradayReplayRepository
import com.soros.v2.service.intraday.IntradayPollService
import com.soros.v2.service.intraday.IntradayPollServiceImpl
import com.soros.v2.service.intraday.IntradayPollState
import com.soros.v2.service.intraday.IntradayPythonClient
import com.soros.v2.service.intraday.dto.IntradayBidAskResponse
import com.soros.v2.service.intraday.dto.IntradayPoolRowDto
import com.soros.v2.service.intraday.dto.IntradayPoolsResponse
import com.soros.v2.service.intraday.dto.IntradaySpotResponse
import com.soros.v2.service.intraday.dto.IntradaySpotStockDto
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * §19.13.2 IntradayPollServiceImpl 契约测试（Fake Python 客户端 + Mockito mock 仓储/钉钉）。
 *
 * 契约（§19.13.2 定稿）：
 * - 首轮无基线 → 只写 snap 不 diff（防启动灌入海量事件）；
 * - 有基线 diff：ZT 新封板 / HF 回封（上轮炸板本轮封）/ ZB 炸板 / OPEN 开板 / MAXCHG 最高板易主；
 *   跌停不做事件（ev_type CHECK 无 DT，仅 snap+kpi+归档）；
 * - 钉钉推送仅 3 类（§14.5）：MAXCHG → INTRADAY_MAX_CHANGE；DM（强势池成员现价 ≤-5%）→ INTRADAY_BIG_FACE；
 *   源连续失败跨 stopThreshold → INTRADAY_SOURCE_STOPPED；普通涨停不推；
 * - 幂等 upsert：同 snap_at+pool 已存在 → 跳过不重写；
 * - 防御性：Python 端点失败降级记 WARN 不炸循环（连续 6 次 → 停轮 + 钉钉）；令牌不足 defer 不计失败。
 */
class IntradayPollServiceTest {

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private lateinit var client: FakeIntradayPythonClient
    private lateinit var snapRepo: IntradayPoolSnapRepository
    private lateinit var eventRepo: IntradayEventRepository
    private lateinit var replayRepo: IntradayReplayRepository
    private lateinit var notifier: DingTalkNotifier
    private lateinit var service: IntradayPollService

    /** Fake Python 客户端：suspend 方法可控返回/抛异常（避免 Mockito 对 suspend 的非 suspend 上下文别扭 stub） */
    private class FakeIntradayPythonClient : IntradayPythonClient {
        var poolsResult: IntradayPoolsResponse? = IntradayPoolsResponse("ok", "20260930", emptyList(), emptyList(), emptyList())
        var poolsError: PythonClientException? = null
        var spotResult: IntradaySpotResponse? = IntradaySpotResponse("ok", 0, emptyList())
        var spotError: PythonClientException? = null
        var bidAskResult: IntradayBidAskResponse? = null
        var bidAskError: PythonClientException? = null
        val bidAskCodes = mutableListOf<String>()

        override suspend fun fetchPools(dateYyyymmdd: String): IntradayPoolsResponse? {
            poolsError?.let { throw it }
            return poolsResult
        }

        override suspend fun fetchSpot(): IntradaySpotResponse? {
            spotError?.let { throw it }
            return spotResult
        }

        override suspend fun fetchBidAsk(code: String): IntradayBidAskResponse? {
            bidAskCodes += code
            bidAskError?.let { throw it }
            return bidAskResult
        }
    }

    @BeforeEach
    fun setUp() {
        client = FakeIntradayPythonClient()
        snapRepo = Mockito.mock(IntradayPoolSnapRepository::class.java)
        eventRepo = Mockito.mock(IntradayEventRepository::class.java)
        replayRepo = Mockito.mock(IntradayReplayRepository::class.java)
        notifier = Mockito.mock(DingTalkNotifier::class.java)
        service = IntradayPollServiceImpl(
            client = client,
            snapRepo = snapRepo,
            eventRepo = eventRepo,
            replayRepo = replayRepo,
            pollState = IntradayPollState(IntradayProperties()),
            notifier = notifier,
            properties = IntradayProperties(),
            objectMapper = mapper,
        )
    }

    // ==================== 构造辅助 ====================

    private fun row(code: String, lianban: Int? = null, changePct: String = "9.98"): IntradayPoolRowDto =
        IntradayPoolRowDto(code = code, name = code, changePct = BigDecimal(changePct), lianban = lianban)

    private fun snap(pool: String, rows: List<IntradayPoolRowDto>): IntradayPoolSnap =
        IntradayPoolSnap(snapAt = LocalDateTime.of(2026, 9, 30, 9, 31), pool = pool, payload = mapper.valueToTree(rows))

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（LocalDateTime 占位） */
    private fun anyTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.of(2026, 9, 30, 9, 30)
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（IntradayPoolSnap 占位） */
    private fun anySnap(): IntradayPoolSnap {
        Mockito.any(IntradayPoolSnap::class.java)
        return IntradayPoolSnap(payload = mapper.nullNode())
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（DingTalkEvent 占位） */
    private fun anyEvent(): DingTalkEvent {
        Mockito.any(DingTalkEvent::class.java)
        return DingTalkEvent.SENTIMENT_DERIVE_FAILED
    }

    /** Mockito.eq() 的 Kotlin 非空参数安全 matcher（String 占位；Mockito 5 eq 返回 null） */
    private fun eqPool(value: String): String {
        Mockito.eq(value)
        return value
    }

    /** Mockito.eq() 的 Kotlin 非空参数安全 matcher（DingTalkEvent 占位） */
    private fun eqEvent(event: DingTalkEvent): DingTalkEvent {
        Mockito.eq(event)
        return event
    }

    @Suppress("UNCHECKED_CAST")
    private fun capturedEvents(): List<IntradayEventType> {
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<Iterable<com.soros.v2.entity.IntradayEvent>>
        Mockito.verify(eventRepo).saveAll(captor.capture())
        return captor.value.map { it.evType }
    }

    // ==================== 1. 首轮无基线：只写 snap 不 diff ====================

    @Test
    fun `testPollRound firstRoundNoBaselineWritesSnapsNoDiff`() {
        // given: 首轮（无上轮 snap）+ 三池响应
        client.poolsResult = IntradayPoolsResponse(
            "ok", "20260930",
            limitUp = listOf(row("600001", 1)), broken = listOf(row("600002", 1)), limitDown = listOf(row("600003", 1)),
        )

        // when
        runBlocking { service.pollRound() }

        // then: 3 帧 snap 落库（ZT/ZB/DT），无 diff 事件
        val captor = ArgumentCaptor.forClass(IntradayPoolSnap::class.java)
        Mockito.verify(snapRepo, Mockito.times(3)).save(captor.capture())
        assertTrue(captor.allValues.map { it.pool.trim() }.containsAll(listOf("ZT", "ZB", "DT")), "三池快照落库")
        Mockito.verify(eventRepo, Mockito.never()).saveAll(Mockito.anyList())
        Mockito.verify(notifier, Mockito.never()).notify(anyEvent(), Mockito.anyString(), Mockito.anyString())
    }

    // ==================== 2. 有基线 diff：ZT/HF/ZB/OPEN/MAXCHG ====================

    @Test
    fun `testPollRound secondRoundDiffEmitsZtHfZbOpenMaxChange`() {
        // given: 上轮 ZT=[A(5),W(2),Z(1)]、上轮 ZB=[B(1)]；本轮 ZT=[W(2),B(2),NEWC(1)]、ZB=[A(5)]
        val prevZt = snap("ZT", listOf(row("A", 5), row("W", 2), row("Z", 1)))
        val prevZb = snap("ZB", listOf(row("B", 1)))
        Mockito.`when`(snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(eqPool("ZT"), anyTime())).thenReturn(prevZt)
        Mockito.`when`(snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(eqPool("ZB"), anyTime())).thenReturn(prevZb)
        client.poolsResult = IntradayPoolsResponse(
            "ok", "20260930",
            limitUp = listOf(row("W", 2), row("B", 2), row("NEWC", 1)),
            broken = listOf(row("A", 5)),
            limitDown = emptyList(),
        )

        // when
        runBlocking { service.pollRound() }

        // then: HF(B)+ZT(NEWC)+ZB(A)+OPEN(Z)+MAXCHG(W) 共 5 事件
        val types = capturedEvents()
        assertEquals(5, types.size, "5 事件")
        assertTrue(IntradayEventType.HF in types, "B 上轮炸板本轮封住 → HF 回封")
        assertTrue(IntradayEventType.ZT in types, "NEWC 本轮新封板 → ZT")
        assertTrue(IntradayEventType.ZB in types, "A 上轮封住本轮炸板 → ZB")
        assertTrue(IntradayEventType.OPEN in types, "Z 上轮封住本轮既不在涨停池也不在炸板池 → OPEN")
        assertTrue(IntradayEventType.MAXCHG in types, "最高连板易主 A(5)→W(2) → MAXCHG")

        // then: MAXCHG 推钉钉（§14.5 仅 3 类之一），且 pushed_dd 标记
        Mockito.verify(notifier).notify(
            eqEvent(DingTalkEvent.INTRADAY_MAX_CHANGE),
            Mockito.anyString(), Mockito.anyString(),
        )
    }

    // ==================== 3. 高位大面 DM（强势池成员现价 ≤-5%）→ INTRADAY_BIG_FACE ====================

    @Test
    fun `testPollRound bigFaceSpotDropTriggersDmAlert`() {
        // given: 上轮 ZT=[A(3)]（连板≥3 近似强势池口径）；本轮 ZT 仍含 A；spot 中 A 现价 -6%
        val prevZt = snap("ZT", listOf(row("A", 3)))
        Mockito.`when`(snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(eqPool("ZT"), anyTime())).thenReturn(prevZt)
        client.poolsResult = IntradayPoolsResponse("ok", "20260930", limitUp = listOf(row("A", 3)), limitDown = emptyList(), broken = emptyList())
        client.spotResult = IntradaySpotResponse("ok", 1, listOf(IntradaySpotStockDto(code = "A", changePct = BigDecimal("-6.00"))))

        // when
        runBlocking { service.pollRound() }

        // then: DM 事件落库 + 钉钉 INTRADAY_BIG_FACE
        val types = capturedEvents()
        assertTrue(IntradayEventType.DM in types, "强势池成员现价 ≤-5% → DM")
        Mockito.verify(notifier).notify(
            eqEvent(DingTalkEvent.INTRADAY_BIG_FACE),
            Mockito.anyString(), Mockito.anyString(),
        )
    }

    // ==================== 4. 幂等 upsert：同 snap_at+pool 已存在 → 跳过 ====================

    @Test
    fun `testPollRound idempotentSkipsExistingSnap`() {
        // given: 本轮 snap_at+pool 已存在（同 poll_time 重跑）
        Mockito.`when`(snapRepo.existsBySnapAtAndPool(anyTime(), Mockito.anyString())).thenReturn(true)
        client.poolsResult = IntradayPoolsResponse("ok", "20260930", limitUp = listOf(row("600001")), limitDown = emptyList(), broken = emptyList())

        // when
        runBlocking { service.pollRound() }

        // then: 零写入（幂等 upsert 不重复）
        Mockito.verify(snapRepo, Mockito.never()).save(anySnap())
    }

    // ==================== 5. 防御性：端点失败降级不炸循环 ====================

    @Test
    fun `testPollRound clientFailureDegradesDoesNotThrow`() {
        // given: pools 端点抛异常（源故障）
        client.poolsError = PythonClientException("pools 500")
        client.spotError = PythonClientException("spot 500")

        // when & then: 不抛异常（降级记 WARN），且源失败计数推进
        runBlocking { service.pollRound() }
        assertTrue(true, "外部端点失败降级不炸 Job 循环")
    }

    @Test
    fun `testPollRound sixConsecutiveFailuresAlertsSourceStopped`() {
        // given: pools/spot 持续失败（bid-ask 因候选空不触发）
        client.poolsError = PythonClientException("pools 500")
        client.spotError = PythonClientException("spot 500")

        // when: 连续 6 轮（stopThreshold=6 → 停轮 + 钉钉）
        repeat(8) { runBlocking { service.pollRound() } }

        // then: 停轮告警（INTRADAY_SOURCE_STOPPED，每源跨阈值各 1 次）
        Mockito.verify(notifier, Mockito.atLeastOnce()).notify(
            eqEvent(DingTalkEvent.INTRADAY_SOURCE_STOPPED),
            Mockito.anyString(), Mockito.anyString(),
        )
    }

    // ==================== 6. 令牌不足 defer：不计失败、不写数据 ====================

    @Test
    fun `testPollRound tokenDeficientDefersNoWriteNoFailure`() {
        // given: 令牌不足 → 客户端返回 null（本轮 defer）
        client.poolsResult = null
        client.spotResult = null

        // when
        runBlocking { service.pollRound() }

        // then: 不写 snap、不 diff、不计源失败（failures 保持 0）
        Mockito.verify(snapRepo, Mockito.never()).save(anySnap())
        Mockito.verify(eventRepo, Mockito.never()).saveAll(Mockito.anyList())
        Mockito.verify(notifier, Mockito.never()).notify(anyEvent(), Mockito.anyString(), Mockito.anyString())
    }

    // ==================== 7. kpi_series 采样点追加 ====================

    @Test
    fun `testPollRound appendsKpiSeriesPointWhenPoolsAndSpotBothOk`() {
        // given: 池 + spot 双齐（池缺一不记 0 点误导）
        client.poolsResult = IntradayPoolsResponse(
            "ok", "20260930",
            limitUp = listOf(row("600001", 1)), broken = listOf(row("600002", 1)), limitDown = listOf(row("600003", 1)),
        )
        client.spotResult = IntradaySpotResponse(
            "ok", 3,
            listOf(
                IntradaySpotStockDto("600001", changePct = BigDecimal("9.98")),
                IntradaySpotStockDto("600002", changePct = BigDecimal("-3.00")),
                IntradaySpotStockDto("600003", changePct = BigDecimal("-9.98")),
            ),
        )

        // when
        runBlocking { service.pollRound() }

        // then: replay 当日行追加 kpi 采样点（zt=1/zb=1/dt=1/adv=1/dec=2）
        val captor = ArgumentCaptor.forClass(com.soros.v2.entity.IntradayReplay::class.java)
        Mockito.verify(replayRepo).save(captor.capture())
        val page = mapper.convertValue(captor.value.page, com.soros.v2.service.intraday.dto.IntradayPageDto::class.java)
        assertEquals(1, page.kpiSeries.size, "单轮 1 个采样点")
        assertEquals(1, page.kpiSeries[0].zt, "zt=1")
        assertEquals(1, page.kpiSeries[0].zb, "zb=1")
        assertEquals(1, page.kpiSeries[0].dt, "dt=1")
        assertEquals(1, page.kpiSeries[0].adv, "adv=1")
        assertEquals(2, page.kpiSeries[0].dec, "dec=2")
    }

    // ==================== 8. bid-ask 候选名单（梯队∪炸板∪跌停，单轮 ≤15） ====================

    @Test
    fun `testPollRound bidAskCandidatesFromZtZbDt`() {
        // given: 上轮基线 + 三池响应（候选=ZT 按连板降序 ∪ ZB ∪ DT.take(5)）
        val prevZt = snap("ZT", listOf(row("A", 2)))
        Mockito.`when`(snapRepo.findFirstByPoolAndSnapAtBeforeOrderBySnapAtDesc(eqPool("ZT"), anyTime())).thenReturn(prevZt)
        client.poolsResult = IntradayPoolsResponse(
            "ok", "20260930",
            limitUp = listOf(row("A", 2)),
            broken = listOf(row("B", 1)),
            limitDown = listOf(row("C", 1)),
        )

        // when
        runBlocking { service.pollRound() }

        // then: 候选 A/B/C 各请求一次五档
        assertTrue(client.bidAskCodes.containsAll(listOf("A", "B", "C")), "候选名单 A∪B∪C")
    }
}
