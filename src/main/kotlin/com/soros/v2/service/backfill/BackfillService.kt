package com.soros.v2.service.backfill

import com.soros.v2.service.backfill.dto.BackfillRequest
import com.soros.v2.service.backfill.dto.BackfillStatusResponse

/**
 * §六 历史回填业务接口（手动触发 + 进度查询；PLAN Step 6）。
 *
 * - 触发走 POST /api/v1/jobs/backfill：后台协程执行 COPY 两段式回填，
 *   运行中二次触发抛 [com.soros.v2.exception.BusinessException]（→ 422 防重入）；
 * - 进度走 GET /api/v1/jobs/backfill/status：返回内存态 [BackfillStatusResponse]；
 * - 回填完成链（设计定稿）：全部批次合并 → 派生列补算 SQL → 3 股抽查对拍 →
 *   情绪回放（§13.5，失败不阻塞回填结果只告警）→ 钉钉 digest。
 */
interface BackfillService {

    /**
     * 触发回填（后台执行，立即返回当前状态）。
     *
     * @throws BusinessException 运行中二次触发（422）；from>to 区间非法（422）
     */
    fun start(request: BackfillRequest): BackfillStatusResponse

    /** 查询当前回填状态（内存态；IDLE=未跑过，终态保留上次结果） */
    fun status(): BackfillStatusResponse
}
