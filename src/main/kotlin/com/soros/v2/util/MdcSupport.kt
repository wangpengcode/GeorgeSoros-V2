package com.soros.v2.util

import org.slf4j.MDC

/**
 * §13.3 结构化日志 MDC 固定字段：job / code / phase / date_range / attempt。
 *
 * 用法：MdcSupport.withMdc(MdcSupport.JOB to "daily-collect", MdcSupport.PHASE to "fetch") { ... }
 * 协程内用 withMdcSuspend（suspend block 内可调 suspend 函数）。
 * 说明：MDC 为线程局部；调用方保证同线程或协程内传播（runBlocking(sorosIo) 单线程内天然成立）。
 */
object MdcSupport {

    const val JOB = "job"
    const val CODE = "code"
    const val PHASE = "phase"
    const val DATE_RANGE = "date_range"
    const val ATTEMPT = "attempt"

    /** 在指定 MDC 上下文中执行 block，结束后恢复原 MDC（值传 null 表示移除该键） */
    fun <T> withMdc(vararg entries: Pair<String, String?>, block: () -> T): T {
        val previous = entries.map { it.first to MDC.get(it.first) }
        entries.forEach { (k, v) -> if (v != null) MDC.put(k, v) else MDC.remove(k) }
        return try {
            block()
        } finally {
            previous.forEach { (k, v) -> if (v != null) MDC.put(k, v) else MDC.remove(k) }
        }
    }

    /** 协程内版本：block 为 suspend，可在 MDC 上下文中调 suspend 函数（§13.3 结构化日志） */
    suspend fun <T> withMdcSuspend(vararg entries: Pair<String, String?>, block: suspend () -> T): T {
        val previous = entries.map { it.first to MDC.get(it.first) }
        entries.forEach { (k, v) -> if (v != null) MDC.put(k, v) else MDC.remove(k) }
        return try {
            block()
        } finally {
            previous.forEach { (k, v) -> if (v != null) MDC.put(k, v) else MDC.remove(k) }
        }
    }
}
