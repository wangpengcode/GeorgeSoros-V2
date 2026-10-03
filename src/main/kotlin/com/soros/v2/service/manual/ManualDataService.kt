package com.soros.v2.service.manual

import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.service.manual.dto.ManualStockInfoRequest
import java.time.LocalDate

/**
 * §11.4 ManualDataService（V1 webhook 兼容最小集 + 手动重跑）。
 *
 * - POST /history/daily：恒返 ok；code 给出时触发手动重跑（Python 拉取 → saveBatch）
 * - GET /history/max/date/{code}：增量锚点
 * - info 系列：stock_info / stock_index 单条 upsert + 全量列表
 * - ST 隔离铁律：手动补数入口 is_st=true 直接拒绝（[ManualStockInfoRequest.isSt]）
 */
interface ManualDataService {

    /** 该股最大交易日（增量锚点；无数据返回 null） */
    fun findMaxTradeDate(code: String): LocalDate?

    /** 手动重跑：按 code+区间从 Python 拉日 K 并入库；无该股/无数据返回 null */
    suspend fun replayDaily(code: String, startDate: LocalDate, endDate: LocalDate): SaveBatchResult?

    /** 单条 upsert stock_info（is_st=true 拒绝；board 校验 MAIN/GEM/STAR） */
    fun upsertStockInfo(request: ManualStockInfoRequest): StockInfo

    /** 全量 stock_info 列表 */
    fun listAllStockInfo(): List<StockInfo>

    /** 单条 upsert stock_index（code UNIQUE 幂等） */
    fun upsertStockIndex(code: String, name: String): StockIndex

    /** 全量 stock_index 列表 */
    fun listAllStockIndex(): List<StockIndex>
}
