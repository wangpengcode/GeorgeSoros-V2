package com.soros.v2.util

import com.soros.v2.domain.Board
import java.math.BigDecimal

/**
 * §4.5 涨停/跌停检测。
 *
 * 关键设计决策：用数据源提供的原始**不复权** change_pct（真实涨跌幅）判定，不从 qfq OHLC 自行计算。
 * 阈值（按 board）：主板 10%（容差 9.9）；创业板/科创板 20%（容差 19.9）。
 *
 * §4.8 IPO 首 5 日守卫（上市首 5 日无涨跌幅限制，新误标）在 saveBatch 层强制置 false，不在此处。
 */
object LimitUpDetector {

    private val MAIN_THRESHOLD = BigDecimal("9.9")
    private val GEM_STAR_THRESHOLD = BigDecimal("19.9")

    /**
     * 检测涨停/跌停。
     *
     * @param changePct 数据源提供的原始涨跌幅%（不复权，真实涨跌幅）
     * @param board     市场板（MAIN / GEM / STAR）
     * @return Pair(isLimitUp, isLimitDown)
     */
    fun detect(changePct: BigDecimal, board: Board): Pair<Boolean, Boolean> {
        val threshold = when (board) {
            Board.GEM, Board.STAR -> GEM_STAR_THRESHOLD
            Board.MAIN -> MAIN_THRESHOLD
        }
        return (changePct >= threshold) to (changePct <= threshold.negate())
    }
}
