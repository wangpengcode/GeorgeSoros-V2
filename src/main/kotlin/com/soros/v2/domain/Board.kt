package com.soros.v2.domain

import com.soros.v2.exception.BusinessException

/**
 * 市场板（stock_info.board CHECK 约束值域：MAIN 主板 / GEM 创业板 / STAR 科创板）。
 *
 * 语义（PLAN §2.4 枚举表）：
 * - 涨停判定阈值按 board 区分（§4.5：主板 10%、双创 20%）；
 * - 北交所、B 股不采集（§2.2 隔离原则），board 不设对应值。
 */
enum class Board(val displayName: String) {
    MAIN("主板"),
    GEM("创业板"),
    STAR("科创板"),
    ;

    companion object {
        /**
         * 从 Python /stock-list 的 board 字段（MAIN/GEM/STAR）解析。
         * Python 侧 board 值已与 §2.4 枚举对齐；未知值视为契约漂移抛业务异常。
         */
        fun fromPython(value: String): Board =
            entries.firstOrNull { it.name == value }
                ?: throw BusinessException("未知 board: $value（值域 MAIN/GEM/STAR，北交所/B 股不采集）")
    }
}
