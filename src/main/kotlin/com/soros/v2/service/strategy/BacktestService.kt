package com.soros.v2.service.strategy

import com.soros.v2.service.strategy.dto.BacktestCreateRequest
import com.soros.v2.service.strategy.dto.BacktestResultDto

/**
 * §19.13.3 G4 定稿 POST /api/v1/backtests 服务契约。
 */
interface BacktestService {

    /**
     * 创建回测（a 期只做参数契约 + 落库占位，回测引擎挂 b 期）。
     *
     * `req` 可空——Mockito 5.14.2 `any(Class)` 返回默认值 null，Kotlin 非空参数字节码检查兼容
     * （对齐 StockHistoryRepository 既有模式）；控制器侧始终传非空，服务层空值守卫兜底。
     */
    fun create(req: BacktestCreateRequest?): BacktestResultDto
}
