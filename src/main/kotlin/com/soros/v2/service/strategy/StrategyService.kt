package com.soros.v2.service.strategy

import com.soros.v2.service.strategy.dto.StrategyCreateRequest
import com.soros.v2.service.strategy.dto.StrategyDetailDto
import com.soros.v2.service.strategy.dto.StrategyHistoryItemDto
import com.soros.v2.service.strategy.dto.StrategySummaryDto
import com.soros.v2.service.strategy.dto.StrategyUpdateRequest
import com.soros.v2.service.strategy.dto.StrategyYamlDto

/**
 * §19.13.3 策略控制台 a 期 CRUD + YAML 契约 + G3 回滚。
 */
interface StrategyService {

    /** 策略列表（含 version/status/alert_enabled/note） */
    fun list(): List<StrategySummaryDto>

    /** 新建（结构化 JSON → 服务端生成 YAML；DRAFT 态 version=1，落 config + history v1 快照） */
    fun create(req: StrategyCreateRequest?): StrategyDetailDto

    /** 修改（version+1，落 history 新版本快照，历史链不断） */
    fun update(id: Long, req: StrategyUpdateRequest?): StrategyDetailDto

    /** 导出当前版本 YAML 全文 */
    fun getYaml(id: Long): StrategyYamlDto

    /** 版本列表（升序，G3） */
    fun history(id: Long): List<StrategyHistoryItemDto>

    /** 回滚（G3）：目标版本 yaml 复制为新版本（version+1）写回 config + 落 history 新版本快照指向旧内容 */
    fun rollback(id: Long, version: Int): StrategyDetailDto
}
