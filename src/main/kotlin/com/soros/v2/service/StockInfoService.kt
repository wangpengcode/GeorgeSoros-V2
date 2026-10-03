package com.soros.v2.service

import com.soros.v2.entity.StockInfo

/**
 * §4.4/§2.2 股票基础信息服务（列表拉取入库/刷新，供 DailyCollectJob 与涨停/IPO 守卫消费）。
 *
 * ST/退市隔离铁律：is_st / delisted 仅用于"识别并排除"，禁止作为业务可选项出现在任何条件/接口/前端。
 */
interface StockInfoService {

    /**
     * 拉取股票列表并 upsert stock_info，返回本次采集有效股票（已过滤 ST/退市/北交所，仅 MAIN/GEM/STAR）。
     * 同时回填：board/market/search_key；ipo_date 来源 BaoStock query_stock_basic（§2.4）。
     */
    suspend fun refreshStockList(): List<StockInfo>

    /** 按证券代码查基础信息（IPO 守卫 / board 校验用） */
    fun findByCode(code: String): StockInfo?

    /** 5 个基准指数基础信息入库/刷新（stock_index，code 带前缀值口径特例 sh000001） */
    fun saveBenchmarkIndices(): Int
}
