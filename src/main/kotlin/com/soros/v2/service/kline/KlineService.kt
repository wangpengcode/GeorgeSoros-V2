package com.soros.v2.service.kline

import com.soros.v2.service.kline.dto.KlineResponse
import java.time.LocalDate

/**
 * §19.13.1 K线复盘端点服务（GET /api/v1/kline）。
 *
 * 窗口计算（trading_calendar 吸附）+ bars∘chip join + code 存在性校验。
 * 行为矩阵（优先级：date > 显式 to；from 缺省 250 日窗口；from 显式即全量）：
 * - 仅 code：to=最近交易日（≤today，吸附口径 A 同款），from=to−249 交易日
 * - code+date：to=≤date 最近交易日（date 语义=该日为终点），from=to−249
 * - code+from+to：显式区间（含两端）；date 与 from/to 同时传时 date 作废 + WARN
 * - code+from（无 to）→ to=最近交易日；code+to（无 from）→ from=to−249
 * - 「全量」按钮 = from=2021-01-01（后端按实际最早 bar 自然截断，回填起点 2021-10-01 起步）
 */
interface KlineService {

    /**
     * 按 代码+窗口 查 K线 bars（stock_history qfq OHLC+量额 ∘ signal_daily 筹码 8 列按 trade_date join）。
     *
     * @param code 证券代码（裸数字 600000；控制器必填，服务层防御性可空——Mockito 5.14.2 `eq()` 返回默认值 null 兼容）
     * @param from 起始交易日（含；null=默认窗口起点）
     * @param to 结束交易日（含；null=终点吸附）
     * @param date 终点吸附基准日（语义=该日为终点；与 from/to 同时传时作废 + WARN）
     * @return KlineResponse（bars 升序；chip 无 signal_daily 行=null）
     */
    fun getKline(code: String?, from: LocalDate?, to: LocalDate?, date: LocalDate?): KlineResponse
}
