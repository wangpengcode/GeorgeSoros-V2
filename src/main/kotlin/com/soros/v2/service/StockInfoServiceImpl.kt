package com.soros.v2.service

import com.soros.v2.domain.Board
import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.util.BenchmarkIndices
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * StockInfoService 实现。
 *
 * - refreshStockList：拉取 → 过滤 isSt/delisted/北交所（83/87/43/920 前缀）
 *   → board=Board.fromPython → upsert stock_info（含 search_key）→ 返回有效列表（仅 MAIN/GEM/STAR）。
 *   search_key 暂以"代码+小写名称"构造（全拼/拼音首字母需 pinyin 库，留待 BoardCollectJob 增强）。
 * - saveBenchmarkIndices：BenchmarkIndices.INDICES upsert stock_index（幂等，code UNIQUE）。
 */
@Service
class StockInfoServiceImpl(
    private val stockInfoRepository: StockInfoRepository,
    private val stockIndexRepository: StockIndexRepository,
    private val pythonClient: PythonDataServiceClient,
) : StockInfoService {

    private val logger = LoggerFactory.getLogger(StockInfoServiceImpl::class.java)

    override suspend fun refreshStockList(): List<StockInfo> {
        val all = pythonClient.fetchStockList()
        val valid = all.filter { it.isCollectible() }
        logger.info("[stock-info] 拉取 {} 只，有效 {} 只（ST/退市/北交所已排除）", all.size, valid.size)
        return valid.map { upsert(it) }
    }

    override fun findByCode(code: String): StockInfo? =
        stockInfoRepository.findByCode(code)

    override fun saveBenchmarkIndices(): Int {
        for (index in BenchmarkIndices.INDICES) {
            val existing = stockIndexRepository.findByCode(index.code)
            if (existing == null) {
                stockIndexRepository.save(
                    StockIndex().apply {
                        code = index.code
                        name = index.name
                    },
                )
            } else if (existing.name != index.name) {
                existing.name = index.name
                stockIndexRepository.save(existing)
            }
        }
        return BenchmarkIndices.INDICES.size
    }

    /** ST/退市隔离铁律 + 北交所 83/87/43/920 前缀双保险过滤；仅 MAIN/GEM/STAR 入有效列表 */
    private fun StockListDto.isCollectible(): Boolean =
        !isSt && !delisted &&
            COLLECTIBLE_BOARDS.any { it.name == board } &&
            !code.startsWith("83") && !code.startsWith("87") &&
            !code.startsWith("43") && !code.startsWith("920")

    private fun upsert(stock: StockListDto): StockInfo {
        val board = Board.fromPython(stock.board).name
        val entity = stockInfoRepository.findByCode(stock.code) ?: StockInfo().apply { code = stock.code }
        entity.name = stock.name
        entity.market = stock.market
        entity.board = board
        entity.searchKey = buildSearchKey(stock.code, stock.name ?: "")
        return stockInfoRepository.save(entity)
    }

    private fun buildSearchKey(code: String, name: String): String =
        "$code ${name.lowercase()}".trim()

    private companion object {
        /** 可采集市场板（§4.4：MAIN 主板/GEM 创业板/STAR 科创板；北交所/B 股不采集） */
        val COLLECTIBLE_BOARDS = setOf(Board.MAIN, Board.GEM, Board.STAR)
    }
}
