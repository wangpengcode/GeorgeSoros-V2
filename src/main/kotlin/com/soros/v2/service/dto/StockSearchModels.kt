package com.soros.v2.service.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * §11.1 /stock-search 响应元素（GET /api/v1/stock-search?q=&limit=）。
 *
 * 一期口径：匹配 code 前缀 OR name 小写包含（pinyin search_key 拼音生成挂账不实现）；
 * 排除 is_st=true / delisted=true；limit 10。响应键 code/name/industry 过命名字典（§17.6）。
 */
data class StockSearchItem(
    /** 证券代码 */
    @JsonProperty("code") val code: String,
    /** 名称 */
    @JsonProperty("name") val name: String,
    /** 行业（JSON 数组，主行业=第一个，展示用） */
    @JsonProperty("industry") val industry: List<String>?,
)
