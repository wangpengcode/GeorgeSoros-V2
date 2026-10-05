# Kotlin 轨进展（均分流量重构 2026-10-05）

## 范围
① processSegment K=2 验证空契约（empty_sources ≥2 distinct 才验证空，否则 pending-empty）
② run() 轮次重扫（零进展停 + segments 空停 + 轮次硬上限）
③ KDoc 更新

## 进展
- [x] 2026-10-05 摸底：BackfillJob.kt / PythonApiModels.kt / BackfillJobRerunTest.kt 已读；测试风格=fake client+planService+mockito
- [x] 测试先行：BackfillJobRerunTest 改造（test24 改 pending 语义 + 新增 K=2 契约用例 ×5）
- [x] 测试先行：新增 BackfillJobRoundRescanTest（轮次重扫 6 用例）
- [x] 跑红：18 tests, 7 failed（新契约未实现，符合预期）
- [x] 实现：DTO emptySources + processSegment K=2 + collectPlanDrivenRounds + CollectResult/ChunkResult 扩展 + KDoc
- [x] 跑绿：两目标测试类全绿
- [x] 全量回归：451 tests, 0 failures, 0 skipped（含 TestContainers 集成测试）
- [x] 集成测试同步：BackfillRerunPlanIntegrationTest 端到端用例更新为轮次重扫语义
  （FakePythonClient 模拟 Python _empty_votes 跨轮累积：首轮单源空 pending → 第 2 轮换源 K=2 确认收口；
  断言重拉仅限 pending 票各 2 次，齐票/成功票零重拉）

## 改动文件
- src/main/kotlin/com/soros/v2/job/BackfillJob.kt（K=2 分支 + collectPlanDrivenRounds + 轮次上限守卫 + KDoc）
- src/main/kotlin/com/soros/v2/service/dto/PythonApiModels.kt（StockBarsResult.emptySources）
- src/test/kotlin/com/soros/v2/job/BackfillJobRerunTest.kt（K=2 契约 ×5 + FakePlanService 收敛语义）
- src/test/kotlin/com/soros/v2/job/BackfillJobRoundRescanTest.kt（新增，轮次重扫 6 用例）
- src/test/kotlin/com/soros/v2/integration/BackfillRerunPlanIntegrationTest.kt（端到端轮次语义）

## 实现要点（穿透校验过的语义）
- 轮次重扫停止三选一：segments 空 / 整轮零进展（0 行+0 验证空+0 待确认） / MAX_RESYNC_ROUNDS=10
- 达上限后不再重建计划（build 次数与轮次同界，≤10）
- 零进展判据不含 failed 数（失败≠进展）；pending-empty 计进展（下轮 K=2 可完成确认）
- 水位推进 jdbcTemplate.update(sql, day, code, day) 3 参签名
- 遗留 legacy 路径（planService=null）未动，CollectResult/ChunkResult 新字段带默认值=0

## 未做（红线遵守）
- 未 git commit；未重启服务；未写数据库；未发外部请求
