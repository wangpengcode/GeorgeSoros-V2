package com.soros.v2.domain

/**
 * 回填缺失段原因（BackfillPlanService 分类产出）。
 * - HEAD：头部缺失 [expectedStart, min_d-1]（2026-10-05 起计划不再拆分产出，保留供遗留兜底/台账语义）
 * - TAIL：尾部缺失 [下一开市日, expectedEnd]（同上，保留枚举）
 * - MID：中间洞（gaps-and-islands 产出，同上，保留枚举）
 * - NO_DATA：库内无此码（整窗一段）
 * - FULL：整窗重拉（2026-10-05 计划语义：部分缺失票一次性整窗拉齐，不做洞级拆分——
 *   用户口径「一次性把所有股票的数据拉齐然后来检查」，每票成本=一次外部链，与洞数无关）
 */
enum class SegmentReason { HEAD, TAIL, MID, NO_DATA, FULL }
