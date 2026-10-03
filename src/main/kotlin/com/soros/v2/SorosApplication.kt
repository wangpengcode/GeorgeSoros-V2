package com.soros.v2

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * GeorgeSoros-V2 主应用。
 * 设计权威：docs/PLAN.md；DDL 唯一 SoT：docs/design/schema.sql；命名字典：docs/design/naming-dictionary.md
 */
@SpringBootApplication
@EnableScheduling
class SorosApplication

fun main(args: Array<String>) {
    runApplication<SorosApplication>(*args)
}
