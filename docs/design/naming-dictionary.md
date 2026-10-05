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
| max_trade_date | 响应键（非列名） | GET /history/max/date/{code} 增量锚点响应键（V1 兼容语义，非 schema 列，§5 枚举表不收录） |
| degraded | 响应键（非列名） | POST /board-members 降级标记（任一板块拉取失败/降级 → true，消费侧跳过清空防误清全库；非 schema 列，§5 枚举表不收录） |
| stock_history_stage | **表级特例**（非字段） | 回填 COPY 中转表（§六.1，UNLOGGED，无主键/唯一约束/CHECK——约束拖慢 COPY）；列名/口径与 stock_history 全同，不新增任何字段 |
| board | 同名不同义 | stock_info.board=市场板（MAIN/GEM/STAR）；sector_daily.board=行业（industry 主口径，§19.11.1 决策 1，概念不落表条件现算，市场板另置） |

## 四、JSONB 内部键（同受本册约束）

| 所在 | 键名 | 说明 |
|---|---|---|
| limit_up_list/limit_down_list/big_meat_list/big_face_list/collapse_list | code,name,change_pct,limit_up_streak,industry | 全库统一键名 |
| dragon_json | code,name,limit_up_streak,board,industry | 原 streak 已改 |
| intraday_event.detail | limit_up_streak,seal_amount,zhaban_count,change_pct | 原 streak/chg 已改 |
| intraday_replay.page.ladder | code,name,limit_up_streak,change_pct,seal_amount,first_seal_time,zhaban_count,limit_stat | 样稿简写 M7 对齐 |
| kpi_series | t,zt,zb,dt,prem,adr,adv,dec,max_streak,gap_pct | 面板序列专用缩写（adr=涨跌家数比），§14.4 留痕 |

## 五、全字段枚举（字段 ｜ 含义 ｜ 使用表，220 列 / 126 个唯一字段名，脚本自 schema.sql 生成）

| 字段 | 含义 | 使用的地方 |
|---|---|---|
| `id` | 行主键 | stock_history、stock_info、stock_fundamentals、data_quality_log、stock_index、index_history、sentiment_cycle、dragon_cycle、strategy_config、strategy_config_history、backtest_result、trade_ledger、account_state、account_position、watchlist_group、watchlist_member、intraday_pool_snap、intraday_event、daily_note |
| `code` | 证券代码 | stock_history、stock_info、stock_fundamentals、data_quality_log、stock_index、index_history、dragon_cycle、signal_daily、trade_ledger、account_position、watchlist_member、intraday_event、intraday_archive、daily_note |
| `trade_date` | 交易日 | stock_history、trading_calendar、index_history、sentiment_cycle、market_daily、sector_daily、signal_daily、intraday_event、intraday_archive、intraday_replay、daily_note |
| `open` | 开盘价（元，qfq） | stock_history、index_history |
| `close` | 收盘价（元，qfq） | stock_history、index_history |
| `high` | 最高价（元，qfq） | stock_history、index_history |
| `low` | 最低价（元，qfq） | stock_history、index_history |
| `volume` | 成交量（股） | stock_history、index_history |
| `amount` | 成交额（元） | stock_history、index_history |
| `change_pct` | 涨跌幅%（不复权口径） | stock_history |
| `turnover_rate` | 换手率% | stock_history |
| `is_limit_up` | 涨停（按原始 change_pct + board 阈值判定） | stock_history |
| `is_limit_down` | 跌停 | stock_history |
| `limit_up_streak` | 连板数（首板=1，0=非涨停/断板；§4.8 派生） ｜各表：连板数（首板=1，源接口'连板数'） | stock_history、intraday_archive |
| `limit_down_streak` | 跌停连板（§4.9 崩塌池，镜像派生） | stock_history |
| `data_source` | 数据来源（failover 可见性；V6 起 CHECK 值域含 AKSHARE_SINA/YAHOO/TENCENT/SSE） | stock_history、index_history |
| `calibrated` | 是否已校准（CalibrationJob 低频对拍通过后置位；V5） | stock_history |
| `calibrated_source` | 校准对照源（DataSourceType 枚举名） | stock_history |
| `calibrated_at` | 校准时间 | stock_history |
| `created_at` | 行创建时间 | stock_history、data_quality_log、index_history、sentiment_cycle、dragon_cycle、strategy_config、strategy_config_history、backtest_result、trade_ledger、watchlist_group、watchlist_member、intraday_event、daily_note |
| `name` | 名称（列名同名同义：具体对象随表：策略/分组/指数/股票/板块） | stock_info、stock_index、strategy_config、watchlist_group、intraday_event |
| `market` | SH / SZ | stock_info |
| `board` | 市场板（MAIN 主板/GEM 创业板/STAR 科创板）；sector_daily 特例=行业（industry 主口径，§19.11.1） | stock_info、sector_daily |
| `is_st` | 仅用于"识别并排除"，禁止作为业务可选项 | stock_info |
| `delisted` | 缺失≠退市：人工确认才置 true | stock_info |
| `ipo_date` | BaoStock ipoDate；§4.8 IPO 首 5 日守卫 | stock_info |
| `industry` | JSON 数组（一股可属多行业，主行业=第一个，展示用） | stock_info |
| `concept_boards` | JSON 数组 | stock_info |
| `search_key` | 小写 name+全拼+拼音首字母，pg_trgm 模糊搜索 | stock_info |
| `float_shares` | 流通股本（股）；东财快照流通市值÷收盘价反推，BaoStock profit 季度对拍（§17.1 B1） | stock_info |
| `total_shares` | 总股本（股）；市值=股本×当日收盘价，条件求值时现算不落市值列 | stock_info |
| `shares_updated_at` | 股本刷新时间 | stock_info |
| `adj_processed_until` | 除权处理水位（§17.1 B6）：AdjustCheckStep 重算完该股筹码后更新，扫描时水位覆盖即跳过 | stock_info |
| `updated_at` | 行更新时间 | stock_info、stock_fundamentals、stock_index、dragon_cycle、account_state、account_position、intraday_pool_state、daily_note |
| `report_date` | 报告期（季度末），季度/年度通吃 | stock_fundamentals |
| `revenue` | 元（源亿元 ×1e8） | stock_fundamentals |
| `net_profit` | 元 | stock_fundamentals |
| `check_date` | 检查执行日 | data_quality_log |
| `issue_type` | ADJUSTMENT_DRIFT / DELIST_SUSPECT / CONDITION_SKIP(§17.1 B2 条件跳过问题表) / ... | data_quality_log |
| `detail` | 明细（data_quality_log=问题描述文本；intraday_event=事件详情 JSONB {limit_up_streak,seal_amount,zhaban_count,change_pct,kelly}） | data_quality_log、intraday_event |
| `source` | 来源标记 | data_quality_log、trade_ledger |
| `limit_up_count` | 涨停家数（sentiment/market 全市场；sector_daily 板块内） | sentiment_cycle、market_daily、sector_daily |
| `limit_down_count` | 跌停家数 ｜各表：同上 | sentiment_cycle、market_daily |
| `lianban_count` | 连板家数（limit_up_streak>=2） | sentiment_cycle |
| `max_streak` | 最高连板（sentiment=当日 / dragon=周期内 / sector=板块内） | sentiment_cycle、dragon_cycle、sector_daily |
| `dragon_json` | 高度龙明细 [{code,name,limit_up_streak,board,industry}] | sentiment_cycle |
| `pool_count` | 强势池家数 | sentiment_cycle |
| `big_meat_count` | 大肉数（池内今日>=+5%） | sentiment_cycle |
| `big_face_count` | 大面数（池内今日<=-5%） | sentiment_cycle |
| `big_meat_list` | [{code,name,change_pct,limit_up_streak,industry}] | sentiment_cycle |
| `big_face_list` | 结构同上 | sentiment_cycle |
| `followup_json` | 昨日名单今日兑现 [{code,name,src,yest_pct,today_pct,result}] | sentiment_cycle |
| `lists_manual_json` | 名单人工增删留痕 [{side,action,code,name,reason,at}] | sentiment_cycle |
| `leader_json` | 龙头前三名 晋级/断板/大面 | sentiment_cycle |
| `collapse_count` | 崩塌池家数 | sentiment_cycle |
| `collapse_list` | 崩塌池名单 [{code,name,limit_down_streak,industry}]（§17.5 C1，与大肉/大面名单同构） | sentiment_cycle |
| `rebound_count` | 崩塌组今日止跌反核数 | sentiment_cycle |
| `big_cycle_sug` | 大周期建议值 1-6（规则映射） | sentiment_cycle |
| `small_cycle_sug` | 小周期建议值 1-6（规则映射） | sentiment_cycle |
| `big_cycle` | 人工确认值（null=未确认，展示取建议值） | sentiment_cycle |
| `small_cycle` | 小周期人工确认值（null=未确认，展示取建议值） | sentiment_cycle |
| `status_text` | 冰点/混沌/主升/退潮…（建议标签人工终定） | sentiment_cycle |
| `data_coverage` | 数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%，§13.4） | sentiment_cycle、market_daily |
| `start_date` | 起始日（dragon_cycle=上位日；backtest_result=回测起始） | dragon_cycle、backtest_result |
| `end_date` | 结束日（dragon_cycle=阵亡/定性日 null=进行中；backtest_result=回测结束） | dragon_cycle、backtest_result |
| `rebreak_count` | 反包次数 | dragon_cycle |
| `suspended_days` | 停牌天数（停牌周期延续） | dragon_cycle |
| `suspend_json` | [{from,to}] | dragon_cycle |
| `cycle_type` | null=进行中未定性 | dragon_cycle |
| `status` | 状态（值域随表：周期 RISING/BROKEN/SUSPENDED/DEAD；配置 DRAFT/ACTIVE/FROZEN） | dragon_cycle、strategy_config |
| `broken_date` | 最近断板日（反包观察期起点，默认 3 交易日） | dragon_cycle |
| `note` | 备注/说明 | dragon_cycle、strategy_config、watchlist_group、watchlist_member |
| `adv_count` | 上涨家数 | market_daily |
| `dec_count` | 下跌家数 | market_daily |
| `limit_up_list` | [{code,name,change_pct,limit_up_streak,industry,reason}] | market_daily |
| `limit_down_list` | 同构 | market_daily |
| `zhaban_count` | 炸板次数（market_daily 日线近似口径；intraday_archive 池接口原值） | market_daily、intraday_archive |
| `yst_limit_premium` | 昨涨停今溢价 = 昨名单 ∘ 今行情（表自算自洽） | market_daily |
| `yst_promotion` | 分级晋级率 {"total":21.05,"by_level":{"1to2":33.3,...}} | market_daily |
| `yst_face_count` | 昨日大面家数 | market_daily |
| `avg_chg_pct` | 板块涨停名单平均涨幅% | sector_daily |
| `avg_chg_pct_all` | 板块全成员平均涨幅%（词表 #9「板块涨幅榜前列」，§19.11.1 决策 3） | sector_daily |
| `driver_text` | 当日板块驱动主线（LLM 生成，标"系统生成"） | sector_daily |
| `ladder_rank` | 当日梯队排名（全市场排序才得出） | signal_daily |
| `is_zhaban` | 炸板（日线近似，统一口径落库） | signal_daily |
| `sector_ladder_rank` | 板块内板数排名 | signal_daily |
| `profit_ratio` | 获利盘% | signal_daily |
| `cost_dev` | 成本偏离% = (close−avg_cost)/avg_cost×100（qfq 重对基免疫） | signal_daily |
| `c90_low` | 90% 成本区间下沿（p5 分位，qfq 坐标） | signal_daily |
| `c90_high` | 90% 成本区间上沿（p95 分位，qfq 坐标） | signal_daily |
| `c90_conc` | 90% 集中度（东财口径 (p95−p5)/(p95+p5)×100，qfq 坐标） | signal_daily |
| `c70_low` | 70% 成本区间下沿（p15 分位，qfq 坐标） | signal_daily |
| `c70_high` | 70% 成本区间上沿（p85 分位，qfq 坐标） | signal_daily |
| `c70_conc` | 70% 集中度（(p85−p15)/(p85+p15)×100，qfq 坐标） | signal_daily |
| `yaml` | 配置 YAML 全文 | strategy_config、strategy_config_history |
| `version` | 配置版本号（保存 +1） | strategy_config、strategy_config_history |
| `alert_enabled` | 盘中开仓预警开关（§14.9，结果对比页开启） | strategy_config |
| `created_by` | 创建/编辑人 | strategy_config |
| `config_id` | 关联 strategy_config.id（FK） | strategy_config_history、backtest_result |
| `strategy_name` | 策略名（与 strategy_config 对应） ｜各表：策略名 | backtest_result、trade_ledger |
| `params` | 策略 YAML + L1 参数全样 | backtest_result |
| `metrics` | 年化/回撤/Sharpe/胜率/盈亏比/Kelly 组… | backtest_result |
| `equity_curve` | 策略 vs 沪深300 vs 等权基准 | backtest_result |
| `config_version` | 保存时点的 strategy_config.version | backtest_result |
| `evaluated_universe` | §17.1 B3：本次实际求值的股票名单（实盘下单前守卫校验） | backtest_result |
| `is_dry` | §17.2：试跑标记，不落台账、对比默认过滤 | backtest_result |
| `data_snapshot` | {maxDate,rowCount} | backtest_result |
| `git_sha` | 代码版本（可复现三件套之一） | backtest_result |
| `open_date` | 开仓日 | trade_ledger |
| `close_date` | 平仓日（null=持仓中） | trade_ledger |
| `pnl` | 盈亏额（元） | trade_ledger |
| `pnl_ratio` | 盈亏率（%） | trade_ledger |
| `backtest_result_id` | §17.1 B5：BACKTEST 行溯源到 backtest_result.id | trade_ledger |
| `cash` | 手动维护，不对接券商 | account_state |
| `shares` | 整手 | account_position |
| `cost_price` | 成本价（元） | account_position |
| `group_id` | 所属分组 FK→watchlist_group.id | watchlist_member |
| `snap_at` | 快照时间（每轮采样点） | intraday_pool_snap |
| `pool` | 池标识（ZT/ZB/DT/STRONG/PREV） | intraday_pool_snap、intraday_pool_state、intraday_archive |
| `payload` | 接口原样行（未来字段升级不丢） | intraday_pool_snap |
| `enabled` | 是否启用 | intraday_pool_state |
| `reason` | 手动停用原因（如限频封禁规避） | intraday_pool_state |
| `ev_time` | 事件时间 | intraday_event |
| `ev_type` | 事件类型（封板/炸板/ALERT…，CHECK 约束） | intraday_event |
| `pushed_dd` | 钉钉已推送标记 | intraday_event |
| `first_seal_time` | HH:MM:SS（词表升级：早封/晚封板） | intraday_archive |
| `last_seal_time` | 最后封板时间（HH:MM:SS） | intraday_archive |
| `seal_amount` | 封板资金（元） | intraday_archive |
| `complete` | 15:10 归档补齐后置 true | intraday_replay |
| `page` | 页面（intraday_replay=整页渲染 JSONB；daily_note=页面枚举） | intraday_replay、daily_note |
| `content` | 笔记内容（纯人工日志，不进策略条件） | daily_note |
| `seg_from` | 已验证空段起点（含；V7 回填验证空段台账，防停牌反复空拉） | stock_history_gap_check |
| `seg_to` | 已验证空段终点（含） | stock_history_gap_check |
| `rows_returned` | 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空） | stock_history_gap_check |
| `checked_at` | 验证时间（timestamptz） | stock_history_gap_check |

## 六、响应 JSON 键区段（非列名，受本册约束，先增册再用名）

> 对外响应 JSON 键（非 schema 列）同样受命名字典约束：同一语义=同一键名。与 §3 特例（max_trade_date/degraded）并列，集中留痕。

| 键名 | 含义 | 所属端点/DTO |
|---|---|---|
| `stocks` | 股票列表数组（非列名；/stock-list 与 /stock-search 系列响应数组，语义同 Python §11.1 契约） | GET /stock-list（Python）、/stock-search 消费兼容 |
| `items` | 区间/列表响应数组（升序；情绪曲线与龙头时间轴数据源） | GET /sentiment-cycle/range、GET /dragon-cycle |
| `actions` | 个股动作标签清单数组 | GET /sentiment-cycle/{date}/terms、GET /stocks/{code}/actions |
| `label` | 个股动作标签值（反包\|晋级\|断板\|反核止跌\|继续大面\|大肉\|大面\|停牌） | /terms 与 /stocks/{code}/actions 的 actions[] 元素 |
| `evidence` | 判定口径说明（证据文本） | /terms 与 /stocks/{code}/actions 的 actions[] 元素 |
| `stage` | 阶段标签（冰点~高潮，status_text 建议标签） | GET /sentiment-cycle/{date}/terms |
| `dragon_name` | 当前龙头名称（null=无） | GET /sentiment-cycle/{date}/terms |
| `dragon_status` | 龙头状态 RISING/BROKEN/SUSPENDED/DEAD（null=无进行中龙头） | GET /sentiment-cycle/{date}/terms |
| `code` | 证券代码（响应键复用列名语义） | /stock-search、/stocks/{code}/actions |
| `name` | 名称（响应键复用列名语义） | /stock-search、/terms actions[] |
| `industry` | 行业 JSON 数组（主行业=第一个，展示用；响应键复用列名语义） | /stock-search、limit_up_list 系列 |
| `status` | 回填任务状态（IDLE/RUNNING/COMPLETED/FAILED；非列名语义随端点，与 dragon_cycle.status 同名字典留痕） | POST /jobs/backfill、GET /jobs/backfill/status |
| `progress` | 回填进度对象（非列名） | GET /jobs/backfill/status |
| `total_codes` | 本次回填股票总数 | GET /jobs/backfill/status 响应 progress |
| `processed_codes` | 已处理股票数（成功+失败） | GET /jobs/backfill/status 响应 progress |
| `succeeded_codes` | 入库成功股票数 | GET /jobs/backfill/status 响应 progress |
| `failed_codes` | 失败股票数 | GET /jobs/backfill/status 响应 progress |
| `total_batches` | 总批数 | GET /jobs/backfill/status 响应 progress |
| `processed_batches` | 已处理批数 | GET /jobs/backfill/status 响应 progress |
| `total_rows` | 本次回填目标总行数（0=尚未确定） | GET /jobs/backfill/status 响应 progress |
| `processed_rows` | 已入库行数（COPY 合并进主表） | GET /jobs/backfill/status 响应 progress |
| `current_batch` | 当前批序号（1-based） | GET /jobs/backfill/status 响应 progress |
| `started_at` | 启动时间（ISO-8601） | GET /jobs/backfill/status 响应 progress |
| `finished_at` | 结束时间（ISO-8601；null=运行中） | GET /jobs/backfill/status 响应 progress |
| `error` | 失败原因（仅 FAILED 非 null） | GET /jobs/backfill/status 响应 |
| `stock_count` | 本次刷新后的有效股票数（已过滤 ST/退市/北交所） | POST /info/refresh 响应 |
| `items` | batch 逐段窗口请求数组（{code,start_date,end_date}；与 codes+dates 二选一） | POST /daily-bars/batch 请求体（Python） |
| `segments` | 重跑计划缺失段扁平清单（一票可多条，全齐票零段） | BackfillPlanService.buildRerunPlan 出参 |
| `reason` | 缺失段分类原因 HEAD/TAIL/MID/NO_DATA（与 failed[] 的 reason=失败文案不同义，§3 特例留痕） | FetchSegment、failed[] 元素 |
| `from` | 回放区间起点（ISO 日期） | POST /jobs/signal-replay、POST /jobs/sentiment-replay 响应 SignalReplaySummary |
| `to` | 回放区间终点（ISO 日期） | POST /jobs/signal-replay、POST /jobs/sentiment-replay 响应 SignalReplaySummary |
| `signal_rows` | 落库 signal_daily 行数（§19.11.1 回放摘要） | POST /jobs/signal-replay 响应 SignalReplaySummary |
| `market_rows` | 落库 market_daily 行数（§19.11.1 回放摘要） | POST /jobs/signal-replay 响应 SignalReplaySummary |
| `sector_rows` | 落库 sector_daily 行数（§19.11.1 回放摘要） | POST /jobs/signal-replay 响应 SignalReplaySummary |
| `codes_processed` | 处理股票数（§19.11.1 回放摘要） | POST /jobs/signal-replay 响应 SignalReplaySummary |
