package com.soros.v2.controller

import com.soros.v2.service.StockInfoService
import com.soros.v2.service.dto.StockSearchItem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §11.1 StockSearchController：股票模糊搜索（GET /api/v1/stock-search）。
 *
 * 前端 sentiment.html 名单添加浮层消费（GET /stock-search?q=&limit=8，防御性兼容数组/stocks/items）。
 * 一期口径：code 前缀 OR name 小写包含；排除 is_st=true / delisted=true；limit 默认 10 上限 20。
 * 响应 [{code,name,industry}]（键过命名字典 §17.6）。
 */
@RestController
@RequestMapping("/api/v1")
class StockSearchController(
    private val stockInfoService: StockInfoService,
) {

    /** GET /api/v1/stock-search?q=&limit= → 模糊搜索命中列表（q 空 → 400 由全局处理器映射） */
    @GetMapping("/stock-search")
    fun search(
        @RequestParam("q") query: String,
        @RequestParam("limit") limit: Int = 10,
    ): List<StockSearchItem> = stockInfoService.search(query, limit)
}
