package com.soros.v2.service.strategy

import com.soros.v2.domain.StockActionLabel
import java.time.LocalDate

/**
 * §19.13.3 G5 定稿：标签类条件求值服务（路径 L 数据源）。
 *
 * 实现语义：对「五池并集 ∪ 进行中龙头」候选集逐票复用 SentimentClassifier.labelFor 现算（不落库），
 * 输出 `Map<code, Map<trade_date, label>>`。a 期只定义契约（4 个红测试 Mock 本接口）；
 * b 期挂载候选集派生（五池并集∪龙头 + 崩塌池 C1/C2 候选内判定 + 逐日回溯 dragon_cycle）。
 */
interface LabelEvaluationService {

    /**
     * 现算候选池标签（endDate 窗口末日，windowDays 窗口长度）。
     *
     * `endDate` 可空——Mockito 5.14.2 `any()` 返回默认值 null，Kotlin 非空参数字节码检查兼容
     * （对齐 StockHistoryRepository 既有模式）；生产调用方始终传非空窗口末日。
     *
     * @return 候选票 → 逐日标签映射（候选集外 universe 票不在结果集里，L5）
     */
    fun getLabels(endDate: LocalDate?, windowDays: Int): Map<String, Map<LocalDate, StockActionLabel>>
}
