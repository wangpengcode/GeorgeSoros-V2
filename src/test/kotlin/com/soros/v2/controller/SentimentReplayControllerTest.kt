package com.soros.v2.controller

import com.soros.v2.service.sentiment.SentimentReplayService
import com.soros.v2.service.sentiment.SentimentReplaySummary
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * §19.12 SentimentReplayController force 参数契约测试（直调 controller，Fake 服务，参考 SentimentControllerTest）。
 *
 * 契约（§19.12 决策 4 / 落码清单）：
 * - force 缺省 → replay(from, to, false)（增量补缺断点续跑默认，端点 ?from=&to=）；
 * - force=true 显式传递 → replay(from, to, true)（全量重建，端点 ?from=&to=&force=true）；
 * - 摘要原样回传（既有 from/to/sentimentRows/dragonRows/dragonCycleList 向后兼容 + 新 force 字段）。
 */
class SentimentReplayControllerTest {

    private class FakeReplayService : SentimentReplayService {
        var lastFrom: LocalDate? = null
        var lastTo: LocalDate? = null
        var lastForce: Boolean? = null
        var result: SentimentReplaySummary? = null

        override fun replay(from: LocalDate, to: LocalDate, force: Boolean): SentimentReplaySummary {
            lastFrom = from
            lastTo = to
            lastForce = force
            return result ?: SentimentReplaySummary(from, to, 0, 0, emptyList())
        }
    }

    private val from = LocalDate.of(2026, 9, 1)
    private val to = LocalDate.of(2026, 9, 30)

    @Test
    fun `testReplay forceDefaultsToFalse`() {
        // given
        val service = FakeReplayService()
        val controller = SentimentReplayController(service)

        // when: 不传 force（端点 ?from=&to=，§19.12 决策 4 force 缺省 false）
        val resp = controller.replay(from, to)

        // then: force 缺省 false → replay(from, to, false)
        assertEquals(from, service.lastFrom, "from 透传")
        assertEquals(to, service.lastTo, "to 透传")
        assertEquals(false, service.lastForce, "force 缺省 false（增量补缺断点续跑默认）")
        assertEquals(from, resp.from, "摘要原样回传")
        assertEquals(false, resp.force, "摘要 force 缺省 false")
    }

    @Test
    fun `testReplay forceTruePropagatesToService`() {
        // given
        val service = FakeReplayService()
        val controller = SentimentReplayController(service)

        // when: force=true（端点 ?from=&to=&force=true，全量重建）
        val resp = controller.replay(from, to, force = true)

        // then: force=true 透传（BackfillJob §13.5 钩子同传 true）
        assertEquals(true, service.lastForce, "force=true 透传（全量重建）")
        assertEquals(true, resp.force, "摘要 force 回传")
    }
}
