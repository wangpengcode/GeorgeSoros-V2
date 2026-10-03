package com.soros.v2.service

import com.soros.v2.domain.Board
import com.soros.v2.domain.DataSourceType
import com.soros.v2.service.dto.DailyBar
import com.soros.v2.service.dto.SaveBatchResult
import java.time.LocalDate

/**
 * §4.4/§4.6/§4.7/§4.8 股票日线入库业务接口。
 *
 * saveBatch 联动实现（②③④ 合并于一处，§4.7）：
 * 1. DataValidator 行内自洽（防线②，非法行跳过记日志）
 * 2. LimitUpDetector 涨停检测（不复权 change_pct + board 阈值，§4.5）
 * 3. prev_close 链式校验（§4.6：|prev_close − 库内昨收| ≤ max(0.01, prev_close×0.5%)；
 *    漂移 → data_quality_log(ADJUSTMENT_DRIFT) + 单股全量重拉）
 * 4. §4.8 连板数派生（读前一日 streak + trading_calendar 定位 + IPO 首 5 日守卫）
 * 5. upsert（ON CONFLICT DO UPDATE 幂等）+ data_source 记录
 */
interface StockHistoryService {

    /** 该股最大交易日（采集水位 / §4.7 滚动窗口起点 findMaxDate+1） */
    fun findMaxDate(code: String): LocalDate?

    /** 核心入库（见接口 KDoc；因内含 §4.6 单股全量重拉的 suspend 客户端调用而为 suspend 函数） */
    suspend fun saveBatch(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
        board: Board,
    ): SaveBatchResult

    /** 基准指数日 K 入库（index_history，code 带前缀值口径特例 sh000001，qfq 同 stock_history） */
    suspend fun saveIndexBatch(
        code: String,
        bars: List<DailyBar>,
        source: DataSourceType,
    ): Int
}
