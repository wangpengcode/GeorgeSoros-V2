package com.soros.v2.repository

import com.soros.v2.entity.StockFundamentals
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * stock_fundamentals 数据访问接口（数据底座层）。
 */
interface StockFundamentalsRepository : JpaRepository<StockFundamentals, Int> {

    /** 按 代码+报告期 查财务行（UNIQUE(code, report_date) 至多 1 行） */
    fun findByCodeAndReportDate(code: String, reportDate: LocalDate): StockFundamentals?

    /** 是否已存在 代码+报告期 行（季度循环拉取幂等判重） */
    fun existsByCodeAndReportDate(code: String, reportDate: LocalDate): Boolean

    /** 指定报告期全部财务行（对账 / 手动补数查看） */
    fun findByReportDate(reportDate: LocalDate): List<StockFundamentals>
}
