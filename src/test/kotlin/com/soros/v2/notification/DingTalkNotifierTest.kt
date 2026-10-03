package com.soros.v2.notification

import com.soros.v2.config.DingTalkProperties
import com.soros.v2.domain.DingTalkEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §11.3 钉钉告警唯一出口契约测试（MockWebServer 模拟钉钉 webhook 端，无 Spring）。
 *
 * 契约（§11.3 表 / 接口 KDoc）：
 * - webhook 未配置（空串）→ log-only 不报错，不发起 HTTP、不抛异常；
 * - webhook 非空 → POST 钉钉 webhook（title/content 进正文），失败仅记日志不抛（告警自身故障不得拖垮采集）；
 * - 限频去重：ERROR 同类 10 分钟 1 条 / DELIST_SUSPECT 每股 1 条 / ADJUSTMENT_DRIFT_UNRESOLVED 每事件 1 条 / INFO 每日 digest。
 *
 * notify / notifyDailyDigest 均已实现（GREEN）。
 */
class DingTalkNotifierTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: IllegalStateException) {
            // 幂等忽略（无并发场景）
        }
    }

    /** 构造已配置 webhook 的 Notifier */
    private fun notifierWithWebhook(): DingTalkNotifierImpl {
        val props = DingTalkProperties().apply { webhook = server.url("/webhook").toString() }
        return DingTalkNotifierImpl(props)
    }

    /** 构造未配置 webhook（空串）的 Notifier */
    private fun notifierEmptyWebhook(): DingTalkNotifierImpl {
        val props = DingTalkProperties() // webhook 默认空串
        return DingTalkNotifierImpl(props)
    }

    // ==================== 正常流程 ====================

    @Test
    fun `testNotify withWebhook postsToDingTalk`() {
        // given: webhook 已配置
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0,"errmsg":"ok"}"""))
        val notifier = notifierWithWebhook()

        // when
        notifier.notify(DingTalkEvent.PYTHON_SERVICE_OFFLINE, "Python 服务离线", "detail=连接超时")

        // then: 发起 POST 且正文携带 title/content（钉钉接口语义）
        val request = server.takeRequest()
        assertEquals("/webhook", request.path, "POST 路径=webhook path")
        assertEquals("POST", request.method, "方法=POST")
        val body = request.body.readUtf8()
        assertTrue(body.contains("Python 服务离线"), "正文应含 title")
        assertTrue(body.contains("detail=连接超时"), "正文应含 content")
    }

    @Test
    fun `testNotifyDailyDigest postsOnce`() {
        // given
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        val notifier = notifierWithWebhook()

        // when
        notifier.notifyDailyDigest("采集完成：成功 100 失败 0")

        // then
        assertEquals(1, server.requestCount, "每日 digest 应 POST 1 次")
        assertTrue(server.takeRequest().body.readUtf8().contains("采集完成"), "digest 正文含摘要")
    }

    // ==================== 异常路径 / 边界 ====================

    @Test
    fun `testNotify emptyWebhook logOnly noHttp noThrow`() {
        // given: webhook 未配置（${SOROS_DINGTALK_WEBHOOK:} 为空）
        val notifier = notifierEmptyWebhook()

        // when & then: 不发起 HTTP、不抛异常（log-only），server.requestCount 必须为 0
        assertDoesNotThrow({ notifier.notify(DingTalkEvent.SOURCE_DEGRADED, "源降级", "akshare 持续 30 分钟") }, "webhook 空不得抛异常")
        assertEquals(0, server.requestCount, "webhook 空时不得发起任何 HTTP（log-only）")
    }

    @Test
    fun `testNotify webhookFailure doesNotThrow`() {
        // given: 钉钉 webhook 返回 500（告警自身故障不得拖垮采集）
        server.enqueue(MockResponse().setResponseCode(500))
        val notifier = notifierWithWebhook()

        // when & then: 失败仅记日志不抛
        assertDoesNotThrow({ notifier.notify(DingTalkEvent.BATCH_FAILURE_RATE_HIGH, "批次失败率高", "12%") }, "钉钉 500 不得抛异常")
    }

    @Test
    fun `testNotify sameEvent rateLimited tenMinutes`() {
        // given: 同类事件 10 分钟 1 条（§11.3：PYTHON_SERVICE_OFFLINE 同类限频）
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        val notifier = notifierWithWebhook()

        // when: 10 分钟内同事件两次
        notifier.notify(DingTalkEvent.PYTHON_SERVICE_OFFLINE, "Python 服务离线", "第一次")
        notifier.notify(DingTalkEvent.PYTHON_SERVICE_OFFLINE, "Python 服务离线", "第二次")

        // then: 只发 1 条 HTTP（第二次被限频吞掉）
        assertEquals(1, server.requestCount, "同类事件 10 分钟内只发 1 条（限频去重）")
    }

    @Test
    fun `testNotify delistSuspect perCode dedup`() {
        // given: DELIST_SUSPECT 每股 1 条（不同 code 各自一条）
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        val notifier = notifierWithWebhook()

        // when: 同 code 两次 + 不同 code 一次
        notifier.notify(DingTalkEvent.DELIST_SUSPECT, "600005 连续 20 交易日无行", "code=600005")
        notifier.notify(DingTalkEvent.DELIST_SUSPECT, "600005 连续 20 交易日无行", "code=600005")
        notifier.notify(DingTalkEvent.DELIST_SUSPECT, "600036 连续 20 交易日无行", "code=600036")

        // then: 每股 1 条 → 2 条（同 code 第二次被去重）
        assertEquals(2, server.requestCount, "DELIST_SUSPECT 每股 1 条（同 code 去重）")
    }

    @Test
    fun `testNotify adjustmentDriftUnresolved perEvent dedup`() {
        // given: ADJUSTMENT_DRIFT_UNRESOLVED 每事件 1 条（LOW-14：非"10 分钟同类窗口"，不同 code 各自 1 条）
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        val notifier = notifierWithWebhook()

        // when: 两个不同 code 的 UNRESOLVED 事件
        notifier.notify(DingTalkEvent.ADJUSTMENT_DRIFT_UNRESOLVED, "600000 漂移未解决", "code=600000 date=2026-09-30")
        notifier.notify(DingTalkEvent.ADJUSTMENT_DRIFT_UNRESOLVED, "600036 漂移未解决", "code=600036 date=2026-09-30")

        // then: 各发 1 条 → 2 条（每事件独立计频，不共享同类 10 分钟窗口）
        assertEquals(2, server.requestCount, "ADJUSTMENT_DRIFT_UNRESOLVED 每事件 1 条（不同 code 各自 1 条）")
    }

    @Test
    fun `testNotify differentEvents notRateLimitedTogether`() {
        // given: 限频按"同类"——不同事件不计同频
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":0}"""))
        val notifier = notifierWithWebhook()

        // when: 三种不同事件连续发
        notifier.notify(DingTalkEvent.PYTHON_SERVICE_OFFLINE, "a", "a")
        notifier.notify(DingTalkEvent.BATCH_FAILURE_RATE_HIGH, "b", "b")
        notifier.notify(DingTalkEvent.SOURCE_DEGRADED, "c", "c")

        // then: 互不计频，各发 1 条
        assertEquals(3, server.requestCount, "不同事件各自独立计频（同类才限频）")
    }
}
