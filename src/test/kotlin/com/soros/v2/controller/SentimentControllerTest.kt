package com.soros.v2.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.soros.v2.domain.CycleStatus
import com.soros.v2.domain.CycleType
import com.soros.v2.domain.PoolAction
import com.soros.v2.domain.PoolSide
import com.soros.v2.exception.BusinessException
import com.soros.v2.service.sentiment.DragonCycleService
import com.soros.v2.service.sentiment.SentimentService
import com.soros.v2.service.sentiment.dto.DragonCycleConfirmRequest
import com.soros.v2.service.sentiment.dto.DragonCycleItem
import com.soros.v2.service.sentiment.dto.DragonCycleListResponse
import com.soros.v2.service.sentiment.dto.SentimentConfirmRequest
import com.soros.v2.service.sentiment.dto.SentimentCycleRangeResponse
import com.soros.v2.service.sentiment.dto.SentimentCycleResponse
import com.soros.v2.service.sentiment.dto.SentimentListsRequest
import com.soros.v2.service.sentiment.dto.SentimentTermsResponse
import com.soros.v2.service.sentiment.dto.StockActionItem
import com.soros.v2.service.sentiment.dto.StockActionsResponse
import com.soros.v2.service.sentiment.dto.StockActionTimelineItem
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §4.9/§11.1 SentimentController 8 端点契约测试（直调 controller，Fake 服务，参考 ManualDataControllerTest）。
 *
 * 契约（类 KDoc / §4.9 / §17.5）：
 * - 8 端点请求 → 服务委托（date/from/to/code/status/limit 透传）+ 响应 DTO 回传；
 * - 对外 JSON 键 snake_case（DTO @JsonProperty 全库统一，过命名字典 §17.6）；
 * - 服务抛 BusinessException 向上传播（HTTP 422 映射由全局异常处理器负责，当前无 @RestControllerAdvice，
 *   Implementer 补全局处理器 + MockMvc 集成测试见调度器流程）。
 *
 * 服务层业务逻辑（confirm 人工值优先 / updateLists 非 ST 校验 + 留痕 / 重算 count）由 SentimentServiceImplTest 覆盖。
 */
class SentimentControllerTest {

    private class FakeSentimentService : SentimentService {
        var cycleResult: SentimentCycleResponse? = null
        var rangeResult: SentimentCycleRangeResponse = SentimentCycleRangeResponse(emptyList())
        var confirmResult: SentimentCycleResponse = buildCycleResponse()
        var listsResult: SentimentCycleResponse = buildCycleResponse()
        var termsResult: SentimentTermsResponse = SentimentTermsResponse(
            tradeDate = LocalDate.of(2026, 9, 30),
            stage = "发酵",
            bigCycle = 4,
            smallCycle = 3,
            dragonStatus = "RISING",
            dragonName = "测试龙",
            actions = listOf(StockActionItem("600000", "测试龙", "晋级", "连板延续")),
        )
        var actionsResult: StockActionsResponse = StockActionsResponse(
            code = "600000",
            actions = listOf(
                StockActionTimelineItem(
                    tradeDate = LocalDate.of(2026, 9, 30),
                    label = "晋级",
                    limitUpStreak = 6,
                    changePct = BigDecimal("9.98"),
                ),
            ),
        )
        var lastCycleDate: LocalDate? = null
        var lastRange: Pair<LocalDate, LocalDate>? = null
        var confirmCall: Pair<LocalDate, SentimentConfirmRequest>? = null
        var listsCall: Pair<LocalDate, SentimentListsRequest>? = null
        var lastTermsDate: LocalDate? = null
        var lastActionsCall: Triple<String, LocalDate, LocalDate>? = null
        var confirmError: Exception? = null

        override fun getCycle(tradeDate: LocalDate): SentimentCycleResponse? {
            lastCycleDate = tradeDate
            return cycleResult
        }

        override fun getRange(from: LocalDate, to: LocalDate): SentimentCycleRangeResponse {
            lastRange = from to to
            return rangeResult
        }

        override fun confirm(tradeDate: LocalDate, request: SentimentConfirmRequest): SentimentCycleResponse {
            confirmCall = tradeDate to request
            confirmError?.let { throw it }
            return confirmResult
        }

        override fun updateLists(tradeDate: LocalDate, request: SentimentListsRequest): SentimentCycleResponse {
            listsCall = tradeDate to request
            return listsResult
        }

        override fun getTerms(tradeDate: LocalDate): SentimentTermsResponse {
            lastTermsDate = tradeDate
            return termsResult
        }

        override fun getStockActions(code: String, from: LocalDate, to: LocalDate): StockActionsResponse {
            lastActionsCall = Triple(code, from, to)
            return actionsResult
        }
    }

    private class FakeDragonCycleService : DragonCycleService {
        var listResult: DragonCycleListResponse = DragonCycleListResponse(emptyList())
        var confirmResult: DragonCycleItem = buildDragonItem()
        var listStatus: CycleStatus? = null
        var listLimit: Int = 50
        var confirmId: Long? = null

        override fun list(status: CycleStatus?, limit: Int): DragonCycleListResponse {
            listStatus = status
            listLimit = limit
            return listResult
        }

        override fun confirm(id: Long, request: DragonCycleConfirmRequest): DragonCycleItem {
            confirmId = id
            return confirmResult
        }
    }

    private lateinit var sentiment: FakeSentimentService
    private lateinit var dragon: FakeDragonCycleService
    private lateinit var controller: SentimentController
    private lateinit var mapper: ObjectMapper

    companion object {
        private val day = LocalDate.of(2026, 9, 30)

        private fun buildCycleResponse() = SentimentCycleResponse(
            tradeDate = day,
            limitUpCount = 45,
            limitDownCount = 8,
            lianbanCount = 12,
            maxStreak = 6,
            dragonJson = listOf(
                com.soros.v2.service.sentiment.dto.DragonJsonItem("600000", "测试龙", 6, "MAIN", listOf("软件服务")),
            ),
            poolCount = 30,
            bigMeatCount = 9,
            bigFaceCount = 3,
            bigMeatList = listOf(
                com.soros.v2.service.sentiment.dto.PoolListItem("600000", "测试龙", BigDecimal("6.50"), 6, listOf("软件服务")),
            ),
            bigFaceList = emptyList(),
            followupJson = emptyList(),
            listsManualJson = emptyList(),
            leaderJson = null,
            collapseCount = 5,
            collapseList = emptyList(),
            reboundCount = 1,
            bigCycleSug = 4,
            smallCycleSug = 3,
            bigCycle = null,
            smallCycle = null,
            statusText = "发酵",
            dataCoverage = "FULL",
        )

        private fun buildDragonItem() = DragonCycleItem(
            id = 1L,
            code = "600000",
            startDate = day.minusDays(10),
            endDate = null,
            maxStreak = 6,
            rebreakCount = 0,
            suspendedDays = 0,
            suspendJson = null,
            cycleType = null,
            status = CycleStatus.RISING,
            brokenDate = null,
            note = null,
            createdAt = LocalDateTime.of(2026, 9, 20, 20, 30),
            updatedAt = LocalDateTime.of(2026, 9, 20, 20, 30),
        )
    }

    @BeforeEach
    fun setUp() {
        sentiment = FakeSentimentService()
        sentiment.cycleResult = buildCycleResponse()
        dragon = FakeDragonCycleService()
        controller = SentimentController(sentiment, dragon)
        // 生产侧 Spring Boot 自动装配包含 KotlinModule；测试手建 mapper 需显式注册，否则
        // Kotlin data class 的构造器 @JsonProperty 不生效（键回退为驼峰 getter 名）
        mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    }

    // ==================== GET /sentiment-cycle?date= 单日详情 ====================

    @Test
    fun `testGetCycle delegatesDateAndReturnsDto`() {
        // when
        val resp = controller.getCycle(day)

        // then
        assertEquals(day, sentiment.lastCycleDate, "date 参数透传")
        assertEquals(day, resp!!.tradeDate, "trade_date 回传")
        assertEquals(45, resp.limitUpCount, "limit_up_count 回传")
    }

    @Test
    fun `testGetCycle noRowReturnsNull`() {
        // given: 无行
        sentiment.cycleResult = null

        // when & then: 200 null body（§11.1 无行语义）
        assertNull(controller.getCycle(day), "无行返回 null")
    }

    // ==================== GET /sentiment-cycle/range 区间序列 ====================

    @Test
    fun `testGetRange delegatesFromToAndReturnsItems`() {
        // when
        val resp = controller.getRange(day.minusDays(5), day)

        // then
        assertEquals(day.minusDays(5) to day, sentiment.lastRange, "from/to 透传")
        assertTrue(resp.items.isEmpty(), "items 回传")
    }

    // ==================== PUT /sentiment-cycle/{date}/confirm ====================

    @Test
    fun `testConfirm delegatesDateAndRequest`() {
        // when
        val resp = controller.confirm(day, SentimentConfirmRequest(bigCycle = 3, smallCycle = 2, statusText = "主升"))

        // then: 请求透传（人工确认值优先展示由 Service 实现，见 SentimentServiceImplTest）
        assertEquals(day, sentiment.confirmCall!!.first, "date 透传")
        assertEquals(3, sentiment.confirmCall!!.second.bigCycle, "big_cycle 透传")
        assertEquals(2, sentiment.confirmCall!!.second.smallCycle, "small_cycle 透传")
        assertEquals("主升", sentiment.confirmCall!!.second.statusText, "status_text 透传")
        assertEquals(day, resp.tradeDate, "响应回传")
    }

    // ==================== PUT /sentiment-cycle/{date}/lists ====================

    @Test
    fun `testUpdateLists delegatesDateAndRequest`() {
        // when
        val resp = controller.updateLists(
            day,
            SentimentListsRequest(side = PoolSide.MEAT, action = PoolAction.ADD, code = "600000", reason = "手动补入"),
        )

        // then: side/action/code/reason 透传（非 ST 校验 + lists_manual_json 留痕 + 重算 count 由 Service 实现）
        assertEquals(day, sentiment.listsCall!!.first, "date 透传")
        assertEquals(PoolSide.MEAT, sentiment.listsCall!!.second.side, "side 透传")
        assertEquals(PoolAction.ADD, sentiment.listsCall!!.second.action, "action 透传")
        assertEquals("600000", sentiment.listsCall!!.second.code, "code 透传")
        assertEquals("手动补入", sentiment.listsCall!!.second.reason, "reason 透传")
        assertEquals(day, resp.tradeDate, "响应回传")
    }

    // ==================== GET /sentiment-cycle/{date}/terms ====================

    @Test
    fun `testGetTerms delegatesDateAndReturnsTerms`() {
        // when
        val resp = controller.getTerms(day)

        // then
        assertEquals(day, sentiment.lastTermsDate, "date 透传")
        assertEquals("发酵", resp.stage, "stage 回传")
        assertEquals(4, resp.bigCycle, "big_cycle 回传")
        assertEquals("RISING", resp.dragonStatus, "dragon_status 回传")
        assertEquals("晋级", resp.actions.first().label, "actions 回传")
    }

    // ==================== GET /stocks/{code}/actions ====================

    @Test
    fun `testGetStockActions delegatesCodeAndRange`() {
        // when
        val resp = controller.getStockActions("600000", day.minusDays(10), day)

        // then
        assertEquals(Triple("600000", day.minusDays(10), day), sentiment.lastActionsCall, "code/from/to 透传")
        assertEquals("600000", resp.code, "code 回传")
        assertEquals("晋级", resp.actions.first().label, "actions 回传")
    }

    // ==================== GET /dragon-cycle ====================

    @Test
    fun `testListDragonCycles defaults status null limit 50`() {
        // when: 无参
        val resp = controller.listDragonCycles(null, 50)

        // then
        assertNull(dragon.listStatus, "status 缺省 null（全部）")
        assertEquals(50, dragon.listLimit, "limit 缺省 50")
        assertTrue(resp.items.isEmpty(), "items 回传")
    }

    @Test
    fun `testListDragonCycles passes status and limit`() {
        // when
        val resp = controller.listDragonCycles(CycleStatus.DEAD, 10)

        // then
        assertEquals(CycleStatus.DEAD, dragon.listStatus, "status 透传")
        assertEquals(10, dragon.listLimit, "limit 透传")
        assertTrue(resp.items.isEmpty(), "items 回传")
    }

    // ==================== PUT /dragon-cycle/{id}/confirm ====================

    @Test
    fun `testConfirmDragonCycle delegatesIdAndRequest`() {
        // given: 服务确认后返回 cycle_type=BIG 的结果（确认人工改判回传）
        dragon.confirmResult = buildDragonItem().copy(cycleType = CycleType.BIG)

        // when
        val resp = controller.confirmDragonCycle(42L, DragonCycleConfirmRequest(cycleType = CycleType.BIG, note = "人工改判"))

        // then
        assertEquals(42L, dragon.confirmId, "id 透传")
        assertEquals(CycleType.BIG, resp.cycleType, "cycle_type 回传")
    }

    // ==================== snake_case 字段 ====================

    @Test
    fun `testSnakeCaseFields all response keys snake_case`() {
        // given: 单日响应（含全部主列）
        val json = mapper.writeValueAsString(controller.getCycle(day))

        // then: 命名字典 §17.6——对外键全 snake_case
        assertTrue(json.contains("\"trade_date\""), "trade_date")
        assertTrue(json.contains("\"limit_up_count\""), "limit_up_count")
        assertTrue(json.contains("\"limit_down_count\""), "limit_down_count")
        assertTrue(json.contains("\"lianban_count\""), "lianban_count")
        assertTrue(json.contains("\"max_streak\""), "max_streak")
        assertTrue(json.contains("\"dragon_json\""), "dragon_json")
        assertTrue(json.contains("\"pool_count\""), "pool_count")
        assertTrue(json.contains("\"big_meat_count\""), "big_meat_count")
        assertTrue(json.contains("\"big_face_count\""), "big_face_count")
        assertTrue(json.contains("\"big_meat_list\""), "big_meat_list")
        assertTrue(json.contains("\"collapse_count\""), "collapse_count")
        assertTrue(json.contains("\"rebound_count\""), "rebound_count")
        assertTrue(json.contains("\"big_cycle_sug\""), "big_cycle_sug")
        assertTrue(json.contains("\"small_cycle_sug\""), "small_cycle_sug")
        assertTrue(json.contains("\"status_text\""), "status_text")
        assertTrue(json.contains("\"data_coverage\""), "data_coverage")
        assertTrue(!json.contains("\"tradeDate\""), "不得出现驼峰 tradeDate")
    }

    @Test
    fun `testSnakeCaseFields bigMeatList inner keys`() {
        // given: 大肉名单内部键（§4.9/§17.6：change_pct/limit_up_streak，不用 pct/streak）
        val json = mapper.writeValueAsString(controller.getCycle(day))

        // then: 名单内部键 snake_case
        assertTrue(json.contains("\"change_pct\""), "big_meat_list 内部键 change_pct")
        assertTrue(json.contains("\"limit_up_streak\""), "big_meat_list 内部键 limit_up_streak")
    }

    @Test
    fun `testSnakeCaseFields requestKeys`() {
        // given: 确认请求序列化（输入键 snake_case）
        val json = mapper.writeValueAsString(SentimentConfirmRequest(bigCycle = 3, statusText = "主升"))

        // then
        assertTrue(json.contains("\"big_cycle\""), "请求键 big_cycle")
        assertTrue(json.contains("\"small_cycle\""), "请求键 small_cycle")
        assertTrue(json.contains("\"status_text\""), "请求键 status_text")
    }

    // ==================== 错误传播：BusinessException 向上 ====================

    @Test
    fun `testConfirm serviceBusinessExceptionPropagates`() {
        // given: 服务抛业务异常（值域不符/周期不存在等）
        sentiment.confirmError = BusinessException("周期不存在")

        // when & then: 控制器不吞，向上传播（HTTP 422/404 由全局异常处理器映射，当前无 @RestControllerAdvice）
        assertThrows(BusinessException::class.java) {
            controller.confirm(day, SentimentConfirmRequest(bigCycle = 3))
        }
    }

    @Test
    fun `testConfirmRequest bigCycleOutOfRangeRejectedAtDtoLevel`() {
        // given: 大周期人工值越界（1-6 之外，DTO init require）
        // when & then: 请求构造即拒绝（§11.1 值域校验，422 语义前置）
        assertThrows(IllegalArgumentException::class.java) {
            SentimentConfirmRequest(bigCycle = 7)
        }
    }
}
