package com.soros.v2.service

import com.soros.v2.domain.BoardType
import com.soros.v2.entity.StockInfo
import com.soros.v2.service.dto.StockSearchItem

/**
 * §4.4/§2.2 股票基础信息服务（列表拉取入库/刷新，供 DailyCollectJob 与涨停/IPO 守卫消费）。
 *
 * ST/退市隔离铁律：is_st / delisted 仅用于"识别并排除"，禁止作为业务可选项出现在任何条件/接口/前端。
 */
interface StockInfoService {

    /**
     * 拉取股票列表并 upsert stock_info，返回本次采集有效股票（已过滤 ST/退市/北交所，仅 MAIN/GEM/STAR）。
     * 同时回填：board/market/search_key/ipo_date（§4.8 IPO 首 5 日守卫前置；BaoStock query_stock_basic）。
     */
    suspend fun refreshStockList(): List<StockInfo>

    /**
     * 批量回填 ipo_date（BaoStock query_stock_basic；仅更新已有行，不新增；§4.8 IPO 守卫前置）。
     * 由 BoardCollectJob 开跑前调用，保证 IPO 首 5 日守卫水位最新；返回实际更新行数。
     */
    suspend fun backfillIpoDates(): Int

    /**
     * 板块成分覆盖写快照（§4.8 BoardCollectJob 消费）：
     * Python /api/v1/board-members → {板块名:[codes]} → 反转成 code→[板块名] 覆盖写
     * stock_info.industry（INDUSTRY）/ concept_boards（CONCEPT）。
     * 只关注当前成分，不保留历史；返回实际更新行数。
     */
    suspend fun refreshBoardSnapshot(boardType: BoardType): Int

    /** 按证券代码查基础信息（IPO 守卫 / board 校验用） */
    fun findByCode(code: String): StockInfo?

    /**
     * GET /api/v1/stock-search（§11.1）：股票模糊搜索（名单添加、梯队查询等输入场景共用）。
     *
     * 一期口径：q trim 非空，匹配 code 前缀 OR name 小写包含；排除 is_st=true / delisted=true；
     * limit 上限 20（调用方默认 10，前端 8；§19.13.1 协调方裁决上限 20）。响应 [{code,name,industry}]（键过命名字典 §17.6）。
     */
    fun search(query: String, limit: Int): List<StockSearchItem>

    /** 5 个基准指数基础信息入库/刷新（stock_index，code 带前缀值口径特例 sh000001） */
    fun saveBenchmarkIndices(): Int
}
