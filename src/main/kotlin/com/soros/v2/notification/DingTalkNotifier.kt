package com.soros.v2.notification

import com.soros.v2.domain.DingTalkEvent

/**
 * §11.3 钉钉告警唯一出口。
 *
 * 契约：
 * - webhook 未配置（${SOROS_DINGTALK_WEBHOOK:}）时 log-only 不报错，不抛异常；
 * - 限频去重（§11.3 表）：ERROR 同类 10 分钟 1 条 / DELIST_SUSPECT 每股 1 条 /
 *   ADJUSTMENT_DRIFT_UNRESOLVED 每事件 1 条 / INFO 每日 digest。
 */
interface DingTalkNotifier {

    /**
     * 告警事件推送（level 由 [DingTalkEvent] 决定；title/content 正文）。
     * event 非空契约：实现层对 null 防御性跳过（防 Java 侧非法调用）。
     */
    fun notify(event: DingTalkEvent, title: String, content: String)

    /** 每日采集 digest（INFO，每日 1 条） */
    fun notifyDailyDigest(summary: String)
}
