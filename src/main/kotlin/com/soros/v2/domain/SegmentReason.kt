package com.soros.v2.domain

/**
 * 回填缺失段原因（BackfillPlanService 分类产出）。
 * - HEAD：头部缺失 [expectedStart, min_d-1]（2026-10-05 起计划不再拆分产出，保留供遗留兜底/台账语义）
 * - TAIL：尾部缺失 [下一开市日, expectedEnd]（同上，保留枚举）
 * - MID：中间洞（gaps-and-islands 产出，同上，保留枚举）
 * - NO_DATA：库内无此码且从未导入（整窗一段）
 * - FULL：水位线落后段（2026-10-05 用户定稿：input_data_last_day < 最新开市日 →
 *   从水位线后首个开市日到 expectedEnd 一段拉齐，断点续传不重拉已核对区间）
 */
enum class SegmentReason { HEAD, TAIL, MID, NO_DATA, FULL }
