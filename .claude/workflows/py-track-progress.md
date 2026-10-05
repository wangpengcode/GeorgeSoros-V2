# Python 轨进展（均分流量重构 2026-10-05）

- [x] 侦察完成：base.py/router.py/models.py/channels.py/config.py/circuit_breaker.py 全读；旧分片引用面 = tests/test_sharding.py、test_batch_parallel.py、test_ipguard.py(2处)、test_yahoo_adapter.py(1处)、test_data_router_ledger.py(failover 语义)、test_api_contract.py(1处 reason)、test_batch_items.py(1处 reason)
- [x] TDD 红：test_sharding.py 重写为轮转语义（15 用例）、test_em_leg.py 新建（4 用例）、test_baostock_relogin.py 追加网络自愈（2 用例）；17 新用例先红
- [x] 实现：全部落地（见下变更清单）
- [x] 全量回归绿：228 passed（基线 209，净增 21 用例），无 skip

## 变更清单（全部为 2026-10-05 均分流量定稿）

**源代码：**
- `adapters/base.py`：删 `_shard_owner`/`_ordered_for_stock`（取模分片）+ 删 `_fetch_stock_daily` inline failover；新增 `_healthy_candidates`（封禁/熔断open/驱逐/复权能力过滤，half_open 保留）、`_assign_source`（健康源轮转+空票偏好）、源级驱逐（连续 2 次 SourceError → 300s 冷却，成功清零）、`_empty_votes` 空票（跨轮按源累计、取到数据清）、空结果=count=0 占位（带 empty_sources）、`fetch_daily_bars(..., source=)` 钉源参数、`_channel_counters`/`channel_counters()`（total_calls/empty_results/failures，未发请求不计 total_calls）、可注入时钟 `_clock`
- `adapters/baostock_adapter.py`：`_query_with_session_retry` 增加「网络接收错误」自愈分支（重登录+重试一次，与会话过期同路径）——生产事故①根因修复
- `adapters/akshare_adapter.py`：EM 腿级熔断（连续 3 次失败 → 腿开直走新浪，300s 一次探针，探针成/败分别闭合/刷新窗口；EM 失败仍不上抛不烧源级熔断）；`import time`
- `router.py`：两个 batch 端点改为「每票 _assign_source 轮转分配 → 按分配源分组并行 → worker 钉死 source」；无健康源直接 failed 零请求；删 `_is_all_sources_empty`/`_placeholder_source`（占位下沉到 fetch 层，codes/items 两模式语义一致）；指数 code 走尾批
- `models.py`：`StockBarsResult.empty_sources: Optional[list[str]]`（非空恒 null，缺省=Spring 保守不推水位）；`ChannelStatus` 增 total_calls/empty_results/failures
- `channels.py`：透出调用计数
- `config.py`：`shard_sources` 标注废弃（保留字段兼容旧环境变量，不参与路由）

**测试：** 重写 test_sharding.py（15）、test_batch_parallel.py（9）、test_router_failover.py（6）；新建 test_em_leg.py（4）；追加 test_baostock_relogin.py（2）；更新 test_data_router_ledger.py、test_channels_endpoint.py（键集+计数）、test_yahoo_adapter.py、test_sina_failover.py、test_sse_adapter.py、test_tencent_adapter.py、test_index.py、test_api_contract.py、test_batch_items.py、test_ipguard.py（改名）

## 部署注意（与 Kotlin 轨对齐）
- 部署顺序：Spring 先发（batch 响应多出 empty_sources=null 字段，旧 Spring 未知字段忽略=保守不推水位）→ Python 后发
- 轮转状态/空票/计数均进程态，重启即清
