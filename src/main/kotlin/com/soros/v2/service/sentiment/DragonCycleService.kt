package com.soros.v2.service.sentiment

import com.soros.v2.domain.CycleStatus
import com.soros.v2.service.sentiment.dto.DragonCycleConfirmRequest
import com.soros.v2.service.sentiment.dto.DragonCycleItem
import com.soros.v2.service.sentiment.dto.DragonCycleListResponse

/**
 * §4.9/§11.1 龙头生命周期服务（查 dragon_cycle，网页时间轴数据源）。
 */
interface DragonCycleService {

    /** GET /api/v1/dragon-cycle?status=&limit= → 龙头生命周期列表（按 start_date DESC） */
    fun list(status: CycleStatus?, limit: Int): DragonCycleListResponse

    /** PUT /api/v1/dragon-cycle/{id}/confirm → 人工改判龙头周期大小/备注（§4.9 状态机兜底） */
    fun confirm(id: Long, request: DragonCycleConfirmRequest): DragonCycleItem
}
