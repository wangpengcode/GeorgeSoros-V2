package com.soros.v2.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

/**
 * §13.2 WebClient 双 profile 超时/重试（default/backfill）与连接池 + §2.4 严格反序列化。
 *
 * - default profile：connect 2s + response 10s（[PythonClientProperties.responseTimeout]）；
 * - backfill profile：connect 2s + response 60s（[PythonClientProperties.backfillResponseTimeout]，单 code 5 年 ~1200 行）。
 *   由 [com.soros.v2.service.PythonDataServiceClientImpl] 按请求区间（>366 自然日）选择目标 WebClient。
 * - 严格反序列化：注入 Spring 容器 ObjectMapper（application.yml spring.jackson.deserialization.fail-on-unknown-properties=true
 *   真正生效），通过 exchangeStrategies 自定义 decoder/encoder，与测试侧 WebClient 构造方式一致。
 * - 责任边界（穿透确认）：Kotlin→Python 是唯一上游链路，多源 failover 全在 Python Router 内部完成；
 *   Kotlin 不做"换数据源重试"，仅对 Python 整体熔断（PythonCircuitBreaker）。
 */
@Configuration
class WebClientConfig {

    /** Python HttpClient（default profile）：connect 2s + response 10s（§13.2） */
    @Bean
    fun pythonHttpClient(properties: PythonClientProperties): HttpClient =
        buildHttpClient("python-client-pool", properties.connectTimeout, properties.responseTimeout)

    /** Python HttpClient（backfill profile）：connect 2s + response 60s（§13.2） */
    @Bean
    fun pythonBackfillHttpClient(properties: PythonClientProperties): HttpClient =
        buildHttpClient("python-backfill-pool", properties.connectTimeout, properties.backfillResponseTimeout)

    /** Python WebClient（default profile；strict Jackson 反序列化，baseUrl 固定） */
    @Bean("pythonWebClient")
    fun pythonWebClient(
        @Qualifier("pythonHttpClient") httpClient: HttpClient,
        properties: PythonClientProperties,
        objectMapper: ObjectMapper,
    ): WebClient = buildWebClient(httpClient, properties.baseUrl, objectMapper)

    /** Python WebClient（backfill profile：response 60s；strict Jackson 反序列化） */
    @Bean("pythonBackfillWebClient")
    fun pythonBackfillWebClient(
        @Qualifier("pythonBackfillHttpClient") httpClient: HttpClient,
        properties: PythonClientProperties,
        objectMapper: ObjectMapper,
    ): WebClient = buildWebClient(httpClient, properties.baseUrl, objectMapper)

    private fun buildHttpClient(poolName: String, connectTimeout: Duration, responseTimeout: Duration): HttpClient {
        val pool = ConnectionProvider.builder(poolName)
            .maxConnections(100)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .maxIdleTime(Duration.ofSeconds(30))
            .build()
        return HttpClient.create(pool)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
            .responseTimeout(responseTimeout)
    }

    private fun buildWebClient(httpClient: HttpClient, baseUrl: String, objectMapper: ObjectMapper): WebClient =
        WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(
                ExchangeStrategies.builder().codecs { configurer ->
                    configurer.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(objectMapper))
                    configurer.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(objectMapper))
                }.build(),
            )
            .build()
}
