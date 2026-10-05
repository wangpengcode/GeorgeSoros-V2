package com.soros.v2.service.intraday

/**
 * §19.13.2 盘中轮询服务（IntradayJob 90s 一轮的落表时序核心）。
 *
 * 时序：拉 5 池（实际 Python 契约三池一响应 limit_up/limit_down/broken）→ 写 intraday_pool_snap
 * （payload=接口原样英文 DTO 行，幂等 upsert）→ 读上轮最新 snap → diff 出事件（ZT/HF/ZB/DT/OPEN/MAXCHG）
 * → 写 intraday_event → spot 出 adv/dec + 高位股大面（强势池成员现价 ≤-5%）→ 钉钉推送（§14.5 规则，
 * 仅 3 类：源停轮/大面/最高板易主）→ intraday_replay 当日行追加 kpi_series（90s 采样点）。
 */
interface IntradayPollService {

    /** 一轮完整轮询（外部端点失败降级记 WARN 不炸循环；令牌不足 defer 本轮） */
    suspend fun pollRound()
}
