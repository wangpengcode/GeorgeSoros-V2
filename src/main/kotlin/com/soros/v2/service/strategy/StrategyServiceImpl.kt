package com.soros.v2.service.strategy

import com.soros.v2.domain.StrategyStatus
import com.soros.v2.entity.StrategyConfig
import com.soros.v2.entity.StrategyConfigHistory
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.StrategyConfigHistoryRepository
import com.soros.v2.repository.StrategyConfigRepository
import com.soros.v2.service.strategy.dto.StrategyCreateRequest
import com.soros.v2.service.strategy.dto.StrategyDetailDto
import com.soros.v2.service.strategy.dto.StrategyHistoryItemDto
import com.soros.v2.service.strategy.dto.StrategySummaryDto
import com.soros.v2.service.strategy.dto.StrategyUpdateRequest
import com.soros.v2.service.strategy.dto.StrategyYamlDto
import com.soros.v2.service.strategy.mapper.toDetail
import com.soros.v2.service.strategy.mapper.toHistoryItem
import com.soros.v2.service.strategy.mapper.toSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * §19.13.3 StrategyServiceImpl：策略 CRUD + 版本递增 + history 链 + G3 回滚。
 *
 * - create：结构化 JSON → 服务端生成 YAML，DRAFT 态 version=1，落 strategy_config + history v1 快照；
 * - update：version+1，新 yaml 落 config + history 新版本快照，历史链不断；
 * - rollback（G3）：目标版本 yaml 复制为新版本（当前 version+1）写回 config，同时落 history
 *   新版本号快照指向旧内容，历史链可无限回退；
 * - 异常语义：消息含"不存在" → 控制器侧 GlobalExceptionHandler 404；其余业务违规 → 422。
 */
@Service
class StrategyServiceImpl(
    private val configRepository: StrategyConfigRepository,
    private val historyRepository: StrategyConfigHistoryRepository,
) : StrategyService {

    override fun list(): List<StrategySummaryDto> =
        configRepository.findAll().map { it.toSummary() }

    @Transactional
    override fun create(req: StrategyCreateRequest?): StrategyDetailDto {
        val nonNullReq = req ?: throw BusinessException("请求体不能为空")
        if (nonNullReq.name.isBlank()) throw BusinessException("策略名不能为空")
        if (configRepository.existsByName(nonNullReq.name)) {
            throw BusinessException("策略名 ${nonNullReq.name} 已存在")
        }
        val yaml = YamlGenerator.generate(nonNullReq.name, nonNullReq.conditions)
        val saved = configRepository.save(
            StrategyConfig(
                name = nonNullReq.name,
                yaml = yaml,
                version = 1,
                status = StrategyStatus.DRAFT.name,
                alertEnabled = nonNullReq.alertEnabled,
                note = nonNullReq.note,
            ),
        )
        historyRepository.save(
            StrategyConfigHistory(configId = saved.id, yaml = yaml, version = 1),
        )
        return saved.toDetail()
    }

    @Transactional
    override fun update(id: Long, req: StrategyUpdateRequest?): StrategyDetailDto {
        val nonNullReq = req ?: throw BusinessException("请求体不能为空")
        val config = findConfig(id)
        val newVersion = config.version + 1
        val yaml = YamlGenerator.generate(config.name, nonNullReq.conditions)
        config.yaml = yaml
        config.version = newVersion
        config.alertEnabled = nonNullReq.alertEnabled
        config.note = nonNullReq.note
        val saved = configRepository.save(config)
        historyRepository.save(
            StrategyConfigHistory(configId = id, yaml = yaml, version = newVersion),
        )
        return saved.toDetail()
    }

    override fun getYaml(id: Long): StrategyYamlDto = StrategyYamlDto(findConfig(id).yaml)

    override fun history(id: Long): List<StrategyHistoryItemDto> {
        findConfig(id)
        return historyRepository.findByConfigIdOrderByVersionAsc(id).map { it.toHistoryItem() }
    }

    @Transactional
    override fun rollback(id: Long, version: Int): StrategyDetailDto {
        val config = findConfig(id)
        val target = historyRepository.findByConfigIdAndVersion(id, version)
            ?: throw BusinessException("版本 $version 不存在")
        val newVersion = config.version + 1
        config.yaml = target.yaml
        config.version = newVersion
        val saved = configRepository.save(config)
        historyRepository.save(
            StrategyConfigHistory(configId = id, yaml = target.yaml, version = newVersion),
        )
        return saved.toDetail()
    }

    private fun findConfig(id: Long): StrategyConfig =
        configRepository.findById(id).orElseThrow { BusinessException("策略 $id 不存在") }
}
