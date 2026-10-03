package com.soros.v2.util

import java.time.LocalDate

/**
 * §4.8 连板/跌停连板派生共用口径（单点铁律：增量路径 saveBatch 与回填抽查对拍共用，禁止第二套实现）。
 *
 * 阈值判定单点在 [LimitUpDetector]（不复权 change_pct + board 阈值 9.9/19.9），本对象只含：
 * - IPO 首 5 日守卫（§4.8：上市首 5 日无涨跌幅限制，强制 is_limit_up/is_limit_down=false、streak=0）；
 * - streak 累加算术（is_limit ? 昨日 streak+1 : 0，跌停镜像共用）。
 */
object LimitStreakComputer {

    /** §4.8 IPO 首 5 日守卫：守卫窗口 = 自 ipo_date（含）起的连续交易天数 */
    private const val IPO_GUARD_TRADING_DAYS = 5

    /**
     * §4.8 IPO 首 5 日守卫：bar 距 ipo_date 不足 [IPO_GUARD_TRADING_DAYS] 个交易日 → true。
     *
     * 口径（穿透修正 2026-10-04）：bar 处于以 ipo_date 为首（含）的连续 [IPO_GUARD_TRADING_DAYS]
     * 个交易日内即守卫——即 ipo_date 不早于 bar 前第 5 个交易日（`ipoDate >= fiveBack.first()`）。
     * 此前用 `>` 的 off-by-one 会把「第 5 根 bar」（上市日计数第 5 个交易日）漏守卫，与回填
     * recompute SQL（row_number≤5）不一致——本版对齐。
     */
    fun isWithinIpoGuard(
        ipoDate: LocalDate?,
        barDate: LocalDate,
        recentTradingDays: (LocalDate, Int) -> List<LocalDate>,
    ): Boolean {
        if (ipoDate == null) return false
        val fiveBack = recentTradingDays(barDate, IPO_GUARD_TRADING_DAYS)
        if (fiveBack.size < IPO_GUARD_TRADING_DAYS) return true
        return ipoDate >= fiveBack.first()
    }

    /** streak 累加：is_limit ? 昨日 streak+1 : 0（连板/跌停连板镜像共用） */
    fun nextStreak(isLimit: Boolean, prevStreak: Int): Short =
        if (isLimit) (prevStreak + 1).toShort() else 0
}
