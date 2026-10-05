package com.soros.v2.service.intraday

import java.time.LocalDate

/**
 * §19.13.2 收盘权威归档（15:10 cron；盘后权威表，词表升级的数据源）。
 *
 * 时序：重拉当日池（date=当日）→ 清当日 intraday_archive 行后重写（幂等）→ 构建整页快照
 * （kpi_series 保留盘中采样 + ladder=ZT 归档行 + events=intraday_event + panels）→ 落 intraday_replay
 * 置 complete=true → 清理过期 intraday_pool_snap（保留 [IntradayProperties.snapRetentionDays] 天）。
 */
interface IntradayArchiveService {

    /** 归档指定交易日（外部端点失败降级记 ERROR，不写半成品归档，replay.complete 保持 false） */
    suspend fun archiveDay(tradeDate: LocalDate)
}
