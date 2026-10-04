package com.soros.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M1 脚手架冒烟测试（TestContainers PostgreSQL 16）：
 * 断言口径 = 2026-10-03 docker 权威校验（26 表 / 44 索引含 PK+UNIQUE 自动索引 / pg_trgm）。
 * Step6 增量：V4__stock_history_stage.sql 新增 UNLOGGED 中转表（无 id/无约束/无索引，COPY 两段式瞬态，
 * PLAN §六.1）→ 表数 26→27，索引仍 44（stage 不建索引）。
 * 2026-10-04 增量：V7__stock_history_gap_check.sql 新增停牌空段验证表（PLAN §19.8，回填重跑计划
 * verified-empty 语义）→ 表数 27→28，索引 45→46（PK(code,seg_from,seg_to) 自动索引）。
 */
@SpringBootTest
@Testcontainers
class SorosApplicationTests(
    @Autowired val jdbc: JdbcTemplate,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Test
    fun contextLoads() {
        // Spring 上下文 + Flyway + DataSource 全链装配成功即通过
    }

    @Test
    fun `flyway migrates 28 tables with 46 indexes`() {
        val tables = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema='public' AND table_type='BASE TABLE' " +
                "AND table_name <> 'flyway_schema_history'",
            Int::class.java,
        ) ?: fail("queryForObject 返回 null，异常状态")
        assertEquals(28, tables, "V1 建 26 张业务表 + V4 stock_history_stage + V7 stock_history_gap_check（PLAN §19.8）")

        val indexes = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='public' " +
                "AND tablename <> 'flyway_schema_history'",
            Int::class.java,
        ) ?: fail("queryForObject 返回 null，异常状态")
        assertEquals(46, indexes, "索引总数 46（V5 校准局部索引 +1、V7 gap_check 主键 +1；含主键/UNIQUE 自动索引；新增索引必须同步命名字典与 schema.sql）")

        val trgm = jdbc.queryForObject(
            "SELECT count(*) FROM pg_extension WHERE extname='pg_trgm'",
            Int::class.java,
        ) ?: fail("queryForObject 返回 null，异常状态")
        assertEquals(1, trgm, "stock_info.search_key 模糊搜索依赖 pg_trgm 扩展")
    }
}
