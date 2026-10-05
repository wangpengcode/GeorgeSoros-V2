package com.soros.v2.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 配置属性装配入口（PLAN §三 config 层）：绑定 soros.* 配置树为类型安全 Bean。
 */
@Configuration
@EnableConfigurationProperties(
    DataCollectionProperties::class,
    PythonClientProperties::class,
    DingTalkProperties::class,
    SentimentProperties::class,
    BackfillProperties::class,
    IntradayProperties::class,
)
class PropertiesConfig
