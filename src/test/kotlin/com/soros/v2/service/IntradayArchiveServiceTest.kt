package com.soros.v2.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.config.IntradayProperties
import com.soros.v2.entity.IntradayArchive
import com.soros.v2.exception.PythonClientException
import com.soros.v2.repository.IntradayArchiveRepository
import com.soros.v2.repository.IntradayEventRepository
import com.soros.v2.repository.IntradayPoolSnapRepository
import com.soros.v2.repository.IntradayPoolStateRepository
import com.soros.v2.repository.IntradayReplayRepository
import com.soros.v2.service.intraday.IntradayArchiveService
import com.soros.v2.service.intraday.IntradayArchiveServiceImpl
import com.soros.v2.service.intraday.IntradayPollState
import com.soros.v2.service.intraday.IntradayPythonClient
import com.soros.v2.service.intraday.dto.IntradayPoolRowDto
import com.soros.v2.service.intraday.dto.IntradayPoolsResponse
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
 * §19.13.2 IntradayArchiveServiceImpl 契约测试（Fake Python 客户端 + Mockito mock 仓储）。
 *
 * 契约（§19.13.2 15:10 权威归档）：
 * - 重拉当日三池（limit_up→ZT / broken→ZB / limit_down→DT）→ 先清当日行再重写（幂等）；
 * - 构建整页快照：ladder=ZT 归档行（按连板降序）、kpi_series 保留盘中采样、panels 现拼；
 * - replay.complete=true 落库；过期 snap 清理（保留 snapRetentionDays 天）；
 * - 防御性：重拉失败/令牌不足 → 记 ERROR 不写半成品归档、complete 保持 false。
 */
class IntradayArchiveServiceTest {

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private lateinit var client: FakeIntradayPythonClient
    private lateinit var archiveRepo: IntradayArchiveRepository
    private lateinit var replayRepo: IntradayReplayRepository
    private lateinit var eventRepo: IntradayEventRepository
    private lateinit var poolStateRepo: IntradayPoolStateRepository
    private lateinit var snapRepo: IntradayPoolSnapRepository
    private lateinit var service: IntradayArchiveService

    private class FakeIntradayPythonClient : IntradayPythonClient {
        var poolsResult: IntradayPoolsResponse? = IntradayPoolsResponse("ok", "20260930", emptyList(), emptyList(), emptyList())
        var poolsError: PythonClientException? = null
        override suspend fun fetchPools(dateYyyymmdd: String): IntradayPoolsResponse? {
            poolsError?.let { throw it }
            return poolsResult
        }

        override suspend fun fetchSpot(): com.soros.v2.service.intraday.dto.IntradaySpotResponse? = null
        override suspend fun fetchBidAsk(code: String): com.soros.v2.service.intraday.dto.IntradayBidAskResponse? = null
    }

    @BeforeEach
    fun setUp() {
        client = FakeIntradayPythonClient()
        archiveRepo = Mockito.mock(IntradayArchiveRepository::class.java)
        replayRepo = Mockito.mock(IntradayReplayRepository::class.java)
        eventRepo = Mockito.mock(IntradayEventRepository::class.java)
        poolStateRepo = Mockito.mock(IntradayPoolStateRepository::class.java)
        snapRepo = Mockito.mock(IntradayPoolSnapRepository::class.java)
        service = IntradayArchiveServiceImpl(
            client = client,
            archiveRepo = archiveRepo,
            replayRepo = replayRepo,
            eventRepo = eventRepo,
            poolStateRepo = poolStateRepo,
            snapRepo = snapRepo,
            pollState = IntradayPollState(IntradayProperties()),
            properties = IntradayProperties(),
            objectMapper = mapper,
        )
    }

    private fun row(code: String, lianban: Int? = null): IntradayPoolRowDto =
        IntradayPoolRowDto(code = code, name = code, changePct = BigDecimal("9.98"), lianban = lianban)

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（LocalDate 占位） */
    private fun anyDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.of(2026, 9, 30)
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（LocalDateTime 占位） */
    private fun anyTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.of(2026, 9, 30, 15, 10)
    }

    /** Mockito.any() 的 Kotlin 非空参数安全 matcher（IntradayReplay 占位） */
    private fun anyReplay(): com.soros.v2.entity.IntradayReplay {
        Mockito.any(com.soros.v2.entity.IntradayReplay::class.java)
        return com.soros.v2.entity.IntradayReplay()
    }

    // ==================== 1. 正常归档：清→写→整页快照→complete=true→清理 ====================

    @Test
    fun `testArchiveDay writesArchiveCompletesReplayCleansSnaps`() {
        // given: 当日三池（limit_up 2 / broken 1 / limit_down 1）
        client.poolsResult = IntradayPoolsResponse(
            "ok", "20260930",
            limitUp = listOf(row("A", 3), row("B", 1)),
            broken = listOf(row("C", 1)),
            limitDown = listOf(row("D", 1)),
        )

        // when
        runBlocking { service.archiveDay(LocalDate.of(2026, 9, 30)) }

        // then: 先清当日行（幂等重归档）
        Mockito.verify(archiveRepo).deleteByTradeDate(LocalDate.of(2026, 9, 30))

        // then: 4 行归档落库（ZT 2 / ZB 1 / DT 1，pool 归属正确）
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<Iterable<IntradayArchive>>
        Mockito.verify(archiveRepo).saveAll(captor.capture())
        val rows = captor.value.toList()
        assertEquals(4, rows.size, "归档 4 行")
        assertTrue(rows.count { it.pool?.trim() == "ZT" } == 2, "ZT 池 2 行")
        assertTrue(rows.count { it.pool?.trim() == "ZB" } == 1, "ZB 池 1 行")
        assertTrue(rows.count { it.pool?.trim() == "DT" } == 1, "DT 池 1 行")

        // then: replay 整页快照 complete=true
        val replayCaptor = ArgumentCaptor.forClass(com.soros.v2.entity.IntradayReplay::class.java)
        Mockito.verify(replayRepo).save(replayCaptor.capture())
        assertTrue(replayCaptor.value.complete, "complete=true")

        // then: 过期 snap 清理
        Mockito.verify(snapRepo).deleteBySnapAtBefore(anyTime())
    }

    // ==================== 2. 防御性：重拉失败 → 不写半成品归档 ====================

    @Test
    fun `testArchiveDay clientFailureDegradesNoArchive`() {
        // given: pools 重拉抛异常
        client.poolsError = PythonClientException("pools 500")

        // when & then: 不抛异常（降级记 ERROR），不写归档/不置 complete
        runBlocking { service.archiveDay(LocalDate.of(2026, 9, 30)) }
        Mockito.verify(archiveRepo, Mockito.never()).deleteByTradeDate(anyDate())
        Mockito.verify(archiveRepo, Mockito.never()).saveAll(Mockito.anyList())
        Mockito.verify(replayRepo, Mockito.never()).save(anyReplay())
    }

    // ==================== 3. 防御性：令牌不足 → 不写半成品归档 ====================

    @Test
    fun `testArchiveDay tokenDeficientNoArchive`() {
        // given: 令牌不足 → 客户端返回 null
        client.poolsResult = null

        // when & then: 不写归档/不置 complete
        runBlocking { service.archiveDay(LocalDate.of(2026, 9, 30)) }
        Mockito.verify(archiveRepo, Mockito.never()).deleteByTradeDate(anyDate())
        Mockito.verify(replayRepo, Mockito.never()).save(anyReplay())
    }

    // ==================== 4. 整页 ladder：ZT 归档行按连板降序 ====================

    @Test
    fun `testArchiveDay ladderUsesArchiveZtRowsOrderedByStreak`() {
        // given: 上轮 replay 已有 1 个 kpi 采样点；归档 ZT 行 A(3)>B(1)
        val replay = com.soros.v2.entity.IntradayReplay().apply {
            tradeDate = LocalDate.of(2026, 9, 30)
            page = mapper.valueToTree(
                com.soros.v2.service.intraday.dto.IntradayPageDto(
                    kpiSeries = listOf(
                        com.soros.v2.service.intraday.dto.IntradayKpiPoint("09:31:00", 2, 1, 0, adv = 100, dec = 50),
                    ),
                ),
            )
        }
        Mockito.`when`(replayRepo.findByTradeDate(LocalDate.of(2026, 9, 30))).thenReturn(replay)
        Mockito.`when`(archiveRepo.findLadderByTradeDate(LocalDate.of(2026, 9, 30))).thenReturn(
            listOf(
                com.soros.v2.entity.IntradayArchive(
                    id = com.soros.v2.entity.IntradayArchiveId(LocalDate.of(2026, 9, 30), "A"),
                    limitUpStreak = 3, pool = "ZT",
                ),
                com.soros.v2.entity.IntradayArchive(
                    id = com.soros.v2.entity.IntradayArchiveId(LocalDate.of(2026, 9, 30), "B"),
                    limitUpStreak = 1, pool = "ZT",
                ),
            ),
        )

        // when
        runBlocking { service.archiveDay(LocalDate.of(2026, 9, 30)) }

        // then: 整页快照保留盘中 kpi + ladder 按连板降序（A(3) 在前）
        val replayCaptor = ArgumentCaptor.forClass(com.soros.v2.entity.IntradayReplay::class.java)
        Mockito.verify(replayRepo).save(replayCaptor.capture())
        val page = mapper.convertValue(replayCaptor.value.page, com.soros.v2.service.intraday.dto.IntradayPageDto::class.java)
        assertEquals(1, page.kpiSeries.size, "盘中采样点保留")
        assertEquals(2, page.ladder.size, "ladder 2 行")
        assertEquals("A", page.ladder[0].code, "A(3) 在前")
        assertEquals("SEAL", page.ladder[0].limitStat, "ZT 封住 → SEAL")
    }
}
