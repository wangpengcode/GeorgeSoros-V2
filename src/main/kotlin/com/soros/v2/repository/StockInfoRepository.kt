package com.soros.v2.repository

import com.soros.v2.entity.StockInfo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

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

    /** 批量按代码查（§4.8 涨停梯队装配 industry/concept_boards；CrossValidate 样本校验） */
    fun findByCodeIn(codes: Collection<String>): List<StockInfo>

    /** 有效股票数（非 ST/非退市；§11.1 披露季采集幂等跳过的期望水位） */
    fun countByIsStFalseAndDelistedFalse(): Long

    /** 随机抽 N 只非 ST/非退市有效股代码（§11.2 交叉验证小样本；仅 MAIN/GEM/STAR 参与） */
    @Query(
        value = """
            SELECT code FROM stock_info
            WHERE is_st = false AND delisted = false
              AND board IN ('MAIN', 'GEM', 'STAR')
            ORDER BY random()
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findRandomValidCodes(limit: Int): List<String>
}
