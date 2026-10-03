package com.soros.v2.service.backfill

import com.soros.v2.config.BackfillProperties
import com.soros.v2.domain.BackfillStatus
import com.soros.v2.exception.BusinessException
import com.soros.v2.job.BackfillJob
import com.soros.v2.service.backfill.dto.BackfillProgress
import com.soros.v2.service.backfill.dto.BackfillRequest
import com.soros.v2.service.backfill.dto.BackfillStatusResponse
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * §六 BackfillService 实现（手动触发 + 内存态进度）。
 *
 * 设计定稿（2026-10-04）：
 * - 单实例单进程内存态，不落库——回填按 ON CONFLICT DO UPDATE 幂等，重启丢失状态等价于
 *   "未跑过"，重跑即断点续传（BackfillJob 内还有已覆盖代码跳过），无持久化检查点需求；
 * - 防重入：RUNNING 中二次 start() 抛 [BusinessException]（GlobalExceptionHandler → 422）；
 * - 后台执行收口于 sorosIo dispatcher（§13.1 coroutine dispatcher 收口），逐批 onProgress
 *   原子替换 [BackfillProgress]，GET /jobs/backfill/status 直接读内存态。
 */
@Service
class BackfillServiceImpl(
    private val job: BackfillJob,
    private val backfillProperties: BackfillProperties,
    @Qualifier("sorosIo") private val sorosIo: CoroutineDispatcher,
) : BackfillService {

    @Volatile
    private var currentStatus: BackfillStatus = BackfillStatus.IDLE

    @Volatile
    private var currentProgress: BackfillProgress? = null

    @Volatile
    private var errorMessage: String? = null

    override fun start(request: BackfillRequest): BackfillStatusResponse {
        if (currentStatus == BackfillStatus.RUNNING) {
            throw BusinessException("回填任务运行中，禁止重复触发（POST /jobs/backfill → 422）")
        }
        val from = request.startDate ?: backfillProperties.defaultStartDate
        val to = request.endDate ?: LocalDate.now()
        if (from.isAfter(to)) {
            throw BusinessException("回填区间非法：from($from) > to($to)")
        }
        currentStatus = BackfillStatus.RUNNING
        errorMessage = null
        currentProgress = BackfillProgress(startedAt = Instant.now())
        CoroutineScope(sorosIo).launch {
            try {
                job.run(from, to) { progress -> currentProgress = progress }
                currentProgress = currentProgress?.copy(finishedAt = Instant.now())
                currentStatus = BackfillStatus.COMPLETED
            } catch (e: Exception) {
                errorMessage = e.message
                currentProgress = currentProgress?.copy(finishedAt = Instant.now())
                currentStatus = BackfillStatus.FAILED
                // 异常已由 BackfillJob 内部消化（失败批进 failed，不置 FAILED）；
                // 此处捕获的是编排级未分类异常（如环境故障），进度终态 FAILED 供查询。
            }
        }
        return status()
    }

    override fun status(): BackfillStatusResponse =
        BackfillStatusResponse(
            status = currentStatus,
            progress = currentProgress,
            error = errorMessage,
        )
}
