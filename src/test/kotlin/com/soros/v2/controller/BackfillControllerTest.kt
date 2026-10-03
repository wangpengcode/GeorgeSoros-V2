package com.soros.v2.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.soros.v2.domain.BackfillStatus
import com.soros.v2.exception.BusinessException
import com.soros.v2.service.backfill.BackfillService
import com.soros.v2.service.backfill.dto.BackfillProgress
import com.soros.v2.service.backfill.dto.BackfillRequest
import com.soros.v2.service.backfill.dto.BackfillStatusResponse
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §六 BackfillController 契约测试（直调 controller，Fake BackfillService，参考 ManualDataControllerTest）。
 *
 * 契约（类 KDoc / §17.5 / §六 响应键区段）：
 * - POST /api/v1/jobs/backfill：请求透传（含 null body → 默认 BackfillRequest）；
 *   后台执行语义由 Service 负责（start 立即返回 RUNNING）；
 * - GET /api/v1/jobs/backfill/status：委托 Service，响应 DTO 回传（内存态）；
 * - RUNNING 中二次 POST → Service 抛 BusinessException 向上传播（GlobalExceptionHandler → 422）；
 * - start>end：DTO init 前置拦截（400 语义）；
 * - 对外 JSON 键 snake_case（命名字典 §六 响应键：total_codes/processed_codes/started_at/finished_at...）。
 */
class BackfillControllerTest {

    private class FakeBackfillService : BackfillService {
        var startResult: BackfillStatusResponse = BackfillStatusResponse(BackfillStatus.IDLE)
        var statusResult: BackfillStatusResponse = BackfillStatusResponse(BackfillStatus.IDLE)
        var lastStartRequest: BackfillRequest? = null
        var startError: Exception? = null

        override fun start(request: BackfillRequest): BackfillStatusResponse {
            lastStartRequest = request
            startError?.let { throw it }
            return startResult
        }

        override fun status(): BackfillStatusResponse = statusResult
    }

    private lateinit var fake: FakeBackfillService
    private lateinit var controller: BackfillController
    private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        fake = FakeBackfillService()
        controller = BackfillController(fake)
        // 生产侧 Spring Boot 自动装配含 KotlinModule；测试手建 mapper 需显式注册（§11.1 同款）
        mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    }

    // ==================== POST /api/v1/jobs/backfill：触发透传 ====================

    @Test
    fun `testStart delegatesRequestAndReturnsStatus`() {
        // given: Service 返回 RUNNING（后台执行）
        fake.startResult = BackfillStatusResponse(
            status = BackfillStatus.RUNNING,
            progress = BackfillProgress(startedAt = Instant.ofEpochMilli(1_000)),
        )

        // when
        val resp = controller.start(
            BackfillRequest(startDate = LocalDate.of(2021, 10, 1), endDate = LocalDate.of(2021, 10, 5)),
        )

        // then: 请求透传 + 响应回传
        assertEquals(BackfillRequest(LocalDate.of(2021, 10, 1), LocalDate.of(2021, 10, 5)), fake.lastStartRequest, "start_date/end_date 透传")
        assertEquals(BackfillStatus.RUNNING, resp.status, "响应 status=RUNNING")
        assertTrue(resp.progress?.startedAt != null, "响应 progress.started_at 非空")
    }

    @Test
    fun `testStart nullBodyFallsBackToDefaultRequest`() {
        // when: 无 body（required=false，V1 兼容风格）
        controller.start(null)

        // then: 缺省 BackfillRequest()（start/end 均 null → Service 取配置/今日）
        assertEquals(BackfillRequest(), fake.lastStartRequest, "null body → 默认请求")
    }

    @Test
    fun `testStart runningReentryBusinessExceptionPropagates`() {
        // given: RUNNING 中二次触发，Service 抛 BusinessException（422 由全局处理器映射）
        fake.startError = BusinessException("回填任务运行中，禁止重复触发（POST /jobs/backfill → 422）")

        // when & then: 控制器不吞，向上传播（HTTP 422 由 GlobalExceptionHandler 负责）
        assertThrows(BusinessException::class.java) {
            controller.start(BackfillRequest())
        }
    }

    @Test
    fun `testStart invalidRangeRejectedAtDtoLevel`() {
        // given: start_date 晚于 end_date → DTO init 拒绝（400 语义，请求构造即失败）
        assertThrows(IllegalArgumentException::class.java) {
            BackfillRequest(startDate = LocalDate.of(2026, 10, 5), endDate = LocalDate.of(2026, 10, 1))
        }
    }

    // ==================== GET /api/v1/jobs/backfill/status ====================

    @Test
    fun `testStatus delegatesAndReturnsMemoryState`() {
        // given: Service 内存态 COMPLETED（终态保留上次结果）
        fake.statusResult = BackfillStatusResponse(
            status = BackfillStatus.COMPLETED,
            progress = BackfillProgress(
                totalCodes = 5000,
                processedCodes = 5000,
                succeededCodes = 4980,
                failedCodes = 20,
                totalBatches = 100,
                processedBatches = 100,
                totalRows = 6_000_000L,
                processedRows = 6_000_000L,
                currentBatch = 100,
                startedAt = Instant.ofEpochMilli(1_000),
                finishedAt = Instant.ofEpochMilli(3_600_000),
            ),
        )

        // when
        val resp = controller.status()

        // then: 委托 Service，响应 DTO 回传
        assertEquals(BackfillStatus.COMPLETED, resp.status, "status=COMPLETED 回传")
        assertEquals(5000, resp.progress!!.totalCodes, "total_codes 回传")
        assertEquals(20, resp.progress!!.failedCodes, "failed_codes 回传")
        assertTrue(resp.progress!!.finishedAt != null, "finished_at 回传")
    }

    // ==================== snake_case 字段（命名字典 §六 响应键区段） ====================

    @Test
    fun `testSnakeCaseFields progressAndStatusKeys`() {
        // given: 带全字段进度的状态响应
        val resp = BackfillStatusResponse(
            status = BackfillStatus.RUNNING,
            progress = BackfillProgress(
                totalCodes = 5000,
                processedCodes = 100,
                succeededCodes = 90,
                failedCodes = 10,
                totalBatches = 100,
                processedBatches = 2,
                totalRows = 6_000_000L,
                processedRows = 1_000L,
                currentBatch = 2,
                startedAt = Instant.ofEpochMilli(1_000),
                finishedAt = null,
            ),
        )

        // when
        val json = mapper.writeValueAsString(resp)

        // then: 命名字典 §六——对外键全 snake_case
        assertTrue(json.contains("\"status\""), "status")
        assertTrue(json.contains("\"total_codes\""), "total_codes")
        assertTrue(json.contains("\"processed_codes\""), "processed_codes")
        assertTrue(json.contains("\"succeeded_codes\""), "succeeded_codes")
        assertTrue(json.contains("\"failed_codes\""), "failed_codes")
        assertTrue(json.contains("\"total_batches\""), "total_batches")
        assertTrue(json.contains("\"processed_batches\""), "processed_batches")
        assertTrue(json.contains("\"total_rows\""), "total_rows")
        assertTrue(json.contains("\"processed_rows\""), "processed_rows")
        assertTrue(json.contains("\"current_batch\""), "current_batch")
        assertTrue(json.contains("\"started_at\""), "started_at")
        assertTrue(json.contains("\"finished_at\""), "finished_at")
        assertTrue(json.contains("\"progress\""), "progress")
        assertTrue(!json.contains("totalCodes"), "不得出现驼峰 totalCodes")
        assertTrue(!json.contains("startedAt"), "不得出现驼峰 startedAt")
    }

    @Test
    fun `testSnakeCaseFields requestKeys`() {
        // given: 触发请求序列化（输入键 snake_case，§六 复用 §3 区间端点语义）
        val json = mapper.writeValueAsString(BackfillRequest(LocalDate.of(2021, 10, 1), null))

        // then
        assertTrue(json.contains("\"start_date\""), "请求键 start_date")
        assertTrue(json.contains("\"end_date\""), "请求键 end_date")
    }
}
