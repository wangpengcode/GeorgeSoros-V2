package com.soros.v2.repository

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.domain.DataCoverage
import com.soros.v2.entity.DragonCycle
import com.soros.v2.entity.SentimentCycle
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §4.9 sentiment_cycle / dragon_cycle 仓储契约测试（@DataJpaTest + TestContainers PG16）。
 *
 * 覆盖：
 * - sentiment_cycle 全业务列（§4.9，除 id/created_at 审计列）+ JSONB 列（dragon_json/big_meat_list/
 *   big_face_list/followup_json/lists_manual_json/leader_json/collapse_list）落库读回 roundtrip
 * - trade_date UNIQUE / big_cycle_sug CHECK 1-6 约束（schema.sql DDL）
 * - V3 迁移后 data_coverage 列存在且接受 FULL/PARTIAL
 * - 业务键查询：findByTradeDate / findByTradeDateBetweenOrderByTradeDateAsc / existsByTradeDate
 * - dragon_cycle 14 列 roundtrip + suspend_json JSONB + uq_dragon_active 每股唯一进行中周期
 * - deleteByStartDateBetween（§13.5 回放删后重建） / deleteByTradeDateBetween（回放水位）
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SentimentCycleRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var sentimentCycleRepository: SentimentCycleRepository

    @Autowired
    private lateinit var dragonCycleRepository: DragonCycleRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    private val mapper = ObjectMapper()

    // ==================== 构造辅助 ====================

    private fun jsonArray(block: (ArrayNode) -> Unit): JsonNode {
        val arr = mapper.createArrayNode()
        block(arr)
        return arr
    }

    private fun jsonObj(block: ObjectNode.() -> Unit): ObjectNode {
        val obj = mapper.createObjectNode()
        obj.block()
        return obj
    }

    /** 全业务列填充的 sentiment_cycle（含全部 JSONB 列），满足 schema.sql CHECK 约束 */
    private fun fullCycle(
        tradeDate: LocalDate = LocalDate.of(2026, 9, 30),
        bigCycleSug: Short? = 4,
        smallCycleSug: Short? = 3,
    ) = SentimentCycle().apply {
        this.tradeDate = tradeDate
        this.limitUpCount = 45
        this.limitDownCount = 8
        this.lianbanCount = 12
        this.maxStreak = 6
        this.dragonJson = jsonArray {
            it.add(
                jsonObj {
                    put("code", "600000")
                    put("name", "测试龙")
                    put("limit_up_streak", 6)
                    put("board", "MAIN")
                    putArray("industry").add("软件服务")
                },
            )
        }
        this.poolCount = 30
        this.bigMeatCount = 9
        this.bigFaceCount = 3
        this.bigMeatList = jsonArray {
            it.add(
                jsonObj {
                    put("code", "600000")
                    put("name", "测试龙")
                    put("change_pct", 6.5)
                    put("limit_up_streak", 6)
                    putArray("industry").add("软件服务")
                },
            )
        }
        this.bigFaceList = jsonArray {
            it.add(
                jsonObj {
                    put("code", "300750")
                    put("name", "测试面")
                    put("change_pct", -6.5)
                    put("limit_up_streak", 2)
                    putArray("industry").add("电池")
                },
            )
        }
        this.followupJson = jsonArray {
            it.add(
                jsonObj {
                    put("code", "600000")
                    put("name", "测试龙")
                    put("src", "meat")
                    put("yest_pct", 6.5)
                    put("today_pct", 3.0)
                    put("result", "回落")
                },
            )
        }
        this.listsManualJson = jsonArray {
            it.add(
                jsonObj {
                    put("side", "MEAT")
                    put("action", "ADD")
                    put("code", "600000")
                    put("name", "测试龙")
                    put("reason", "手动补入")
                    put("at", "2026-09-30T20:00:00")
                },
            )
        }
        this.leaderJson = jsonArray {
            it.add(
                jsonObj {
                    put("code", "600000")
                    put("action", "晋级")
                },
            )
        }
        this.collapseCount = 5
        this.collapseList = jsonArray {
            it.add(
                jsonObj {
                    put("code", "000587")
                    put("name", "测试崩")
                    put("limit_down_streak", 2)
                    putArray("industry").add("ST板块")
                },
            )
        }
        this.reboundCount = 1
        this.bigCycleSug = bigCycleSug
        this.smallCycleSug = smallCycleSug
        this.bigCycle = null
        this.smallCycle = null
        this.statusText = "发酵"
        this.dataCoverage = DataCoverage.FULL
    }

    private fun dragon(
        code: String = "600000",
        startDate: LocalDate = LocalDate.of(2026, 9, 1),
        endDate: LocalDate? = null,
        maxStreak: Short = 6,
        status: CycleStatus = CycleStatus.RISING,
    ) = DragonCycle().apply {
        this.code = code
        this.startDate = startDate
        this.endDate = endDate
        this.maxStreak = maxStreak
        this.rebreakCount = 0
        this.suspendedDays = 0
        this.suspendJson = null
        this.cycleType = if (endDate == null) null else CycleType.SMALL
        this.status = status
        this.brokenDate = null
        this.note = null
    }

    // ==================== sentiment_cycle 全列 + JSONB roundtrip ====================

    @Test
    fun `testSentimentCycle fullColumnRoundtrip preserves all business columns`() {
        // given: 全业务列填充（含 7 个 JSONB 列）
        val day = LocalDate.of(2026, 9, 30)
        sentimentCycleRepository.save(fullCycle(day))
        entityManager.flush()
        entityManager.clear()

        // when: 业务键读回
        val read = sentimentCycleRepository.findByTradeDate(day)

        // then: 标量列逐字段对齐
        assertNotNull(read, "trade_date 读回")
        assertEquals(day, read!!.tradeDate, "trade_date")
        assertEquals(45, read.limitUpCount, "limit_up_count")
        assertEquals(8, read.limitDownCount, "limit_down_count")
        assertEquals(12, read.lianbanCount, "lianban_count")
        assertEquals(6, read.maxStreak, "max_streak")
        assertEquals(30, read.poolCount, "pool_count")
        assertEquals(9, read.bigMeatCount, "big_meat_count")
        assertEquals(3, read.bigFaceCount, "big_face_count")
        assertEquals(5, read.collapseCount, "collapse_count")
        assertEquals(1, read.reboundCount, "rebound_count")
        assertEquals(4, read.bigCycleSug, "big_cycle_sug")
        assertEquals(3, read.smallCycleSug, "small_cycle_sug")
        assertNull(read.bigCycle, "big_cycle 未确认 null")
        assertNull(read.smallCycle, "small_cycle 未确认 null")
        assertEquals("发酵", read.statusText, "status_text")
        assertEquals(DataCoverage.FULL, read.dataCoverage, "data_coverage")
    }

    @Test
    fun `testSentimentCycle jsonbColumnsRoundtrip preserve array nodes`() {
        // given: 全 JSONB 列填充
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        entityManager.flush()
        entityManager.clear()

        // when
        val read = sentimentCycleRepository.findByTradeDate(LocalDate.of(2026, 9, 30))

        // then: 每个 JSONB 列都非 null 且内容正确（§4.9 内部键过命名字典）
        assertNotNull(read!!.dragonJson, "dragon_json")
        assertEquals("600000", read.dragonJson!![0].get("code").asText(), "dragon_json[0].code")
        assertEquals(6, read.dragonJson!![0].get("limit_up_streak").asInt(), "dragon_json[0].limit_up_streak")
        assertNotNull(read.bigMeatList, "big_meat_list")
        assertEquals(6.5, read.bigMeatList!![0].get("change_pct").asDouble(), 0.0001, "big_meat_list[0].change_pct")
        assertNotNull(read.bigFaceList, "big_face_list")
        assertEquals(-6.5, read.bigFaceList!![0].get("change_pct").asDouble(), 0.0001, "big_face_list[0].change_pct")
        assertNotNull(read.followupJson, "followup_json")
        assertEquals("回落", read.followupJson!![0].get("result").asText(), "followup_json[0].result")
        assertNotNull(read.listsManualJson, "lists_manual_json")
        assertEquals("MEAT", read.listsManualJson!![0].get("side").asText(), "lists_manual_json[0].side")
        assertEquals("ADD", read.listsManualJson!![0].get("action").asText(), "lists_manual_json[0].action")
        assertNotNull(read.leaderJson, "leader_json")
        assertEquals("晋级", read.leaderJson!![0].get("action").asText(), "leader_json[0].action")
        assertNotNull(read.collapseList, "collapse_list")
        assertEquals(2, read.collapseList!![0].get("limit_down_streak").asInt(), "collapse_list[0].limit_down_streak")
    }

    @Test
    fun `testSentimentCycle v3DataCoverageColumnAcceptsFullAndPartial`() {
        // given: V3 迁移后 data_coverage 列存在（FULL + PARTIAL 各落一行）
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 29)).apply { dataCoverage = DataCoverage.PARTIAL })
        entityManager.flush()
        entityManager.clear()

        // when: 区间查询（§13.5 回放水位依赖升序序列）
        val rows = sentimentCycleRepository.findByTradeDateBetweenOrderByTradeDateAsc(
            LocalDate.of(2026, 9, 29),
            LocalDate.of(2026, 9, 30),
        )

        // then: 两行都读回，覆盖标记各自保留
        assertEquals(2, rows.size, "区间两行")
        assertEquals(LocalDate.of(2026, 9, 29), rows.first().tradeDate, "升序第一行=9-29")
        assertEquals(DataCoverage.PARTIAL, rows.first().dataCoverage, "PARTIAL 行读回")
        assertEquals(DataCoverage.FULL, rows.last().dataCoverage, "FULL 行读回")
    }

    // ==================== 约束：trade_date UNIQUE / big_cycle_sug CHECK ====================

    @Test
    fun `testSentimentCycle duplicateTradeDateViolatesUnique`() {
        // given: 已存在 9-30 行
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        entityManager.flush()

        // when & then: 同 trade_date 再插一行 → save 即触发 INSERT（IDENTITY 生成）
        //  UNIQUE 约束拒绝重复日（§4.9 每日一行的硬约束）
        assertThrows(DataIntegrityViolationException::class.java) {
            sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        }
    }

    @Test
    fun `testSentimentCycle bigCycleSugOutOfRangeViolatesCheck`() {
        // given: big_cycle_sug=7 越界（schema.sql CHECK BETWEEN 1 AND 6）
        // when & then: save 即触发 INSERT（IDENTITY 生成）→ CHECK 约束拒绝
        assertThrows(DataIntegrityViolationException::class.java) {
            sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30), bigCycleSug = 7))
        }
    }

    // ==================== 业务键查询 ====================

    @Test
    fun `testExistsByTradeDate returnsPresence`() {
        // given: 9-30 行存在
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        entityManager.flush()
        entityManager.clear()

        // then: 21:30 兜底 cron 幂等判重（§13.4）
        assertTrue(sentimentCycleRepository.existsByTradeDate(LocalDate.of(2026, 9, 30)), "已有今日行=true")
        assertTrue(!sentimentCycleRepository.existsByTradeDate(LocalDate.of(2026, 10, 1)), "明日行=false")
    }

    @Test
    fun `testFindMinTradeDateInRange returnsEarliestRow`() {
        // given: 9-29 / 9-30 两行
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 29)))
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        entityManager.flush()
        entityManager.clear()

        // when: 回放水位查询（§13.5）
        val min = sentimentCycleRepository.findMinTradeDateInRange(
            LocalDate.of(2026, 9, 29),
            LocalDate.of(2026, 9, 30),
        )

        // then
        assertEquals(LocalDate.of(2026, 9, 29), min, "区间最小交易日=9-29")
    }

    @Test
    fun `testDeleteByTradeDateBetween deletesRangeRows`() {
        // given: 区间内 9-29/9-30 + 区间外 9-28
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 28)))
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 29)))
        sentimentCycleRepository.save(fullCycle(LocalDate.of(2026, 9, 30)))
        entityManager.flush()
        entityManager.clear()

        // when: §13.5 回放前删区间行（重放语义）
        sentimentCycleRepository.deleteByTradeDateBetween(
            LocalDate.of(2026, 9, 29),
            LocalDate.of(2026, 9, 30),
        )
        entityManager.flush()
        entityManager.clear()

        // then: 区间内两行删除，区间外保留
        assertTrue(!sentimentCycleRepository.existsByTradeDate(LocalDate.of(2026, 9, 29)), "9-29 已删")
        assertTrue(!sentimentCycleRepository.existsByTradeDate(LocalDate.of(2026, 9, 30)), "9-30 已删")
        assertTrue(sentimentCycleRepository.existsByTradeDate(LocalDate.of(2026, 9, 28)), "9-28 保留")
    }

    // ==================== dragon_cycle 14 列 + JSONB + uq_dragon_active ====================

    @Test
    fun `testDragonCycle fullColumnRoundtrip`() {
        // given: 进行中周期（含 suspend_json 区间）
        val start = LocalDate.of(2026, 9, 1)
        val dc = dragon("600000", start).apply {
            this.suspendJson = jsonArray {
                it.add(
                    jsonObj {
                        put("from", "2026-09-05")
                        put("to", "2026-09-08")
                    },
                )
            }
            this.note = "总龙头"
        }
        dragonCycleRepository.save(dc)
        entityManager.flush()
        entityManager.clear()

        // when
        val read = dragonCycleRepository.findByCodeAndEndDateIsNull("600000")

        // then: 逐字段对齐（§4.9 状态机锚）
        assertNotNull(read, "uq_dragon_active 进行中周期读回")
        assertEquals("600000", read!!.code, "code")
        assertEquals(start, read.startDate, "start_date 上位日")
        assertNull(read.endDate, "end_date 进行中 null")
        assertEquals(6, read.maxStreak, "max_streak")
        assertEquals(0, read.rebreakCount, "rebreak_count")
        assertEquals(0, read.suspendedDays, "suspended_days")
        assertNotNull(read.suspendJson, "suspend_json")
        assertEquals("2026-09-05", read.suspendJson!![0].get("from").asText(), "suspend_json[0].from")
        assertNull(read.cycleType, "cycle_type 进行中未定性 null")
        assertEquals(CycleStatus.RISING, read.status, "status RISING")
        assertNull(read.brokenDate, "broken_date null")
        assertEquals("总龙头", read.note, "note")
    }

    @Test
    fun `testUqDragonActive rejectsSecondActiveCycleForSameCode`() {
        // given: 600000 进行中周期
        dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 9, 1)))
        entityManager.flush()

        // when & then: 同 code 再开一条进行中周期（事件触发+兜底 cron 双跑防重复插行，§17.2 I6）
        //  save 即触发 INSERT（IDENTITY 生成）→ 部分唯一索引 uq_dragon_active 拒绝
        assertThrows(DataIntegrityViolationException::class.java) {
            dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 9, 10)))
        }
    }

    @Test
    fun `testUqDragonActive allowsDifferentCodesAndClosedCycleSameCode`() {
        // given: 600000 进行中 + 600036 进行中（不同 code）+ 600000 已结束（end_date 非 null）
        dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 9, 1)))
        dragonCycleRepository.save(dragon("600036", LocalDate.of(2026, 9, 2)))
        dragonCycleRepository.save(
            dragon("600000", LocalDate.of(2026, 8, 1), endDate = LocalDate.of(2026, 8, 20)),
        )
        entityManager.flush()

        // then: 全部落库（不同 code 可并行；同 code 已结束周期不占部分唯一）
        assertEquals(3, dragonCycleRepository.count(), "3 条全落库")
    }

    @Test
    fun `testFindTopByEndDateIsNullOrderByMaxStreakDescStartDateAsc returnsHighestActive`() {
        // given: 两条进行中周期，600036 max_streak=7 > 600000 max_streak=6
        dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 9, 1), maxStreak = 6))
        dragonCycleRepository.save(dragon("600036", LocalDate.of(2026, 9, 2), maxStreak = 7))
        dragonCycleRepository.save(dragon("600050", LocalDate.of(2026, 8, 1), maxStreak = 5))
        entityManager.flush()
        entityManager.clear()

        // when: BOOT 首日龙头候选（§4.9 step1 上位）
        val top = dragonCycleRepository.findTopByEndDateIsNullOrderByMaxStreakDescStartDateAsc()

        // then: 最高板者胜出
        assertEquals("600036", top!!.code, "进行中周期最高板=600036")
    }

    @Test
    fun `testFindByStatusOrderByStartDateDesc filtersByStatus`() {
        // given: 2 进行中 + 1 DEAD
        dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 9, 1)))
        dragonCycleRepository.save(dragon("600036", LocalDate.of(2026, 9, 2)))
        dragonCycleRepository.save(
            dragon("600050", LocalDate.of(2026, 8, 1), endDate = LocalDate.of(2026, 8, 20), status = CycleStatus.DEAD),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 网页时间轴 DEAD 过滤
        val dead = dragonCycleRepository.findByStatusOrderByStartDateDesc(CycleStatus.DEAD)

        // then
        assertEquals(1, dead.size, "仅 1 条 DEAD")
        assertEquals("600050", dead.first().code, "DEAD=600050")
    }

    @Test
    fun `testFindTopByCodeAndEndDateIsNotNullOrderByEndDateDesc returnsLatestClosed`() {
        // given: 600000 两条已结束周期（8 月 + 9 月）
        dragonCycleRepository.save(
            dragon("600000", LocalDate.of(2026, 8, 1), endDate = LocalDate.of(2026, 8, 20), status = CycleStatus.DEAD),
        )
        dragonCycleRepository.save(
            dragon("600000", LocalDate.of(2026, 9, 1), endDate = LocalDate.of(2026, 9, 20), status = CycleStatus.DEAD),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 上位判定前查旧龙头（§4.9 step1 参照）
        val latest = dragonCycleRepository.findTopByCodeAndEndDateIsNotNullOrderByEndDateDesc("600000")

        // then: 最近结束的周期
        assertEquals(LocalDate.of(2026, 9, 20), latest!!.endDate, "最近结束周期 9-20")
    }

    @Test
    fun `testDeleteByStartDateBetween deletesRangeCycles`() {
        // given: start_date 区间内 9-01/9-02 + 区间外 8-01
        dragonCycleRepository.save(dragon("600000", LocalDate.of(2026, 8, 1)))
        dragonCycleRepository.save(dragon("600036", LocalDate.of(2026, 9, 1)))
        dragonCycleRepository.save(dragon("600050", LocalDate.of(2026, 9, 2)))
        entityManager.flush()
        entityManager.clear()

        // when: §13.5 回放删后重建
        dragonCycleRepository.deleteByStartDateBetween(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 2),
        )
        entityManager.flush()
        entityManager.clear()

        // then
        assertEquals(1, dragonCycleRepository.count(), "仅 8-01 保留")
        assertNull(dragonCycleRepository.findByCodeAndEndDateIsNull("600036"), "9-01 已删")
        assertNull(dragonCycleRepository.findByCodeAndEndDateIsNull("600050"), "9-02 已删")
        assertNotNull(dragonCycleRepository.findByCodeAndEndDateIsNull("600000"), "8-01 保留")
    }

    @Test
    fun `testFindMinStartDateInRange returnsEarliestStart`() {
        // given: start_date 9-01/9-02
        dragonCycleRepository.save(dragon("600036", LocalDate.of(2026, 9, 1)))
        dragonCycleRepository.save(dragon("600050", LocalDate.of(2026, 9, 2)))
        entityManager.flush()
        entityManager.clear()

        // when: 回放水位（§13.5 dragon_cycle 重建起点）
        val min = dragonCycleRepository.findMinStartDateInRange(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 2),
        )

        // then
        assertEquals(LocalDate.of(2026, 9, 1), min, "区间最早上位日=9-01")
    }
}
