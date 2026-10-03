package com.soros.v2.service.manual.mapper

import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.service.manual.dto.StockIndexDto
import com.soros.v2.service.manual.dto.StockInfoDto

/**
 * §11.4 手动补数出参映射（Entity → DTO，字段过命名字典）。
 */

/** stock_info → 出参 DTO */
fun StockInfo.toDto(): StockInfoDto = StockInfoDto(
    code = code,
    name = name,
    market = market,
    board = board,
    isSt = isSt,
    delisted = delisted,
    ipoDate = ipoDate,
    industry = industry,
    conceptBoards = conceptBoards,
)

/** stock_index → 出参 DTO */
fun StockIndex.toDto(): StockIndexDto = StockIndexDto(
    code = code,
    name = name,
)
