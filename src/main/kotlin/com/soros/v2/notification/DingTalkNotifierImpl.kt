package com.soros.v2.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.soros.v2.config.DingTalkProperties
import com.soros.v2.domain.DingTalkEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * DingTalkNotifier 实现（§11.3 告警唯一出口）。
 *
 * - webhook 为空 → logger.info 打告警内容（log-only），不发起 HTTP、不抛异常；
 * - webhook 非空 → POST 钉钉 webhook（markdown），失败仅记日志不抛（告警自身故障不得拖垮采集）；
 * - 限频去重（§11.3）：ConcurrentHashMap 记录 event→最近推送时间，ERROR/WARN 同类 10 分钟 1 条；
 *   DELIST_SUSPECT 每股 1 条（按 content 中的 code= 去重）；INFO 每日 digest 24h 1 条。
 */
@Service
class DingTalkNotifierImpl(
    private val properties: DingTalkProperties,
) : DingTalkNotifier {

    private val logger = LoggerFactory.getLogger(DingTalkNotifierImpl::class.java)
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private companion object {
        const val RATE_LIMIT_MILLIS = 10 * 60 * 1000L
        const val DIGEST_RATE_LIMIT_MILLIS = 24 * 60 * 60 * 1000L
        const val DELIST_SUSPECT_KEY_PREFIX = "DELIST_SUSPECT:"
        const val UNRESOLVED_KEY_PREFIX = "ADJUSTMENT_DRIFT_UNRESOLVED:"
        const val DIGEST_KEY = "DAILY_COLLECT_DIGEST"
        val CODE_PATTERN = Regex("code=(\\d+)")
        val jsonMapper = ObjectMapper()
    }

    override fun notify(event: DingTalkEvent, title: String, content: String) {
        if (event == null) { // 防御 Java 侧非法 null 调用（接口非空契约，生产 Kotlin 调用方不可达）
            logger.warn("[DingTalk] event=null 跳过告警（防御性）title={}", title)
            return
        }
        if (properties.webhook.isBlank()) {
            logger.info("[DingTalk log-only] event={} title={} content={}", event, title, content)
            return
        }
        val key = dedupeKey(event, content)
        if (isRateLimited(key)) {
            logger.info("[DingTalk rate-limited] event={} key={} title={}", event, key, title)
            return
        }
        if (postToDingTalk(title, content)) {
            lastSentAt[key] = System.currentTimeMillis()
        }
    }

    override fun notifyDailyDigest(summary: String) {
        if (properties.webhook.isBlank()) {
            logger.info("[DingTalk log-only] daily digest: {}", summary)
            return
        }
        if (isRateLimited(DIGEST_KEY, DIGEST_RATE_LIMIT_MILLIS)) {
            logger.info("[DingTalk rate-limited] daily digest skipped")
            return
        }
        if (postToDingTalk(summary, summary)) {
            lastSentAt[DIGEST_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * 去重键（§11.3）：
     * - DELIST_SUSPECT 按 content 中的 code= 去重（每股 1 条）；
     * - ADJUSTMENT_DRIFT_UNRESOLVED 按 code= 去重（每事件 1 条，LOW-14：非"10 分钟同类窗口"，各事件独立计频）；
     * - 其余 ERROR/WARN 按事件类型 10 分钟 1 条。
     */
    private fun dedupeKey(event: DingTalkEvent, content: String): String = when (event) {
        DingTalkEvent.DELIST_SUSPECT -> codeKey(DELIST_SUSPECT_KEY_PREFIX, content)
        DingTalkEvent.ADJUSTMENT_DRIFT_UNRESOLVED -> codeKey(UNRESOLVED_KEY_PREFIX, content)
        else -> event.name
    }

    private fun codeKey(prefix: String, content: String): String {
        val code = CODE_PATTERN.find(content)?.groupValues?.get(1) ?: content
        return "$prefix$code"
    }

    private fun isRateLimited(key: String, windowMillis: Long = RATE_LIMIT_MILLIS): Boolean {
        val last = lastSentAt[key] ?: return false
        return System.currentTimeMillis() - last < windowMillis
    }

    /** POST 钉钉 webhook；任何失败仅日志（不抛），返回是否成功（成功才刷新限频时间戳） */
    private fun postToDingTalk(title: String, content: String): Boolean = try {
        val body = buildPayload(title, content)
        val request = HttpRequest.newBuilder(URI.create(properties.webhook))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) {
            logger.info("[DingTalk posted] status={} title={}", response.statusCode(), title)
            true
        } else {
            logger.error("[DingTalk post failed] status={} title={} webhook={}", response.statusCode(), title, properties.webhook)
            false
        }
    } catch (e: Exception) {
        logger.error("[DingTalk post failed] title={} webhook={} error={}", title, properties.webhook, e.message)
        false
    }

    /** 钉钉 markdown 消息体（title/content 进正文） */
    private fun buildPayload(title: String, content: String): String {
        val payload = mapOf(
            "msgtype" to "markdown",
            "markdown" to mapOf(
                "title" to title,
                "text" to "$title\n$content",
            ),
        )
        return jsonMapper.writeValueAsString(payload)
    }
}
