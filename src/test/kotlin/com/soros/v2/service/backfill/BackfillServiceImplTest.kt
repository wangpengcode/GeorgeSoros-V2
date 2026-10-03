package com.soros.v2.service.backfill

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.BackfillStatus
import com.soros.v2.exception.BusinessException
import com.soros.v2.job.BackfillJob
import com.soros.v2.service.backfill.dto.BackfillRequest
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * §六 BackfillService 状态机契约测试（单实例单进程内存态；Mock BackfillJob，Mockito 默认
 * Answer 规避 suspend 方法 Continuation 匹配问题——已实证 unstubbed mock 的 suspend run 返回 null）。
 *
 * 契约（BackfillServiceImpl KDoc / §六 / 类 KDoc）：
 * - IDLE 初始态：status()=IDLE、progress=null、error=null；
 * - start() 同步置 RUNNING（progress.startedAt 非空），后台协程执行；
 * - 防重入：RUNNING 中二次 start() 抛 BusinessException（GlobalExceptionHandler → 422）；
 * - from>to 区间非法抛 BusinessException（422；DTO 级 start>end 由 BackfillRequest init 前置拦截，
 *   服务级校验覆盖 end_date=null 场景）；
 * - 完成：全部批次 + 派生列补算 + 情绪回放链跑完 → COMPLETED，finished_at 置位；
 * - 失败：编排级未分类异常 → FAILED，error 透出 + finished_at 置位（批内失败不置 FAILED）。
 */
class BackfillServiceImplTest {

    private fun anyBackfillJob(): BackfillJob = Mockito.mock(BackfillJob::class.java)

    private fun awaitNotRunning(service: BackfillService, maxMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + maxMs
        while (service.status().status == BackfillStatus.RUNNING) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("任务未在 ${maxMs}ms 内离开 RUNNING（状态机卡死）")
            }
            Thread.sleep(10)
        }
    }

    // ==================== IDLE 初始态 ====================

    @Test
    fun `testStatus initiallyIDLEWithNullProgressAndError`() {
        // given: 未触发
        val service = BackfillServiceImpl(anyBackfillJob(), BackfillProperties(), Dispatchers.IO)

        // when
        val resp = service.status()

        // then: IDLE + 无进度 + 无错误（内存态初始值）
        assertEquals(BackfillStatus.IDLE, resp.status, "初始 IDLE")
        assertNull(resp.progress, "初始 progress=null（IDLE 首次查询）")
        assertNull(resp.error, "初始 error=null")
    }

    // ==================== start() 同步置 RUNNING + 防重入 ====================

    @Test
    fun `testStart returnsRunningAndReentryThrowsBusinessException`() {
        // given: 后台 job 阻塞（保持 RUNNING，模拟真实执行中的回填）
        val latch = CountDownLatch(1)
        val blockingJob = Mockito.mock(BackfillJob::class.java, Answer<Any?> { latch.await(); null })
        val service = BackfillServiceImpl(blockingJob, BackfillProperties(), Dispatchers.IO)

        // when: 首次触发
        val resp = service.start(BackfillRequest())

        // then: 立即返回 RUNNING（后台协程执行中）+ started_at 置位
        assertEquals(BackfillStatus.RUNNING, resp.status, "start() 立即返回 RUNNING")
        assertNotNull(resp.progress?.startedAt, "progress.started_at 已置位")
        assertEquals(BackfillStatus.RUNNING, service.status().status, "内存态同步 RUNNING")

        // when & then: RUNNING 中二次触发 → BusinessException（GlobalExceptionHandler → 422 防重入）
        assertThrows(BusinessException::class.java) {
            service.start(BackfillRequest())
        }
        latch.countDown()
    }

    // ==================== from>to 区间非法（服务级校验） ====================

    @Test
    fun `testStart fromAfterToThrowsBusinessException`() {
        // given: start_date=2030-01-01 > 今日（end_date 缺省=今日），DTO init 不拦截（endDate=null）
        val service = BackfillServiceImpl(anyBackfillJob(), BackfillProperties(), Dispatchers.IO)

        // when & then: 服务级 from.isAfter(to) 校验 → BusinessException（422 语义）
        assertThrows(BusinessException::class.java) {
            service.start(BackfillRequest(startDate = LocalDate.of(2030, 1, 1)))
        }
        assertEquals(BackfillStatus.IDLE, service.status().status, "区间非法不置 RUNNING（状态保持 IDLE）")
    }

    @Test
    fun `testRequest startAfterEndRejectedAtDtoLevel`() {
        // given: start_date 与 end_date 均给且 start>end → DTO init 前置拦截（400 语义）
        assertThrows(IllegalArgumentException::class.java) {
            BackfillRequest(startDate = LocalDate.of(2026, 10, 5), endDate = LocalDate.of(2026, 10, 1))
        }
    }

    // ==================== IDLE → RUNNING → COMPLETED（finished_at 置位） ====================

    @Test
    fun `testStart completesSetsCompletedAndFinishedAt`() {
        // given: job.run 正常返回（unstubbed mock suspend 返回 null，service 忽略返回值）
        val service = BackfillServiceImpl(anyBackfillJob(), BackfillProperties(), Dispatchers.IO)

        // when: 触发
        service.start(BackfillRequest())
        awaitNotRunning(service)

        // then: COMPLETED + finished_at 置位 + 无错误（完成链已执行）
        val resp = service.status()
        assertEquals(BackfillStatus.COMPLETED, resp.status, "IDLE→RUNNING→COMPLETED 终态")
        assertNotNull(resp.progress?.finishedAt, "finished_at 置位（终态保留上次结果）")
        assertNull(resp.error, "COMPLETED 无 error")
    }

    // ==================== IDLE → RUNNING → FAILED（error 透出 + finished_at） ====================

    @Test
    fun `testStart failureSetsFailedWithErrorAndFinishedAt`() {
        // given: job.run 抛编排级未分类异常（默认 Answer 抛 RuntimeException）
        val throwingJob = Mockito.mock(BackfillJob::class.java, Answer<Any?> { throw RuntimeException("环境故障") })
        val service = BackfillServiceImpl(throwingJob, BackfillProperties(), Dispatchers.IO)

        // when
        service.start(BackfillRequest())
        awaitNotRunning(service)

        // then: FAILED + error 透出 + finished_at 置位（进度终态供查询）
        val resp = service.status()
        assertEquals(BackfillStatus.FAILED, resp.status, "未分类异常 → FAILED")
        assertEquals("环境故障", resp.error, "FAILED error 透出异常消息")
        assertNotNull(resp.progress?.finishedAt, "FAILED 同样置位 finished_at")
    }
}
