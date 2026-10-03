package com.soros.v2.exception

import com.soros.v2.domain.QualityIssueType

/**
 * 全系统异常体系（命名过 docs/design/naming-dictionary.md 语义：Soros 家族前缀）。
 *
 * 分类定稿（任务挂账项）：
 * - [BusinessException]        业务规则异常（违反业务规则/前置条件，值域不符）
 * - [DataValidationException]  数据校验异常（DataValidator 行内自洽失败 / §4.6 链式校验漂移）
 * - [PythonClientException]    Python 数据服务客户端异常（连接失败/超时/非 2xx/熔断 open）
 *
 * 全部继承 [SorosBaseException]（RuntimeException），调用方按类型分流，杜绝裸 Exception。
 */
abstract class SorosBaseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 业务规则异常：违反业务规则/前置条件（如 IPO 守卫、停牌语义、board 值域不符）。
 */
class BusinessException(
    message: String,
    cause: Throwable? = null,
) : SorosBaseException(message, cause)

/**
 * 数据校验异常：DataValidator 行内自洽失败 / §4.6 prev_close 链式校验漂移。
 *
 * @property issueType 对应 data_quality_log.issue_type（ADJUSTMENT_DRIFT / ...），
 *                     便于 saveBatch 落质量日志时直接透传。
 */
class DataValidationException(
    message: String,
    val issueType: QualityIssueType,
    cause: Throwable? = null,
) : SorosBaseException(message, cause)

/**
 * Python 数据服务客户端异常：连接失败 / 超时 / 非 2xx / 熔断 open。
 * Kotlin→Python 是唯一上游链路；本异常触发 PythonCircuitBreaker 失败计数。
 */
class PythonClientException(
    message: String,
    cause: Throwable? = null,
) : SorosBaseException(message, cause)
