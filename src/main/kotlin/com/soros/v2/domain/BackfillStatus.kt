package com.soros.v2.domain

/**
 * 回填任务运行状态（BackfillService 内存态，GET /api/v1/jobs/backfill/status）。
 *
 * 值域（非 schema 列，命名字典 §六 响应键区段留痕）：
 * - IDLE      从未触发（初始态；无复位机制，终态 COMPLETED/FAILED 永久保留直至重启/再次触发）
 * - RUNNING   后台回填执行中（防重入：运行中二次 POST → 422）
 * - COMPLETED 全部批次完成 + 派生列补算 + 情绪回放链已执行（含补算/回放失败降级）
 * - FAILED    批次执行抛未分类异常（BatchJob 内部失败批只记 failed，不置 FAILED）
 *
 * 设计定稿（2026-10-04）：单实例单进程内存态，不落库——回填按 ON CONFLICT DO UPDATE
 * 幂等，重启后丢失状态等价于"未跑过"，重跑即断点续传，无持久化检查点需求。
 */
enum class BackfillStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
}
