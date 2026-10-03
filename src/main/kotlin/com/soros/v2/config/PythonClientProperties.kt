package com.soros.v2.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * soros.python-client 配置树（§13.1/§13.2）。
 * yml 未显式给出的键用本类默认值（§13.2 定稿参数）。
 */
@ConfigurationProperties(prefix = "soros.python-client")
class PythonClientProperties {

    /** Python FastAPI 基地址（§13.1） */
    var baseUrl: String = "http://localhost:8000"

    /** default profile：connect 超时（§13.2） */
    var connectTimeout: Duration = Duration.ofSeconds(2)

    /** default profile：response 超时（§13.2） */
    var responseTimeout: Duration = Duration.ofSeconds(10)

    /** default profile：重试次数（仅 ConnectException/5xx/timeout 触发） */
    var retryCount: Int = 2

    /** default profile：重试退避（毫秒） */
    var retryBackoffMillis: Long = 500

    /** backfill profile：response 超时（单 code 5 年 ~1200 行） */
    var backfillResponseTimeout: Duration = Duration.ofSeconds(60)

    /** backfill profile：重试次数 */
    var backfillRetryCount: Int = 1

    /** Kotlin 侧整体熔断：连续失败阈值（§13.2：20） */
    var circuitBreakerFailureThreshold: Int = 20

    /** Kotlin 侧整体熔断：open 时长（秒，§13.2：60） */
    var circuitBreakerOpenSeconds: Int = 60

    /** 协程并发度上限（≤5，防打满 Python 侧限流） */
    var maxConcurrency: Int = 5
}
