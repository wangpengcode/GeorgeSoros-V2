package com.soros.v2.controller

import com.soros.v2.service.strategy.StrategyService
import com.soros.v2.service.strategy.dto.StrategyCreateRequest
import com.soros.v2.service.strategy.dto.StrategyDetailDto
import com.soros.v2.service.strategy.dto.StrategyHistoryItemDto
import com.soros.v2.service.strategy.dto.StrategySummaryDto
import com.soros.v2.service.strategy.dto.StrategyUpdateRequest
import com.soros.v2.service.strategy.dto.StrategyYamlDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §19.13.3 StrategyController：策略控制台 a 期端点契约 + G3 回滚。
 *
 * 端点契约（§19.13.3 a 期 + G3 定稿）：
 * - GET  /api/v1/strategies → 列表（含 version/status/alert_enabled/note）；
 * - POST /api/v1/strategies → 新建（结构化 JSON，服务端生成 YAML；DRAFT 态，version=1）；
 * - PUT  /api/v1/strategies/{id} → 修改（落 strategy_config_history，version+1）；
 * - GET  /api/v1/strategies/{id}/yaml → 导出（yaml 键）；
 * - GET  /api/v1/strategies/{id}/history → 版本列表（G3）；
 * - POST /api/v1/strategies/{id}/rollback?version= → 回滚（G3）；
 * - 404：id/version 不存在 → BusinessException 含"不存在"；400：body 缺必填 / 缺 version 参数。
 */
@RestController
@RequestMapping("/api/v1")
class StrategyController(
    private val strategyService: StrategyService,
) {

    /** GET /api/v1/strategies → 策略列表 */
    @GetMapping("/strategies")
    fun list(): List<StrategySummaryDto> = strategyService.list()

    /** POST /api/v1/strategies → 新建（DRAFT 态 version=1，服务端生成 YAML） */
    @PostMapping("/strategies")
    fun create(@RequestBody req: StrategyCreateRequest): StrategyDetailDto = strategyService.create(req)

    /** PUT /api/v1/strategies/{id} → 修改（version+1 + history 快照） */
    @PutMapping("/strategies/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody req: StrategyUpdateRequest,
    ): StrategyDetailDto = strategyService.update(id, req)

    /** GET /api/v1/strategies/{id}/yaml → 导出当前版本 YAML 全文 */
    @GetMapping("/strategies/{id}/yaml")
    fun getYaml(@PathVariable id: Long): StrategyYamlDto = strategyService.getYaml(id)

    /** GET /api/v1/strategies/{id}/history → 版本列表（升序，G3） */
    @GetMapping("/strategies/{id}/history")
    fun history(@PathVariable id: Long): List<StrategyHistoryItemDto> = strategyService.history(id)

    /** POST /api/v1/strategies/{id}/rollback?version= → 回滚（G3：目标版本 yaml 复制为新版本） */
    @PostMapping("/strategies/{id}/rollback")
    fun rollback(
        @PathVariable id: Long,
        @RequestParam("version") version: Int,
    ): StrategyDetailDto = strategyService.rollback(id, version)
}
