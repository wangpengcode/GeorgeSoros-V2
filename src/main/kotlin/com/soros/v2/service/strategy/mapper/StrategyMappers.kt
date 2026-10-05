package com.soros.v2.service.strategy.mapper

import com.soros.v2.entity.StrategyConfig
import com.soros.v2.entity.StrategyConfigHistory
import com.soros.v2.service.strategy.dto.StrategyDetailDto
import com.soros.v2.service.strategy.dto.StrategyHistoryItemDto
import com.soros.v2.service.strategy.dto.StrategySummaryDto

/** Entity → 列表 DTO（§19.13.3 列表含 version/status/alert_enabled/note） */
internal fun StrategyConfig.toSummary(): StrategySummaryDto = StrategySummaryDto(
    id = id,
    name = name,
    version = version,
    status = status,
    alertEnabled = alertEnabled,
    note = note,
)

/** Entity → 详情 DTO（含 yaml 全文） */
internal fun StrategyConfig.toDetail(): StrategyDetailDto = StrategyDetailDto(
    id = id,
    name = name,
    yaml = yaml,
    version = version,
    status = status,
    alertEnabled = alertEnabled,
    note = note,
)

/** 历史快照 → history 项 DTO（G3 版本列表，升序由 repo 保证） */
internal fun StrategyConfigHistory.toHistoryItem(): StrategyHistoryItemDto = StrategyHistoryItemDto(
    version = version,
    yaml = yaml,
    createdAt = createdAt,
)
