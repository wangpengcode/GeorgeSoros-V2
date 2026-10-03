package com.soros.v2.repository

import com.soros.v2.entity.DataQualityLog
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * data_quality_log 数据访问接口（数据底座层）。
 */
interface DataQualityLogRepository : JpaRepository<DataQualityLog, Int> {

    /** 按 问题类型+检查执行日 查日志（§17.1 B2 条件跳过问题表消费） */
    fun findByIssueTypeAndCheckDate(issueType: String, checkDate: LocalDate): List<DataQualityLog>

    /** 按 问题类型+检查执行日之后 统计（重复问题收敛看板） */
    fun countByIssueTypeAndCheckDateAfter(issueType: String, checkDate: LocalDate): Long
}
