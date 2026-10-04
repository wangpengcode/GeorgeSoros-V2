package com.soros.v2.domain

/**
 * 回填缺失段原因（BackfillPlanService 分类产出）。
 * - HEAD：头部缺失 [expectedStart, min_d-1]
 * - TAIL：尾部缺失 [下一开市日, expectedEnd]
 * - MID：中间洞（gaps-and-islands 产出，可能多条）
 * - NO_DATA：库内无此码
 */
enum class SegmentReason { HEAD, TAIL, MID, NO_DATA }
