package com.soros.v2.repository

import com.soros.v2.entity.StockInfo
import org.springframework.data.jpa.repository.JpaRepository

/**
 * stock_info 数据访问接口（数据底座层）。
 */
interface StockInfoRepository : JpaRepository<StockInfo, Int> {

    /** 按证券代码查基础信息（code UNIQUE 至多 1 行） */
    fun findByCode(code: String): StockInfo?

    /** ST 名单（仅用于"识别并排除"，禁止作为业务可选项） */
    fun findByIsStTrue(): List<StockInfo>

    /** 按退市标记查（缺失≠退市：人工确认才置 true） */
    fun findByDelisted(delisted: Boolean): List<StockInfo>

    /** pg_trgm 模糊搜索入口（search_key 小写 name+全拼+拼音首字母） */
    fun findBySearchKeyContainingIgnoreCase(searchKey: String): List<StockInfo>
}
