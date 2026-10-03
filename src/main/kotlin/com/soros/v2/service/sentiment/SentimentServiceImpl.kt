package com.soros.v2.service.sentiment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.soros.v2.domain.PoolAction
import com.soros.v2.domain.PoolSide
import com.soros.v2.entity.StockHistory
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.DragonCycleRepository
import com.soros.v2.repository.SentimentCycleRepository
import com.soros.v2.repository.StockHistoryRepository
import com.soros.v2.repository.StockInfoRepository
import com.soros.v2.service.sentiment.dto.SentimentConfirmRequest
import com.soros.v2.service.sentiment.dto.SentimentCycleRangeResponse
import com.soros.v2.service.sentiment.dto.SentimentCycleResponse
import com.soros.v2.service.sentiment.dto.SentimentListsRequest
import com.soros.v2.service.sentiment.dto.SentimentTermsResponse
import com.soros.v2.service.sentiment.dto.StockActionTimelineItem
import com.soros.v2.service.sentiment.dto.StockActionsResponse
import com.soros.v2.service.sentiment.mapper.toCollapseListItems
import com.soros.v2.service.sentiment.mapper.toDto
import com.soros.v2.service.sentiment.mapper.toManualListItems
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.stereotype.Service

/**
 * §4.9/§11.1 情绪周期查询与人工确认服务。
 *
 * 契约（类 KDoc / §4.9 / §11.1）：
 * - getCycle/getRange：查 sentiment_cycle，Entity → DTO（JSONB 数组解析为显式 DTO，过命名字典）；
 * - confirm：人工确认值优先于建议值展示（big_cycle 覆盖 big_cycle_sug 展示；建议值保留对照），
 *   周期不存在 → BusinessException（404 语义）；
 * - updateLists：非 ST 校验（stock_info.is_st=true 直接 BusinessException，ST 隔离铁律），
 *   增删后 lists_manual_json 留痕 {side,action,code,name,reason,at} + 重算 big_meat_count/big_face_count；
 * - getTerms：SentimentClassifier 现算（与 Job 落库同实现，口径永不漂移）；
 * - getStockActions：个股动作标签时间线（labelFor 与 buildActions 共用，单点判定）。
 */
@Service
class SentimentServiceImpl(
    private val sentimentCycleRepository: SentimentCycleRepository,
    private val stockInfoRepository: StockInfoRepository,
    private val stockHistoryRepository: StockHistoryRepository,
    private val dragonCycleRepository: DragonCycleRepository,
    private val classifier: SentimentClassifier,
) : SentimentService {

    override fun getCycle(tradeDate: LocalDate): SentimentCycleResponse? =
        sentimentCycleRepository.findByTradeDate(tradeDate)?.toDto()

    override fun getRange(from: LocalDate, to: LocalDate): SentimentCycleRangeResponse =
        SentimentCycleRangeResponse(
            sentimentCycleRepository.findByTradeDateBetweenOrderByTradeDateAsc(from, to).map { it.toDto() },
        )

    override fun confirm(tradeDate: LocalDate, request: SentimentConfirmRequest): SentimentCycleResponse {
        val row = requireRow(tradeDate)
        request.bigCycle?.let { row.bigCycle = it.toShort() }
        request.smallCycle?.let { row.smallCycle = it.toShort() }
        request.statusText?.let { row.statusText = it }
        sentimentCycleRepository.save(row)
        return row.toDto()
    }

    override fun updateLists(tradeDate: LocalDate, request: SentimentListsRequest): SentimentCycleResponse {
        val row = requireRow(tradeDate)
        val info = requireNonSt(request.code)
        val sideList = sideListOf(row, request.side)
        when (request.action) {
            PoolAction.ADD -> appendToList(sideList, request, info, row.tradeDate)
            PoolAction.REMOVE -> removeFromList(sideList, request.code)
        }
        writeBackList(row, request.side, sideList)
        recountListCount(row, request.side)
        appendManualTrace(row, request, info)
        sentimentCycleRepository.save(row)
        return row.toDto()
    }

    override fun getTerms(tradeDate: LocalDate): SentimentTermsResponse {
        val row = requireRow(tradeDate)
        val stage = classifier.classifyStage(row)
        val big = (row.bigCycle ?: row.bigCycleSug)?.toInt() ?: 1
        val small = (row.smallCycle ?: row.smallCycleSug)?.toInt() ?: 1
        val active = dragonCycleRepository.findTopByEndDateIsNullOrderByMaxStreakDescStartDateAsc()
        val dragonName = active?.let { stockInfoRepository.findByCode(it.code)?.name }
        val actions = classifier.buildActions(
            bars = stockHistoryRepository.findByTradeDateAndIsLimitUpTrue(tradeDate),
            activeDragon = active,
            reboundCodes = collapseReboundCodes(row, tradeDate),
        )
        return SentimentTermsResponse(
            tradeDate = tradeDate,
            stage = stage.label,
            bigCycle = big,
            smallCycle = small,
            dragonStatus = active?.status?.name,
            dragonName = dragonName,
            actions = actions,
        )
    }

    override fun getStockActions(code: String, from: LocalDate, to: LocalDate): StockActionsResponse {
        val bars = stockHistoryRepository.findByCodeAndTradeDateBetween(code, from, to).sortedBy { it.tradeDate }
        val active = dragonCycleRepository.findByCodeAndEndDateIsNull(code)
        // 崩塌池按日映射（与 /terms 同数据源同实现，消除反核止跌口径漂移）
        val collapseCodesByDate = sentimentCycleRepository
            .findByTradeDateBetweenOrderByTradeDateAsc(from, to)
            .associate { row -> row.tradeDate to collapseCodesOf(row) }
        val timeline = mutableListOf<StockActionTimelineItem>()
        for (index in bars.indices) {
            val today = bars[index]
            val prev = bars.getOrNull(index - 1)
            // 反核止跌：该股当日∈崩塌池 且 今日涨停（=/terms collapseReboundCodes 同口径）
            val reboundCodes = if (today.code in (collapseCodesByDate[today.tradeDate].orEmpty()) && today.isLimitUp) {
                setOf(today.code)
            } else {
                emptySet()
            }
            val label = classifier.labelFor(today, prev, active, reboundCodes, bars) ?: continue
            timeline.add(
                StockActionTimelineItem(
                    tradeDate = today.tradeDate,
                    label = label.label,
                    limitUpStreak = today.limitUpStreak.toInt(),
                    changePct = today.changePct,
                ),
            )
        }
        return StockActionsResponse(code = code, actions = timeline)
    }

    // ==================== private 支撑 ====================

    /** 交易日行守卫：不存在抛业务异常（HTTP 404 语义，全局处理器映射） */
    private fun requireRow(tradeDate: LocalDate) =
        sentimentCycleRepository.findByTradeDate(tradeDate)
            ?: throw BusinessException("情绪周期不存在：trade_date=$tradeDate")

    /** 非 ST 校验：证券不存在或 ST 直接拒绝（ST 隔离铁律——禁止作为业务可选项） */
    private fun requireNonSt(code: String) =
        stockInfoRepository.findByCode(code)
            ?.takeIf { !it.isSt }
            ?: throw BusinessException("证券不存在或为 ST：code=$code（ST 隔离铁律）")

    private fun sideListOf(row: com.soros.v2.entity.SentimentCycle, side: PoolSide): ArrayNode = when (side) {
        PoolSide.MEAT -> row.bigMeatList?.takeIf { it.isArray } as? ArrayNode ?: MAPPER.createArrayNode()
        PoolSide.FACE -> row.bigFaceList?.takeIf { it.isArray } as? ArrayNode ?: MAPPER.createArrayNode()
    }

    private fun writeBackList(row: com.soros.v2.entity.SentimentCycle, side: PoolSide, list: ArrayNode) {
        when (side) {
            PoolSide.MEAT -> row.bigMeatList = list
            PoolSide.FACE -> row.bigFaceList = list
        }
    }

    private fun recountListCount(row: com.soros.v2.entity.SentimentCycle, side: PoolSide) {
        val size = sideListOf(row, side).size()
        when (side) {
            PoolSide.MEAT -> row.bigMeatCount = size
            PoolSide.FACE -> row.bigFaceCount = size
        }
    }

    /** ADD：追加 {code,name,change_pct,limit_up_streak,industry}（change_pct 缺省 0，不炸解析） */
    private fun appendToList(list: ArrayNode, request: SentimentListsRequest, info: com.soros.v2.entity.StockInfo, tradeDate: LocalDate) {
        val todayBar = stockHistoryRepository.findByCodeAndTradeDate(request.code, tradeDate)
        list.add(
            MAPPER.createObjectNode().apply {
                put("code", request.code)
                put("name", info.name ?: request.name ?: "")
                put("change_pct", todayBar?.changePct ?: BigDecimal.ZERO)
                put("limit_up_streak", todayBar?.limitUpStreak?.toInt() ?: 0)
                info.industry?.let { industries ->
                    val arr = putArray("industry")
                    industries.forEach { arr.add(it) }
                }
            },
        )
    }

    /** REMOVE：按 code 移除名单元素（不存在则静默跳过，幂等） */
    private fun removeFromList(list: ArrayNode, code: String) {
        var index = -1
        for (i in 0 until list.size()) {
            if (list.get(i).get("code")?.asText() == code) {
                index = i
                break
            }
        }
        if (index >= 0) list.remove(index)
    }

    /** lists_manual_json 留痕：{side,action,code,name,reason,at}（ISO-8601） */
    private fun appendManualTrace(row: com.soros.v2.entity.SentimentCycle, request: SentimentListsRequest, info: com.soros.v2.entity.StockInfo) {
        val traces = row.listsManualJson?.takeIf { it.isArray } as? ArrayNode ?: MAPPER.createArrayNode()
        traces.add(
            MAPPER.createObjectNode().apply {
                put("side", request.side.name)
                put("action", request.action.name)
                put("code", request.code)
                put("name", info.name ?: request.name)
                request.reason?.let { put("reason", it) }
                put("at", LocalDateTime.now().toString())
            },
        )
        row.listsManualJson = traces
    }

    /** 崩塌池代码集合（/terms 与 /stocks/{code}/actions 共用，防口径漂移） */
    private fun collapseCodesOf(row: com.soros.v2.entity.SentimentCycle): Set<String> =
        row.collapseList?.toCollapseListItems()?.map { it.code }?.toSet() ?: emptySet()

    /** 崩塌池今日止跌反核集合（collapse 名单 ∩ 今日涨停；terms 反核标签入参） */
    private fun collapseReboundCodes(row: com.soros.v2.entity.SentimentCycle, date: LocalDate): Set<String> {
        val limitUpCodes = stockHistoryRepository.findByTradeDateAndIsLimitUpTrue(date).map { it.code }.toSet()
        return collapseCodesOf(row) intersect limitUpCodes
    }

    private companion object {
        val MAPPER: ObjectMapper = ObjectMapper()
    }
}
