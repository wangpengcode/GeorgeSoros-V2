-- =============================================================================
-- GeorgeSoros-V2 全库 DDL（PostgreSQL 16）
-- 依据 docs/PLAN.md 设计定稿生成（2026-10-03），25 张表 · 6 层
-- 口径铁律：OHLC=qfq / change_pct&涨跌停判定=不复权 / ST 全链路隔离（采集侧过滤）
-- 派生列纪律：limit_up/down_streak 由应用写入；历史回填走 COPY 后必须窗口 SQL 补算（§六.6）
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =============================================================================
-- 一、数据底座层（一期采集，7 张）
-- =============================================================================

CREATE TABLE stock_history (
    id                BIGSERIAL PRIMARY KEY,
    st_code           VARCHAR(20) NOT NULL,      -- 600000（裸数字，不带 sh/sz）
    st_date           DATE NOT NULL,
    st_open           NUMERIC(12,4),             -- qfq
    st_close          NUMERIC(12,4),             -- qfq
    st_high           NUMERIC(12,4),             -- qfq
    st_low            NUMERIC(12,4),             -- qfq
    st_volume         BIGINT,                    -- 统一单位=股
    total_amount      NUMERIC(20,4),
    change_pct        NUMERIC(10,4),             -- 涨跌幅%（不复权口径）
    turnover_rate     NUMERIC(10,4),             -- 换手率%
    is_limit_up       BOOLEAN DEFAULT FALSE,     -- 涨停（按原始 change_pct + board 阈值判定）
    is_limit_down     BOOLEAN DEFAULT FALSE,     -- 跌停
    limit_up_streak   SMALLINT DEFAULT 0,        -- 连板数（首板=1，0=非涨停/断板；§4.8 派生）
    limit_down_streak SMALLINT DEFAULT 0,        -- 跌停连板（§4.9 崩塌池，镜像派生）
    data_source       VARCHAR(20) DEFAULT 'UNKNOWN'
                      CHECK (data_source IN ('BAOSTOCK','AKSHARE','MOOTDX','UNKNOWN')),
    created_at        TIMESTAMP DEFAULT NOW(),
    UNIQUE (st_code, st_date)
);
-- 索引审计（§2.2）：UNIQUE 自带 (st_code,st_date) 复合索引，勿再建同列普通索引
CREATE INDEX idx_history_date ON stock_history (st_date);   -- 按日全市场扫描

COMMENT ON COLUMN stock_history.st_open IS '前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用';
COMMENT ON COLUMN stock_history.st_volume IS '统一单位=股（AKShare/mootdx 手×100，探针实测校准）';

CREATE TABLE stock_info (
    id             SERIAL PRIMARY KEY,
    code           VARCHAR(20) NOT NULL UNIQUE, -- 600000
    name           VARCHAR(100),
    market         VARCHAR(10),                 -- SH / SZ
    board          VARCHAR(20) DEFAULT 'MAIN' CHECK (board IN ('MAIN','GEM','STAR')),
    is_st          BOOLEAN DEFAULT FALSE,       -- 仅用于"识别并排除"，禁止作为业务可选项
    delisted       BOOLEAN DEFAULT FALSE,       -- 缺失≠退市：人工确认才置 true
    ipo_date       DATE,                        -- BaoStock ipoDate；§4.8 IPO 首 5 日守卫
    industry       TEXT,                        -- JSON 数组（一股可属多行业，主行业=第一个，展示用）
    concept_boards TEXT,                        -- JSON 数组
    search_key     VARCHAR(300),                -- 小写 name+全拼+拼音首字母，pg_trgm 模糊搜索
    float_shares   BIGINT,                      -- 流通股本（股）；东财快照流通市值÷收盘价反推，BaoStock profit 季度对拍（§17.1 B1）
    total_shares   BIGINT,                      -- 总股本（股）；市值=股本×当日收盘价，条件求值时现算不落市值列
    shares_updated_at TIMESTAMP,                -- 股本刷新时间
    adj_processed_until DATE,                   -- 除权处理水位（§17.1 B6）：AdjustCheckStep 重算完该股筹码后更新，扫描时水位覆盖即跳过
    updated_at     TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_stock_info_board  ON stock_info (board);
CREATE INDEX idx_stock_info_st     ON stock_info (is_st, delisted);
CREATE INDEX idx_stock_info_search ON stock_info USING gin (search_key gin_trgm_ops);

CREATE TABLE stock_fundamentals (
    id          SERIAL PRIMARY KEY,
    st_code     VARCHAR(20) NOT NULL,
    report_date DATE NOT NULL,                  -- 报告期（季度末），季度/年度通吃
    revenue     NUMERIC(20,2),                  -- 元（源亿元 ×1e8）
    net_profit  NUMERIC(20,2),                  -- 元
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (st_code, report_date)
);

CREATE TABLE data_quality_log (
    id         SERIAL PRIMARY KEY,
    check_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    issue_type VARCHAR(50) NOT NULL,            -- ADJUSTMENT_DRIFT / DELIST_SUSPECT / CONDITION_SKIP(§17.1 B2 条件跳过问题表) / ...
    detail     TEXT,
    source     VARCHAR(20),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE stock_index (
    id         SERIAL PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,     -- sh000001（指数带前缀，特例）
    name       VARCHAR(100),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE trading_calendar (
    trade_date DATE PRIMARY KEY
);  -- AKShare tool_trade_date_hist_sina 全量；覆盖不足"明年年底"自动重拉续期

CREATE TABLE index_history (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(20) NOT NULL,          -- sh000001（带前缀）
    trade_date   DATE NOT NULL,
    st_open      NUMERIC(12,4),
    st_close     NUMERIC(12,4),
    st_high      NUMERIC(12,4),
    st_low       NUMERIC(12,4),
    st_volume    BIGINT,
    total_amount NUMERIC(20,4),
    data_source  VARCHAR(20) DEFAULT 'UNKNOWN',
    created_at   TIMESTAMP DEFAULT NOW(),
    UNIQUE (code, trade_date)
);  -- qfq 口径同 stock_history；DailyCollectJob 顺带采 5 个基准指数

-- =============================================================================
-- 二、情绪派生层（2 张，SentimentCycleJob 20:40 事件触发）
-- =============================================================================

CREATE TABLE sentiment_cycle (
    id               BIGSERIAL PRIMARY KEY,
    st_date          DATE NOT NULL UNIQUE,
    limit_up_count   INT NOT NULL DEFAULT 0,
    limit_down_count INT NOT NULL DEFAULT 0,
    lianban_count    INT NOT NULL DEFAULT 0,    -- 连板家数（limit_up_streak>=2）
    max_streak       SMALLINT,                  -- 当日最高板
    dragon_json      JSONB,                     -- 高度龙明细 [{code,name,streak,board,industry}]
    pool_count       INT NOT NULL DEFAULT 0,    -- 强势池家数
    big_meat_count   INT NOT NULL DEFAULT 0,    -- 大肉数（池内今日>=+5%）
    big_face_count   INT NOT NULL DEFAULT 0,    -- 大面数（池内今日<=-5%）
    big_meat_list    JSONB,                     -- [{code,name,change_pct,limit_up_streak,industry}]
    big_face_list    JSONB,                     -- 结构同上
    followup_json    JSONB,                     -- 昨日名单今日兑现 [{code,name,src,yest_pct,today_pct,result}]
    lists_manual_json JSONB,                    -- 名单人工增删留痕 [{side,action,code,name,reason,at}]
    leader_json      JSONB,                     -- 龙头前三名 晋级/断板/大面
    collapse_count   INT NOT NULL DEFAULT 0,    -- 崩塌池家数
    collapse_list    JSONB,                     -- 崩塌池名单 [{code,name,limit_down_streak,industry}]（§17.5 C1，与大肉/大面名单同构）
    rebound_count    INT NOT NULL DEFAULT 0,    -- 崩塌组今日止跌反核数
    big_cycle_sug    SMALLINT,                  -- 大周期建议值 1-6（规则映射）
    small_cycle_sug  SMALLINT,
    big_cycle        SMALLINT,                  -- 人工确认值（null=未确认，展示取建议值）
    small_cycle      SMALLINT,
    status_text      VARCHAR(50),               -- 冰点/混沌/主升/退潮…（建议标签人工终定）
    created_at       TIMESTAMP DEFAULT NOW(),
    CHECK (big_cycle_sug   BETWEEN 1 AND 6),
    CHECK (small_cycle_sug BETWEEN 1 AND 6)
);

CREATE TABLE dragon_cycle (
    id             BIGSERIAL PRIMARY KEY,
    st_code        VARCHAR(20) NOT NULL,        -- 龙头代码
    start_date     DATE NOT NULL,               -- 上位日
    end_date       DATE,                        -- 阵亡/定性日（null=进行中）
    max_streak     SMALLINT NOT NULL DEFAULT 0,
    rebreak_count  SMALLINT NOT NULL DEFAULT 0, -- 反包次数
    suspended_days SMALLINT NOT NULL DEFAULT 0, -- 停牌天数（停牌周期延续）
    suspend_json   JSONB,                       -- [{from,to}]
    cycle_type     VARCHAR(10) CHECK (cycle_type IN ('BIG','SMALL')),  -- null=进行中未定性
    status         VARCHAR(10) NOT NULL CHECK (status IN ('RISING','BROKEN','SUSPENDED','DEAD')),
    broken_date    DATE,                        -- 最近断板日（反包观察期起点，默认 3 交易日）
    note           VARCHAR(200),
    created_at     TIMESTAMP DEFAULT NOW(),
    updated_at     TIMESTAMP DEFAULT NOW()
);
-- §17.2 I6：进行中周期每股唯一（事件触发+兜底 cron 双跑防重复插行）
CREATE UNIQUE INDEX uq_dragon_active ON dragon_cycle (st_code) WHERE end_date IS NULL;

-- =============================================================================
-- 三、信号预计算层（3 张，SignalPrecomputeJob 事件触发，查询永不回扫日线）
-- =============================================================================

CREATE TABLE market_daily (
    st_date           DATE PRIMARY KEY,
    adv_count         SMALLINT,                 -- 上涨家数
    dec_count         SMALLINT,                 -- 下跌家数
    limit_up_count    SMALLINT,                 -- 冗余 = jsonb_array_length(limit_up_list)，同源校验
    limit_down_count  SMALLINT,                 -- 同上
    limit_up_list     JSONB,                    -- [{code,name,change_pct,limit_up_streak,industry,reason}]
                                                --   reason=LLM 归因（AttributionStep，仅复盘展示不进条件）
    limit_down_list   JSONB,                    -- 同构
    zhaban_count      SMALLINT,                 -- 炸板家数（日线近似口径）
    yst_limit_premium NUMERIC(6,2),             -- 昨涨停今溢价 = 昨名单 ∘ 今行情（表自算自洽）
    yst_promotion     JSONB,                    -- 分级晋级率 {"total":21.05,"by_level":{"1to2":33.3,...}}
    yst_face_count    SMALLINT                  -- 昨日大面家数
);

CREATE TABLE sector_daily (
    st_date        DATE NOT NULL,
    board          VARCHAR(100) NOT NULL,
    limit_up_count SMALLINT,
    max_streak     SMALLINT,
    avg_chg_pct    NUMERIC(10,4),
    driver_text    TEXT,                        -- 当日板块驱动主线（LLM 生成，标"系统生成"）
    PRIMARY KEY (st_date, board)
);

CREATE TABLE signal_daily (
    st_code            VARCHAR(20) NOT NULL,
    st_date            DATE NOT NULL,
    streak_rank        SMALLINT,                -- 当日梯队排名（全市场排序才得出）
    is_zhaban          BOOLEAN,                 -- 炸板（日线近似，统一口径落库）
    sector_streak_rank SMALLINT,                -- 板块内板数排名
    -- 筹码分布 6+ 列（路径 A 自算递推；不存分布曲线本身）
    profit_ratio       NUMERIC(6,2),            -- 获利盘%
    cost_dev           NUMERIC(8,4),            -- 成本偏离% = 平均成本/现价−1（比率，qfq 重对基免疫）
    c90_low            NUMERIC(12,4),           -- 90% 成本区间（qfq 坐标）
    c90_high           NUMERIC(12,4),
    c90_conc           NUMERIC(6,2),            -- 90% 集中度（东财口径 (p90−p10)/(p90+p10)×100）
    c70_low            NUMERIC(12,4),
    c70_high           NUMERIC(12,4),
    c70_conc           NUMERIC(6,2),
    PRIMARY KEY (st_code, st_date)
);

-- =============================================================================
-- 四、策略 / 回测 / 实盘层（二期，8 张）
-- =============================================================================

CREATE TABLE strategy_config (
    id            SERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    yaml          TEXT NOT NULL,                -- 落库即权威格式；backtest_result.params 的唯一来源
    version       INT NOT NULL DEFAULT 1,       -- 保存即 version+1
    status        VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    alert_enabled BOOLEAN DEFAULT FALSE,        -- 盘中开仓预警开关（§14.9，结果对比页开启）
    created_by    VARCHAR(50),
    created_at    TIMESTAMP DEFAULT NOW(),
    note          TEXT
);

CREATE TABLE strategy_config_history (
    id        BIGSERIAL PRIMARY KEY,
    config_id INT NOT NULL REFERENCES strategy_config(id),
    yaml      TEXT NOT NULL,
    version   INT NOT NULL,
    edited_at TIMESTAMP DEFAULT NOW()
);  -- 每次保存留痕，可回滚可 diff

CREATE TABLE backtest_result (
    id            BIGSERIAL PRIMARY KEY,
    strategy_name VARCHAR(100) NOT NULL,
    params        JSONB NOT NULL,               -- 策略 YAML + L1 参数全样
    start_date    DATE NOT NULL,
    end_date      DATE NOT NULL,
    metrics       JSONB,                        -- 年化/回撤/Sharpe/胜率/盈亏比/Kelly 组…
    equity_curve  JSONB,                        -- 策略 vs 沪深300 vs 等权基准
    config_id     INT REFERENCES strategy_config(id),  -- §17.2：结果↔配置版本绑定
    config_version INT,                         -- 保存时点的 strategy_config.version
    evaluated_universe JSONB,                   -- §17.1 B3：本次实际求值的股票名单（实盘下单前守卫校验）
    is_dry        BOOLEAN DEFAULT FALSE,        -- §17.2：试跑标记，不落台账、对比默认过滤
    data_snapshot JSONB,                        -- {maxDate,rowCount}
    git_sha       VARCHAR(40),
    created_at    TIMESTAMP DEFAULT NOW()
);  -- params+data_snapshot+git_sha 三件套保证可复现

CREATE TABLE trade_ledger (
    id         BIGSERIAL PRIMARY KEY,
    strategy   VARCHAR(100) NOT NULL,
    st_code    VARCHAR(20) NOT NULL,
    open_date  DATE NOT NULL,
    close_date DATE,                            -- null=持仓中
    pnl        NUMERIC(16,2),
    pnl_ratio  NUMERIC(10,4),
    source     VARCHAR(10) NOT NULL CHECK (source IN ('BACKTEST','PAPER','LIVE')),
    backtest_result_id BIGINT,                 -- §17.1 B5：BACKTEST 行溯源到 backtest_result.id
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_ledger_strategy_close ON trade_ledger (strategy, close_date DESC);
-- Kelly 只查（§17.2 两段式）：先 LIVE/PAPER 近 60 笔，不足 20 笔补 BACKTEST；
-- 样本 <20 笔一律不出建议仓位。BACKTEST 导入铁律：先 DELETE WHERE strategy=? AND source='BACKTEST' 再整批插入（不重复算）

CREATE TABLE account_state (
    id         SERIAL PRIMARY KEY,
    cash       NUMERIC(20,2) NOT NULL,          -- 手动维护，不对接券商
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE account_position (
    id         SERIAL PRIMARY KEY,
    st_code    VARCHAR(20) NOT NULL UNIQUE,
    shares     INT NOT NULL CHECK (shares > 0), -- 整手
    cost_price NUMERIC(12,4) NOT NULL,
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 自选股分组 = 回测/策略 universe（2026-10-03 用户裁定：语义=当前名单，不留痕不 as-of；
-- 唯一边界=结果页标注 universe 来源）
CREATE TABLE watchlist_group (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,     -- PCB / 存储芯片 / 创新药…
    note       TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE watchlist_member (
    id       SERIAL PRIMARY KEY,
    group_id INT NOT NULL REFERENCES watchlist_group(id) ON DELETE CASCADE,
    st_code  VARCHAR(20) NOT NULL,
    note     TEXT,
    added_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (group_id, st_code)
);

-- =============================================================================
-- 五、盘中实时层（三期，5 张）—— 独立故障域，绝不写 stock_history，不入 §13.4 握手链
-- =============================================================================

CREATE TABLE intraday_pool_snap (
    id      BIGSERIAL PRIMARY KEY,
    snap_at TIMESTAMP NOT NULL,
    pool    CHAR(6) NOT NULL CHECK (pool IN ('ZT','ZB','DT','STRONG','PREV')),
    payload JSONB NOT NULL                     -- 接口原样行（未来字段升级不丢）
);  -- 原始轮次保留 3 天供回溯调试，定期清理

CREATE TABLE intraday_pool_state (            -- 池运行时开关（§17.5 C2 用户裁定 A：热启停落库，重启不丢）
    pool       CHAR(6) PRIMARY KEY CHECK (pool IN ('ZT','ZB','DT','STRONG','PREV')),
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    reason     VARCHAR(200),                  -- 手动停用原因（如限频封禁规避）
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE intraday_event (
    id        BIGSERIAL PRIMARY KEY,
    trade_date DATE NOT NULL,
    ev_time   TIMESTAMP NOT NULL,
    ev_type   VARCHAR(20) NOT NULL
              CHECK (ev_type IN ('ZT','ZB','HF','DM','MAXCHG','OPEN','ALERT')),
              -- ZT涨停/ZB炸板/HF回封/DM大面/MAXCHG最高板易主/OPEN开板/ALERT策略开仓预警(§14.9)
    st_code   VARCHAR(20),
    st_name   VARCHAR(100),
    detail    JSONB,                           -- {streak,seal_amount,zhaban_count,chg,kelly{...}}
    pushed_dd BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_ie_date_time ON intraday_event (trade_date, ev_time);

CREATE TABLE intraday_archive (
    trade_date      DATE NOT NULL,
    st_code         VARCHAR(20) NOT NULL,
    first_seal_time VARCHAR(8),                -- HH:MM:SS（词表升级：早封/晚封板）
    last_seal_time  VARCHAR(8),
    zhaban_count    SMALLINT,
    seal_amount     NUMERIC(16,2),             -- 封板资金（元）
    streak          SMALLINT,
    pool            CHAR(6),
    UNIQUE (trade_date, st_code)
);  -- 15:10 用 date=当日 重拉 6 池接口权威归档；长历史自上线日逐日自建

CREATE TABLE intraday_replay (
    trade_date DATE PRIMARY KEY,
    complete   BOOLEAN DEFAULT FALSE,           -- 15:10 归档补齐后置 true
    page       JSONB
    -- 整页渲染快照，schema = GET /intraday/summary 响应（同一 Kotlin DTO）：
    -- {kpi_series:[{t,zt,zb,dt,prem,adr}...], ladder:[...], events:[...],
    --  panels:{big_face,ding_talk,strategy_alerts}}
    -- 盘中 90s 采样追加 kpi_series；回放=单表单查询零 join；落库前渲染自校验
);  -- ~400KB/日 → 年 ~100MB

-- =============================================================================
-- 六、笔记（1 张，纯人工复盘日志，不进策略条件）
-- =============================================================================

CREATE TABLE daily_note (
    id         BIGSERIAL PRIMARY KEY,
    st_date    DATE NOT NULL,                   -- 归属交易日（按天维度组织）
    page       VARCHAR(20) NOT NULL
               CHECK (page IN ('SENTIMENT','STRATEGY','INTRADAY','KLINE','GENERAL')),
    st_code    VARCHAR(20),                     -- 个股笔记挂靠（K线页），NULL=市场级
    content    TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP,
    UNIQUE NULLS NOT DISTINCT (st_date, page, st_code)  -- §17.1 B9：NULL 视同相等，市场级笔记(st_code=NULL)同样唯一；编辑=PUT 更新已有行，永不盲插
);
