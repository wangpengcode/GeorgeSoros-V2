package com.soros.v2.service.strategy

import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.StrategyConfigRepository
import com.soros.v2.service.strategy.dto.BacktestCreateRequest
import com.soros.v2.service.strategy.dto.BacktestResultDto
import org.springframework.stereotype.Service

/**
 * §19.13.3 BacktestServiceImpl（a 期参数契约实现，回测引擎挂 b 期）。
 *
 * - is_dry 显式必填、无默认值兜底（G4 铁律）——缺失抛 BusinessException（不含"不存在"→ 422）；
 * - start_date ≤ end_date（区间非法 → 422）；
 * - strategy_name 存在性（找不到 → 消息含"不存在"→ 404）；
 * - 合法体落库占位：a 期返回占位主键 id（0），b 期引擎挂载后落 backtest_result 回填真实 id。
 */
@Service
class BacktestServiceImpl(
    private val strategyConfigRepository: StrategyConfigRepository,
) : BacktestService {

    override fun create(req: BacktestCreateRequest?): BacktestResultDto {
        val nonNullReq = req ?: throw BusinessException("请求体不能为空")
        val isDry = nonNullReq.isDry ?: throw BusinessException("is_dry 必须显式指定（试跑/正式）")
        if (nonNullReq.startDate.isAfter(nonNullReq.endDate)) {
            throw BusinessException("start_date 不能大于 end_date")
        }
        if (strategyConfigRepository.findByName(nonNullReq.strategyName) == null) {
            throw BusinessException("策略 ${nonNullReq.strategyName} 不存在")
        }
        return BacktestResultDto(
            id = PLACEHOLDER_ID,
            strategyName = nonNullReq.strategyName,
            configId = nonNullReq.configId,
            startDate = nonNullReq.startDate,
            endDate = nonNullReq.endDate,
            isDry = isDry,
        )
    }

    private companion object {
        /** a 期落库占位主键（b 期引擎挂载后回填真实 backtest_result.id） */
        const val PLACEHOLDER_ID: Long = 0L
    }
}
