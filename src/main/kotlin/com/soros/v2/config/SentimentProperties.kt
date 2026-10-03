package com.soros.v2.config

import java.math.BigDecimal
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * soros.sentiment 配置树（§13.1，§4.9 池子/崩塌/龙头阈值全部配置化）。
 *
 * yml 现状（application.yml）：
 * ```
 * soros.sentiment:
 *   big-meat-threshold: 5.0
 *   big-face-threshold: -5.0
 *   pool:     { min-streak: 3, min-limit-ups: 2, min-5d-gain: 50 }
 *   collapse: { min-streak: 2, max-5d-drop: 30 }
 *   dragon:   { observe-days: 3, small-max-limit_up_streak: 7 }
 * ```
 */
@ConfigurationProperties(prefix = "soros.sentiment")
class SentimentProperties {

    /** 大肉阈值：强势池内今日 change_pct ≥ 该值计大肉（默认 5.0） */
    var bigMeatThreshold: BigDecimal = BigDecimal("5.0")

    /** 大面阈值：强势池内今日 change_pct ≤ 该值计大面（默认 -5.0） */
    var bigFaceThreshold: BigDecimal = BigDecimal("-5.0")

    /** 强势池阈值（§4.9 P1-P3） */
    var pool: PoolThresholds = PoolThresholds()

    /** 崩塌池阈值（§4.9 C1-C2） */
    var collapse: CollapseThresholds = CollapseThresholds()

    /** 龙头状态机阈值（§4.9） */
    var dragon: DragonThresholds = DragonThresholds()

    /** 强势池阈值（§4.9 对象池定义 P1-P3） */
    class PoolThresholds {
        /** P1 近 3 个交易日内最高 limit_up_streak 下限（默认 3，覆盖 4 板以上高标） */
        var minStreak: Int = 3

        /** P2 近 5 个交易日内涨停次数下限（默认 2） */
        var minLimitUps: Int = 2

        /** P3 近 5 个交易日累计涨幅下限（默认 50%，qfq 收盘价窗口计算） */
        var min5dGain: BigDecimal = BigDecimal("50")
    }

    /** 崩塌池阈值（§4.9 对象池定义 C1-C2） */
    class CollapseThresholds {
        /** C1 跌停连板数下限（默认 2，连续跌停） */
        var minStreak: Int = 2

        /** C2 近 5 个交易日累计跌幅下限（默认 30%，高位崩下来） */
        var max5dDrop: BigDecimal = BigDecimal("30")
    }

    /** 龙头状态机阈值（§4.9 状态机逐日推进） */
    class DragonThresholds {
        /** 反包观察期天数（默认 3 交易日，broken_date 起） */
        var observeDays: Int = 3

        /** 小周期最高板上限（默认 7）：断板时 max_streak ≤ 该值且无反包 → SMALL */
        var smallMaxLimitUpStreak: Int = 7
    }
}
