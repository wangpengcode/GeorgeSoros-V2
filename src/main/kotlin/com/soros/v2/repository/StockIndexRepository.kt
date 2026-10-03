package com.soros.v2.repository

import com.soros.v2.entity.StockIndex
import org.springframework.data.jpa.repository.JpaRepository

/**
 * stock_index 数据访问接口（数据底座层）。
 */
interface StockIndexRepository : JpaRepository<StockIndex, Int> {

    /** 按指数代码查（code UNIQUE 至多 1 行，值口径带 sh 前缀） */
    fun findByCode(code: String): StockIndex?

    /** 按代码集合批量查（5 个基准指数一次载入） */
    fun findByCodeIn(codes: Collection<String>): List<StockIndex>
}
