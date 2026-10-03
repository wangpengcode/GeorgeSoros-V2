package com.soros.v2.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * §13.1 coroutine dispatcher 收口：
 * 所有 DB / WebClient 调用必须收口于该受限 dispatcher（`sorosIo`），
 * 防止采集高峰把线程池打满拖垮 HTTP 服务。禁止在业务代码直接用 Dispatchers.IO。
 */
@Configuration
class CoroutinesConfig {

    @Bean("sorosIo")
    fun sorosIoDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(32)
}
