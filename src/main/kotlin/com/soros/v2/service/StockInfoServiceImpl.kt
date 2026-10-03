package com.soros.v2.service

import com.soros.v2.domain.Board
import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockIndex
import com.soros.v2.entity.StockInfo
import com.soros.v2.repository.StockIndexRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.dto.BoardMembersRequest
import com.soros.v2.service.dto.BoardMembersSnapshot
import com.soros.v2.service.dto.StockListDto
import com.soros.v2.util.BenchmarkIndices
import java.time.LocalDate
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

    override suspend fun backfillIpoDates(): Int {
        val all = pythonClient.fetchStockList()
        var updated = 0
        for (stock in all) {
            val raw = stock.ipoDate ?: continue
            val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: continue
            val entity = stockInfoRepository.findByCode(stock.code) ?: continue
            if (entity.ipoDate != parsed) {
                entity.ipoDate = parsed
                stockInfoRepository.save(entity)
                updated++
            }
        }
        logger.info("[stock-info] ipo_date 回填完成：更新 {} 行（全量扫描 {} 只）", updated, all.size)
        return updated
    }

    override suspend fun refreshBoardSnapshot(boardType: BoardType): Int {
        val snapshot = pythonClient.fetchBoardMembersSnapshot(BoardMembersRequest(boardType.pythonName))
        val byCode = invertBoardMap(snapshot.boards)
        var updated = overwriteSnapshotMembers(boardType, byCode)
        when {
            snapshot.boards.isEmpty() ->
                logger.warn("[stock-info] 板块快照为空 boardType={}，跳过清空保留旧值（源未就绪/异常）", boardType)
            snapshot.degraded ->
                logger.warn("[stock-info] 板块快照不完整 boardType={}（任一板块拉取失败/降级），跳过清空保留旧值", boardType)
            else -> updated += clearRemovedMembers(boardType, byCode)
        }
        logger.info("[stock-info] 板块快照覆盖写完成 boardType={} boards={} updated={}", boardType, snapshot.boards.size, updated)
        return updated
    }

    /** {板块名:[codes]} 反转成 code→[板块名]（覆盖写升序落库） */
    private fun invertBoardMap(boards: Map<String, List<String>>): Map<String, List<String>> {
        val byCode = HashMap<String, MutableList<String>>()
        for ((boardName, codes) in boards) {
            for (code in codes) {
                byCode.getOrPut(code) { mutableListOf() }.add(boardName)
            }
        }
        return byCode
    }

    /** 覆盖写快照中出现的成分股（只更新已知行不新增；同快照幂等） */
    private fun overwriteSnapshotMembers(boardType: BoardType, byCode: Map<String, List<String>>): Int {
        var updated = 0
        for (chunk in byCode.keys.chunked(200)) {
            val byCodeMap = stockInfoRepository.findByCodeIn(chunk).associateBy { it.code }
            for ((code, boardNames) in chunk.map { it to (byCode[it] ?: emptyList()) }) {
                val entity = byCodeMap[code] ?: continue
                val snapshot = boardNames.sorted()
                val target = if (boardType == BoardType.INDUSTRY) entity.industry else entity.conceptBoards
                if (target != snapshot) {
                    if (boardType == BoardType.INDUSTRY) entity.industry = snapshot else entity.conceptBoards = snapshot
                    stockInfoRepository.save(entity)
                    updated++
                }
            }
        }
        return updated
    }

    /** 完整快照语义：DB 中不在快照内的行（除名股）清空对应数组（防陈旧归属残留） */
    private fun clearRemovedMembers(boardType: BoardType, byCode: Map<String, List<String>>): Int {
        var updated = 0
        for (entity in stockInfoRepository.findAll()) {
            if (entity.code in byCode) continue
            val isDirty = if (boardType == BoardType.INDUSTRY) !entity.industry.isNullOrEmpty()
            else !entity.conceptBoards.isNullOrEmpty()
            if (isDirty) {
                if (boardType == BoardType.INDUSTRY) entity.industry = emptyList() else entity.conceptBoards = emptyList()
                stockInfoRepository.save(entity)
                updated++
            }
        }
        return updated
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
        stock.ipoDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull()?.let { entity.ipoDate = it } }
        return stockInfoRepository.save(entity)
    }

    private fun buildSearchKey(code: String, name: String): String =
        "$code ${name.lowercase()}".trim()

    private companion object {
        /** 可采集市场板（§4.4：MAIN 主板/GEM 创业板/STAR 科创板；北交所/B 股不采集） */
        val COLLECTIBLE_BOARDS = setOf(Board.MAIN, Board.GEM, Board.STAR)
    }
}
