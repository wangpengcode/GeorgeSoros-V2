package com.soros.v2.service.intraday.mapper

import com.soros.v2.entity.IntradayArchive
import com.soros.v2.entity.IntradayArchiveId
import com.soros.v2.service.intraday.dto.IntradayEventDetail
import com.soros.v2.service.intraday.dto.IntradayLadderItem
import com.soros.v2.service.intraday.dto.IntradayPoolRowDto
import java.math.BigDecimal
import java.time.LocalDate

/**
 * §19.13.2 盘中映射（跨类型转换收口，禁止在 ServiceImpl 内联逐字段拷贝）。
 */

/** 池行 → 归档行（pool 归属 + UNIQUE(trade_date, code) 复合主键；缺列 NULL，不造默认值） */
fun IntradayPoolRowDto.toArchive(tradeDate: LocalDate, pool: String): IntradayArchive = IntradayArchive(
    id = IntradayArchiveId(tradeDate = tradeDate, code = code),
    firstSealTime = firstTime,
    lastSealTime = null,
    zhabanCount = brokenCount?.toShort(),
    sealAmount = null,
    limitUpStreak = lianban?.toShort(),
    pool = pool,
)

/** 池行 → 连板梯队行（§14.4 ladder 全名；limit_stat 由池归属派生） */
fun IntradayPoolRowDto.toLadderItem(limitStat: String?): IntradayLadderItem = IntradayLadderItem(
    code = code,
    name = name,
    limitUpStreak = lianban,
    changePct = changePct,
    sealAmount = null,
    firstSealTime = firstTime,
    zhabanCount = brokenCount,
    limitStat = limitStat,
)

/** 池行 → 事件详情（§14.4：{limit_up_streak,seal_amount,zhaban_count,change_pct}） */
fun IntradayPoolRowDto.toEventDetail(): IntradayEventDetail = IntradayEventDetail(
    limitUpStreak = lianban,
    sealAmount = null,
    zhabanCount = brokenCount,
    changePct = changePct,
)

/** 归档行 → 连板梯队行（ladder 现拼读 archive 时用；name/change_pct 归档表无此列 → null） */
fun IntradayArchive.toLadderItem(): IntradayLadderItem = IntradayLadderItem(
    code = id.code,
    name = null,
    limitUpStreak = limitUpStreak?.toInt(),
    changePct = null,
    sealAmount = sealAmount,
    firstSealTime = firstSealTime,
    zhabanCount = zhabanCount?.toInt(),
    limitStat = limitStatOf(),
)

/** 归档行 limit_stat 派生（ZT 封住；未封/炸板池行按 pool 归属标注；pool CHAR(6) PG 补空格需 trim） */
fun IntradayArchive.limitStatOf(): String? = when (pool?.trim()) {
    "ZT" -> "SEAL"
    "ZB" -> "ZHA"
    else -> null
}

/** 涨跌家数比 adv/dec（§17.6 定稿口径；dec=0 时 null 防除零） */
fun advDecRatio(adv: Int, dec: Int): BigDecimal? =
    if (dec > 0) {
        BigDecimal(adv).divide(BigDecimal(dec), 2, java.math.RoundingMode.HALF_UP)
    } else {
        null
    }
