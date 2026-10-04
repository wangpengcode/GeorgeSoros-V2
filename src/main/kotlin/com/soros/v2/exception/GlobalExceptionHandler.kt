package com.soros.v2.exception

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 全局异常处理器（§11.1 契约 + 调度器裁定 #4）。
 *
 * 统一错误信封：{status:"error", error:{code, message}}。
 * - BusinessException     → 422（消息含"不存在"的 not-found 语义 → 404，见 testConfirm 404 契约）
 * - DataValidationException → 422（数据校验异常，DataValidator 自洽失败）
 * - IllegalArgumentException → 400（DTO init require 值域校验前置）
 * - MissingServletRequestParameter/TypeMismatch → 400（query 参数缺失/格式不符）
 * - DataIntegrityViolationException → 409（唯一约束/CHECK 冲突，幂等双跑防重插）
 * - 兜底 Exception → 500（防御性：不外泄堆栈）
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorEnvelope> {
        val status = if (e.message?.contains("不存在") == true) HttpStatus.NOT_FOUND else HttpStatus.UNPROCESSABLE_ENTITY
        log.warn("[GlobalExceptionHandler] BusinessException({}): {}", status.value(), e.message)
        return ResponseEntity.status(status).body(ErrorEnvelope(error = ErrorBody("BUSINESS_ERROR", e.message ?: "业务规则异常")))
    }

    @ExceptionHandler(DataValidationException::class)
    fun handleDataValidation(e: DataValidationException): ResponseEntity<ErrorEnvelope> {
        log.warn("[GlobalExceptionHandler] DataValidationException({}): {}", HttpStatus.UNPROCESSABLE_ENTITY.value(), e.message)
        return ResponseEntity.unprocessableEntity().body(ErrorEnvelope(error = ErrorBody("DATA_VALIDATION_ERROR", e.message ?: "数据校验异常")))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorEnvelope> {
        log.warn("[GlobalExceptionHandler] IllegalArgumentException(400): {}", e.message)
        return ResponseEntity.badRequest().body(ErrorEnvelope(error = ErrorBody("BAD_REQUEST", e.message ?: "请求参数非法")))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorEnvelope> {
        log.warn("[GlobalExceptionHandler] HttpMessageNotReadableException(400): {}", e.message)
        return ResponseEntity.badRequest().body(ErrorEnvelope(error = ErrorBody("BAD_REQUEST", "请求体解析失败或字段值域不符")))
    }

    /** 缺少必填 query 参数（如 /limit-up-board?trade_date=...）→ 400，不落兜底 500 */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ErrorEnvelope> {
        log.warn("[GlobalExceptionHandler] MissingServletRequestParameterException(400): {}", e.message)
        return ResponseEntity.badRequest().body(ErrorEnvelope(error = ErrorBody("MISSING_PARAM", "缺少必填参数: ${e.parameterName}")))
    }

    /** query 参数类型/格式不符（如 trade_date=abc）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorEnvelope> {
        log.warn("[GlobalExceptionHandler] MethodArgumentTypeMismatchException(400): {}", e.message)
        return ResponseEntity.badRequest().body(ErrorEnvelope(error = ErrorBody("PARAM_INVALID", "参数格式不符: ${e.name}")))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: DataIntegrityViolationException, request: WebRequest): ResponseEntity<ErrorEnvelope> {
        log.error("[GlobalExceptionHandler] DataIntegrityViolationException(409): uri={} msg={}", request.getDescription(false), e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorEnvelope(error = ErrorBody("DATA_CONFLICT", "数据冲突（唯一约束/CHECK 拒绝），请检查幂等或值域")))
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception, request: WebRequest): ResponseEntity<ErrorEnvelope> {
        log.error("[GlobalExceptionHandler] 未分类异常(500): uri={} msg={}", request.getDescription(false), e.message, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorEnvelope(error = ErrorBody("INTERNAL_ERROR", "服务器内部错误")))
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}

/** 统一错误信封 {status:"error", error:{code,message}} */
data class ErrorEnvelope(
    val status: String = "error",
    val error: ErrorBody,
)

data class ErrorBody(
    val code: String,
    val message: String,
)
