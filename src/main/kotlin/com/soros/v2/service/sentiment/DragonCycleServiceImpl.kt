package com.soros.v2.service.sentiment

import com.soros.v2.domain.CycleStatus
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.service.sentiment.dto.DragonCycleConfirmRequest
import com.soros.v2.service.sentiment.dto.DragonCycleItem
import com.soros.v2.service.sentiment.dto.DragonCycleListResponse
import com.soros.v2.service.sentiment.mapper.toDto
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * §4.9/§11.1 龙头生命周期服务。
 *
 * - list：findTopN（status 可选 + 数量上限，start_date DESC），limit 上限 50 缺省；
 * - confirm：人工改判 cycle_type/note（§4.9 状态机兜底），周期不存在 → BusinessException（404 语义）。
 */
@Service
class DragonCycleServiceImpl(
    private val dragonCycleRepository: DragonCycleRepository,
) : DragonCycleService {

    override fun list(status: CycleStatus?, limit: Int): DragonCycleListResponse {
        val safeLimit = limit.coerceAtLeast(1)
        val cycles = dragonCycleRepository.findTopN(status, PageRequest.of(0, safeLimit))
        return DragonCycleListResponse(cycles.map { it.toDto() })
    }

    override fun confirm(id: Long, request: DragonCycleConfirmRequest): DragonCycleItem {
        val cycle = dragonCycleRepository.findById(id).orElse(null)
            ?: throw BusinessException("龙头周期不存在：id=$id")
        request.cycleType?.let { cycle.cycleType = it }
        request.note?.let { cycle.note = it }
        dragonCycleRepository.save(cycle)
        return cycle.toDto()
    }
}
