package com.soros.v2.service.manual

import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.PythonDataServiceClient
import com.soros.v2.service.StockHistoryService
import com.soros.v2.service.dto.DailyBarsBatchRequest
import com.soros.v2.service.dto.SaveBatchResult
import com.soros.v2.service.manual.dto.ManualStockInfoRequest
import java.time.LocalDate
import org.springframework.stereotype.Service

/**
 * §11.4 ManualDataService 实现（V1 兼容最小集 + 手动重跑）。
 *
 * - replayDaily 走 §4.4 saveBatch 正道（涨停检测/漂移自愈/连板派生/IPO 守卫全链路，复用 DailyCollectJob 同款）；
 * - upsertStockInfo 拒 ST（ST 隔离铁律）+ board 值域校验；
 * - 失败不抛：手动重跑异常由 Controller 兜底（恒返 ok 语义不能因重跑失败变 4xx）。
 */
@Service
class ManualDataServiceImpl(
    private val pythonClient: PythonDataServiceClient,
    private val stockHistoryService: StockHistoryService,
    private val stockInfoRepository: StockInfoRepository,
    private val stockIndexRepository: StockIndexRepository,
) : ManualDataService {

    override fun findMaxTradeDate(code: String): LocalDate? = stockHistoryService.findMaxDate(code)

    override suspend fun replayDaily(code: String, startDate: LocalDate, endDate: LocalDate): SaveBatchResult? {
        val info = stockInfoRepository.findByCode(code) ?: return null
        rejectReplay(info)
        val response = pythonClient.fetchDailyBarsBatch(
            DailyBarsBatchRequest(listOf(code), startDate.toString(), endDate.toString(), "qfq"),
        )
        val result = response.results[code]
        if (result == null || result.data.isEmpty()) return null
        return stockHistoryService.saveBatch(
            code,
            result.data,
            DataSourceType.fromPython(result.source),
            Board.fromPython(info.board),
        )
    }

    override fun upsertStockInfo(request: ManualStockInfoRequest): StockInfo {
        if (request.isSt) {
            throw BusinessException("ST 隔离铁律：手动补数禁止录入 ST 股票（is_st 仅用于识别并排除）")
        }
        val boardName = request.board?.let { Board.fromPython(it).name } ?: Board.MAIN.name
        val entity = stockInfoRepository.findByCode(request.code) ?: StockInfo().apply { code = request.code }
        request.name?.let { entity.name = it }
        request.market?.let { entity.market = it }
        entity.board = boardName
        return stockInfoRepository.save(entity)
    }

    override fun listAllStockInfo(): List<StockInfo> = stockInfoRepository.findAll()

    /** ST/退市隔离铁律：手动重跑与 upsert 同口径，is_st/delisted 仅用于识别并排除 */
    private fun rejectReplay(info: StockInfo) {
        if (info.isSt) {
            throw BusinessException("ST 隔离铁律：手动重跑禁止 ST 股票（is_st 仅用于识别并排除）")
        }
        if (info.delisted) {
            throw BusinessException("退市股禁止手动重跑（delisted 缺失≠退市，人工确认才置 true）")
        }
    }

    override fun upsertStockIndex(code: String, name: String): StockIndex {
        val entity = stockIndexRepository.findByCode(code) ?: StockIndex().apply { this.code = code }
        entity.name = name
        return stockIndexRepository.save(entity)
    }

    override fun listAllStockIndex(): List<StockIndex> = stockIndexRepository.findAll()
}
