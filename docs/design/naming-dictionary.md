# 全系统命名字典（2026-10-03 命名穿透产出）

**规则**：同一语义 = 同一列名，全库（26 表 → Kotlin Entity → Python DTO → 前端消费）一致；
同名必同义；特例必须在本册留痕。新表/新列/新 DTO 字段先查本册，没有的概念先增册再用名。

## 一、语义 → 统一命名（已全库对齐）

| 语义概念 | 统一列名/键名 | 口径与特例 |
|---|---|---|
| 证券代码 | `code` | 股票=裸数字 600000；指数=带前缀 sh000001（值口径特例，仅 stock_index/index_history） |
| 交易日 | `trade_date` | 各表行归属交易日；trading_calendar 主键 |
| 开盘价/最高价/最低价/收盘价 | `open/high/low/close` | qfq 前复权（元）；指数不除权直接点位 |
| 成交量 | `volume` | 统一=股（AKShare 手×100，M0 探针校准） |
| 成交额 | `amount` | 元（原 total_amount 已废） |
| 涨跌幅 | `change_pct` | 不复权真实涨跌幅% |
| 换手率 | `turnover_rate` | % |
| 连板数 | `limit_up_streak` | 首板=1；Kotlin 派生；池接口'连板数'映射至此 |
| 跌停连板数 | `limit_down_streak` | 首日=1；池接口 DT'连续跌停'映射至此 |
| 昨日连板数 | `prev_limit_up_streak` | 仅 PREV 池 DTO |
| 最高连板 | `max_streak` | sentiment_cycle（当日）/dragon_cycle（周期内）/sector_daily（板块内） |
| 涨停家数 | `limit_up_count` | sentiment_cycle/market_daily/sector_daily |
| 跌停家数 | `limit_down_count` | sentiment_cycle/market_daily |
| 封板资金 | `seal_amount` | 仅 ZT（元）；DT 的'封单资金'=seal_order_amount 独立字段 |
| 首次/最后封板时间 | `first_seal_time/last_seal_time` | HH:MM:SS |
| 炸板次数 | `zhaban_count` | ZT/ZB/归档 |
| 策略名 | `strategy_name` | backtest_result/trade_ledger（trade_ledger 原 strategy 已废） |
| 配置版本 | `config_id/config_version` | strategy_config_history/backtest_result 同名同义 |
| 流通/总股本 | `float_shares/total_shares` | 股；市值=股本×收盘价现算不落列 |
| 行创建时间 | `created_at` | 全库统一（原 added_at/edited_at 已废） |
| 行更新时间 | `updated_at` | 全库统一 |
| 池标识 | `pool` | CHAR(6)：ZT/ZB/DT/STRONG/PREV |

## 二、2026-10-03 穿透改名清单（V1 遗留 st_ 家族清零，库未建零迁移成本）

| 原 | 新 | 涉及表数 |
|---|---|---|
| st_code / stock_code | code | 12 |
| st_date | trade_date | 7 |
| st_open/st_high/st_low/st_close | open/high/low/close | 2（stock_history/index_history） |
| st_volume | volume | 2 |
| total_amount | amount | 2 |
| streak（裸） | limit_up_streak | 1（intraday_archive） |
| dt_streak | limit_down_streak | 0（映射表内） |
| prev_streak | prev_limit_up_streak | 0（映射表内） |
| st_name | name | 1（intraday_event） |
| strategy（裸，trade_ledger） | strategy_name | 1 |
| added_at / edited_at | created_at | 2 |

## 三、特例与保留留痕（同名不同义 / 概念不同不算不一致）

| 名称 | 性质 | 说明 |
|---|---|---|
| code 带前缀 | **值口径特例**（非列名） | 仅指数表值带 sh；列名全库统一 code |
| check_date | 概念不同 | data_quality_log 检查执行日（非交易日归属概念） |
| report_date | 概念不同 | stock_fundamentals 报告期（季度末） |
| start_date/end_date | 概念不同 | 区间端点（dragon_cycle 周期、backtest_result 回测区间） |
| open_date/close_date | 概念不同 | trade_ledger 开仓/平仓日 |
| snap_at / ev_time | 概念不同 | 快照时间 / 事件时间（非行 created_at） |
| shares_updated_at / adj_processed_until | 概念不同 | stock_info 字段级水位（非行 updated_at） |
| seal_amount vs seal_order_amount | 同名前缀不同义 | 封板资金（ZT）vs 封单资金（DT）——映射表显式区分 |
| payload / page / detail | 概念不同 | 原样快照 / 整页渲染 / 事件详情（三种 JSONB） |
| st_change（历史留痕） | 已废误名 | V1 换手率误名，字典留痕防旧记忆复活 |

## 四、JSONB 内部键（同受本册约束）

| 所在 | 键名 | 说明 |
|---|---|---|
| limit_up_list/limit_down_list/big_meat_list/big_face_list/collapse_list | code,name,change_pct,limit_up_streak,industry | 全库统一键名 |
| dragon_json | code,name,limit_up_streak,board,industry | 原 streak 已改 |
| intraday_event.detail | limit_up_streak,seal_amount,zhaban_count,change_pct | 原 streak/chg 已改 |
| intraday_replay.page.ladder | code,name,limit_up_streak,change_pct,seal_amount,first_seal_time,zhaban_count,limit_stat | 样稿简写 M7 对齐 |
| kpi_series | t,zt,zb,dt,prem,adr,adv,dec,max_streak,gap_pct | 面板序列专用缩写（adr=涨跌家数比），§14.4 留痕 |

## 五、全库逐列清单（自动生成自 schema.sql，26 表）

- **stock_history**：`id`（行主键） · `code`（600000（裸数字，不带 sh/sz）） · `trade_date`（交易日） · `open`（qfq） · `close`（qfq） · `high`（qfq） · `low`（qfq） · `volume`（统一单位=股） · `amount`（成交额（元）） · `change_pct`（涨跌幅%（不复权口径）） · `turnover_rate`（换手率%） · `is_limit_up`（涨停（按原始 change_pct + board 阈值判定）） · `is_limit_down`（跌停） · `limit_up_streak`（连板数（首板=1，0=非涨停/断板；§4.8 派生）） · `limit_down_streak`（跌停连板（§4.9 崩塌池，镜像派生）） · `data_source`（数据来源（failover 可见性）） · `created_at`（行创建时间）
- **stock_info**：`id`（行主键） · `code`（600000） · `name`（名称） · `market`（SH / SZ） · `board`（市场板（MAIN 主板/GEM 创业板/STAR 科创板）） · `is_st`（仅用于"识别并排除"，禁止作为业务可选项） · `delisted`（缺失≠退市：人工确认才置 true） · `ipo_date`（BaoStock ipoDate；§4.8 IPO 首 5 日守卫） · `industry`（JSON 数组（一股可属多行业，主行业=第一个，展示用）） · `concept_boards`（JSON 数组） · `search_key`（小写 name+全拼+拼音首字母，pg_trgm 模糊搜索） · `float_shares`（流通股本（股）；东财快照流通市值÷收盘价反推，BaoStock profit 季度对拍（§17.1 B1）） · `total_shares`（总股本（股）；市值=股本×当日收盘价，条件求值时现算不落市值列） · `shares_updated_at`（股本刷新时间） · `adj_processed_until`（除权处理水位（§17.1 B6）：AdjustCheckStep 重算完该股筹码后更新，扫描时水位覆盖即跳过） · `updated_at`（行更新时间）
- **stock_fundamentals**：`id`（行主键） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `report_date`（报告期（季度末），季度/年度通吃） · `revenue`（元（源亿元 ×1e8）） · `net_profit`（元） · `updated_at`（行更新时间）
- **data_quality_log**：`id`（行主键） · `check_date`（检查执行日） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `issue_type`（ADJUSTMENT_DRIFT / DELIST_SUSPECT / CONDITION_SKIP(§17.1 B2 条件跳过问题表) / ...） · `detail`（明细/问题描述） · `source`（来源/触发源） · `created_at`（行创建时间）
- **stock_index**：`id`（行主键） · `code`（sh000001（指数带前缀，特例）） · `name`（名称） · `updated_at`（行更新时间）
- **trading_calendar**：`trade_date`（交易日）
- **index_history**：`id`（行主键） · `code`（sh000001（带前缀）） · `trade_date`（交易日） · `open`（开盘价（元）） · `close`（收盘价（元）） · `high`（最高价（元）） · `low`（最低价（元）） · `volume`（成交量（股）） · `amount`（成交额（元）） · `data_source`（数据来源（failover 可见性）） · `created_at`（行创建时间）
- **sentiment_cycle**：`id`（行主键） · `trade_date`（交易日） · `limit_up_count`（涨停家数） · `limit_down_count`（跌停家数） · `lianban_count`（连板家数（limit_up_streak>=2）） · `max_streak`（当日最高板） · `dragon_json`（高度龙明细 [{code,name,limit_up_streak,board,industry}]） · `pool_count`（强势池家数） · `big_meat_count`（大肉数（池内今日>=+5%）） · `big_face_count`（大面数（池内今日<=-5%）） · `big_meat_list`（[{code,name,change_pct,limit_up_streak,industry}]） · `big_face_list`（结构同上） · `followup_json`（昨日名单今日兑现 [{code,name,src,yest_pct,today_pct,result}]） · `lists_manual_json`（名单人工增删留痕 [{side,action,code,name,reason,at}]） · `leader_json`（龙头前三名 晋级/断板/大面） · `collapse_count`（崩塌池家数） · `collapse_list`（崩塌池名单 [{code,name,limit_down_streak,industry}]（§17.5 C1，与大肉/大面名单同构）） · `rebound_count`（崩塌组今日止跌反核数） · `big_cycle_sug`（大周期建议值 1-6（规则映射）） · `small_cycle_sug`（小周期建议值 1-6（规则映射）） · `big_cycle`（人工确认值（null=未确认，展示取建议值）） · `small_cycle`（小周期人工确认值（null=未确认，展示取建议值）） · `status_text`（冰点/混沌/主升/退潮…（建议标签人工终定）） · `created_at`（行创建时间）
- **dragon_cycle**：`id`（行主键） · `code`（龙头代码） · `start_date`（上位日） · `end_date`（阵亡/定性日（null=进行中）） · `max_streak`（周期内最高连板） · `rebreak_count`（反包次数） · `suspended_days`（停牌天数（停牌周期延续）） · `suspend_json`（[{from,to}]） · `cycle_type`（null=进行中未定性） · `status`（周期状态（RISING/BROKEN/SUSPENDED/DEAD）） · `broken_date`（最近断板日（反包观察期起点，默认 3 交易日）） · `note`（备注） · `created_at`（行创建时间） · `updated_at`（行更新时间）
- **market_daily**：`trade_date`（交易日） · `adv_count`（上涨家数） · `dec_count`（下跌家数） · `limit_up_count`（冗余 = jsonb_array_length(limit_up_list)，同源校验） · `limit_down_count`（同上） · `limit_up_list`（[{code,name,change_pct,limit_up_streak,industry,reason}]） · `limit_down_list`（同构） · `zhaban_count`（炸板家数（日线近似口径）） · `yst_limit_premium`（昨涨停今溢价 = 昨名单 ∘ 今行情（表自算自洽）） · `yst_promotion`（分级晋级率 {"total":21.05,"by_level":{"1to2":33.3,...}}） · `yst_face_count`（昨日大面家数）
- **sector_daily**：`trade_date`（交易日） · `board`（市场板（MAIN 主板/GEM 创业板/STAR 科创板）） · `limit_up_count`（板块内涨停家数） · `max_streak`（板块内最高连板） · `avg_chg_pct`（板块涨停名单平均涨幅%） · `driver_text`（当日板块驱动主线（LLM 生成，标"系统生成"））
- **signal_daily**：`code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `trade_date`（交易日） · `streak_rank`（当日梯队排名（全市场排序才得出）） · `is_zhaban`（炸板（日线近似，统一口径落库）） · `sector_streak_rank`（板块内板数排名） · `profit_ratio`（获利盘%） · `cost_dev`（成本偏离% = 平均成本/现价−1（比率，qfq 重对基免疫）） · `c90_low`（90% 成本区间（qfq 坐标）） · `c90_high`（90% 成本区间上沿（元）） · `c90_conc`（90% 集中度（东财口径 (p90−p10)/(p90+p10)×100）） · `c70_low`（70% 成本区间下沿（元）） · `c70_high`（70% 成本区间上沿（元）） · `c70_conc`（70% 成本集中度（%））
- **strategy_config**：`id`（行主键） · `name`（名称） · `yaml`（落库即权威格式；backtest_result.params 的唯一来源） · `version`（保存即 version+1） · `status`（配置状态（DRAFT/ACTIVE/FROZEN）） · `alert_enabled`（盘中开仓预警开关（§14.9，结果对比页开启）） · `created_by`（创建/编辑人） · `created_at`（行创建时间） · `note`（备注）
- **strategy_config_history**：`id`（行主键） · `config_id`（源配置 FK→strategy_config.id） · `yaml`（配置 YAML 全文快照） · `version`（版本号（每次保存 +1）） · `created_at`（行创建时间）
- **backtest_result**：`id`（行主键） · `strategy_name`（策略名（与 strategy_config 对应）） · `params`（策略 YAML + L1 参数全样） · `start_date`（回测起始日） · `end_date`（回测结束日） · `metrics`（年化/回撤/Sharpe/胜率/盈亏比/Kelly 组…） · `equity_curve`（策略 vs 沪深300 vs 等权基准） · `config_id`（§17.2：结果↔配置版本绑定） · `config_version`（保存时点的 strategy_config.version） · `evaluated_universe`（§17.1 B3：本次实际求值的股票名单（实盘下单前守卫校验）） · `is_dry`（§17.2：试跑标记，不落台账、对比默认过滤） · `data_snapshot`（{maxDate,rowCount}） · `git_sha`（代码版本（可复现三件套之一）） · `created_at`（行创建时间）
- **trade_ledger**：`id`（行主键） · `strategy_name`（策略名） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `open_date`（开仓日） · `close_date`（null=持仓中） · `pnl`（盈亏额（元）） · `pnl_ratio`（盈亏率（%）） · `source`（来源/触发源） · `backtest_result_id`（§17.1 B5：BACKTEST 行溯源到 backtest_result.id） · `created_at`（行创建时间）
- **account_state**：`id`（行主键） · `cash`（手动维护，不对接券商） · `updated_at`（行更新时间）
- **account_position**：`id`（行主键） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `shares`（整手） · `cost_price`（成本价（元）） · `updated_at`（行更新时间）
- **watchlist_group**：`id`（行主键） · `name`（PCB / 存储芯片 / 创新药…） · `note`（分组说明） · `created_at`（行创建时间）
- **watchlist_member**：`id`（行主键） · `group_id`（所属分组 FK→watchlist_group.id） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `note`（个股备注） · `created_at`（行创建时间）
- **intraday_pool_snap**：`id`（行主键） · `snap_at`（快照时间（每轮采样点）） · `pool`（池标识（ZT/ZB/DT/STRONG/PREV）） · `payload`（接口原样行（未来字段升级不丢））
- **intraday_pool_state**：`pool`（池标识（ZT/ZB/DT/STRONG/PREV）） · `enabled`（是否启用） · `reason`（手动停用原因（如限频封禁规避）） · `updated_at`（行更新时间）
- **intraday_event**：`id`（行主键） · `trade_date`（交易日） · `ev_time`（事件时间） · `ev_type`（事件类型（封板/炸板/ALERT…，CHECK 约束）） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `name`（名称） · `detail`（{limit_up_streak,seal_amount,zhaban_count,chg,kelly{...}}） · `pushed_dd`（钉钉已推送标记） · `created_at`（行创建时间）
- **intraday_archive**：`trade_date`（交易日） · `code`（证券代码（股票=裸数字 600000；指数=带前缀 sh000001，仅 stock_index/index_history）） · `first_seal_time`（HH:MM:SS（词表升级：早封/晚封板）） · `last_seal_time`（最后封板时间（HH:MM:SS）） · `zhaban_count`（炸板次数） · `seal_amount`（封板资金（元）） · `limit_up_streak`（连板数（首板=1，源接口'连板数'）） · `pool`（池标识（ZT/ZB/DT/STRONG/PREV））
- **intraday_replay**：`trade_date`（交易日） · `complete`（15:10 归档补齐后置 true） · `page`（整页渲染数据（=GET /intraday/summary 同一 DTO 序列化））
- **daily_note**：`id`（行主键） · `trade_date`（归属交易日（按天维度组织）） · `page`（页面枚举（四页+GENERAL）） · `code`（个股笔记挂靠（K线页），NULL=市场级） · `content`（笔记内容（纯人工日志，不进策略条件）） · `created_at`（行创建时间） · `updated_at`（行更新时间）
