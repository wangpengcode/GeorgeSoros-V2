package com.soros.v2.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * soros.dingtalk 配置树（§13.1）。
 */
@ConfigurationProperties(prefix = "soros.dingtalk")
class DingTalkProperties {

    /** §11.3 webhook（${SOROS_DINGTALK_WEBHOOK:}）；为空时 log-only 不报错 */
    var webhook: String = ""
}
