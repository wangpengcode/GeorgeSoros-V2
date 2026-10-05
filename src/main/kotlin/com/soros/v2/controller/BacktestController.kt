package com.soros.v2.controller

import com.soros.v2.service.strategy.BacktestService
import com.soros.v2.service.strategy.dto.BacktestCreateRequest
import com.soros.v2.service.strategy.dto.BacktestResultDto
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * §19.13.3 G4 定稿 POST /api/v1/backtests 端点（a 期只做参数契约 + 落库占位，回测引擎挂 b 期）。
 *
 * - 合法体返回 202 Accepted + 落库占位 id（项目唯一 @ResponseStatus(ACCEPTED) 例外）；
 * - is_dry 显式必填（缺 → 服务层抛 BusinessException 不含"不存在"→ 422）；
 * - strategy_name 不存在 → 消息含"不存在"→ 404。
 */
@RestController
@RequestMapping("/api/v1")
class BacktestController(
    private val backtestService: BacktestService,
) {

    /** POST /api/v1/backtests → 202 + 占位 id（G4 请求体契约 {strategy_name, config_id?, start_date, end_date, is_dry}） */
    @PostMapping("/backtests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun create(@RequestBody req: BacktestCreateRequest): BacktestResultDto = backtestService.create(req)
}
