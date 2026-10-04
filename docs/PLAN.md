# GeorgeSoros V2 — 从零搭建多源数据采集系统

## Context

V1 系统（`GeorgeSoros/soros-data-adaptor`）基于 Spring Boot 2.4.6 + Kotlin 1.4.32 + JDK 8 + ShardingSphere 5.0.0-alpha，已无法在 JDK 26 上编译。经过深度穿透评估：

- **ShardingSphere 不需要**：千万级数据单表 + 复合索引即可，100 分片表纯属复杂度负担（plan 早期记录的「5.5M 行」为代码估算非实测）
- **原地升级需 12 周**：javax→jakarta（9 文件）、Kotlin 1.4→2.0 增量迁移、data class + @Entity 反模式（8 实体）、kotlin.streams.toList 废弃（6 文件）
- **PostgreSQL 全面优于 MySQL**：点查快 9 倍、批量写入快 3-5 倍、原生 RANGE 分区、TimescaleDB 可选
- **V1 代码无迁移价值**：2654 行全是胶水代码 + bug + 反模式；数据库亦无数据资产（**2026-10-02 核实：V1 MySQL 已不存在，无数据可迁**）

**决策：V2 从零搭建**（`GeorgeSoros-V2`），PostgreSQL + 无分片 + 干净架构；~~V1 数据一次性迁移~~ → **2026-10-02 修正：V1 无数据可迁**，历史数据改为从数据源直接回填（见 §六）。

---

## 一、技术栈

| 组件 | V1（废弃） | V2（新） |
|------|-----------|---------|
| JDK | 8 | **21** |
| Gradle | 6.8.3 | **8.8** |
| Kotlin | 1.4.32 | **2.0.21** |
| Spring Boot | 2.4.6 | **3.4.1** |
| 数据库 | MySQL 8.0 + ShardingSphere | **PostgreSQL 16** |
| ORM | JPA + Hibernate 5 | **JPA + Hibernate 6** |
| 分片 | ShardingSphere 100 表 MOD | **无分片，单表 + 复合索引** |
| JSON | Gson + Jackson + Fastjson2 | **Jackson（统一）** |
| 异步 | @Async + ThreadPoolTaskExecutor | **kotlinx-coroutines** |
| HTTP Client | OkHttp（无配置类） | **Spring WebClient 或 Ktor Client** |
| 测试 | 0 覆盖 | **从第一个 Entity 开始写测试** |

---

## 二、数据库设计

### 2.1 PostgreSQL 选型理由

| 指标 | MySQL 8.0 | PostgreSQL 16 | 优势方 |
|------|-----------|---------------|--------|
| 点查（索引命中） | 0.9-1.0 ms | **0.09-0.13 ms** | PG 9x |
| 范围扫描（1 股 1 年） | 1-5 ms | 1-3 ms | PG |
| 批量 INSERT 5000 行 | 0.5-2 sec | **0.3-1.5 sec**（COPY） | PG 3-5x |
| 全表分析查询 | 2-8 sec | **1.5-6 sec**（并行查询） | PG |
| 存储体积 | ~4.5x 大 | 更紧凑 | PG |
| 原生分区 | RANGE/HASH（有限制） | **声明式分区（RANGE/LIST/HASH）** | PG |
| 时序扩展 | 无 | **TimescaleDB** | PG |

### 2.2 表结构（单表，无分片）

**stock_history**（核心表；A股全市场 ≈125 万行/年，**仅存近 5 年**（2021-10 至今，2026-10-02 决策）≈ 600-650 万行）

```sql
CREATE TABLE stock_history (
    id          BIGSERIAL PRIMARY KEY,
    code     VARCHAR(20) NOT NULL,     -- 600000（裸数字，不带 sh/sz）
    trade_date     DATE NOT NULL,             -- 2024-01-02
    open     NUMERIC(12,4),
    close    NUMERIC(12,4),
    high     NUMERIC(12,4),
    low      NUMERIC(12,4),
    volume   BIGINT,
    amount NUMERIC(20,4),
    change_pct  NUMERIC(10,4),            -- 涨跌幅%（修正命名）
    turnover_rate NUMERIC(10,4),          -- 换手率%（修正命名）
    is_limit_up BOOLEAN DEFAULT FALSE,    -- 涨停
    is_limit_down BOOLEAN DEFAULT FALSE,  -- 跌停
    limit_up_streak SMALLINT DEFAULT 0,   -- 连板数（首板=1，0=非涨停/断板，§4.8 写入时派生）
    limit_down_streak SMALLINT DEFAULT 0, -- 跌停连板数（§4.9 崩塌池依据，与 limit_up_streak 镜像派生）
    -- V5 起 CHECK 扩 6 值（+AKSHARE_SINA/YAHOO，§18.3）：
    data_source VARCHAR(20) DEFAULT 'UNKNOWN' CHECK (data_source IN ('BAOSTOCK', 'AKSHARE', 'AKSHARE_SINA', 'MOOTDX', 'YAHOO', 'UNKNOWN')),
    created_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (code, trade_date)
);

-- 核心索引（2026-10-02 索引审计：UNIQUE(code,trade_date) 自带同列复合索引，
-- 原 idx_history_code_date 与之完全重复已删除，勿再建同列普通索引）
CREATE INDEX idx_history_date ON stock_history (trade_date);  -- 按日全市场扫描
```

**查询模式 → 索引对照（§2.2.2，防后续加功能乱加/漏加索引）**：

| 查询模式 | 代表场景 | 命中 |
|---------|---------|------|
| `WHERE code=? [AND trade_date BETWEEN]` | 回测加载、波浪计算、个股 actions、单股重拉 | UNIQUE(code,trade_date) 自带索引 |
| `WHERE trade_date=? / >=?` | SentimentCycleJob、梯队排名、market_daily/sector_daily 聚合、滚动重拉近7日 | idx_history_date（单日~5000行，内存过滤） |
| 近 N 日入池窗口（强势池/崩塌池） | 近3日最高连板≥3 等 | idx_history_date 取 ~2.5 万行后内存开窗，不需专门索引 |
| 全表顺序扫 | 回测启动装载、WaveComputeJob、回放 | 本应 seq scan，索引无关 |
| 预计算表直读 | 情绪网页、limit_ecology/sector/market_env 条件 | sentiment_cycle / market_daily / sector_daily / signal_daily，**不碰日线** |
| 模糊搜索 | GET /stock-search | stock_info.search_key GIN(trgm) |

派生层存在的意义即"查询永不逐次回扫日线"：日频派生全落库，现算仅限单日截面/单股窗口的有界查询。

### 2.2.1 容量评估与分区策略（分层递进）

PostgreSQL 单表硬限制 32 TB，A 股数据量估算：

```
5000 股 × 250 交易日/年 = 125 万行/年
**实际存量：近 5 年 ≈ 625 万行 ≈ ~3 GB（含索引 ~6 GB）**（2026-10-02 决策：只存 5 年）
10 年 ≈ 1250 万行 ≈ ~6 GB（含索引 ~12 GB）
20 年 ≈ 2500 万行 ≈ ~12 GB（含索引 ~24 GB）
50 年 ≈ 6250 万行 ≈ ~30 GB（含索引 ~60 GB）
→ 50 年数据也不到 100 GB，单表完全无压力
```

**分层策略**：

| 阶段 | 数据量 | 策略 | 何时触发 |
|------|--------|------|---------|
| **Phase A：单表** | ≤ 6000 万行（≈50 年全量，含历史回填） | 单表 + 复合索引，不做任何分区 | **V2 初始方案** |
| **Phase B：年分区** | > 6000 万行 | PostgreSQL 原生 RANGE 分区（按年） | 理论触发（约 50 年后） |
| **Phase C：分区+归档** | > 1 亿行 | 分区 + 旧数据迁移冷存储 | 不会到 |

**Phase B 升级脚本**（数据量到时执行，5 分钟在线完成）：

```sql
-- 1. 重命名原表
ALTER TABLE stock_history RENAME TO stock_history_legacy;

-- 2. 创建分区表（结构完全一致）
CREATE TABLE stock_history (LIKE stock_history_legacy INCLUDING ALL)
    PARTITION BY RANGE (trade_date);

-- 3. 按年创建分区
CREATE TABLE stock_history_2024 PARTITION OF stock_history
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE stock_history_2025 PARTITION OF stock_history
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
-- 每年加一行，或用 pg_partman 自动管理

-- 4. 迁移数据（分批执行避免锁表）
INSERT INTO stock_history SELECT * FROM stock_history_legacy;

-- 5. 验证后删除旧表
DROP TABLE stock_history_legacy;
```

**为什么不用 ShardingSphere**：
- V1 按股票代码 MOD 100 分片 → 跨股查询广播到 100 张表，性能灾难
- PostgreSQL 原生分区按时间 RANGE → 跨股查询只扫 1 个分区，零广播
- 原生分区对应用 100% 透明（逻辑表名不变），ShardingSphere 需要独立 YAML + Driver 配置
- 原生分区 Spring Data JPA 完全兼容，ShardingSphere 需验证

**stock_info**（~5000 行）

```sql
CREATE TABLE stock_info (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(20) NOT NULL UNIQUE,  -- 600000
    name        VARCHAR(100),
    market      VARCHAR(10),                   -- SH / SZ
    board       VARCHAR(20) DEFAULT 'MAIN' CHECK (board IN ('MAIN', 'GEM', 'STAR')),
    is_st       BOOLEAN DEFAULT FALSE,
    delisted    BOOLEAN DEFAULT FALSE,
    ipo_date    DATE,                      -- 上市日（BaoStock query_stock_basic.ipoDate，§4.8 IPO 守卫用）
    industry    TEXT,                          -- JSON 数组（一股可属多行业口径，§4.8 板块归属）
    concept_boards TEXT,                       -- JSON 数组（概念板块，一股可属多个）
    search_key  VARCHAR(300),                   -- 模糊搜索键（小写）：name + 全拼 + 拼音首字母，
                                                -- StockInfoService 落库时生成（pinyin-pro），如
                                                -- "平潭发展 pingtanfazhan ptfz"；GET /stock-search 用
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_stock_info_board ON stock_info (board);
CREATE INDEX idx_stock_info_st ON stock_info (is_st, delisted);
CREATE INDEX idx_stock_info_search ON stock_info USING gin (search_key gin_trgm_ops);  -- pg_trgm 模糊检索
```

**ST / 停牌 / 退市判定规则（2026-10-02 用户修正：缺失≠退市，停牌可长达半年以上）**

| 状态 | 判定依据 | 规则 |
|------|---------|------|
| ST | 主：BaoStock `isST`（**逐日历史**，戴帽/摘帽可追溯）；辅：AKShare 风险警示板 `stock_zh_a_st_em` + 名称含 ST/*ST/退 | stock_info.is_st 每日刷新当日状态 |
| 停牌 | 主：BaoStock `tradestatus=0`（显式标识）；AKShare/mootdx 停牌日无行，**不做推断** | 停牌行不入 stock_history（Python 侧过滤）；停牌股**保留在采集列表**，拉不到行不触发任何状态变更 |
| 退市 | 主：BaoStock `query_stock_basic` 的 `status=0` + `outDate`（官方退市日期）；辅：AKShare 两网及退市名单 | **缺失数据永远不直接判退市**：连续 20 交易日无数据 → 仅写 data_quality_log(`DELIST_SUSPECT`) + 钉钉提醒，**人工确认后才置 delisted=true** |

> 三源能力差异：mootdx 三项全无（纯行情源）；AKShare 停牌/缺失语义歧义。凡涉及状态判定一律以 BaoStock 显式字段为权威，探针阶段（Step 3）实测三个字段回写本表。

**ST/*ST 全系统隔离原则（2026-10-02 用户定，铁律）**：ST/*ST/退市股**不是本系统任何环节的关注对象**——采集侧过滤不入库（DailyCollectJob `.filter { !it.isSt && !it.delisted }`），行情、连板梯队、板块、策略、回测、报表全链路**天然无 ST**。`is_st` 字段仅服务于"识别并排除"这一个动作，是技术字段不是业务数据，禁止在策略/查询/报表中作为可配置项出现（不存在"要不要看 ST"这个选项）。

**stock_fundamentals**（~10 万行 = 5000 股 × 4 季 × 5 年，季度粒度）

```sql
CREATE TABLE stock_fundamentals (
    id          SERIAL PRIMARY KEY,
    code     VARCHAR(20) NOT NULL,
    report_date DATE NOT NULL,             -- 报告期（季度末 2024-12-31），季度/年度通吃（2026-10-02 用户决策：每季度都存）
    revenue     NUMERIC(20,2),             -- 元（AKShare stock_yjbb_em 原始单位亿元，×1e8 转换）
    net_profit  NUMERIC(20,2),             -- 元（同上）
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (code, report_date)
);
```

**data_quality_log**

```sql
CREATE TABLE data_quality_log (
    id          SERIAL PRIMARY KEY,
    check_date  DATE NOT NULL,
    code  VARCHAR(20) NOT NULL,
    issue_type  VARCHAR(50) NOT NULL,
    detail      TEXT,
    source      VARCHAR(20),
    created_at  TIMESTAMP DEFAULT NOW()
);
```

**stock_index**（指数数据，保留）

```sql
CREATE TABLE stock_index (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(20) NOT NULL,     -- sh000001（指数带前缀）
    name        VARCHAR(100),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (code)
);
```

**trading_calendar**（交易日历，2026-10-02 新增：一次拉取全量入库）

```sql
CREATE TABLE trading_calendar (
    trade_date  DATE PRIMARY KEY
);
-- Python GET /api/v1/trading-calendar → AKShare tool_trade_date_hist_sina 全量
-- **种子基线：docs/seed/trade_calendar.csv（1990-12-19 ~ 2026-12-31 共 8797 交易日，M0 探针 2026-10-03 验证质量后留存）**，M1 建表后 COPY 导入，后续靠 Job 增量续期
-- TradingCalendarService 启动载入内存；覆盖不足"明年年底"时自动重拉续期
-- 查不到日期回退周一~周五判定 + data_quality_log
```

**index_history**（指数日K，2026-10-02 新增：回测 benchmark + 波浪算法 INDEX 模式的数据缺口补齐）

```sql
CREATE TABLE index_history (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(20) NOT NULL,     -- sh000001（带前缀，同 stock_index 特例）
    trade_date  DATE NOT NULL,
    open     NUMERIC(12,4),
    close    NUMERIC(12,4),
    high     NUMERIC(12,4),
    low      NUMERIC(12,4),
    volume   BIGINT,
    amount NUMERIC(20,4),
    data_source VARCHAR(20) DEFAULT 'UNKNOWN',
    created_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (code, trade_date)
);
-- DailyCollectJob 顺带采集 5 个基准指数（sh000001 等），qfq 口径同 stock_history
```

**sentiment_cycle**（情绪周期表，2026-10-02 新增：每日一行，SentimentCycleJob 派生，§4.9）

```sql
CREATE TABLE sentiment_cycle (
    id              BIGSERIAL PRIMARY KEY,
    trade_date         DATE NOT NULL UNIQUE,        -- 交易日
    limit_up_count  INT NOT NULL DEFAULT 0,      -- 全市场涨停家数
    limit_down_count INT NOT NULL DEFAULT 0,     -- 全市场跌停家数
    lianban_count   INT NOT NULL DEFAULT 0,      -- 连板家数（limit_up_streak>=2）
    max_streak      SMALLINT,                    -- 当日最高板
    dragon_json     JSONB,                       -- 高度龙明细：[{code,name,limit_up_streak,board,industry}]（industry=主行业，取数组第一个，展示用）
    pool_count      INT NOT NULL DEFAULT 0,      -- 强势池家数（§4.9 入池规则）
    big_meat_count  INT NOT NULL DEFAULT 0,      -- 大肉数（池内今日 change_pct>=+5%）
    big_face_count  INT NOT NULL DEFAULT 0,      -- 大面数（池内今日 change_pct<=-5%）
    big_meat_list   JSONB,                       -- 大肉个股名单 [{code,name,change_pct,limit_up_streak,industry}]（industry=主行业展示值）
    big_face_list   JSONB,                       -- 大面个股名单（结构同上）
    followup_json   JSONB,                       -- 昨日名单今日兑现 [{code,name,src:MEAT/FACE,yest_pct,today_pct,result}]
    lists_manual_json JSONB,                     -- 名单人工增删留痕 [{side,action:ADD/REMOVE,code,name,reason,at}]
    leader_json     JSONB,                       -- 龙头表现：近1-3日最高板前三名 晋级/断板/大面
    collapse_count  INT NOT NULL DEFAULT 0,      -- 崩塌池家数
    collapse_list   JSONB,                       -- 崩塌池个股名单 [{code,name,limit_down_streak,industry}]（§17.5 C1 补，与大肉/大面名单同构）
    rebound_count   INT NOT NULL DEFAULT 0,      -- 崩塌组今日止跌反核数（涨停或 >=+5%）
    big_cycle_sug   SMALLINT,                    -- 大周期建议值 1-6（规则映射，人工可改）
    small_cycle_sug SMALLINT,                    -- 小周期建议值 1-6
    big_cycle       SMALLINT,                    -- 大周期人工确认值（null=未确认，展示时取建议值）
    small_cycle     SMALLINT,                    -- 小周期人工确认值
    status_text     VARCHAR(50),                 -- 超短周期状态（建议标签 冰点/混沌/主升/退潮，人工终定）
    created_at      TIMESTAMP DEFAULT NOW()
);
-- 派生表：每日 DailyCollectJob 完成后由 SentimentCycleJob 计算，不依赖新数据源
```

**dragon_cycle**（龙头生命周期，2026-10-02 新增：大/小周期的锚，§4.9 状态机逐日推进）

```sql
CREATE TABLE dragon_cycle (
    id              BIGSERIAL PRIMARY KEY,
    code         VARCHAR(20) NOT NULL,        -- 龙头代码
    start_date      DATE NOT NULL,               -- 上位日（前一龙头阵亡次日）
    end_date        DATE,                        -- 阵亡/定性日（null=进行中）
    max_streak      SMALLINT NOT NULL DEFAULT 0, -- 周期内最高板
    rebreak_count   SMALLINT NOT NULL DEFAULT 0, -- 反包次数
    suspended_days  SMALLINT NOT NULL DEFAULT 0, -- 停牌天数（停牌周期延续）
    suspend_json    JSONB,                       -- 停牌区间明细 [{from,to}]
    cycle_type      VARCHAR(10),                 -- BIG / SMALL / null(进行中未定性)
    status          VARCHAR(10) NOT NULL,        -- RISING/BROKEN/SUSPENDED/DEAD
    broken_date     DATE,                        -- 最近一次断板日（观察期起点）
    note            VARCHAR(200),                -- 人工备注
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_dragon_cycle_status ON dragon_cycle (status);
-- 同一时刻至多一条进行中（status != 'DEAD' 且 end_date IS NULL 的最新一条）；人工可通过 confirm 接口改判
```

### 2.3 与 V1 的关键区别

| V1 | V2 | 原因 |
|----|----|----- |
| 100 张分片表 stock_history_0~99 | **1 张表 stock_history** | 单表覆盖 50 年容量（含全量回填），无分片必要 |
| partition_code 列（分片键） | **删除** | 无分片则无此列 |
| sh600000/sz000001 前缀 | **裸数字 600000** | V1 的 Entity init 里有 replace 逻辑但实际数据带前缀，V2 统一裸数字 |
| change 存换手率、zdRange 存涨跌幅 | **change_pct + turnover_rate 正确命名** | 修正 V1 字段命名 bug |
| GenerationType.SEQUENCE | **GenerationType.IDENTITY**（PostgreSQL BIGSERIAL） | MySQL 不支持 sequence，Hibernate 6 不再静默降级 |
| javax.persistence | **jakarta.persistence** | Spring Boot 3.x 要求 |
| data class + @Entity | **普通 class + @Entity** | 避免 data class 的 equals/hashCode 与 JPA 代理冲突 |

### 2.4 数据字典（字段口径单点定义，防"字段乱用"）

> 动因：V1 的 st_change 实存换手率、zd_range 实存涨跌幅——命名与含义脱节靠口口相传。V2 用四层机制让字段语义只有一个权威出处，漂移在 CI 阶段爆掉而不是进库。

**四层机制（从数据库字段往外推）**：

| 层 | 载体 | 作用 |
|----|------|------|
| ① DDL 注释层 | Flyway `V1__init_schema.sql` 每列 `COMMENT ON COLUMN`（含口径/单位/允许值） | 字典跟 schema 走，psql `\d+` 随时可见，永不失联 |
| ② 约束层 | 枚举字段 `CHECK` 约束（见下方枚举表） | 数据库层兜底，脏枚举值写不进去 |
| ③ 文档字典层 | `docs/data-dictionary.md`（下方映射表的展开版） | 跨语言四端口径一张表锁死：PG 列 ↔ Kotlin 属性 ↔ Python JSON ↔ 数据源原始字段 |
| ④ 代码单点层 | Kotlin enum（Board / DataSourceType / QualityIssueType）+ Python `constants.py`；Jackson `fail-on-unknown-properties` 严格模式 | 魔法字符串禁止散落；契约漂移在启动/测试期失败 |

**枚举允许值（代码单点定义 + CHECK 兜底）**：

| 枚举 | 允许值 | CHECK 约束 |
|------|--------|-----------|
| data_source | `BAOSTOCK` / `AKSHARE` / `AKSHARE_SINA` / `MOOTDX` / `YAHOO` / `UNKNOWN`（V5 扩展，§18.3；akshare 内部 EM→sina failover 归因 akshare-sina） | ✔ stock_history |
| board | `MAIN` / `GEM` / `STAR`（北交所、B 股不采集） | ✔ stock_info |
| issue_type（data_quality_log） | `ADJUSTMENT_DRIFT` / `ADJUSTMENT_DRIFT_UNRESOLVED` / `RAW_FALLBACK` / `CROSS_VALIDATE_MISMATCH` / `DELIST_SUSPECT` / `NO_BAR_TODAY` / 其他新增 | ✘（会扩展，代码 enum 单点即可） |
| market | `SH` / `SZ` | ✘（仅展示用） |

**stock_history 字段字典（核心表，四端映射）**：

| PG 列 | 口径 / 含义 / 单位 | Kotlin | Python JSON | BaoStock | AKShare | mootdx |
|-------|-------------------|--------|-------------|----------|---------|--------|
| code | 裸数字 600000（不带 sh/sz 前缀） | code | code | code 去前缀 | 股票代码 | code |
| trade_date | 交易日 | date | date | date | 日期 | datetime[:10] |
| open / high / low / close | **qfq 前复权价**（元，2 位小数） | open/high/low/close | open/high/low/close | adjustflag=2 | adjust=qfq | ⚠ 不复权（降级源，记 RAW_FALLBACK） |
| volume | 成交量，**统一单位=股** | volume | volume | volume（原生股） | 成交量（手）×100 | vol（手）×100 |
| amount | 成交额（元） | totalAmount | amount | amount | 成交额 | amount |
| change_pct | **不复权**真实涨跌幅%（数据源原始值，§4.5 涨停检测依据） | changePct | change_percent | pctChg | 涨跌幅 | (close−last_close)/last_close |
| turnover_rate | 换手率%（**V1 曾误名 st_change**，字典留痕防旧记忆） | turnoverRate | turnover | turn | 换手率 | 无 → 0 |
| is_limit_up / is_limit_down | Kotlin 侧按 board 阈值计算（§4.5），Python 不传 | isLimitUp/isLimitDown | — | — | — | — |
| limit_up_streak | 连板数，首板=1，0=非涨停/断板；**Kotlin 派生**（§4.8），Python 不传；停牌断板、IPO 首 5 日强制 0 | limitUpStreak | — | — | — | — |
| limit_down_streak | 跌停连板数，首日=1；**Kotlin 派生**（§4.8 镜像规则），Python 不传；用于 §4.9 崩塌池 | limitDownStreak | — | — | — | — |
| data_source | 实际写入来源（failover 可见性） | dataSource | source | — | — | — |
| calibrated | 该行是否已被 CalibrationJob 与新鲜源对拍通过（V5，§18.3④）；默认 FALSE，merge/saveBatch 均不覆盖 | calibrated | — | — | — | — |
| calibrated_source | 校准通过时的对拍源（枚举大写，如 BAOSTOCK） | calibratedSource | — | — | — | — |
| calibrated_at | 校准标记时间（TIMESTAMPTZ） | calibratedAt | — | — | — | — |
| created_at | 入库时间（DO UPDATE 时不覆盖） | createdAt | — | — | — | — |

**其他表关键口径**：

- `limit_up_streak`：Kotlin 写入时派生（§4.8），与 is_limit_up 同源同写，禁止外部直接 UPDATE。
- `limit_down_streak`：Kotlin 写入时派生（§4.8 镜像规则），与 is_limit_down 同源同写，禁止外部直接 UPDATE。
- `stock_info.ipo_date`：来源 BaoStock `query_stock_basic.ipoDate`（与退市判定同一次拉取）；供 §4.8 IPO 守卫与回测新股边界。
- `stock_fundamentals.report_date`：季度末日期（2024-12-31），**每季度都存**（用户决策）；金额单位统一**元**（AKShare stock_yjbb_em 原始亿元 ×1e8）。
- `stock_info.industry`（JSON 数组） / `concept_boards`（JSON 数组）：来源 BoardCollectJob 东财板块成分（行业每日/概念每周），非股票列表接口；均只存当前成分快照（§4.8）。
- `amplitude`（振幅%）：**已决策删除（2026-10-02）**——可由 最高/最低/昨收 随时派生，不设列不养契约。
- `prev_close`（除权后昨收）：**传输字段，不落库**，仅供 §4.6 漂移检测；三源口径一致（mootdx 除外，见 §4.6）。
- **证券代码列名全库统一 `code`（2026-10-03 命名穿透后定稿，st_/stock_ 变体已清零）**；口径特例在**值层面**：股票=裸数字 600000，**指数=带前缀 sh000001**（仅 stock_index/index_history 两表）——字典显式标注，防有人"顺手统一"值口径。
- `volume`/`amount` 单位换算（×100 等）为契约约定，**M0 探针已实测（2026-10-03，docs/research/probe-units-v2.md）**：BaoStock volume 原生=股（amount/volume≈收盘价）、amount=元、preclose 有值、tradestatus/isST 字段确认存在；AKShare 成交量=手（×100 换算正确）、成交额=元、qfq 与不复权列名完全一致。mootdx 未测（本机无可用节点，备源低优先，M3 接入时实测校准）。
- Kotlin 属性名与 Python JSON key 不同（如 turnoverRate ↔ turnover）是**有意为之**（@Column 显式映射 + DTO 独立命名），禁止"顺手统一"两侧命名——统一动作必须过字典。
- **全系统命名总册**：`docs/design/naming-dictionary.md`（2026-10-03 命名穿透产出，同语义字段全库同名定稿 + 26 表逐列清单 + 特例留痕）。新表/新列/新 DTO 字段必须先查总册再命名，总册没有的概念先增册再用名。
- **Entity 注释铁律（2026-10-03 用户要求）**：Kotlin Entity 每个字段必须带 KDoc 注释，注释文本与 DDL 列注释**同文**（以 schema.sql 为源），M1 生成 Entity 时执行——字典注释随 schema 走，`\d+` 与 IDE 悬浮两处可见，永不失联。

---

## 三、项目结构

```
GeorgeSoros-V2/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── src/
│   ├── main/
│   │   ├── kotlin/com/soros/v2/
│   │   │   ├── SorosApplication.kt              -- @SpringBootApplication + @EnableScheduling
│   │   │   │
│   │   │   ├── config/
│   │   │   │   ├── AppProperties.kt              -- @ConfigurationProperties(prefix = "app")
│   │   │   │   ├── DataCollectionProperties.kt   -- @ConfigurationProperties(prefix = "app.data-collection")
│   │   │   │   └── HttpClientConfig.kt           -- WebClient Bean
│   │   │   │
│   │   │   ├── entity/
│   │   │   │   ├── StockHistory.kt               -- @Entity, 普通 class（非 data class）
│   │   │   │   ├── StockInfo.kt
│   │   │   │   ├── StockFundamentals.kt
│   │   │   │   ├── StockIndex.kt
│   │   │   │   └── DataQualityLog.kt
│   │   │   │
│   │   │   ├── repository/
│   │   │   │   ├── StockHistoryRepository.kt
│   │   │   │   ├── StockInfoRepository.kt
│   │   │   │   ├── StockFundamentalsRepository.kt
│   │   │   │   ├── StockIndexRepository.kt
│   │   │   │   └── DataQualityLogRepository.kt
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── StockHistoryService.kt         -- CRUD + 业务逻辑
│   │   │   │   ├── StockInfoService.kt
│   │   │   │   └── PythonDataServiceClient.kt     -- HTTP 调 Python 微服务
│   │   │   │
│   │   │   ├── job/
│   │   │   │   ├── DailyCollectJob.kt             -- @Scheduled 每日 20:00（§4.7 防线①）
│   │   │   │   ├── BackfillJob.kt                 -- 手动触发，COPY 两段式历史回填（§六）
│   │   │   │   ├── FundamentalsCollectJob.kt      -- @Scheduled 季报披露季循环拉业绩报表（§11.1）
│   │   │   │   ├── BoardCollectJob.kt             -- 行业每日/概念每周板块成分（§4.8）
│   │   │   │   ├── SentimentCycleJob.kt           -- 每日情绪周期派生 + 龙头状态机推进 + 钉钉日报（§4.9）
│   │   │   │   └── CrossValidateJob.kt            -- @Scheduled 每周日
│   │   │   │
│   │   │   ├── controller/
│   │   │   │   ├── HealthController.kt
│   │   │   │   ├── ManualDataController.kt        -- 手动补数据 webhook（兼容 V1，§11.4）
│   │   │   │   ├── LimitUpController.kt           -- 涨停梯队/当日最高板查询（§4.8）
│   │   │   │   └── SentimentController.kt         -- 情绪周期查询/人工确认接口（§4.9）
│   │   │   │
│   │   │   └── util/
│   │   │       ├── LimitUpDetector.kt             -- 涨停/跌停检测
│   │   │       └── SorosAlgorithm.kt              -- V1 SorosUtils 迁移（波浪分析）
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__init_schema.sql            -- Flyway 自动执行
│   │
│   └── test/
│       └── kotlin/com/soros/v2/
│           ├── repository/
│           │   └── StockHistoryRepositoryTest.kt  -- @DataJpaTest + TestContainers
│           ├── service/
│           │   └── StockHistoryServiceTest.kt
│           └── util/
│               └── LimitUpDetectorTest.kt
│
├── soros-data-service/                            -- Python 微服务（从 V1 plan 保留）
│   ├── main.py
│   ├── config.py
│   ├── models.py
│   ├── router.py
│   ├── circuit_breaker.py
│   ├── rate_limiter.py
│   ├── notifier.py
│   ├── health.py
│   ├── adapters/
│   │   ├── base.py
│   │   ├── baostock_adapter.py
│   │   ├── akshare_adapter.py
│   │   └── mootdx_adapter.py
│   └── tests/
│       ├── conftest.py
│       ├── test_rate_limiter.py
│       ├── test_circuit_breaker.py
│       ├── test_router.py
│       ├── test_baostock_adapter.py
│       ├── test_akshare_adapter.py
│       └── test_mootdx_adapter.py
│
└── scripts/
    └── migrate_v1_data.sql                        -- V1 数据一次性迁移脚本
```

---

## 四、核心代码设计

### 4.1 Entity 设计（普通 class，非 data class）

```kotlin
@Entity
@Table(name = "stock_history")
class StockHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "code", nullable = false, length = 20)
    var code: String = "",

    @Column(name = "trade_date", nullable = false)
    var date: LocalDate? = null,

    @Column(name = "open")
    var open: BigDecimal? = null,

    @Column(name = "close")
    var close: BigDecimal? = null,

    @Column(name = "high")
    var high: BigDecimal? = null,

    @Column(name = "low")
    var low: BigDecimal? = null,

    @Column(name = "volume")
    var volume: Long? = null,

    @Column(name = "amount")
    var totalAmount: BigDecimal? = null,

    @Column(name = "change_pct")
    var changePct: BigDecimal? = null,        // 涨跌幅%（修正命名）

    @Column(name = "turnover_rate")
    var turnoverRate: BigDecimal? = null,     // 换手率%（修正命名）

    @Column(name = "is_limit_up")
    var isLimitUp: Boolean = false,

    @Column(name = "is_limit_down")
    var isLimitDown: Boolean = false,

    @Column(name = "limit_up_streak", nullable = false)
    var limitUpStreak: Short = 0,             // 连板数（§4.8 派生，首板=1）

    @Column(name = "limit_down_streak", nullable = false)
    var limitDownStreak: Short = 0,           // 跌停连板数（§4.8 镜像派生，§4.9 崩塌池依据）

    @Column(name = "data_source", length = 20)
    var dataSource: String = "UNKNOWN",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now()
) {
    // 空构造函数给 JPA
    constructor() : this(null)
}
```

### 4.2 Repository 设计

```kotlin
@Repository
interface StockHistoryRepository : JpaRepository<StockHistory, Long> {

    // 单股历史（利用复合索引，毫秒级）
    fun findByCodeAndDateBetween(
        code: String, start: LocalDate, end: LocalDate
    ): List<StockHistory>

    // 单股最大日期（修复 V1 bug：不再加载全量数据到内存）
    @Query("SELECT MAX(h.date) FROM StockHistory h WHERE h.code = :code")
    fun findMaxDateByCode(@Param("code") code: String): LocalDate?

    // 单股最新收盘价（涨停检测用 prev_close）
    @Query("SELECT h FROM StockHistory h WHERE h.code = :code AND h.date = :date")
    fun findByCodeAndDate(@Param("code") code: String, @Param("date") date: LocalDate): StockHistory?

    // 按日期批量查询（每日采集后用）
    fun findByDateBetween(start: LocalDate, end: LocalDate): List<StockHistory>
}
```

### 4.3 Python 数据服务客户端（Kotlin 侧）

```kotlin
@Service
class PythonDataServiceClient(
    private val webClient: WebClient,
    @Value("\${app.data-service.url:http://localhost:8000}") private val baseUrl: String
) {
    suspend fun healthCheck(): Boolean = runCatching {
        webClient.get().uri("$baseUrl/health").retrieve().toBodilessEntity().awaitBodiless()
        true
    }.getOrDefault(false)

    suspend fun fetchStockList(): List<StockListResponse> =
        webClient.get()
            .uri("$baseUrl/api/v1/stock-list?market=all&board=all")
            .retrieve()
            .bodyToMono<StockListApiResponse>()
            .awaitSingle()
            .data

    suspend fun fetchDailyBarsBatch(
        codes: List<String>, startDate: String, endDate: String
    ): BatchDailyBarsResponse =
        webClient.post()
            .uri("$baseUrl/api/v1/daily-bars/batch")
            .bodyValue(BatchRequest(codes, startDate, endDate, "qfq"))
            .retrieve()
            .bodyToMono<BatchDailyBarsResponse>()
            .awaitSingle()
}
```

### 4.4 DailyCollectJob

```kotlin
@Component
class DailyCollectJob(
    private val pythonClient: PythonDataServiceClient,
    private val historyService: StockHistoryService,
    private val infoService: StockInfoService,
    private val properties: DataCollectionProperties
) {
    private val logger = LoggerFactory.getLogger(DailyCollectJob::class.java)
    private val BATCH_SIZE = 50

    // 20:00 而非 18:00：BaoStock 官方更新窗口为交易日 17:30-19:00，
    // 18:00 可能拉到当日半成品数据（见 §4.7 防线①）
    @Scheduled(cron = "\${app.data-collection.cron:0 0 20 * * MON-FRI}")
    fun execute() = runBlocking {
        // 1. 健康检查
        if (!pythonClient.healthCheck()) {
            logger.error("Python data service offline")
            return@runBlocking
        }

        // 2. 获取股票列表（过滤 ST/退市）
        val stocks = pythonClient.fetchStockList()
            .filter { !it.isSt && !it.delisted }
            .filter { it.board in listOf("MAIN", "GEM", "STAR") }
        logger.info("Stocks to collect: ${stocks.size}")

        // 3. 分批处理
        var successCount = 0
        var failCount = 0

        stocks.chunked(BATCH_SIZE).forEach { batch ->
            // 滚动重拉窗口（§4.7 防线④）：起点取 findMaxDate+1 与 7 个交易日前中较早者，
            // 最近 7 个交易日整段重拉覆盖 —— 吸收数据源盘后修正 + 自动补漏采，
            // findMaxDate 断点逻辑降级为更早缺口的兜底
            val rolloutStart = LocalDate.now().minusDays(10)  // ≈7 个交易日
            val startDate = batch.minOfOrNull {
                val next = historyService.findMaxDate(it.code)?.plusDays(1)
                when {
                    next == null -> properties.defaultStartDate
                    next < rolloutStart -> rolloutStart.toString()
                    else -> next.toString()
                }
            } ?: properties.defaultStartDate
            val endDate = LocalDate.now().toString()

            try {
                val response = pythonClient.fetchDailyBarsBatch(
                    batch.map { it.code }, startDate, endDate
                )
                response.results.forEach { (code, result) ->
                    historyService.saveBatch(code, result.data, result.source, it.board)
                    successCount++
                }
                failCount += response.failed.size
            } catch (e: Exception) {
                logger.error("Batch failed: ${e.message}")
                failCount += batch.size
            }
            delay(2000) // 批次间间隔
        }
        logger.info("Done: success=$successCount, failed=$failCount")
    }
}
```

### 4.5 涨停检测

**关键设计决策：不复权价格做涨停检测**

前复权（qfq）会调整历史价格以消除除权除息影响。但这导致一个问题：
- 除权日的前一天收盘价被复权调整后，与当天收盘价计算出的涨跌幅 ≠ 实际涨跌幅
- 例如：某股 10 送 10，不复权前一天收盘 20 元，当天收盘 10.5 元（实际涨 5%），但 qfq 调整后前一天变成 10 元，算出涨 5%（碰巧对了）。但如果是 10 合 1 缩股，qfq 会给出错误的涨跌幅。

**解决方案：使用数据源原始 change_percent**

BaoStock 的 `pctChg` 和 AKShare 的 `涨跌幅` 都是基于**不复权价格**计算的，是真实涨跌幅。V2 直接用这个值做涨停判断，不再从 OHLCV 自行计算。

```kotlin
object LimitUpDetector {
    /**
     * 检测涨停/跌停
     * @param changePct 数据源提供的原始涨跌幅%（不复权，真实涨跌幅）
     * @param board MAIN / GEM / STAR
     * @return Pair(isLimitUp, isLimitDown)
     */
    fun detect(changePct: BigDecimal, board: String): Pair<Boolean, Boolean> {
        val upThreshold = when (board) {
            "GEM", "STAR" -> BigDecimal("19.9")   // 创业板/科创板 20%
            else -> BigDecimal("9.9")               // 主板 10%
        }
        val downThreshold = upThreshold.negate()

        return changePct >= upThreshold to changePct <= downThreshold
    }
}
```

**Python 侧返回格式**：`change_percent` 字段直接透传数据源原始值（BaoStock pctChg / AKShare 涨跌幅），不做复权调整。Kotlin 侧存为 `change_pct` 列，同时用于涨停检测。

**mootdx 不返回 change_percent 的处理**：mootdx 日K数据不含涨跌幅，但提供 last_close（前收），可用 `(close - last_close) / last_close * 100` 计算。因为 mootdx 返回的是不复权数据，所以这个计算是准确的。

### 4.6 除权漂移检测与自动重拉（存 qfq 决策的配套机制）

**决策（2026-10-02，用户确认）**：stock_history 存**前复权（qfq）OHLC** 作为干净数据——涨跌幅、波浪分析、未来回测收益全在同一条复权曲线上才有可比性。不复权价只用于涨停检测（§4.5，用数据源原始 change_pct，不受影响）。

**漂移问题**：前复权价在每次除权除息后会**回溯调整整条历史**。库内旧 qfq 行是"当时口径"，新拉的行是"最新口径"，同一只股票的时间序列会出现断裂。不能靠人工发现，必须有自动检测 + 自愈：

```
检测规则（在 StockHistoryService.saveBatch 内实现）：
1. 每根 bar 携带数据源的 prev_close（昨收）：
   - BaoStock: query_history_k_data_plus 字段列表加 preclose（M0 探针实测有值 ✓，2026-10-03）
   - AKShare:  stock_zh_a_hist **无昨收列**（M0 探针实测列名仅 日期/股票代码/OHLC/成交量/成交额/振幅/涨跌幅/涨跌额/换手率）→ 传 null
   - mootdx:   自带 last_close（M0 未实测，M3 接入时验证）
   - prev_close=null 的 bar 跳过涨跌幅对拍部分，链式校验 ③（库内昨日 close 基准）不受影响独立有效——failover 到 AKShare 期间检测能力降级但不断链；BaoStock 恢复后下一 bar 自动带回
2. 逐 bar 校验：|prev_close − 前一根 bar 的 close| ≤ max(0.01, prev_close × 0.5%)
   - 批内相邻 bar 直接互相校验
   - 批首 bar 与库内该股最后一根 close 校验（findByCodeAndDate 查前一个交易日）
   - 无前一日数据（IPO 首日/空档）跳过
3. 校验失败 → 判定发生除权除息（或脏数据）：
   a. 写 data_quality_log（issue_type='ADJUSTMENT_DRIFT'，detail 记录日期/两值/来源）
   b. 触发该股**单股全量重拉**：fetchDailyBarsBatch(单code, defaultStartDate, 今天)
      - ON CONFLICT DO UPDATE 幂等覆盖全部历史行 → 整条序列重置为最新复权口径
      - 单股全量仅数千行，即使每日数只触发，代价可忽略
4. 重拉完成后复检：若仍不一致 → issue_type='ADJUSTMENT_DRIFT_UNRESOLVED'，人工介入
```

**自愈性质**：任何一次检测命中并重拉，都会把该股**全部累积漂移一次性清零**（重拉即重新对基），因此 0.5% 容差漏检的小额分红只会留下有界的微小漂移，不会无限累积。

**穿透校验过的边界**：
- 停牌期 qfq 序列不变，复牌日 preclose 仍等于库内最后 close，不会误报；
- 涨跌停、大波动日 preclose 恒等于昨收，不受涨跌幅影响，不会误报；
- BaoStock/mootdx preclose 口径一致（均为除权后昨收），failover 切源不引发误报；AKShare 无该列（M0 探针实测）传 null 降级，见上。

### 4.7 当日数据准确性保障（五道防线）

| 防线 | 机制 | 抓什么错误 |
|------|------|-----------|
| ① 采集时序 | cron 默认 **20:00**（BaoStock 更新窗口 17:30-19:00，18:00 会拉到当日半成品） | 源半成品 |
| ② 行内自洽校验 | `DataValidator`（saveBatch 入库前逐行）：`high ≥ max(open,close)`、`low ≤ min(open,close)`、四价均 >0 且 close∈[low,high]、volume ≥ 0；持有 prev_close 时交叉校验 `|change_pct − (close−prev_close)/prev_close×100| ≤ 0.2%`（除权日豁免，§4.6 已识别） | 单行错值 |
| ③ 链式校验 | §4.6 prev_close 机制（每根新 bar 昨收 = 库内昨日 close） | 当日数据与历史断链 |
| ④ 滚动重拉 | 每日增量起点取 `findMaxDate+1` 与 **10 自然日前（≈7 交易日）** 的较早者，最近 7 个交易日整段 `DO UPDATE` 重拉覆盖（3.5 万行/日） | 源盘后清算修正、当日采集失败的漏采 |
| ⑤ 双源抽查 | CrossValidateJob 抽样对比两源，差异落 `CROSS_VALIDATE_MISMATCH` | 数据源自身错值 |

**②③④ 的联动**：三者共用同一批 prev_close/校验数据，合并实现于 `StockHistoryService.saveBatch`——逐行先过 DataValidator，链式校验失败走 §4.6 重拉，滚动窗口保证源修正最多滞后 1 天入库。

**当日无数据的处理（与退市规则衔接，缺失≠退市）**：当日采集完无行的股票重试 2 次后记 data_quality_log（`NO_BAR_TODAY`），**不告警不置位**（停牌正常现象）；连续 20 交易日无行升级为 `DELIST_SUSPECT` + 钉钉，人工确认才置 delisted（见 §2.2 判定规则表）。

### 4.8 连板数与涨停梯队（2026-10-02 新增功能）

**定义**：连板数 = 连续涨停的交易日数（首板=1，断板归 0）。纯派生数据——`今日连板 = is_limit_up ? 昨日连板+1 : 0`，在 saveBatch 写入时顺带计算，零额外采集成本。

```
计算规则（saveBatch 内，bars 按日期升序遍历）：
1. bar.isLimitUp == false → limit_up_streak = 0
2. true → 前一交易日（trading_calendar 找）：
   a. 批内有前一日 bar → limit_up_streak = 前一日.limit_up_streak + 1
   b. 批内无 → 查库内该股 < 窗口起点的最近一行（含滚动重拉续基）：
      前一交易日有行且 is_limit_up → limit_up_streak = 该行.limit_up_streak + 1
      前一交易日无行（停牌/未上市）→ limit_up_streak = 1（停牌断板，复盘重计，与市场惯例一致）
3. IPO 守卫：bar.date 距 stock_info.ipo_date 不足 5 个交易日 → is_limit_up 强制 false、limit_up_streak=0
   （上市首 5 日无涨跌幅限制，不存在涨停——顺带修正 §4.5 新股误标风险）
4. 涨停阈值按 board 区分：主板 10cm、双创 20cm（§4.5 复用）；ST 不在采集范围（§2.2 隔离原则）——5cm 梯队天然不存在，是设计而非缺口
```

**limit_down_streak（镜像规则，同一次 saveBatch 顺带计算）**：`今日连跌停 = is_limit_down ? 昨日连跌停+1 : 0`；断板/停牌归 0；IPO 守卫同款适用（上市首 5 日无涨跌幅限制，±20cm 阈值都会误标，is_limit_down 一并强制 false）；供 §4.9 崩塌池使用。

**涨停梯队查询（不落汇总表，查询即得）**：

```
GET /api/v1/limit-up-board?date=2026-10-02
→ { date, limit_up_count, leaderboard: [
     {code, name, limit_up_streak, board /*MAIN|GEM|STAR*/, change_pct, industry, concept_boards}
   ] /* 按 limit_up_streak DESC */ }
当日最高板 = leaderboard[0]（同板数并列时全部返回）
```

**板块归属（BoardCollectJob，stock_info 已预留 industry + concept_boards 列）**：
- 数据源：东财板块成分接口（行业板块 `stock_board_industry_*` ≈86 个 / 概念板块 `stock_board_concept_*` ≈400+ 个）；
- 频率：**行业每日全量、概念每周全量**（TokenBucket 2 rps 限速下概念全量约 3-4 分钟）；
- 写入（2026-10-02 用户确认）：`stock_info.industry` 与 `concept_boards` **均为 JSON 数组**（industry 不再是单一主行业——个股可属多个行业口径，东财行业成分可能一股多属时如实存）；
- **只关注当前成分快照**：覆盖写、不保留成分历史、不做成分关系表规范化（stock_board/member 方案已否决）——板块效应/聚合条件用当前成分回测存在的时变偏差，用户已知悉并接受，报告不做特殊标注；
- sector_daily 聚合：`jsonb_array_elements` 展开两数组后 join+group by（~5000 股 × ~10 板块 ≈ 5 万行展开，毫秒级）；
- 梯队接口 join stock_info 输出板块归属（行业取数组第一个作展示主行业）。
- 探针确认项：板块成分接口列名、成分股代码格式（裸数字/带前缀）、**一股是否实际多行业归属**（若源数据恒单行业，industry 数组单元素自然兼容）。

### 4.9 情绪周期表（2026-10-02 新增：高位股情绪监控 + sentiment_cycle）

**背景（用户口径，2026-10-02 确认）**：情绪指标不看全市场普涨普跌，也不看普通股当日首板——**高位股（近期多涨停/涨幅 50%+ 的股票）与崩塌股（连续跌停/高位暴跌）的表现才代表市场情绪**。参照"剑门体系情绪周期表"模板（大周期/小周期 1-6 评级、大肉总数/大面总数、连板总数、周期高度龙、超短周期状态），V2 每日自动派生，零新增采集。

**术语表（2026-10-02 用户确认，口径单点定义，防落地理解漂移）**：

| 术语 | 系统内定义 | 落点 |
|------|-----------|------|
| 大肉 | 高位强势股今日大涨（≥+5% 或涨停），打板/持股者吃到肉 | 强势池今日 ≥+5% 计数+名单 |
| 大面 | 高位股今日大跌（吃面），尤其高位炸板、连续跌停 | 强势池今日 ≤-5% 计数+名单 |
| 核 / 核按钮 | 大资金集中砸盘，把票砸向跌停（"按了核"） | 大面的成因，不单独建模 |
| 反核 / 止跌反核 | 崩塌股（连续跌停/高位暴跌）被资金逆势承接拉回止跌甚至涨停——**情绪修复的领先信号**；与"反包"方向相反：反核救跌停方向的票，反包是强势股断板后再涨停 | 崩塌池今日 ≥+5% 或涨停 → rebound_count；followup"反核止跌"分类 |
| 反包 | 连板股断板后（1-3 日观察期内）再次涨停、包住断板日实体；反包后创新高 → 小周期升级大周期 | 龙头状态机 BROKEN→RISING |
| 晋级 | 连板股今日继续涨停，n 板 → n+1 板 | leader_json 晋级/断板判定 |
| 断板 | 连板股今日未涨停，连板中断 | 状态机 BROKEN 态，开观察期 |
| 龙头 / 高度龙 / 总龙头 | 龙头=市场最高板带节奏者；高度龙=当日梯队板最高者；总龙头=贯穿整轮、反复反包创新高者（如平潭发展式） | dragon_cycle / dragon_json |
| 梯队（连板梯队） | 当日按连板数分层的涨停股结构（几板几只） | LimitUpController |
| 冰点/退潮/混沌/发酵/主升/高潮 | 情绪周期六阶段：冰点=连板高度与大肉缩至底部、大面衰竭；退潮=大面激增、高标断板；混沌=涨跌互现无主线；发酵=梯队扩张；主升/高潮=最高板持续刷新、大肉批量 | status_text 标签 + 大/小周期阶段标注 |
| 打板 | 涨停瞬间买入的打法 | 大肉/大面默认打板者视角 |

**对象池定义（每日动态计算，阈值全部走配置 sentiment.pool-*.）**：

```
强势池（大肉/大面的统计对象，满足任一即入池）：
  P1. 近 3 个交易日内最高 limit_up_streak >= pool.min-streak (默认 3，覆盖 4 板以上高标)
  P2. 近 5 个交易日内涨停次数 >= pool.min-limit-ups (默认 2，"最近都有好几个涨停")
  P3. 近 5 个交易日累计涨幅 >= pool.min-5d-gain (默认 50%，"最近涨幅超过 50%"，qfq 收盘价窗口计算)

崩塌池（观察大面延续还是止跌）：
  C1. limit_down_streak >= collapse.min-streak (默认 2，连续跌停)
  C2. 近 5 个交易日累计跌幅 <= -collapse.max-5d-drop (默认 30%，高位崩下来的)
```

**SentimentCycleJob（@Scheduled，排在 DailyCollectJob 之后，如 20:40；失败不阻塞主采集，钉钉告警）**：

```
每日一行写 sentiment_cycle（幂等 upsert by trade_date）：
1. 全市场：limit_up_count / limit_down_count / lianban_count(limit_up_streak>=2) / max_streak / dragon_json
2. 大肉数 = 强势池内今日 change_pct >= +5%（pool.big-meat-threshold）
3. 大面数 = 强势池内今日 change_pct <= -5%（pool.big-face-threshold）
4. leader_json = 近 1-3 日最高板前三名逐个记录：晋级（连板延续）/ 断板 / 大面
5. rebound_count = 崩塌池内今日止跌反核数（今日涨停 或 change_pct >= +5%）
   —— 关键区分：普通股当日首板无意义，但崩塌股的首板 = 止跌反核信号（用户口径）
6. 大/小周期建议值 big_cycle_sug/small_cycle_sug：以**龙头生命周期状态机**（下）为定性来源（大/小周期 + 龙头状态），
   1-6 数值评级保留为周期内阶段标注（连板高度 + 大肉/大面比 + 龙头晋级率映射，系数配置化）；
   人工确认值 big_cycle/small_cycle 优先于建议值展示
7. status_text：建议标签（冰点/混沌/主升/退潮，同规则映射），人工终定
8. big_meat_list / big_face_list：大肉/大面**个股名单**（强势池内达标者，含代码/名称/今日涨跌幅/
   连板数/行业）；大肉/大面总数即名单长度（count 列冗余存储便于查询，写入时同源保证一致）
9. followup_json（次日关注兑现，用户口径：今日大肉/大面个股明日继续关注）：
   读昨日行 big_meat_list/big_face_list，逐股取今日表现分类——
   大肉名单：延续（今日再 ≥+5% 或涨停）/ 回落（0~+5）/ 转大面（≤-5）/ 停牌
   大面名单：反核止跌（≥+5% 或涨停）/ 弱势震荡 / 继续大面（≤-5）/ 停牌
   即"昨日关注名单今日兑现情况"，当日名单自动滚动为明日关注名单，无需人工记录
10. 钉钉推送文字版情绪日报（大肉/大面总数 + 当日个股名单 + 昨日名单今日兑现汇总 +
    最高板/龙头晋级情况/崩塌组止跌情况）
```

**龙头生命周期状态机（2026-10-02 用户口径：大/小周期由龙头定义，不是独立评分）**：

> 周期 = 龙头的生命周期。龙头 = 市场最高板、持续晋级带着市场往上走的票（如平潭发展式总龙头）。
> 小周期：龙头 5-7 板断板且**不反包**，周期随之结束；大周期：龙头断板后**反包**再涨停并创新高、多打好几个板——小周期升级为大周期。
> **龙头停牌不结束周期**：停牌期间周期延续（它仍是龙头），复牌后再走断板/反包判定。

```
状态：RISING 上升（连板延续）→ BROKEN 断板（观察期）→ [反包→RISING | 阵亡→DEAD | 停牌→SUSPENDED]

逐日推进（SentimentCycleJob 内，写 dragon_cycle；阈值配置 sentiment.dragon-*.）：
1. 上位：前一龙头 DEAD 后，当日最高板股票接棒开新周期（并列取先到者；confirm 接口可人工改判）
2. RISING：龙头今日涨停（limit_up_streak 延续），max_streak 刷新
3. BROKEN：龙头今日未涨停 → broken_date 起观察期（默认 3 个交易日）
4. 反包：观察期内再次涨停
   └ 若此后 limit_up_streak 超过断板前最高板（创新高）→ cycle_type 定为 BIG；rebreak_count+1
5. DEAD：观察期内无反包 → 周期结束
   └ 断板时 max_streak ≤ dragon.small-max-limit_up_streak (默认 7) 且无反包 → cycle_type = SMALL
   └ max_streak ≥ 8 或曾反包创新高 → cycle_type = BIG
6. SUSPENDED：交易日历开市但龙头无 bar（复用 §4.7 NO_BAR_TODAY 检测）→ 状态置 SUSPENDED，
   周期延续，当日 dragon_json 龙头名标注"XX（停牌）"，suspend_json 记录区间，复牌后回到 2/3 判定
```

**穿透校验**：状态机只用 limit_up_streak + 交易日历（停牌检测已有），零新增采集；大小周期定性、起止日、龙头更替全部可由数据推导且每日落库，历史周期可在网页上对比（平潭发展式完整大周期 vs 5-7 板即断的小周期）。

**术语判定接口（2026-10-02 确认）**：判定逻辑单点 `SentimentClassifier`（术语表口径的代码化）——SentimentCycleJob 落库与查询接口现算**共用同一实现**，口径永不漂移：

```
GET /api/v1/sentiment-cycle/{date}/terms
  当日完整术语解读：{ 阶段标签(冰点~高潮), 大小周期+龙头状态,
    actions: [{code, name, label: 反包|晋级|断板|反核止跌|继续大面|大肉|大面|停牌, evidence}] }
  —— 钉钉日报与网页详情共用这份"今日术语清单"

GET /api/v1/stocks/{code}/actions?from=&to=
  个股动作标签时间线：逐日 [{date, label, limit_up_streak, change_pct}]
  —— 复盘视角：某股 6/18 断板、6/20 反包、6/23 创新高定性大周期
```

按需现算不落库（数据全在 stock_history limit_up_streak 列 + 交易日历）；个股动作标签集同时是二期 L2 DSL 的信号源词汇表（§12.7 策略 YAML 可写 `label: 反包 within_days: 3`）。

**情绪周期网页（2026-10-02 确认，样稿 docs/design/sentiment-dashboard-mock.html）**：

```
实现方式：Spring Boot 静态页（src/main/resources/static/sentiment.html，单文件、零前端框架），
打开即查 /api/v1/sentiment-cycle/range + /api/v1/dragon-cycle 实时渲染——无需"每日更新一列"，
sentiment_cycle/dragon_cycle 每日自动追加，页面永远展示全部历史。
页面结构（样稿已验证）：①当日 KPI（大小周期徽章/状态/最高板+高度龙/大肉/大面/连板数）
②大周期热度带（1 冰点→6 高潮色阶）③主图：大肉(红柱)/大面(绿柱)对比 + 连板总数/最高板曲线
④龙头周期时间轴（dragon_cycle：起止日/最高板/反包/停牌区间/大小定性）
⑤一天一列明细表（还原 xlsx 模板布局，右端最新列高亮，首列固定，横向滚动）
⑥人工编辑：点击 大周期/小周期/状态 单元格弹出选项浮层（1-6 / 冰点~高潮），确认即调
   PUT /sentiment-cycle/{date}/confirm 落库（人工确认值优先展示、标注 ✎ 并保留系统建议值对照）；
   样稿 docs/design/sentiment-dashboard-mock.html 已含完整交互原型（样例数据存 localStorage 演示）
⑦大肉/大面个股名单面板：当日 big_meat_list/big_face_list 展开（个股+涨幅+连板数+行业）+
   "昨日关注名单今日兑现"（followup_json：延续/回落/转大面；反核/弱势/继续大面，停牌标注），
   名单个股明日自动滚动为关注名单；今日名单来自强势池过滤，天然不含 ST；
   **名单可编辑**：逐股 ✕ 移除 / "+ 添加个股"（兜底系统漏判/误判），调
   PUT /api/v1/sentiment-cycle/{date}/lists 落库（服务端校验代码存在且非 ST、重算 count、
   写 lists_manual_json 留痕；样稿同名单位置有完整交互原型）；
   **添加个股支持模糊搜索**：输入 代码/名称/全拼/拼音首字母（如 pt → 平潭发展）实时下拉推荐，
   调 GET /api/v1/stock-search（stock_info.search_key + pg_trgm），选中回填 code+name 再提交；
   样稿已含搜索下拉交互原型（本地样例库单测通过：pt/ptf/6014/lian 均命中）
```

**穿透校验过的数据可行性**：池子各条件只用 stock_history（limit_up_streak / limit_down_streak / qfq 收盘窗口 / change_pct）+ trading_calendar，无需新数据源；ST 已全系统隔离不入库，天然不污染池子；停牌股无当日 bar 自然不参与当日统计；大肉/大面名单经强势池过滤后通常几只~几十只，JSONB 列容量无压力，followup 只依赖昨日行 + 今日 stock_history，滚动链路自洽。

**查询接口（SentimentController）**：

```
GET /api/v1/sentiment-cycle?date=        单日详情（含 leader_json/dragon_json 展开）
GET /api/v1/sentiment-cycle/range?from=&to=  区间序列（画情绪曲线）
PUT /api/v1/sentiment-cycle/{date}/confirm   人工确认/修正 大周期/小周期/状态
```

**与模板的关系**：xlsx 模板本身不再手工维护；sentiment_cycle 按天记录，任意区间可导出为同格式表格。字段对应：连板总数→lianban_count、最高板/周期高度龙→max_streak/dragon_json、大肉总数→big_meat_count、大面总数→big_face_count、大/小周期→龙头状态机定性+阶段标注+人工确认、超短周期状态→status_text。

---

## 五、Python 微服务

### 5.1 整体设计（复用 V1 Plan §三）

- FastAPI + uvicorn，端口 8000
- CircuitBreaker + TokenBucket 限流 + 自动 failover Router
- 股票代码：对外统一**裸数字** 600000（V2 标准），内部适配各数据源格式

### 5.2 三个 Adapter 的代码格式转换

> **2026-10-04 增补第四源**：`YahooAdapter`（可选源，直连 v8/finance/chart，绕开 yfinance 指纹；代码内部 `600000.SS/.SZ`，ticker 映射 6/9→.SS 其余→.SZ）。选型依据与本出口配额约束见 §18.3①；可选源语义（missing-check 只硬查三核心源、failover 恒排尾）见 §18.2。以下表格仍为核心三源。

| 数据源 | 内部格式 | 输入转换 | 输出转换 |
|--------|---------|---------|---------|
| BaoStock | `sh.600000` | `"600000"` → 加前缀 → `"sh.600000"` | row[1] `"sh.600000"` → `.replace(".", "")` → 去前缀 → `"600000"` |
| AKShare | `600000` | `"600000"` → 直接用 | row["股票代码"] `"600000"` → 直接用 → `"600000"` |
| mootdx | `600000` + market | `"600000"` → 直接用 + 计算 market | bar["code"] `"600000"` → 直接用 → `"600000"` |

### 5.3 BaoStock Adapter（V2 版）

```python
def _sync_fetch_daily_bars(self, code, start, end, adjust):
    # 输入 "600000" → BaoStock 需要 "sh.600000"
    prefix = "sh" if code.startswith(("6", "9")) else "sz"
    bs_code = f"{prefix}.{code}"
    adjust_flag = {"qfq": "2", "hfq": "1", "none": "3"}.get(adjust, "2")

    rs = bs.query_history_k_data_plus(
        bs_code,
        "date,code,open,high,low,close,volume,amount,pctChg,turn,preclose,tradestatus",
        start_date=start, end_date=end,
        frequency="d", adjustflag=adjust_flag
    )

    bars = []
    while rs.error_code == '0' and rs.next():
        row = rs.get_row_data()
        # 停牌过滤必须用 tradestatus（row[11]）：官方说明停牌日返回行且 close≠空、=昨收，
        # 用 "close 为空" 判断会把停牌假 bar（OHLC=昨收、量额=0）放进库
        if row[11] != "1":
            continue
        bars.append({
            "date": row[0],
            "code": code,                          # 直接用输入的裸数字
            "open": float(row[2]) if row[2] else 0,
            "high": float(row[3]) if row[3] else 0,
            "low": float(row[4]) if row[4] else 0,
            "close": float(row[5]) if row[5] else 0,
            "volume": float(row[6]) if row[6] else 0,
            "amount": float(row[7]) if row[7] else 0,
            "change_percent": float(row[8]) if row[8] else 0,  # pctChg 不复权
            "turnover": float(row[9]) if row[9] else 0,
            "prev_close": float(row[10]) if row[10] else None,  # 除权后昨收，供 §4.6 漂移检测
        })
    return bars
```

### 5.4 AKShare Adapter（V2 版）

```python
def _sync_fetch_daily_bars(self, code, start, end, adjust):
    # 输入 "600000" → AKShare 直接用裸数字
    df = ak.stock_zh_a_hist(
        symbol=code, period="daily",
        start_date=start.replace("-", ""),
        end_date=end.replace("-", ""),
        adjust=adjust
    )
    if df.empty:
        return []

    bars = []
    for _, row in df.iterrows():
        bars.append({
            "date": str(row["日期"]),
            "code": code,                          # 直接用输入的裸数字
            "open": float(row["开盘"]),
            "high": float(row["最高"]),
            "low": float(row["最低"]),
            "close": float(row["收盘"]),
            "volume": float(row["成交量"]),
            "amount": float(row["成交额"]),
            "change_percent": float(row["涨跌幅"]),  # 不复权
            "turnover": float(row["换手率"]) if "换手率" in row.index else 0,
            "prev_close": float(row["昨收"]) if "昨收" in row.index else None,  # 供 §4.6 漂移检测
        })
    return bars
```

### 5.5 mootdx Adapter（V2 版）

```python
def _sync_fetch_daily_bars(self, code, start, end, adjust):
    # 输入 "600000" → mootdx 直接用裸数字 + market
    market = 1 if code.startswith(("6", "9")) else 0
    all_bars = []
    for i in range(20):
        bars = self._safe_call(
            self._client.client.get_security_bars, 9, market, code, i * 800, 800
        )
        if not bars:
            break
        all_bars.extend(bars)

    # ... 过滤日期、排序 ...

    bars = []
    for _, row in df.iterrows():
        # mootdx 不返回 change_percent，用 last_close 计算（不复权，准确）
        last_close = float(row.get("last_close", 0))
        close = float(row["close"])
        change_pct = ((close - last_close) / last_close * 100) if last_close > 0 else 0

        bars.append({
            "date": row["datetime"][:10],
            "code": code,                          # 裸数字
            "open": float(row["open"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "close": close,
            "volume": float(row["vol"]),
            "amount": float(row["amount"]),
            "change_percent": round(change_pct, 4),
            "turnover": 0,  # mootdx 不提供换手率
            # 不输出 prev_close：mootdx 是不复权数据，与库内 qfq 口径不同，
            # 其 prev_close 参与 §4.6 校验会天天误报。见下方降级说明。
        })
    return bars
```

**mootdx 复权口径降级说明**：mootdx 只提供不复权日K。作为 failover 源写入的 bar 与库内 qfq 序列口径不一致，Kotlin 侧对 `source=mootdx` 的 bar **跳过 §4.6 漂移检测**，并写一条 data_quality_log（issue_type='RAW_FALLBACK'），表示该股该日数据待主源恢复后重拉为 qfq。

### 5.6 Python API 返回格式

```json
{
  "status": "ok",
  "results": {
    "600000": {
      "source": "baostock",
      "count": 250,
      "data": [
        {"date": "2024-01-02", "code": "600000",
         "open": 9.85, "high": 10.20, "low": 9.80, "close": 10.05,
         "volume": 50000000, "amount": 502500000,
         "change_percent": 2.04, "turnover": 0.85,
         "prev_close": 9.84}
      ]
    }
  },
  "failed": [
    {"code": "000001", "reason": "All sources failed"}
  ]
}
```

**注意**：所有 code 字段均为裸数字（不带 sh/sz），与 V2 PostgreSQL 数据库一致。

---

## 六、历史数据回填（数据源直拉，无 V1 迁移）

> **2026-10-02 修正**：经核实 V1 数据库已不存在（本机无 MySQL 安装/数据目录残留，仓库无 dump；早期探查记录中的「~5.5M 行」标题即为 *Estimated Row Counts / from codebase analysis*，从未实测）。**无数据可迁**，原 pgloader/CSV 迁移方案作废。

**替代方案：BackfillJob（COPY 两段式）+ DailyCollectJob 兜底**

1. **写入路径分工（2026-10-02 决策）**：历史回填走 **PG COPY**（10万+行/秒，2000 万行约 1-2 小时），日常增量仍走 saveBatch（含 §4.6/§4.7 校验链）。COPY 不支持 `ON CONFLICT`，用两段式保幂等：
   ```
   ① BackfillJob 拉一批（如 50 股全历史）→ DataValidator 校验 →
      CopyManager COPY INTO stock_history_stage（中转表，TEXT/CSV 流式，瞬时）
   ② INSERT INTO stock_history SELECT * FROM stock_history_stage
      ON CONFLICT (code, trade_date) DO UPDATE SET ...   -- 幂等合并，单条 SQL
   ③ TRUNCATE stock_history_stage → 下一批；stage 结构与主表一致（无 UNIQUE 约束）
   ```
   - 幂等语义与 saveBatch 的 DO UPDATE 完全一致，可中断重跑
   - 免费收益：每批先完整落 stage 再合并，DataValidator 可整批校验后再入主表
   - `stock_history_stage`：**UNLOGGED 表**（不写 WAL，更快，崩溃自清），Flyway V1 一起建，结构与主表一致但**不带 UNIQUE 约束**（约束会拖慢 COPY）
2. **单段回填（2026-10-02 决策：只存近 5 年，无需再分两段）**：
   - `app.data-collection.default-start-date: 20211001`，BackfillJob 一次拉满近 5 年（~600 万行，COPY 下**当天 <1 小时**）
   - 批次限速防触发数据源风控，`DO UPDATE` 幂等可随时中断重跑
   - 数据老化策略（将来启用）：每年 1 月 1 日可跑一次性清理 `DELETE FROM stock_history WHERE trade_date < 当前日 - 5 年`（先做不实现，写进运维手册即可）
3. **兜底**：DailyCollectJob 的滚动重拉/断点逻辑不变——任何漏网缺口由每日 20:00 增量自动补齐（§4.7 防线④）
4. 容量：~1500-2200 万行，Phase A 单表无压力
5. 收益（相比迁移 V1 数据）：无字段映射风险、无 V1 脏数据（V1 有错误数据订正史）、change_pct/涨停标记在源头即为正确语义
6. **派生列补算（2026-10-02 穿透发现的缺口，回填必须做）**：COPY 两段式只搬原始列——`is_limit_up/is_limit_down/limit_up_streak/limit_down_streak` 的正常派生路径在 saveBatch（§4.8），COPY 不经过。回填合并完成后按代码分组一次性补算（gaps-and-islands 窗口函数，BackfillJob 内置 SQL）：
   ```sql
   -- ① 涨停/跌停标记：按 board 阈值（join stock_info，双创 19.9 / 主板 9.9）
   -- ② 连板数（涨停为例；跌停镜像）：
   WITH marked AS (
     SELECT h.id, h.code, h.trade_date, h.is_limit_up,
            SUM(CASE WHEN h.is_limit_up THEN 0 ELSE 1 END)
              OVER (PARTITION BY h.code ORDER BY h.trade_date) AS grp
     FROM stock_history h)
   UPDATE stock_history x SET limit_up_streak =
     CASE WHEN m.is_limit_up
          THEN ROW_NUMBER() OVER (PARTITION BY m.code, m.grp ORDER BY m.trade_date)
          ELSE 0 END
   FROM marked m WHERE x.id = m.id;
   -- ③ IPO 守卫：stock_info.ipo_date 后首 5 根 bar（row_number per code）is_limit_up/两 limit_up_streak 强制 false/0
   -- ④ 停牌断板天然成立（停牌无行即断点）
   -- ⑤ 验证：抽 3 只股票的回填 limit_up_streak 与 saveBatch 增量路径在同日期段重算比对一致
   ```

---

## 七、实施步骤

### Step 1: 项目脚手架（1 天）

1. `gradle init` — Kotlin + Spring Boot 3.4.1 + JDK 21
2. build.gradle.kts — 最小依赖集：spring-boot-starter-web、spring-boot-starter-data-jpa、postgresql、kotlinx-coroutines、flyway-core、spring-boot-starter-test
3. application.yml — 按 §13.1 全样落（Hikari/hibernate batch/Flyway/soros.* 配置段/环境变量注入密钥）+ coroutine dispatcher 收口（IO.limitedParallelism(32)）
4. V1__init_schema.sql — 5 张表的 DDL（每列 COMMENT ON COLUMN 注释口径/单位 + §2.4 枚举 CHECK 约束）
5. docs/data-dictionary.md — §2.4 字段字典展开版（跨语言四端映射）
6. SorosApplication.kt — @SpringBootApplication + @EnableScheduling
7. 编译通过 + contextLoads 测试通过

### Step 2: Entity + Repository + 测试（2 天）

1. 5 个 Entity（普通 class + @Entity，非 data class）
2. 5 个 Repository
3. StockHistoryRepositoryTest — @DataJpaTest + TestContainers（PostgreSQL）
4. 验证：save / findByCodeAndDateBetween / findMaxDateByCode 全部通过

### Step 3: Python 微服务（3-5 天）

1. Phase 0.5 探针测试 — 实测三个数据源 API（**必须实测并回写 §2.4 字典**：volume/amount 的原始单位、AKShare「昨收」列名、BaoStock tradestatus/isST 停牌行真实返回、query_stock_basic 退市字段）
2. 基于探针结果写 Adapter
3. CircuitBreaker + TokenBucket + Router（参数见 §11.1）
4. /health /stock-list /daily-bars/batch /trading-calendar 端点（契约见 §11.1）
4. 全量 pytest 测试（含 JSON 契约字段名断言，防 §2.4 四端映射漂移）
5. 验证：curl 能拉到主板+创业板+科创板日K

### Step 4: Kotlin 侧集成（2-3 天）

1. PythonDataServiceClient（WebClient 按 §13.2 双 profile 超时/重试 + 熔断）
2. StockHistoryService.saveBatch()（含涨停检测 + prev_close 查询 + §4.6 除权漂移检测/单股全量重拉 + §4.7 DataValidator 行内自洽校验 + §4.8 连板数派生与 IPO 守卫）
3. DailyCollectJob（@Scheduled 20:00 + coroutines + §4.7 滚动重拉 7 日窗口 + 5 个基准指数日K采集 + 完成后发 DailyCollectCompleted 事件，§13.4 握手）
4. TradingCalendarService（trading_calendar 一次拉取载入 + 自动续期，供 DELIST_SUSPECT/新鲜度/回填对账消费）
4. ST 过滤 + 失败处理 + 钉钉告警 + 结构化日志/Micrometer 指标（§13.3）
5. 验证：端到端采集 → 入库 → 查询；除权漂移用例（伪造 preclose 不一致 → 触发重拉 → data_quality_log 落记录）；DataValidator 用例（high<close 等非法行被拒 + 记日志）

### Step 5: 基本面 + 辅助功能（2-3 天）

1. FundamentalsCollectJob（季度循环拉 stock_yjbb_em，亿元→元，UNIQUE(code, report_date) 幂等）
2. BoardCollectJob（行业每日/概念每周 → stock_info.industry/concept_boards）
3. CrossValidateJob（§11.2）
4. ManualDataController（兼容 V1 webhook，最小 6 端点，§11.4）
5. LimitUpController（涨停梯队/当日最高板，§4.8）
6. SentimentCycleJob + SentimentController（情绪周期派生/龙头状态机/查询/确认，§4.9，含 sentiment.html 网页；事件触发 + 21:30 兜底 cron，§13.4）
7. SorosAlgorithm（波浪分析迁移，§11.5 两版方案 + 对拍 golden 跑通锁差异清单）
8. 验证：梯队接口返回当日最高板与行情软件打板榜一致；连板数在停牌/新股/断板场景正确；情绪表大肉/大面数与人工复盘核对若干样本日；对拍差异逐条命中 off-by-one 清单

### Step 6: 历史数据回填（COPY，近 5 年当天完成）

1. BackfillJob：COPY 两段式写入（§六：stage 中转表 → ON CONFLICT 合并 → TRUNCATE），DataValidator 整批校验
2. 一次拉满近 5 年（`defaultStartDate=20211001`，~600 万行，当天完成）
3. 派生列补算 SQL（§六.6：is_limit_up/两 limit_up_streak gaps-and-islands + IPO 守卫 + 抽查比对）
4. 情绪历史冷启动回放（§13.5：POST /jobs/sentiment-replay，逐交易日顺序重建 sentiment_cycle/dragon_cycle）
5. 验证：股票覆盖数、行数与交易日历对账、抽样对照行情软件；limit_up_streak 抽查与增量路径一致；回放摘要钉钉通知；漏网缺口由每日增量滚动重拉自动补齐

---

## 七.5、部署方案（2026-10-02 简化：无 V1 切换问题）

> 原方案「V2 独立搭建 → 并行验证 7 天 → 历史回填 → 切换」建立在「V1 有数据、V1 在跑」的前提上。经核实 V1 数据库已不存在、无数据可迁，该前提不成立，切换方案整体作废简化。

### 策略

1. **V2 独立搭建，完成即投产**：Step 1-4 完成后 BackfillJob 一次回填近 5 年（COPY 当天完成），DailyCollectJob 次日起每日增量
2. **V1 退役**：代码保留作参考（SorosUtils 波浪算法迁移完成后仓库归档），无需保留运行环境
3. **V1/V2 完全独立**（端口 19889 vs 新端口；MySQL vs PostgreSQL），无切换动作、无回滚场景
4. 唯一检查项：确认 V1 没有在其他机器上仍运行采集 Job——如有，V2 投产后停掉即可
---

## 八、验证清单

```bash
# 编译
cd GeorgeSoros-V2 && ./gradlew build

# 测试
./gradlew test

# 启动
./gradlew bootRun

# Python 微服务
cd soros-data-service && uvicorn main:app --port 8000

# 端到端验证
curl http://localhost:8000/health
curl -X POST http://localhost:8000/api/v1/daily-bars/batch \
  -d '{"codes":["600000","000001","688981","300750"],"start_date":"2024-01-01","end_date":"2024-12-31"}'

# 数据库验证
SELECT code, COUNT(*) FROM stock_history GROUP BY code ORDER BY COUNT(*) DESC LIMIT 10;
SELECT MAX(trade_date) FROM stock_history WHERE code = '600000';
```

---

## 九、穿透评分（V2 方案，2026-10-02 六项穿透收尾后定稿）

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构 | 95 | 单表 + 复合索引，分区递进策略清晰；Job 依赖链已显式化（§13.4） |
| 数据库 | 95 | PostgreSQL 性能优势，容量评估到 50 年；COPY 派生列缺口已补（§六.6） |
| Python 微服务 | 92 | 三个 Adapter 代码格式转换已写清，涨停用原始 change_percent |
| Kotlin 代码 | 95 | 干净架构，coroutines dispatcher 收口（§13.1），正确 Entity 设计 |
| 部署切换 | 93 | 独立并行 → 7 天验证 → 历史回填 → 切换，回滚零成本 |
| 测试覆盖 | 92 | TestContainers + 情绪模块样例核对 + 算法对拍 golden 设计定稿（§11.5） |
| 历史回填（数据源直拉） | 94 | COPY 两段式 + 派生列补算 SQL（gaps-and-islands）+ 一致性抽查 |
| 情绪周期模块（§4.9） | 96 | 术语表单点口径、龙头状态机、名单滚动、判定接口同实现；冷启动回放与 Job 握手已设计（§13.5/§13.4） |
| **总分** | **97** | 原六项扣分全部完成设计销账（§十三/§六.6/§11.5）；剩余扣分只有"落地执行保真"类 |

### 剩余扣分项（3 分，全部为实施期验证项，无方案级缺口）

| # | 扣分 | 说明 | 何时销账 |
|---|------|------|---------|
| 1 | -1 | 算法对拍 golden：设计已定稿（§11.5），但 V1 复刻版移植与 off-by-one 差异清单须实际跑通锁定 | Step 5 实施时 |
| 2 | -1 | limit_up_streak 补算 SQL（§六.6）与情绪回放（§13.5）为设计稿，落地须实测性能与边界（1300 日回放、年度窗口 UPDATE） | Step 5/6 实施时 |
| 3 | -1 | 集成测试用例集未逐条列纲（TestContainers 场景清单待实施时展开：含情绪派生/COPY 合并/握手事件） | Step 2/4 实施时 |

> 已销账的六项（2026-10-02 穿透收尾）：application.yml 全样与 dispatcher 收口（§13.1）、WebClient 双 profile 超时重试+熔断（§13.2）、结构化日志与 Micrometer 指标（§13.3）、Job 完成握手（§13.4）、情绪历史冷启动回放（§13.5）、COPY 派生列补算（§六.6）。

---

## 十、实施备注

### 10.1 成本预估

**当前模型**：glm-5.3-flash（智谱，API 聚合平台 8 折；折后输入 $0.11/M、输出 $0.09/M、缓存读 $0.38/M、缓存写 $0.31/M。2026-10-02 选型定为主力编码模型，已设为 Claude Code 默认）

| 阶段 | 输入 tokens | 输出 tokens | 费用 |
|------|------------|------------|------|
| 当前会话（Plan，已在 qwen3.8-max 完成，沉没成本） | ~1.5M | ~200K | 已花 |
| Kotlin 后端 | ~3M | ~500K | $0.38 |
| Python 微服务 | ~2M | ~400K | $0.26 |
| 测试 + 调试 | ~1.5M | ~300K | $0.19 |
| 迭代修改 | ~1M | ~200K | $0.13 |
| **落地合计** | **~7.5M** | **~1.4M** | **≈ $0.95（约 ¥7）** |

- 全程（含 Plan 会话也在 glm-5.3-flash）≈ $1.1（约 ¥8）
- 浮动上限：长会话上下文重读走缓存（读 $0.38/M、写 $0.31/M），缓存命中占比高时约 $2-3
- 兜底模型：deepseek-v4-flash（5 折，输入 $0.41/M、输出 $0.21/M，输出窗 393K）——超长文件生成或 glm 不稳定时切换，同预算 ≈ $3.4
- 历史口径（对比用）：qwen3.8-max ≈ $21；claude-sonnet-5 ≈ $35-50

### 10.2 项目状态

- **V1 仓库**：`/Users/mt/Documents/moonton_project/GeorgeSoros`（现有系统，不动）
- **V2 仓库**：`/Users/mt/Documents/moonton_project/GeorgeSoros-V2`（新项目）
- **V2 远程**：`git@github.com:wangpengcode/GeorgeSoros-V2.git`（已 push，main 分支在线）
- **V2 git**：初始 commit `683baae`，已推送至 origin/main
- **Plan 文件**：`docs/PLAN.md`（本文件，2026-10-02 自 ~/.claude/plans/hazy-brewing-lerdorf.md 迁入；设计产出一律随 repo 走）
- **2026-10-04 进展**：东财「封禁」事件复盘定稿（§十八）；四件套落地 commit `44d37c3`（YahooAdapter / 分片三源池 / V5 校准三列 / CalibrationJob），Python 129 + Kotlin 399 测试全绿，双服务已部署；存量 UNKNOWN 180,200 行已批准 relabel AKSHARE_SINA；V1→V5 Flyway 全应用。

### 10.3 等待指令

一期底座（采集/回填/校准/交叉验证）已落地运行。后续实施顺序参照 §七 与 §十二/§十四（二期、盘中实时模块），等待用户指令。

---

## 十一、一期补充设计（2026-10-02 穿透系列定稿）

### 11.1 Python API 全契约（统一前缀 /api/v1）

| 端点 | 方法 | 请求 | 响应 |
|------|------|------|------|
| /health | GET | — | {status, sources: {baostock/akshare/mootdx: ok\|degraded\|down, yahoo: 可选源未注册为 None}} |
| /stock-list | GET | ?market=all&board=all | {status, stocks: [{code,name,market,board,is_st,delisted}]}（AKShare 列表 + st_em/stop_em 合并） |
| /daily-bars/batch | POST | {codes[], start_date, end_date, adjust:"qfq"} | {status, results:{code:{source,count,data[]}}, failed:[{code,reason}]}（部分失败不炸整批） |
| /trading-calendar | GET | — | {dates: ["2021-10-01",...]}（一次全量） |
| /limit-up-board | GET | ?date= | 涨停梯队/当日最高板（§4.8，Kotlin 查库直出，不经过数据源） |
| /sentiment-cycle | GET | ?date= / ?from=&to= | 情绪周期单日/区间查询（§4.9，查 sentiment_cycle） |
| /sentiment-cycle/{date}/confirm | PUT | body: {big_cycle, small_cycle, status_text} | 人工确认情绪评级（§4.9） |
| /sentiment-cycle/{date}/lists | PUT | body: {side: MEAT/FACE, action: ADD/REMOVE, code, name?, reason?} | 人工增删大肉/大面个股名单（§4.9，重算 count + lists_manual_json 留痕，校验非 ST） |
| /sentiment-cycle/{date}/terms | GET | — | 当日术语判定汇总（SentimentClassifier 现算，与 Job 落库同实现，§4.9） |
| /stocks/{code}/actions | GET | ?from=&to= | 个股动作标签时间线（反包/晋级/断板/反核止跌/大肉/大面/停牌，§4.9） |
| /stock-search | GET | ?q=（代码/名称/全拼/拼音首字母，如 pt→平潭发展）&limit=10 | 股票模糊搜索（查 stock_info.search_key，trgm 索引；名单添加、梯队查询等输入场景共用） |
| /dragon-cycle | GET | ?status=&limit= | 龙头生命周期列表（§4.9，查 dragon_cycle，网页时间轴数据源） |
| /dragon-cycle/{id}/confirm | PUT | body: {cycle_type, note} | 人工改判龙头周期大小/备注（§4.9 状态机兜底） |
| /fundamentals | POST | {report_date: "20241231"}（季度末） | {stocks: [{code, revenue, net_profit}]}——AKShare `stock_yjbb_em`，**列名为复合形式 `营业总收入-营业总收入`/`净利润-净利润`（源码确认）**，单位亿元 ×1e8 转元，每次调用自动分页抓全市场，按季度循环调用 |

**接口源探查结论（2026-10-02 Agent 源码级核实，存档 /tmp/soros-v2-design/akshare-endpoints.md）**：
- **股票列表主备双源**：主 `stock_info_a_code_name`（列名英文 code/name）内部靠解析深交所 Excel，有真实故障案例（akshare Issue #5947）→ 备源东财 `stock_zh_a_spot_em`；主源失败自动切备源；
- **北交所过滤**：列表默认含北交所，按代码前缀排除——旧号段 83/87/43 + **2025-10-09 起新号段 920**（board 推导与 ST 过滤同处处理）；
- **交易日历**：`tool_trade_date_hist_sina` 仅 trade_date 一列；实时抓新浪加密文件；**覆盖已探针实测 PASS（2026-10-03）：2026 全年 242 交易日覆盖至 12-31、节假日剔除正确、无重复；种子已留存 docs/seed/trade_calendar.csv（1990~2026）**；2027+ 无未来日历 SLA → 每年 12 月自动重拉续期 + 本地法定节假日表兜底（查不到回退周一~五已设计）；
- 财务披露滞后为法定节奏（一季报 4 月末/半年报 8 月末/三季报 10 月末/年报次年 4 月末前），FundamentalsCollectJob 在披露季循环补拉。

错误信封：顶层 {status:"error", error:{code,message}}；单股失败进 failed[]。

**韧性参数**（2026-10-04 封禁事件后演进，全链路复盘见 §十八）：CircuitBreaker 每源独立（连续 5 次失败 → open 60s → half-open 单探测）+ IPGuard 独立管小时级封禁（§18.2）；TokenBucket 每源独立（baostock 5 rps / AKShare 2 rps + 抖动 / mootdx 3 rps / yahoo 0.5 rps + 0.2s 抖动）；Router 顺序 **baostock → akshare → mootdx → yahoo（可选源，注册才参与，恒排尾）**；多源分片分压 `int(code) % N` 稳态归属（默认池 baostock,akshare,yahoo，§18.3②）；stock-list/is_st/退市状态**只认 baostock/akshare，mootdx/yahoo 永不参与**。

### 11.2 CrossValidateJob（双源交叉验证，只观测不修正）

- 频率：每周日抽 50 股近 20 交易日 + 每日随机 10 股当日；
- 容差：close ±0.1%（两源同 qfq）、volume ±1%（单位换算探测点）、change_pct ±0.02pp；
- 差异落 CROSS_VALIDATE_MISMATCH；单批 >30% 不一致 → 钉钉告警；
- **修正永远走 §4.6/§4.7 正道，交叉验证只做观测**（防"用错误源覆盖正确源"）；mootdx 数据不参与对比。

### 11.3 钉钉告警（V1 无任何告警实现，从 0 新建）

| 事件 | 级别 | 限频 |
|------|------|------|
| Python 服务离线 / 批次失败率>10% | ERROR | 同类 10 分钟 1 条 |
| ADJUSTMENT_DRIFT_UNRESOLVED | ERROR | 每事件 1 条 |
| DELIST_SUSPECT（附人工确认清单） | WARN | 每股 1 条 |
| 某源降级持续>30 分钟 | WARN | 同类 10 分钟 1 条 |
| 回填完成 / 每日采集汇总 | INFO | 每日 digest |

ERROR 只留给"数据可能错"；状态类一律 WARN；去重限频防疲劳。

### 11.4 ManualDataController（基于 V1 探查的最小集）

V1 真实调用方 = resources/py 下 4 个 Python 脚本。**保留 6 端点**：`POST /history/daily`（加批量版，保留单条兼容；**恒返 ok 语义不能改成 4xx/5xx**——被调用方依赖）、`GET /history/max/date/{code}`（增量锚点，语义原样）、`POST /info/stock`、`GET /info/all`、`POST /index/info`、`GET /index/all`。**砍**：TestController 4 个 /test/*。**新增**：按 code+区间手动重跑 Job、失败任务列表+重试。

### 11.5 SorosAlgorithm 迁移方案（基于 V1 探查）

- V1 算法在 soros-data-adaptor/utils/SorosUtils.kt（analysis 模块是空壳）；管道 `findInflectionPoint(6)→merge→findPeekAndValley→littleTrend→bigTrend`，仅消费 code/date/close/high/low（与 V2 qfq 口径天然一致，st_change 恒 null 不影响）；
- **两版分开**：先"V1 忠实复刻版"（逐 bug 复刻 off-by-one：merge 丢末点、peekAndValley 丢尾 2、littleTrend 丢末 3 点+不落进行中段、bigTrend 末段 range 不重算、lastDays 恒 0）对拍验收 → 再出"V2 清理版"（不可变数据类、LocalDate 排序、修 bug），清理版单独维护 diff 清单；
- **仓内对拍**（V1 本机跑不起来）：V1 算法 1:1 移植为 V2 测试目录参考实现，参考 vs 清洁对拍 + L1 手工小向量锁每个 bug 行为；
- V2 优化：一次计算多表落库（V1 是两 Job 各自全量重算四阶段）。

**实现细节定稿（2026-10-02 六项穿透）**：

```
1. 版本与位置：
   - testFixtures/SorosUtilsV1.kt：V1 源码逐行复制进 V2 测试目录（Kotlin 2.0 编译 1.4 语法基本兼容，
     仅必要时补显式类型；禁止"顺手修正"任何逻辑，var 可变管道、共享引用原样保留）
   - main/.../SorosAlgorithmV2.kt：干净版——纯函数（入参 List<Bar> 不可变，管道每步返回新值），
     Bar 只含 code/date/close/high/low；输出结构 = V1 结果表行（stock_inflection_point / BIG_TREND）
2. 对拍 golden 测试：
   - 输入固定 3 只代表股近 5 年：含除权除息、长期停牌复牌、上市不足 20 根的新股边界
   - V1 复刻版 vs V2 清理版同输入各跑一遍，diff 两份结果表全字段；
     差异必须逐条命中 off-by-one 清单（已知的 5+1 处），出现清单外差异 = 有实现 bug，测试红
   - 另配 L1 手工小向量（6/7/19/20/21 根 bar 边界）锁定每个 off-by-one 的精确行为
3. 排序兼容：V1 按日期字符串字典序（依赖 yyyyMMdd 定宽）——V2 复刻版保留原样，清理版用 LocalDate；
   对拍输入日期格式统一 yyyyMMdd，消除格式变量
4. 表结构兼容：stock_inflection_point / BIG_TREND 字段名照抄 V1
   （code/wave_direction/data_type/last_days/st_range），不"顺手修正"，保下游报表兼容
5. 调度：V2 单 SorosJob，由 DailyCollectJob 完成事件触发（§13.4 握手），只重算 findMaxDate 有变化的股票；
   废弃 V1 双 Job 全量重算与死配置（range=0.5/multiWaveInterval=44/inflectionPointDays=20 不迁移）
```

---

## 十二、二期设计：回测与策略引擎（2026-10-02，落地时展开）

**定位**：回测=考场+裁判，策略=考生。策略实现 Strategy 接口（决策逻辑），回测引擎把它放进历史数据逐日执行并打分；三期 AI 策略工厂生成的代码实现同一接口即自动可测。

### 12.1 架构：事件驱动逐 bar 回放

```
交易日历逐日推进 → for each trading day D:
  1. 撮合昨日订单：T+1 开盘价成交；一字涨停不买/一字跌停不卖（REJECTED）
  2. BarContext（当日全市场 bar + 历史窗口 + 预计算信号 + 组合快照）
  3. signals = strategy.onBar(ctx)
  4. 风控链过滤 → 挂入明日队列
  5. mark-to-market（停牌股冻结估值）→ 记净值
→ BacktestResult{净值曲线, 成交明细, 指标, 事件流}
```

模块：`soros-backtest/`（engine/ strategy/ portfolio/ risk/ cost/ report/ persist/），独立 Gradle 模块，复用一期 entity/repository。

### 12.2 核心规则

| 规则 | 实现 |
|------|------|
| 成交价 | 次日开盘价 × (1±滑点)，滑点固定 0.1%（非随机，可复现） |
| 一字板 | open==high==low==close && isLimitUp/Down → REJECTED |
| T+1 | Position.availableShares 日切解冻（按交易日历，自然处理长假）；SELL 只卖可用部分 |
| 整手 | BUY floor(金额/open/100)×100；卖出可零股 |
| 停牌 | 禁交易、冻结估值；挂单跨停牌即作废（订单只挂次日） |
| 成本 | 佣金双向 0.025% + 印花税仅卖出（<2023-08-28 为 0.1%，后 0.05%）+ 过户费双向 0.001%；qfq 口径忽略最低佣金 5 元（误差<0.1%，报告标注） |
| 分红 | 不模拟——qfq 序列已隐含（等效分红再投资） |

### 12.3 Kelly 与风控链

`f* = p − (1−p)/b`（滚动 60 笔已平仓估 p/b）→ 实际仓位 = clamp(f*/2, 0, 25%)；已平仓 <20 笔退化等权 1/maxPositions。风控链顺序截断：Kelly → 单票≤25% → 持仓数≤10 → 现金约束。

### 12.4 信号供给（二期第一任务）

建表兼容 V1：`stock_inflection_point(code,date,close,high,low,type; UNIQUE(code,date))`、`big_trend(code,wave_direction,data_type,start_date,end_date,last_days,st_range)`。`WaveComputeJob` 每收盘一次计算多表落库（复用 §11.5 清洁版算法）。回测只读信号表——确定性要求。

#### 12.4.1 信号层数据设计（2026-10-02，配合 §12.7.1 词表）

**数据流分层**（YAML 只存策略逻辑定义，数据 100% 来自表）：

```
采集层（一期已有）: stock_history / index_history / stock_info / concept_boards
                    / sentiment_cycle / dragon_cycle / stock_fundamentals
  ↓ 每收盘 SignalPrecomputeJob 派生（事件触发同 §13.4 握手；历史并入 §13.5 回放补算）
预计算信号层: market_daily / sector_daily / signal_daily + big_trend / stock_inflection_point
  ↓ 回测启动时加载进 BarContext（§12.1）
策略求值: YAML（~1KB 逻辑描述，无行情数字）× BarContext 表数据 → 逐 bar 条件判定
```

**词表条件按实现方式分三类**：

| 类 | 条件 | 实现 |
|----|------|------|
| A 直读已有列 | 连板数、换手率、流通市值、股价、上市天数、指数涨跌、术语标签、周期阶段、龙头状态 | 一期已落库 |
| B 单股时序现算 | N日新高/新低、MA偏离、gap、振幅、量比、连续放量、距前高 | 回测加载该股序列后纯函数现算，**零新表**（N 是参数，不为每个 N 落库） |
| C 跨股聚合落库 | 梯队排名、板块涨停家数、板块内排名、涨跌家数比、昨涨停溢价、昨日大面家数、炸板 | 每根 bar 全市场扫不可接受，且是"当日全市场快照"，天然成表 |

**新增三张预计算表**：

```sql
market_daily(           -- 全市场温度计，一日一行
  trade_date DATE PK,
  adv_count        SMALLINT,   -- 上涨家数
  dec_count        SMALLINT,   -- 下跌家数
  limit_up_count   SMALLINT,   -- 今日涨停家数（冗余 = jsonb_array_length(limit_up_list)，同源校验）
  limit_down_count SMALLINT,   -- 今日跌停家数（同上）
  limit_up_list    JSONB,      -- [{code,name,change_pct,limit_up_streak,industry}]（industry=主行业展示值）（2026-10-02 用户确认）
  limit_down_list  JSONB,      -- 同构；涨停/跌停查询直接命中冗余名单，免全表扫 is_limit_up
  zhaban_count     SMALLINT,   -- 炸板家数（日线近似口径）
  yst_limit_premium NUMERIC(6,2),  -- 昨涨停今溢价 = 昨日 limit_up_list ∘ 今日行情（表自算自洽）
  yst_promotion    JSONB,     -- 分级晋级率（借鉴同花顺复盘）：{"total":21.05,"by_level":{"1to2":33.3,"2to3":25.0,...}}
                              --   = 昨日 N-1 板家数 ∘ 今日 N 板家数，与 yst_limit_premium 同一自算逻辑，同源自洽
  yst_face_count   SMALLINT    -- 昨日大面家数
)
sector_daily(           -- 板块聚合，日×板块
  trade_date, board, PRIMARY KEY(trade_date, board),
  limit_up_count, max_streak, avg_chg_pct,
  driver_text TEXT               -- 当日板块驱动主线一句话（LLM 生成，标"系统生成"，2026-10-02 定稿）
)
signal_daily(           -- 个股×日，只存"必须全市场排序才得出"的列
  code, trade_date, PRIMARY KEY(code, trade_date),
  streak_rank SMALLINT,           -- 当日梯队排名
  is_zhaban BOOLEAN,              -- 炸板（日线近似，统一口径落库）
  sector_streak_rank SMALLINT     -- 板块内板数排名
)
```

- 名单模式与 sentiment_cycle.big_meat_list/big_face_list 同构（大肉名单 ⊂ 涨停名单），count 冗余同源校验复用同一纪律；ST 天然不在库内；极端高潮日 ~150 家 < 20KB/行无压力。
- market_env/limit_ecology/sector 三组条件的查询与情绪网页、涨停池接口共用这份数据，单一事实来源。
- 精度折损已标注：炸板=日线近似（盘中触及涨停未封）、昨涨停溢价按收盘价口径。

**筹码分布自算（2026-10-03 穿透定稿，路径 A）**：`signal_daily` 追加 6 列 profit_ratio（获利盘%）、cost_dev（成本偏离%）、c90_low/high/conc、c70_low/high/conc——不存分布曲线本身（~50MB/年）。递推 `D_t = D_{t-1}×(1−tr) + tr×triangular(qfq_high, qfq_low, qfq_close)`，价格坐标全程 qfq、180 桶。穿透解决的两个口径坑：①不用 amount/volume 算形状峰（不复权坐标与 qfq 混杂），峰=qfq_close；②不落绝对平均成本（qfq 重对基会漂移），落 cost_dev=平均成本/现价−1（比率对复权平移免疫）；c90/c70 四个绝对 qfq 坐标列除权日会过期，由 AdjustCheckStep 全历史重算（§17.1 B6）。边界处理：一字板 ±0.5% 扁平兜底、换手率 clamp ≤1、停牌无 bar 筹码冻结、上市首日全量换手。计算：全市场全量重算 ≈32 亿次浮点运算（5400 股×~2000 日×~300 桶）JVM 秒~分钟级，桶状态驻留内存不持久化，增量每日 O(股×桶)。东财 stock_cyq_em 源码级探针（docs/research/chip-cyq-probe.md）：**它不是服务端接口，是 akshare 本地跑东财前端 JS 的 150 档三角分布+换手衰减递推，且只返回 90 个交易日**——与我们路径 A 同族模型，无权威性优势，加 mini_racer 依赖与 WAF 对抗（TLS 指纹拦截、30+ 次触发 IP 封禁）→ 不进主链；对拍重定位=同 fqt=qfq 口径下校准峰位/衰减参数（预期同族差异 <2%），非真值校验。

**K 线页展示端点（2026-10-03 §17.5 C4 补；响应 schema 2026-10-03 字段级对拍定稿）**：`GET /api/v1/stocks/{code}/kline?from=&to=` —— stock_history（qfq OHLC+量额）∘ signal_daily 筹码 8 列一次聚合返回；MA/MACD/换手等衍生指标前端现算（样稿同款公式）；叠加全局日期联动 ?date=（§15.2）回放日截断。不用 V1 兼容端点 /history/daily 供前端（webhook 语义，口径不同）。响应 schema：

```json
{ "code": "600000", "bars": [ {
    "date": "2026-09-30",
    "open": 9.22, "high": 9.49, "low": 9.16, "close": 9.48,
    "volume": 147484820, "amount": 1386209937.38,
    "chip": { "profit_ratio": 92.9, "cost_dev": -1.2,
              "c90_low": 8.10, "c90_high": 9.55, "c90_conc": 61.2,
              "c70_low": 8.65, "c70_high": 9.40, "c70_conc": 43.8,
              "avg_cost": 9.59 } } ] }
```
- chip 8 列名与 signal_daily DDL **一字不差**（样稿 `st.{profit,dev,r90[],c90,...}` 简写为演示态，M7 对齐；r90/r70 区间端点在 DTO 拆平为 c90_low/c90_high，前端拼回）。
- `avg_cost` = 现算派生 `close/(1+cost_dev/100)`（cost_dev 定义=(close−avg_cost)/avg_cost×100，反解即得），**不新增库列**；cost_dev 为 NULL（warm-up 前 60 日，§17 ⑥）的 bar 整个 chip=null，前端画"筹码暂缺"。
- 换手率现算 = volume/float_shares×100（float_shares 来自 stock_info，§17.1 B1）。

**复盘对标（2026-10-02 拆解同花顺「热点复盘」长图，ozone summary_image 接口）**：图中信息 → 本方案落点——涨停/跌停/炸板家数、总溢价幅 → market_daily；分级晋级率（一进二/二进三…）→ yst_promotion（本次补入）；是否首板/连板数 → limit_up_streak 词表组；涨停时间早→晚 → intraday_archive.first_seal_time（§十四，含秒级）；板块分组与板块涨停家数 → sector_daily。

**涨停原因归因（2026-10-02 定稿 ② LLM 生成，用户确认）**：SignalPrecomputeJob 末步加 AttributionStep——输入当日 limit_up_list + sector_daily + stock_info 板块归属（industry/concept_boards），单次批量 prompt（全涨停名单一次调用），输出 JSON schema 校验后落库：`limit_up_list[].reason`（个股题材标签串）+ `sector_daily.driver_text`（板块主线一句话）。要点：
- **受控词表**：reason 只能由该股自己的 industry/concept_boards 词汇组合而成，禁自由发挥——保证可比较、可展示一致；板块归属条件本来就用结构化字段，reason 定位 = 复盘展示 + 人工研究，**不进 L2 条件词表**。
- **诚实边界**：输入只有行情与板块结构，产出的是"题材归类式归因"（这只创新药业股涨停 → 归创新药主线），不是 THS 问财那种"ESMO 年会催化"事件级归因（那需要新闻/公告源，超本期范围）；页面展示标"系统生成"。
- **失败不阻塞**：每日涨停 ~50-150 家，每日 1-2 次调用（主力 glm-5.3-flash，兜底 deepseek-v4-flash），schema 校验失败重试 1 次，仍失败 reason=null，不影响握手链；重跑按日期覆盖（幂等）。
- **LLM 网关探针已实测（2026-10-03，网关 `llm.moontontech.net` /v1/messages Anthropic 兼容）**：①主备两模型均能产出受控词表 JSON 且解析通过 ✓ ②**两模型均默认输出 thinking 块且排在 text 前——解析必须按 `type=="text"` 过滤取块，禁止取 content[0]** ③glm **始终思考不可关闭**（报错明示），不传 thinking 参数时 500 max_tokens 被思考吃光正文截断（stop=max_tokens）→ **归因调用必须显式传 `thinking.budget_tokens`（实测 1024 生效，总预算 600 即完成）或 max_tokens≥2000 兜底** ④deepseek 兜底 500 预算即可，更省 ⑤网关错误形如 `{type:"error",error:{type:"invalid_request_error",code:...}}`，重试分流按 error.type 判断（可重试的 5xx/超时 vs 不可重试的 4xx 参数错）。
- **探针**：验证主力模型是否有当日实时信息能力——若有，归因可升级为事件级（记 §14.7 同批探针）。

### 12.5 绩效与可复现

年化 (1+R)^(252/交易日数)−1、最大回撤、Sharpe（rf=0,√252）、胜率/盈亏比/换手率、对沪深300 超额（index_history sh000001）。落库 `backtest_result(id, strategy_name, params JSONB, start_date, end_date, metrics JSONB, equity_curve JSONB, data_snapshot{maxDate,rowCount}, git_sha, created_at)`——params+data_snapshot+git_sha 三件套保证可复现。**metrics JSONB 键名定稿（2026-10-03 对拍，console 回测指标卡与此一一对应）**：`{annual_return, max_drawdown, sharpe, win_rate, profit_loss_ratio, turnover, excess_vs_hs300, total_trades}`。

### 12.6 测试与性能

MarketSimulator 场景单测（一字板/T+1/整手/印花税日期边界）；手算 10 天黄金用例（BigDecimal 精确断言）；确定性测试（同参数两遍逐笔 diff=0）；TestContainers 60 天冒烟。全量 600 万 bar 驻留内存 ≈300MB，单次回放 <5 分钟。

**边界穿透确认**：上市首日可买不可卖（T+1 自然成立）；新股 5 日无限价——is_limit_up 由 change_pct 阈值算出，天然只对真实限价生效，回测不重算。

### 12.7 配置化分层（2026-10-02 用户认可）

| 层 | 内容 | 何时配置化 |
|----|------|-----------|
| L1 回测/风控/仓位参数 | 起止日期、初始资金、成本、滑点、单票上限、持仓数 | 二期开工起（YAML，顺手） |
| L2 策略逻辑 | `RuleBasedStrategy`（配置解释器）+ **白名单算子**：信号源(big_trend/inflection/limit_up_streak…) × 运算符(equals/within_days/…) × 组合(all_of/any_of) × 止损 | 二期中段（1-2 周），新策略=新 YAML 零开发，且可批量扫参 |
| L3 重逻辑 | 状态机/自定义数学/ML —— 不扩 DSL（防 DSL 蔓延成烂语言），走三期 AI 代码通道 | 三期 |

纪律：算子白名单封闭；策略配置存储见 §12.9（strategy_config 双表 DB 版本化，替代早期「YAML 进 git 走 review」）；配置整体塞入 backtest_result.params（三件套复现机制不变）；不做拖拽画布/可视化流程图编辑器（表单式策略控制台见 §12.9）。两条通道（配置/代码）实现同一 `Strategy` 接口进同一考场。

**L1 YAML 全样**（backtest.yml，引擎固定逻辑 + 参数值）：

```yaml
backtest:
  start-date: 2024-01-02
  end-date: 2025-09-30
  initial-capital: 1000000
  cost:
    commission-rate: 0.00025      # 佣金双向
    stamp-tax-before: 0.001       # 印花税仅卖出，引擎内置分界 2023-08-28
    stamp-tax-after: 0.0005
    transfer-fee: 0.00001
  slippage: 0.001                 # 固定滑点非随机 → 可复现
  risk:
    kelly: { type: HALF, cap: 0.25, fallback: EQUAL_WEIGHT, min-samples: 20 }
    max-position-ratio: 0.25
    max-positions: 10
  lot-size: 100
```

**L2 策略 YAML 样例**（strategy_config.yaml，表单生成、服务端校验）：

```yaml
strategy: da-long-hui-tou
signals:
  buy:
    all_of:
      - { source: big_trend, op: equals, field: wave_direction, value: UP }
      - { source: cycle_stage, op: label, label: 主升 }
      - { source: sentiment_cycle, op: label, label: 反包, within_days: 3 }
      - { source: limit_up_streak, op: between, value: [3, 7] }
      - { source: stock_attr, op: float_mv, value: [20, 80] }   # 流通市值 20-80 亿
      - { source: volume, op: vol_ratio, value: 2 }
    stop-loss:
      - { op: drop_below, pct: -8 }
      - { op: break_streak }
      - { op: trail_stop, pct: 6 }        # 峰值回落 6%
  sell:
    any_of:
      - { source: limit_ecology, op: yst_premium, value: -2 }   # 昨涨停溢价转负
      - { source: sentiment_cycle, op: label, label: 大面 }
  holding: { max-days: 15 }
```

**解释器机制**：`RuleBasedStrategy` 启动时 YAML → 白名单校验（信号源/算子/组合子枚举外直接拒绝）→ 编译成条件树一次；回测逐 bar 只执行编译结果，无解析开销。**批量扫参** = 循环替换 value 跑 N 次回测（参数即 YAML），结果全部落 backtest_result 对比。仓位决策不在策略里（Signal(weight=null)，§12.7 Kelly 段），换策略零仓位逻辑改动。

**universe 无 ST 相关选项**：全库本就不存在 ST/*ST/退市股（§2.2 隔离原则），策略配置没有 exclude_st 之类的开关——不存在"要不要看 ST"的问题。Kelly 参数同理只暴露 `type/half/cap/fallback` 四个安全项，公式本体不进配置（防配置出 f*>1 等荒谬值）。

**Kelly 接入策略的方式**：策略产出 `Signal(weight=null)`——**策略只表达买卖意图，仓位决策不在策略代码里**。RiskChain 中的 KellySizer 读回测内部交易台账（已平仓交易滚动最近 60 笔）计算 p/b → f*=p−(1−p)/b → 实际仓位=clamp(f*/2, 0, 25%)，<20 笔退化等权。每笔平仓自动入账 → 估计滚动更新，策略冷启动等权、热身后渐入 Kelly。

#### 12.7.1 策略条件词表全量定稿（2026-10-02，借鉴经典量化 + A股超短口径）

信号源白名单从 5 个扩到 **13 组**，全部可从一期数据底座派生（stock_history/limit_up_streak/limit_down_streak/sentiment_cycle/dragon_cycle/big_trend/stock_inflection_point/index_history/stock_info/concept_boards/stock_fundamentals）；运算符复用 equals/between/gte/lte/within_days。

| # | 信号源 | 字段/条件 | 数据落点 |
|---|--------|----------|---------|
| 1 | big_trend | wave_direction equals UP/DOWN | big_trend 表（V1 兼容） |
| 2 | stock_inflection_point | type equals PEAK/VALLEY | inflection 表（V1 兼容） |
| 3 | limit_up_streak | between/gte | stock_history 派生列 |
| 4 | sentiment_cycle | 术语标签 label within_days（反包/大肉/大面/止跌反核/晋级/断板） | §4.9 术语判定 |
| 5 | price | drop_below pct（固定止损） | 回测内部 |
| 6 | **price_action** | N日新高/新低、偏离 MA N%、开盘涨幅 gap（open vs 昨收）、振幅区间、MACD 金叉死叉（DIF/DEA/柱，EMA12/26/9，2026-10-03 补，B 类现算） | stock_history 日线派生 |
| 7 | **volume** | 量比≥N、换手率区间、连续 N 日放量/缩量 | stock_history（量/流通股本） |
| 8 | **limit_ecology** | 昨日涨停今日溢价、梯队排名（当前板数当日名次/是否最高板）、炸板（日线近似：high 触及涨停价但 close 未封，标注近似口径）、最高板归属 | stock_history 全市场聚合 |
| 9 | **sector** | 同板块今日涨停家数≥N、板块内板数排名、板块涨幅榜前列 | stock_info 两 JSON 数组展开（jsonb_array_elements）+ sector_daily 聚合（§12.4.1/§4.8） |
| 10 | **market_env** | 指数在 MA N 上/下、指数 N 日涨跌幅、涨跌家数比、昨日大面家数≥N | index_history + 全市场聚合 |
| 11 | **stock_attr** | 流通市值区间、股价绝对值区间、上市天数（次新判定）、距前高 N% | stock_info + stock_fundamentals + stock_history |
| 12 | **cycle_stage** | 周期阶段 equals 冰点/发酵/主升/高潮/退潮/混沌、龙头状态 RISING/BROKEN/DEAD、龙头断板后天数 | sentiment_cycle 标注升级为条件源 + dragon_cycle |
| 13 | **chip**（2026-10-03 补，路径 A 自算定稿） | 获利盘比例 gte/lte/between、成本偏离% between、90%/70% 成本集中度区间 | signal_daily 筹码派生列（§12.4.1 递推设计），A 类直读 |

**周期阶段升级说明**：冰点/退潮等原来只是 sentiment_cycle 的行内标注（§4.9），本词表将其升级为可用条件——使「冰点期买首板、主升期打高度龙、退潮期高低切」这类用户口径策略可直接用 YAML 表达（冰点首板= cycle_stage equals 冰点 within 2 + limit_up_streak equals 1 + stock_attr 低股价）。

**出场侧增补**（原只有固定止损/断板，太薄）：`trail_stop`（移动止损：从持有期最高点回落 N% 离场）、`giveback_stop`（盈利回吐：浮盈曾超 N% 后回吐 M% 离场）——量化出场模块标准件。

**数据边界（穿透确认，不进白名单）**：日频底座算不出的超短常用条件——涨停时间（早盘/尾盘板）、竞价成交量、封单金额/开板次数、分时均线位置，均需分时/L2 数据，二期不采集；其中炸板以日线近似口径进词表并标注精度限制，日后若上分时源再升级。

### 12.8 实盘信号链与仓位顾问 PositionAdvisor（2026-10-02）

**最终交付物：每日"明日执行单"**——某策略 + 明日买卖标的 + 股数 + 仓位% + 依据（p/b/样本数/置信度），钉钉推送，**人工按单执行（V2 不对接券商，建议与执行分离）**。

```
每日 20:30（采集完成后）：
1. 策略信号：Strategy.onBar(今日 ctx) → BUY/SELL 意图（复用回测同一份策略代码，只跑当日）
2. PositionAdvisor.recommend(信号, account_state)：
   a. 查 trade_ledger 该策略最近 60 笔已平仓（来源优先级 LIVE/PAPER > BACKTEST）
   b. p/b → f*=p−(1−p)/b → clamp(f*/2, 0, 25%)；<20 笔退化等权
   c. 目标金额 = 总资金 × 仓位% − 该股已占用 → 按今收价整手换股 → 校验现金/持仓数
3. 输出执行单 + 置信度标签（BACKTEST 台账=中，<20 笔=低，实盘≥60 笔=高）
```

**枢纽表 trade_ledger(strategy, code, open_date, close_date, pnl, pnl_ratio, source: BACKTEST|PAPER|LIVE)**：
回测跑完导入成交明细（BACKTEST）；实盘/模拟盘每平一仓记一条。Kelly 只查 `WHERE strategy=? ORDER BY close_date DESC LIMIT 60`——实盘越久实盘台账占比越高，估计自动从回测经验过渡到实战经验，无切换动作。

**账户状态手动维护**：account_state（现金）+ account_position（持仓/成本），成交后手动更新；不做券商对接。

**漂移防护**：每月重跑回测刷新 BACKTEST 台账；实盘最近 10 笔胜率与台账偏离 >20pp → 钉钉告警"策略可能失效，建议降仓重评"。

**同日多信号分配**：各信号按各自 Kelly 权重取目标金额，受持仓数上限与可用现金约束顺序截断；现金不足时后位信号缩量或弃单（执行单标注）。

**增加开仓条件**：只改策略层（配置 entry 条件或代码），仓位逻辑零改动；新策略无台账 → 等权起步、置信度"低"，随台账积累自动进入 Kelly。

### 12.9 策略控制台（HTML，2026-10-02 用户确认，二期；样稿 docs/design/strategy-console-mock.html）

延续情绪页技术路线：Spring Boot 静态单文件 `strategy-console.html`，零前端框架、零外部依赖（内联 SVG 净值曲线、系统字体）。三个 Tab：①策略 ②回测 ③结果对比。

**两条关键设计决策**：
1. **结构化表单 + YAML 只读预览，不做自由 YAML 文本框**——前端按算子白名单枚举生成表单（下拉选信号源/运算符/条件树增删），右侧实时渲染等价 YAML；校验前置到 UI 层，白名单外配置根本写不出来；YAML 仍是唯一权威格式（落库、入 params 都是它），**反向不支持直接改文本**（免双向同步复杂度）。
2. **存储走 DB 版本表，不直接写 git**（原「YAML 进 git 走 review」纪律调整：单人内部工具，DB 历史等效 review）：

```sql
strategy_config(id, name UNIQUE, yaml TEXT, version, status DRAFT/ACTIVE/RETIRED,
                alert_enabled BOOLEAN DEFAULT FALSE,   -- 盘中开仓预警开关（结果对比页开启，§14.9）
                created_by, created_at, note)
strategy_config_history(id, config_id, yaml, version, created_at)  -- 每次保存留痕，可回滚可 diff

watchlist_group(id SERIAL PK, name VARCHAR(50) UNIQUE, note TEXT, created_at)
watchlist_member(group_id FK→watchlist_group, code, note, created_at,
                 UNIQUE(group_id, code))          -- 自选股分组：用户按基本面 curated 的观察宇宙（PCB/存储芯片/创新药…）
```

**自选股分组 = 回测/策略 universe（2026-10-03 用户需求，已裁定）**：
- **universe 语义 = 当前名单**：只回答「这只票现在是否在某组」，不建变更留痕表（watchlist_history 已砍）。理由：策略跨度由用户定义（典型 3 个月），名单近期变更对短跨度回测无影响；自选池是用户亲手选的基本面票，用当前名单跑近期回测即是本意。唯一边界：结果页标注 universe 来源（全市场 / 自选组名），让"这是在当前自选组上跑的"始终可见——只做标注，不做 as-of
- **策略 YAML 增 universe 段**：`universe: {watchlist: [PCB, 存储芯片]}`（多组并集；缺省 = 全市场）——回测 BarContext 加载时按 universe 过滤，策略求值与仓位链零改动；词表 stock_attr 组同步补条件 `in_watchlist equals 组名`（想把分组当买条件而非范围时用）
- **盘中预警联动（§14.9）**：StrategyAlertEvaluator 候选池扩为 涨停池 ∪ 候选池 ∪ alert_enabled 策略的 universe 成员——自选股不涨停也可能触发策略预警（90s 快照全市场覆盖，无额外采集）
- **控制台加 tab ④ 自选股**：分组管理（建组/改名/备注）+ 组内成员增删 + 批量导入（粘贴代码列表，校验存在性、ST 天然拒绝）；样稿同步

- 回测时从 DB 取 yaml 快照整份序列化进 `backtest_result.params`——三件套复现机制不变；提供「导出 YAML」按钮，想进 git 的人工放置。

**回测执行流（防御性）**：保存(校验：白名单/名称/条件完整性/日期区间) → DRAFT → 「试跑」固定先跑 60 交易日冒烟（配置错误 60 天内暴露，不白等 5 分钟全量）→ 通过才解锁「正式回测」→ `POST /api/v1/backtests` 异步 Job → 前端轮询状态 → 完成跳结果页。

**结果页**：结果表（勾选 2 条进入对比）→ 详情（SVG 净值曲线 策略 vs 沪深300 vs **等权基准**（同策略、等权仓位——直接检验 Kelly 链有无增益）、指标卡、成交明细节选，完整明细落库并导入 trade_ledger(BACKTEST) 供 Kelly）→ 两两 diff 对比（优值高亮，params 已留档可复现）。
**指标卡 Kelly 组（2026-10-02 补，用户要求显性化）**：p（胜率）、b（盈亏比）、滚动 f*、样本数（已平仓笔数）、建议仓位%（f*/2 clamp 后）——与 §12.3 公式同源直读 trade_ledger；对比页 diff 含 Kelly 组；「启用盘中预警」按钮挂结果对比页（§14.9 闭环入口）。

**接口（二期，追加到 API 清单）**：
```
GET    /api/v1/strategies            列表
POST   /api/v1/strategies            新建（结构化 JSON，服务端生成 YAML）
PUT    /api/v1/strategies/{id}       修改（落 history 表）
GET    /api/v1/strategies/{id}/yaml  导出
POST   /api/v1/backtests             触发回测（dryRun=true 强制试跑）
GET    /api/v1/backtests/{id}        状态+结果
-- 自选股（2026-10-03 §17.5 C3 补，表+tab④ 界面已有，端点漏写）
GET    /api/v1/watchlists                 分组列表（含成员数）
POST   /api/v1/watchlists                 新建分组
PUT    /api/v1/watchlists/{id}            改名
DELETE /api/v1/watchlists/{id}            删组（仍有成员时 409）
POST   /api/v1/watchlists/{id}/members    批量导入（服务端校验存在性/非 ST，逐行返回拒绝原因）
DELETE /api/v1/watchlists/{id}/members/{code}
```

**不做清单（边界）**：拖拽画布/可视化流程图编辑器（表单树足够，画布大工程低频使用）；直接编辑 YAML 文本；L3 代码策略在线编辑（只展示列表/结果，代码走 git + 三期通道）；在线改回测引擎物理规则（T+1/整手/一字板是 A 股物理规则，不是配置）。

---

## 十三、实施细节定稿（2026-10-02 六项穿透收尾，销 §九 扣分项）

### 13.1 application.yml 全样（销扣分#1）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/soros
    username: soros
    password: ${SOROS_DB_PASSWORD}          # 环境变量注入，不进 git
    hikari:
      maximum-pool-size: 10                 # 单机内部用途（采集+API+回放），10 足够
      minimum-idle: 2
      connection-timeout: 5000
      max-lifetime: 1800000                 # 30min，小于 PG 端 idle 超时
  jpa:
    hibernate.ddl-auto: validate            # 结构变更只走 Flyway，Hibernate 永不改表
    open-in-view: false                     # 无视图层懒加载需求，关掉省连接
    properties.hibernate:
      jdbc.batch_size: 500                  # saveBatch 批量插入
      order_inserts: true
      order_updates: true
  flyway:
    enabled: true
    locations: classpath:db/migration

soros:
  collect:
    cron: "0 0 20 * * ?"                    # §4.7 防线①
    default-start-date: 2021-10-01          # 近 5 年
    batch-size: 50                          # 每次 HTTP 请求股票数
    rolling-window-days: 10                 # §4.7 防线④
  python-client:
    base-url: http://localhost:8000         # Python FastAPI（§13.2 超时按 profile）
  backfill:
    copy-batch-rows: 50000                  # stage 批大小
  sentiment:
    big-meat-threshold: 5.0
    big-face-threshold: -5.0
    pool:     { min-streak: 3, min-limit-ups: 2, min-5d-gain: 50 }
    collapse: { min-streak: 2, max-5d-drop: 30 }
    dragon:   { observe-days: 3, small-max-limit_up_streak: 7 }   # §4.9 状态机
  dingtalk:
    webhook: ${SOROS_DINGTALK_WEBHOOK}      # §11.3
```

**coroutine dispatcher（代码约定，不进 yml）**：`Dispatchers.IO` 固定限流 32 线程的独立实例（`IO.limitedParallelism(32)`），所有 DB/WebClient 调用收口于该 dispatcher——防止采集高峰把线程池打满拖垮 HTTP 服务。

### 13.2 WebClient 超时/重试（销扣分#4）

```
两条超时 profile（PythonClient 内 Reactor Netty HttpClient 配置）：
- default（日采/查询）：connect 2s + response 10s；retry 2 次 × 500ms 退避
- backfill（历史区间拉取，单 code 5 年 ~1200 行）：response 60s；retry 1 次
重试只对 ConnectException / 5xx / timeout 触发（拉取只读，幂等安全）；
连接池：maxConnections 100、pendingAcquireTimeout 5s、maxIdleTime 30s。
责任边界（穿透确认）：Kotlin→Python 是唯一上游链路，多源 failover 全在 Python Router 内部完成，
Kotlin 不做"换个数据源重试"（避免双重点燃）；Kotlin 侧仅对 Python 整体熔断——
连续 20 次失败熔断 60s，Job 快速失败进钉钉，而不是拖 3 小时超时泥潭。
```

### 13.3 结构化日志与指标（销扣分#3）

```
- 日志：logstash-logback-encoder JSON 格式；MDC 固定字段 job / code / phase / date_range / attempt；
  按天轮转保留 14 天；ERROR 级别必附 issue_type（与 data_quality_log 对齐）
- Micrometer 指标（/actuator/metrics 暴露，不引入 Prometheus/Grafana——单机自用够，将来要再加）：
  soros_collect_rows_total{source}          采集行数
  soros_collect_failed_codes_total          采集失败股票数
  soros_collect_batch_duration              批耗时 timer
  soros_quality_log_total{issue_type}       质量事件计数
  soros_sentiment_derived_total             情绪派生完成次数
- 健康：/actuator/health 含 DB 连通 + Python /health ping
- 告警唯一出口 = §11.3 钉钉，触发条件：Job 失败 / DELIST_SUSPECT / 同 issue_type 1 小时 >100 条
```

### 13.4 Job 编排与完成握手（销扣分#6）

```
- DailyCollectJob 完成后发 Spring ApplicationEvent：
  DailyCollectCompleted(successCodes, failedCodes, durationMs)
- SentimentCycleJob / SorosJob 改为监听事件触发（依赖链显式化），不再依赖固定 20:40 cron；
  21:30 保留一个兜底 cron：先查 sentiment_cycle 今日行是否存在，存在即跳过——
  事件丢失（JVM 重启等）由兜底补算，幂等 upsert 双跑无副作用
- failedCodes 占比 >10%：照常派生，但 sentiment_cycle 行加 data_coverage=PARTIAL 标记 + 钉钉提示
- BoardCollectJob 不入链（板块成分滞后一天可接受），FundamentalsCollectJob 独立季度 cron
```

### 13.5 情绪历史冷启动回放（销扣分#5）

```
- 前置：stock_history 5 年回填完成且派生列已补算（§六.6）
- SentimentCycleService.computeFor(date) 抽为纯函数；Job 每日与回放共用同一实现
- 回放入口：POST /api/v1/jobs/sentiment-replay?from=2021-10-01&to={today}（ManualDataController）
- 严格按 trading_calendar 顺序逐日 computeFor——followup 与龙头状态机都依赖前一日行，
  不可并行、不可跳日；全量 ~1300 交易日，单日计算秒级，总耗时分钟级
- 回放前删除区间内 sentiment_cycle / dragon_cycle（重放语义，防新旧混杂）；
  dragon_cycle 自区间首日重建：首日无"前文"，龙头取区间开始时最高板、status 标 BOOT，
  回放到首个完整断板/反包周期后自然进入正常语义
- 回放完成输出摘要（各表行数、龙头周期清单）→ 钉钉通知，网页左侧历史即刻可看
```

---

## 十四、盘中实时模块（2026-10-02 设计定稿；数据源源码级探针+实测双重确认，附录 docs/research/realtime-sources.md；页面样稿 docs/design/intraday-monitor-mock.html）

### 14.1 定位与三条铁律

1. **实时层绝不写 stock_history**——qfq 日线口径洁净性是 §4.6 漂移检测/回测确定性的根基，盘中易变快照只进独立实时层
2. **拉事件状态，不拉全市场逐秒快照**——超短盘中要的是封板/炸板/大面等事件与梯队状态
3. **独立故障域**——IntradayCollectJob 挂掉只影响实时展示与预警，不进 §13.4 盘后握手链，不拖累日线主线

### 14.2 数据源定稿（探针结论）

| 数据 | 接口 | 关键结论 | 用途 |
|------|------|---------|------|
| 涨停/炸板/跌停/强势/昨日涨停池（**5 池接口定稿**，§17.1 B8；次新池 sub_new 不进主链） | ZT=`stock_zt_pool_em`，ZB=`stock_zt_pool_zbgc_em`，DT=`stock_zt_pool_dtgc_em`，STRONG=`stock_zt_pool_strong_em`，PREV=`stock_zt_pool_previous_em`（函数名 M0 探针实测，2026-10-03） | **六项关键字段全齐**：首次/最后封板时间、炸板次数、封板资金、连板数、涨停统计；**M0 探针四池全通**（2026-09-30：ZT 52/ZB 12/DT 9/STRONG 199/PREV 57 行，列名齐）；date 可查历史但**实测保留 <30 天**（09-30 有行、09-03 起 0 行，且传老日期静默返回空）；单请求全量无翻页 | 梯队榜/事件 diff/收盘归档（超短核心） |
| 全市场实时快照 | `stock_zh_a_spot_em` | 23 列，有最新价/涨跌幅/涨速，**无盘口无涨跌停价**；内部分页 55-60 页、30-90s、批内时点不同步 → 只能低频；**M0 探针实测无板块行混入**（fs 过滤下 total=5562，V1 的 59271 系旧参数无过滤，§14.7 销项） | 涨跌家数、大面预警（90s+抖动） |
| 单只盘口五档 | `stock_bid_ask_em` | 含五档+涨跌停价，单只请求 | 只查候选名单（梯队+预警 ~50 只） |
| 盘前竞价分时 | `stock_zh_a_hist_pre_min_em` | 当日分时**含集合竞价** | 9:25 竞价 gap 探测（竞价情绪） |
| 分钟线 | `stock_zh_a_hist_min_em` | 1 分钟仅近 5 交易日且不复权（源码硬编码） | 盘中分时仅看当日；长历史免费路径=每日增量自建（暂不做） |
| mootdx | TCP 直连 | quotes 五档 80 只/次；**issue #157 公共节点 2026-07 起大面积失效 + 项目停更 2 年** | 实验性备源，不进主链路 |

**交易所直连结论（用户问询后穿透）**：交易所无对外开放实时 API——直连需行情信息使用授权（L1 年费数万起）+专线；官网网页抓取无 SLA、有反爬且属灰色地带；**东财=交易所授权的信息商分销层（合法）**，且字段已超短特化。设计为可升级源抽象：

```
IntradaySource 接口（Python 侧）
  ├─ EastmoneySource   ← V2 初始实现（信息商层）
  ├─ (预留) QmtSource  ← 远期：券商 QMT/miniQMT（需账户，准 L1/L2）
  └─ (预留) L2Source   ← 更远期：正规 Level-2 授权
```

**池接口字段中→英映射表（2026-10-03 字段级对拍补，种子 docs/seed/pool_snapshot_*.json 为实测键名）**——Python 摄取层用此表统一转英文 DTO，禁止两侧各自发明：

| 源字段（中文键） | DTO 字段 | 出现池别 |
|---|---|---|
| 代码 / 名称 / 涨跌幅 / 最新价 / 成交额 / 流通市值 / 总市值 / 换手率 / 所属行业 | code / name / change_pct / price / amount / float_mv / total_mv / turnover / industry | 全 5 池 |
| 首次封板时间 / 炸板次数 / 涨停统计 | first_seal_time / zhaban_count / limit_stat | ZT/ZB/STRONG（"涨停统计"样稿缩写 limit_stat） |
| 最后封板时间 / 封板资金 / 连板数 | last_seal_time / seal_amount / limit_up_streak | 仅 ZT（连板数样稿缩写 lbc） |
| 涨停价 / 涨速 / 振幅 | limit_price / speed / amplitude | ZB/ZT/PREV/STRONG 视池而定，空缺=缺列 |
| 动态市盈率 / 封单资金 / 板上成交额 / 连续跌停 / 开板次数 | pe_ttm / **seal_order_amount**（注意：DT 口径是"封单资金"，≠ZT"封板资金" seal_amount，两个独立字段）/ board_amount / limit_down_streak / open_count | 仅 DT |
| 是否新高 / 量比 / 入选理由 | is_new_high / vol_ratio / select_reason | 仅 STRONG |
| 昨日封板时间 / 昨日连板数 | prev_seal_time / prev_limit_up_streak | 仅 PREV |

**池别字段覆盖声明（对拍修正）**：此前"六项关键字段全齐"表述不精确——**仅 ZT 六项全齐**；ZB 缺 最后封板时间/封板资金/连板数，DT 无连板概念（用 limit_down_streak），STRONG 仅涨停统计，PREV 全为"昨日"口径。`intraday_archive` 权威归档时非 ZT 成员缺列落 **NULL**（列允许 NULL，不造默认值）；原样 JSONB 快照（intraday_pool_snap）保留各池全量原样字段不受影响。

### 14.3 轮询与限频（东财 2025-04 起 IP 级限频，封禁实测 ~5h）

```
```
- 涨停池 4 接口（ZT/ZB/DT/STRONG）各 15-30s、错峰相位（不同时发）
- 全市场快照 90s + 随机抖动
- bid_ask 只查候选名单 ~50 只、30s 一轮（列表现算：池成员∪强势池预警∪龙头）
- TokenBucket 复用 §11.1；AKShare 零防护裸请求（无 UA 无 timeout）→ Python 统一包一层 UA/timeout/重试
- 退避：连续 3 次失败 → 降频 2 倍；连续 6 次 → 停轮 300s + 钉钉告警（防封禁升级）
- 池开关：每轮读 intraday_pool_state，enabled=false 的池跳过该轮（不进 diff/不参与 15:10 归档），API 热切换下一轮生效（§14.5-6，§17.5 C2）
- 交易时段判定：trading_calendar + 9:15-11:30 / 13:00-15:00 窗口，午休/非交易日不空转
```

### 14.4 实时层表结构（新增五表：过程表 snap/event + 权威归档 archive + 渲染快照 replay + 池开关 state，2026-10-03）

```sql
intraday_pool_snap(            -- 每轮池快照（追加，原始轮次保留 3 天供回溯调试）
  id          BIGSERIAL PRIMARY KEY,
  snap_at     TIMESTAMP NOT NULL,
  pool        CHAR(6) NOT NULL,            -- ZT / ZB / DT / STRONG / PREV
  payload     JSONB NOT NULL               -- 接口原样行（未来字段升级不丢）
);
intraday_event(                -- 状态 diff 出事件（本轮 vs 上轮）
  id          BIGSERIAL PRIMARY KEY,
  trade_date  DATE NOT NULL,
  ev_time     TIMESTAMP NOT NULL,
  ev_type     VARCHAR(20) NOT NULL,        -- ZT涨停 / ZB炸板 / HF回封 / DM大面 / MAXCHG最高板易主 / OPEN开板
  code     VARCHAR(20),
  name     VARCHAR(100),
  detail      JSONB,                       -- {limit_up_streak, seal_amount, zhaban_count, change_pct...}
  pushed_dd   BOOLEAN DEFAULT FALSE        -- 是否已推钉钉
);
CREATE INDEX idx_ie_date_time ON intraday_event (trade_date, ev_time);

intraday_archive(              -- 收盘归档（盘后权威表，词表升级的数据源）
  trade_date      DATE NOT NULL,
  code         VARCHAR(20) NOT NULL,
  first_seal_time VARCHAR(8),              -- 首次封板 HH:MM:SS
  last_seal_time  VARCHAR(8),
  zhaban_count    SMALLINT,                -- 炸板次数
  seal_amount     NUMERIC(16,2),           -- 封板资金（元）
  limit_up_streak          SMALLINT,                -- 连板数
  pool            CHAR(6),                 -- 归属池（ZT/ZB/DT...）
  UNIQUE (trade_date, code)
);

intraday_replay(               -- 日维度整页渲染快照，一日一行（2026-10-02 定稿：读模型/渲染契约表）
  trade_date  DATE PRIMARY KEY,
  complete    BOOLEAN DEFAULT FALSE,        -- 15:10 归档补齐后置 true；盘中只含已采样点
  page        JSONB             -- 整页数据：{kpi_series:[{t:"09:30",zt:52,zb:18,dt:3,prem:1.66,adr:2.3,adv:3200,dec:1400,max_streak:5,gap_pct:1.2},...],
                                --   ladder:[{code,name,limit_up_streak,change_pct,seal_amount,first_seal_time,zhaban_count,limit_stat}...]（键名=命名字典全名，样稿简写 streak/chg/seal/first/zha/stat 为演示态 M7 对齐）,
                                --   events:[{time,code,name,tg,txt}...], panels:{big_face,ding_talk,strategy_alerts, pools:[{pool,enabled,last_ok_at,rate_state}]}}
                                --   schema = GET /intraday/summary 响应 schema（同一 Kotlin DTO 序列化）
                                --   字段定稿（2026-10-03 字段级对拍）：adr=涨跌家数比（adv/dec 家数两槽并列，样稿旧样例值 2300 系演示数据误用成交额口径，M7 对齐）；max_streak=最高板、gap_pct=竞价缺口%（§14.6 快照）补槽位；
                                --   events 槽位展开 {time,code,name,tg,txt}（tg=事件类型标签，钉钉记录走独立 panels.ding_talk 不混入 events，样稿混流为演示态 M7 对齐）；
                                --   pools=池状态面板运行时态（enabled ∘ intraday_pool_state；last_ok_at/rate_state 来自轮询器内存态，**不入库**，重启清零可接受）
);
-- 用法与保证：回放 = WHERE trade_date=? 一次查询零 join，前端拿到即渲染（实时页/回放页同一渲染逻辑）；
-- 盘中每 90s 采样点追加进当日行 kpi_series（240 次小 upsert，进程重启曲线不丢），
-- 15:10 IntradayArchiveStep 归档时补齐 ladder/events/panels 并置 complete=true；
-- 归档后自校验：page 反序列化 + schema 校验 + 同源对拍（ladder 条数=intraday_archive 当日行数、
-- kpi 点数完整），失败钉钉告警——保证落库即渲染。量级 ~400KB/日 → 年 ~100MB，随 stock_history 同速增长可接受。

intraday_pool_state(           -- 池运行时开关（2026-10-03 用户裁定 A：热启停落库，重启不丢；§17.5 C2）
  pool        CHAR(6) PRIMARY KEY,         -- ZT / ZB / DT / STRONG / PREV
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  reason      VARCHAR(200),                -- 手动停用原因（如限频封禁规避）
  updated_at  TIMESTAMP DEFAULT NOW()
);
```

**长历史自建（30 天窗口对策）**：每日 15:10 IntradayArchiveStep 用 `date=当日` 重新拉 5 个池接口做**权威归档**（池接口收盘后仍可查，比盘中最后一轮更稳）——自上线日起逐日积累封板时间/炸板/封单历史；**上线前的历史拉不到**（诚实边界，报告标注数据起点）。

### 14.5 消费出口

1. **实时页** `intraday.html`（样稿 docs/design/intraday-monitor-mock.html）：前端每 2-3s 轮询 `GET /api/v1/intraday/summary`（一次聚合：KPI+梯队+事件流+溢价曲线+大面预警+钉钉记录，无 WebSocket 基建）
2. **钉钉盘中预警**（事件驱动，防刷屏）：龙头(最高板)炸板 / 高位股大面(强势池成员现价≤-5%) / 最高板易主 → 推；普通涨停不推
3. **收盘归档 → 词表升级**：intraday_archive 落库后，§12.7.1 limit_ecology 信号源新增可用条件 first_seal_time（早封/晚封板）、zhaban_count、seal_amount——**回测口径自动升级**，数据起点=V2 上线日
4. **历史复盘回放**（2026-10-02 定稿，用户需求）：intraday.html 正式版加日期选择器——选历史日期即切回放模式，数据源 = `intraday_replay` **单表单查询**（page JSONB 与实时 /summary 同一 DTO，渲染逻辑完全复用，落库前已过渲染自校验）；样稿里的演示时钟即回放引擎原型（时钟换成日期驱动）。当日盘后也可重放当日全貌
5. **策略开仓预警**（2026-10-02 定稿）：alert_enabled 策略盘中求值，命中即 ALERT 事件 + Kelly 建议仓位推送（§14.9）
6. **池运行时开关**（2026-10-03 用户裁定 A，§17.5 C2）：`PUT /api/v1/intraday/pools/{pool}/enabled`（body `{enabled, reason?}`，pool∈5 池）→ 写 intraday_pool_state，下一轮立即生效；`GET /intraday/summary` 的池状态面板回读该表（健康灯/最近成功/限频状态/开关——样稿「数据池状态」面板实机化）。日线三源（baostock/akshare/mootdx）切换**不做**人工界面：Router 自动 failover 是容错机制，手动切源破坏口径一致，语义与池开关（运营动作）不同

### 14.6 交易时段与调度

```
IntradayCollectJob（Python 侧轮询进程 or Kotlin 调度 + Python 接口）
  09:15-09:25  竞价：pre_min 接口抓竞价分时 → 竞价 gap 快照（KPI 带"竞价"态）
  09:30-11:30 / 13:00-15:00  按 §14.3 节奏轮询
  15:10        IntradayArchiveStep 权威归档 + 当日事件摘要推钉钉
```

### 14.7 落地前必测清单（M0 探针已销项 5/7，2026-10-03，docs/research/probe-intraday-v2.md）

**已实测销项**：① spot 板块行疑点**排除**（fs 过滤下 total=5562 无混入，V1 的 59271 系旧参数无过滤）② bid_ask 五档**盘后无值**（f31-f50 缺省，仅涨停/跌停价有值——预期内，五档仅盘中实时可得；腾讯源盘后有缓存值，备查）③ 5 池健康度全通 + date 回看**实测保留 <30 天**（09-30 有行、09-03 起静默返空）④ 池接口函数名修正（ZB=zbgc/DT=dtgc，探针报告附全名）⑤ 市值反推验证通过（§17.1 B1）。

**遗留 2 项（M6 盘中实施首日跑）**：5 分钟线实际深度（push2his 历史域从本机网络整域弃答，判定网络出口特例；腾讯 m5 接口实测可达可作备源）/ mootdx 节点连通性（如选备源）。

### 14.8 排期

新增 **Step 7：盘中实时模块（3-4 天）**：Python 源+限频轮询 Job（1 天）→ 三表+事件 diff+归档（1 天）→ intraday.html+钉钉预警（1 天）→ 探针清单实测+联调（0.5-1 天）。

### 14.9 盘中策略开仓预警（2026-10-02 定稿；依赖回测+策略控制台落地，Step 8/二期启用）

**闭环**：结果对比页确认策略表现优 → 「启用盘中预警」（strategy_config.alert_enabled=true）→ 盘中每轮池/快照更新后，StrategyAlertEvaluator 对**涨停池∪候选池（~250 只）**求值 alert_enabled 策略的 entry 条件树（启动时已编译，§12.7）→ 命中 → `intraday_event(ev_type=ALERT, detail={strategy_id, 命中条件摘要, kelly:{仓位%, 整手股数, 置信度}})` → 钉钉推送 + intraday.html「策略预警」面板 + intraday_replay.page.panels.strategy_alerts（回放可见当日触发史；字段名与 §14.4 快照 schema 统一）。

**数据口径穿透（诚实边界，提醒必须带标注）**：
| 条件类别 | 盘中口径 | 权威性 |
|---|---|---|
| 昨日口径（sentiment_cycle/market_env/limit_ecology/sector/连板数） | 直接读预计算表 | 权威 ✓ |
| 当日实时（price/volume/涨停判定） | 快照+涨停池近似（15-90s 粒度） | 预警级 |
| 日线技术（MA/N 日新高等） | 实时价近似当日 bar | 预警级（可选 14:45 后才求值，压伪信号） |

- 提醒文案统一带「**盘中预警，收盘确认**」——日频策略的权威判定在收盘 bar，盘中只是预预警；收盘后 PositionAdvisor 执行单（§12.8）才是正式单，两者同一 Kelly 链。
- **预警带仓位不带裸信号**：detail.kelly 走 PositionAdvisor 同链（trade_ledger 滚动 60 笔 → f* → 半 Kelly → clamp 25%）——用户看到的直接是「策略 X + 股票 Y 命中〈冰点+首板〉，建议仓位 8%（400 股），置信度中」，可执行。
- **防刷屏**：每策略×每股×每日 ≤1 条；条件失效（炸板/回落）不撤回只追加 FOLLOWUP 事件；钉钉只推 alert_enabled 策略，普通池事件照旧走 §14.5 原规则。
- **非自动交易**：V2 不对接券商（§12.8 纪律不变），预警=建议+人工执行。
- **性能**：策略个位数~几十 × 候选 ~250 只 × 已编译条件树，每轮毫秒级；挂在 IntradayCollectJob 轮询后，独立故障域（不进 §13.4 握手链），Evaluator 挂掉只影响预警不影响采集。
- **新增排期 Step 8（1-1.5 天）**：Evaluator+条件映射（0.5 天）→ 钉钉+面板+replay.alerts（0.5 天）→ 伪信号实测调参（14:45 求值开关，0.5 天）。

## 十五、笔记系统（2026-10-03，用户需求：按天记录、跨页汇总）

**定位**：人工复盘日志——盘中随手记想法/盘面感受/个股观察，按**天**组织，跨页面记录、单页汇总。纯人工内容，不进策略条件词表（后续可选 LLM 日总结，与归因同款手法，二期再议）。

```sql
daily_note(
  id        BIGSERIAL PRIMARY KEY,
  trade_date   DATE NOT NULL,              -- 笔记归属交易日（按天维度组织的唯一键）
  page      VARCHAR(20) NOT NULL CHECK (page IN ('SENTIMENT','STRATEGY','INTRADAY','KLINE','GENERAL')),
                                        -- 来源页：情绪周期表/策略控制台/盘中监控/K线复盘/笔记本直接记
  code   VARCHAR(20),                -- 个股笔记挂靠（K线页记某票，NULL=市场级笔记）
  content   TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP,
  UNIQUE (trade_date, page, code)       -- 同日同页同股一条，编辑覆盖（updated_at 留痕）
)
```

- **各页入口**：四个页面右下角悬浮「笔记」按钮 → 侧滑面板：上方列出当天该页已有笔记（可编辑），下方输入框新增；K线页新增时自动带上当前查看的个股（code 挂靠），面板内可切换"仅本股/全市场"
- **笔记本页**（notes 样稿 docs/design/notes-mock.html，侧边栏第 5 项）：按天倒序一节一天，节内按来源页分组展示（页签徽标），当天可跨页补记（page=GENERAL）；支持按来源页/个股过滤
- **联动（轻量）**：盘中监控的实时事件流、大面预警提供「引用到笔记」（预填事件摘要，人工补充判断）；情绪周期表的当日评级/周期阶段可在笔记本节头自动带出（只读上下文，不代写）
- **API**：`GET /api/v1/notes?date=&page=&code=`、`POST /api/v1/notes`（upsert by UNIQUE 键）、`DELETE /api/v1/notes/{id}`；样稿 notes-mock 简写字段与 DTO 映射（2026-10-03 对拍补）：`text↔content`、`d↔trade_date`、`t↔created_at`、`p↔page`、`c↔code`——M7 前端按 DTO 全名消费。
- **排期**：Step 9（0.5-1 天，可与 Step 8 并行）：表+API（0.5 天）→ 四页悬浮面板+笔记本页（0.5 天）

**全局日期联动（复盘模式，2026-10-03 用户确认，样稿已体现）**：情绪周期表是全站**日历骨架**——在周期表点任意日期列，全站进入"该日复盘模式"：
- **载体 = URL `?date=`**：五页共享，切换页面不丢；点「回到今日」清参数回到实时态。情绪/策略/盘中/K线/笔记本各页启动时读参数各自切换数据口径（真实版为服务端按 date 出数，非前端过滤）
- **各页行为**：情绪表=联动源点（点列即跳转+选中列高亮）；盘中监控=顶部回放徽标（intraday_replay 单表单查询）+ **上线日之前显示诚实空态**（"该日无盘中回放数据，日线口径不受影响"——盘中归档仅覆盖上线后交易日，这是唯一硬边界）；K线复盘=回放日滑杆直接定位该日（筹码递推/指标全部重算）；笔记本=滚动定位高亮该日小节，无该日笔记则提示"笔记自上线起累积，历史日期只带出当日市场上下文"；策略控制台=横幅提示该日口径
- **数据边界（穿透结论）**：情绪周期表/kline/笔记/信号均可回补任意历史日期（日线底座 5 年）；唯独 intraday_replay 有上线日边界——日期选择器对"上线日前"的日期在盘中页置灰或空态，二选一实现时取空态（信息量更大）
- 样稿联动件 `gs-dlbar/gs-dljs` 注入五页（零依赖、URL query 传递）；顺手修复 kline-mock 的 toISOString 东八区日期偏移（与情绪页同款 bug，改本地时区格式化）

---

## 十七、穿透审计整改定稿（2026-10-03）

> 4 个审计视角（数据链路 / 调度时序 / 策略回测 / 盘中前端）并行穿透 PLAN+schema+样稿，用户逐条裁定。**本节与正文冲突时，以本节为准。**

### 17.1 阻塞级 9 条（全部定稿）

| # | 问题 | 裁定 | 落点 |
|---|---|---|---|
| B1 | stock_attr「流通市值区间」无数据落点 | 补数据源 | stock_info 加 float_shares / total_shares（单位=股）；DailyCollectJob 每日从东财快照流通市值/总市值 ÷ 收盘价反推回写，BaoStock profit 季度对拍校准；**市值不落列**，条件求值时=股本×当日收盘价现算。**M0 探针验证通过（2026-10-03）**：反推 vs `qt/stock/get` 直读 f84/f85 误差 0.001%（浮点级），方案成立零新增请求 |
| B2 | 条件求值 null 语义全篇未定 | 算不出的跳过 + 问题表记录 | 全局纪律（置于 §12.7.1 首条）：任一条件输入为 null → 该股该日该条件=不命中，跳过并写 data_quality_log(issue_type='CONDITION_SKIP', detail=条件名+原因)。**不新建表——data_quality_log 就是问题表**，加这个 issue_type 即可 |
| B3 | 回测区间可早于信号数据起点 | 从最早一条数据开始用；收益要真实 | 回测入口 start_date 自动夹到 max(请求起点, signal_daily 最早日)，结果页标注「实际起点=X（数据所限）」；停牌日冻结估值不计区间收益（正文 §12 已有，此处重申为铁律） |
| B4 | 周期条件读建议值还是人工值未定 | 都按一个本子算 | **唯一口径：条件只读 big_cycle_sug / small_cycle_sug（建议值）**；人工确认值（big_cycle/small_cycle）仅展示，永不进任何条件 |
| B5 | trade_ledger 重跑污染 Kelly（直接连实盘下单金额） | 不要重复算 | 正式回测导入台账前先 `DELETE FROM trade_ledger WHERE strategy=? AND source='BACKTEST'` 再整批插入；trade_ledger 加 backtest_result_id 可空列（溯源）；同策略回测任务加锁互斥 |
| B6 | c90/c70 绝对 qfq 坐标列除权后过期（§12.4.1 曾自相矛盾，正文已改） | 除权除息 Job，扫到已处理自动跳过 | **AdjustCheckStep**（挂 DailyCollectJob 末尾）：探针检测单股除权（前后复权基准跳变 / 分红送配接口对照，方法 Step 3 实测定）→ 重拉该股 stock_history → 全历史重算该股 signal_daily 筹码 8 列（递推秒级）→ 更新 stock_info.adj_processed_until 水位；Job 每日扫描，水位已覆盖的股**自动跳过**。日 K **不加行级标记列**：除权是股级事件，行级列全表同值纯冗余，股级水位等价实现「扫到已处理就跳过」 |
| B7 | 老股筹码递推无 D_0；递推桶驻留内存单日不可重算 | 筹码尽量算准 | warm-up 规则：回填起点首日 D_0 =「前 60 交易日成交量加权均价」单峰近似，**前 60 个交易日筹码 8 列=NULL → 自动走 B2 跳过+记录**，第 61 日起入条件（宁可标空不用不准的数）；signal_daily 重算只有**区间回放**一种入口（复用 §13.5 模式），Step 6 显式增加 market_daily/sector_daily/signal_daily 三表历史补算步骤 |
| B8 | 正文「6 个池接口」vs 5 池名单 vs pool CHECK 5 值 | 最小改动 | **定稿 5 池**：ZT/ZB/DT/STRONG/PREV（正文已改）；次新池 sub_new（探针实测存在）不进盘中主链（30 天边界与昨停池重叠、次新波动特性另类），列为可选扩展，CHECK 不动 |
| B9 | daily_note UNIQUE(trade_date,page,code) 对 code=NULL 失效（PG NULL≠NULL） | 从数据库读出来编辑，不做插入覆盖 | schema 改 `UNIQUE NULLS NOT DISTINCT`（PG16）；API 语义定稿：编辑=读出已有行 → PUT 更新该行，前端面板**永不盲插**；POST 带 if_updated_at 乐观锁，冲突返 409 |

### 17.2 应修级（本次一并定稿，落地时实施）

**调度**：SorosJob/SignalPrecomputeJob 各加 21:30 兜底 cron（完成标记：big_trend 按 data_type+end_date、signal_daily 按当日行数判存在）｜ 链序强制 DailyCollect→Sentiment→Signal（SignalPrecomputeJob 监听 SentimentCycleJob 完成事件，禁止并行监听 DailyCollectCompleted）｜ ApplicationReadyEvent 启动对账：查最近 N 交易日派生表齐全性，缺则 computeFor 补算 ｜ data_coverage=PARTIAL 的派生行次日滚动重拉后**重算**（防线④扩容）；21:30 兜底跳过条件=「存在且非 PARTIAL」｜ 全部盘后 Job 入口统一 `if(!tradingCalendar.isTradingDay(today)) return`（含 21:30 兜底）｜ 交易日历 2025/2026 覆盖探针**升级为上线前阻塞检查** ｜ 盘中 diff 基准持久化（重启读 intraday_pool_snap 最近一轮做基准）+ intraday_event 按 (trade_date,ev_type,code,5min 窗口) 去重 ｜ 回填避开交易日 19:00-22:00（运维约束），limit_up_streak 补算 SQL 加 `WHERE trade_date<今日` ｜ dragon_cycle 加 partial UNIQUE：`CREATE UNIQUE INDEX uq_dragon_active ON dragon_cycle(code) WHERE end_date IS NULL` ｜ 15:10 归档步纳入 21:30 兜底体系（查 intraday_archive 当日行数，缺则补跑）

**数据**：yst_promotion 晋级率分母定稿——昨日涨停今日停牌=**计入分母、视为未晋级**（与 §4.8 停牌断板一致）｜ 回填起点前延续的连板 limit_up_streak=1 加 BOOT 标注（先例 dragon_cycle BOOT）｜ /stock-search SQL 加 `WHERE NOT is_st AND NOT delisted` ｜ 戴帽前历史行口径成文：保留入库、进入全市场回测 universe、断档按「无行=停牌」语义、摘帽反向同理

**策略**：Kelly 查询两段式——先取 LIVE/PAPER 近 60 笔，不足 20 笔补 BACKTEST 凑；**样本 <20 笔一律不出 Kelly 建议仓位**（面板显「样本不足」，样稿「30 笔减半」文案作废待改）｜ PAPER 定位=执行单页「模拟单」手动登记入口（一期无自动模拟盘，trade_ledger.source='PAPER' 不是死值但生产者是人）｜ dryRun 试跑结果**不落 trade_ledger**，backtest_result.is_dry 标记、结果对比默认过滤 DRY ｜ alert_enabled=策略级开关（挂 strategy_config），结果对比页按钮语义=「对当前版本配置启用」，多份结果同策略时不产生歧义 ｜ backtest_result 加 config_id / config_version / evaluated_universe(JSONB 本次实际求值股票名单) / is_dry

**策略执行守卫（B3 衍生，用户铁律）**：实盘/PAPER 执行单与盘中 ALERT 下单前**必须校验目标股 ∈ 该策略最近一次正式回测的 evaluated_universe**，不在名单 → 拒绝下单并推钉钉——策略只对回测覆盖过的股生效，绝不在未验证的股上真实买卖

**盘中前端**：§14.6 补时刻表（9:25-9:30 竞价间隙池语义、11:30-13:00 午休冻结标记、15:00-15:10 窗口、边界轮次 inclusive）并列入 §14.7 必测清单 ｜ §14.4 声明**事件流=90s 采样级非逐笔**（<90s 一闪而过的炸板可能漏），15:10 归档对拍 ZB 计数作完整性校验 ｜ DTO 字段统一 `panels.strategy_alerts`（§14.9 正文两处 page.alerts 已改）｜ 上线日由 API 派生 `min(intraday_replay.trade_date)`，前端不硬编码；replay 无行返回 `200 + {empty:true, launch_date}`（非 404）｜ 14:45 日线口径求值：筹码类条件读 **T-1 signal_daily**（标注口径），当日价量实时近似；「直接读预计算表 market_env」措辞更正（market_env 是 §12.7 条件组非表）｜ 预警候选池枚举定稿=涨停池∪强势池∪universe 成员∪当日条件命中（轮询时现算）｜ nav 跳转 JS 统一追加当前 ?date=（mock 同步修改）

### 17.3 新增需求待办（2026-10-03 用户提出）

1. **数据池可视化**：盘中 mock 加「池状态」面板——5 池各自健康灯/最近成功时间/限频状态/启用开关，可视化切换
2. **样稿增补清单**：竞价态 KPI 卡、「引用到笔记」按钮、情绪页崩塌池/反核数展示位、笔记面板「仅本股/全市场」切换控件、术语汇总展示位、nav date 透传

### 17.4 穿透评分（满分 100）

| 状态 | 分值 | 说明 |
|---|---|---|
| 整改前（4 Agent 审计出 9 阻塞+~20 应修时） | **72** | 底座/采集/涨停判定/幂等扎实，但回测口径与失效传播有硬伤 |
| 9 条阻塞修复后 | **85** | 口径自洽，可安全落地 |
| 本次应修一并定稿后（当前） | **93** | 设计层穿透闭环 |
| 剩余 7 分 | — | 落地期才能关掉的不确定性：三源单位实测、交易日历 2026 覆盖、除权检测精度、盘中接口时刻语义、预警伪信号调参（均已挂探针项，非设计缺陷） |

### 17.5 样稿接口穿透补全（2026-10-03，用户裁定）

五页样稿逐面板穿透对照 API 清单，4 处缺口定稿（与正文冲突以本节为准）：

| # | 缺口 | 裁定 | 落点 |
|---|---|---|---|
| C1 | 崩塌池名单无落点（只有 collapse_count/rebound_count 两个数字，样稿要显示池内个股） | 补列 | sentiment_cycle 加 `collapse_list JSONB`（[{code,name,limit_down_streak,industry}]，与大肉/大面名单同构）；SentimentCycleJob 顺带算，零新采集（§4.9 DDL 已落） |
| C2 | 盘中池「启用/停用」开关无接口（样稿可点，正文只有配置文件级启停） | **A：运行时热开关**（用户裁定） | 新表 intraday_pool_state + `PUT /api/v1/intraday/pools/{pool}/enabled`（§14.3/14.4/14.5 已落）；日线三源切换维持 Router 自动 failover 不做人工界面（池开关=运营动作，源切换=容错机制，语义不同） |
| C3 | 自选股 CRUD 端点漏写（表+tab④ 界面已有） | 补清单 | §12.9 追加 watchlists 6 端点（批量导入逐行返回拒绝原因，服务端校验存在性/非 ST） |
| C4 | K 线页无展示端点（/history/daily 是 V1 兼容 webhook，前端不该吃） | 补端点 | `GET /api/v1/stocks/{code}/kline`（§12.4.1 已落：OHLC ∘ 筹码 8 列聚合，衍生指标前端算） |

§17.3 待办 ①② 已完成（commit d8e3290：盘中池状态面板 + 样稿增补 6 项）。

### 17.6 字段级三方对拍（2026-10-03，fork 代理机审 + 人工定稿）

对拍范围：PLAN API schema ↔ 5 样稿页面 JS 实际读取字段 ↔ 种子五池快照实测键名。**一致项**：ladder 8 字段与 §14.4 一字不差；情绪页主行 10 字段全有出处；自选股成员表与 watchlist_member 吻合；notes page 枚举一致；panels.strategy_alerts 命名统一已生效。**7 缺口定稿**（修改已全部落正文）：

1. 【高】池状态面板健康灯/最近成功/限频三样无落点 → **定稿=运行时态**：summary 响应 `pools:[{pool,enabled,last_ok_at,rate_state}]`，不入库（§14.4 已落）
2. 【中高】池接口中文字段→英文 DTO 映射表缺失 → §13 补全量映射表（以种子实测键名为准）
3. 【中】"六项关键字段全齐"仅 ZT 成立；DT"封单资金"≠ZT"封板资金" → §13 池别覆盖声明 + 归档缺列 NULL 策略
4. 【中】kpi_series 缺 max_streak/gap_pct 槽位；adr 定稿=涨跌家数比（adv/dec 并列；样稿 2300 系演示数据误用成交额口径）→ §14.4 已落
5. 【中】大肉/大面名单键名 mock `{pct,limit_up_streak}` vs DDL `{change_pct,limit_up_streak}` → **以 DDL 为准，样稿 M7 对齐**
6. 【中】C4 kline 响应 schema 未定义、筹码 8 列名样稿简写、avg_cost 无来源 → §12.4.1 补完整 schema + `avg_cost=close/(1+cost_dev/100)` 派生公式（不落列）
7. 【低】样稿钉钉混在 events（tg='DD'）vs PLAN 独立 panels.ding_talk → **以 PLAN 为准，样稿 M7 对齐**；notes 简写映射已记 §12.9

**无法判定→定稿**：console 回测指标卡 `r.win/r.trades` ↔ metrics JSONB → §12.5 键名定稿（8 键，console 消费同名）；intraday 样稿未实际消费 strategy_alerts（演示态，M7 实装）。

**样稿侧遗留对齐项（M7 批次，不阻塞落地）**：大肉/大面键名、adr 演示值、events 钉钉混流、kline chip 简写名、notes 简写字段。

---

## 十八、2026-10-04 东财「封禁」事件复盘 + 第四源与校准落地

> 事件：akshare 东财日 K 大面积失败，表象酷似 IP 封禁。经逐跳对照实验定性后，系统性地长出了一套数据源韧性体系（netfix / IPGuard / 分流路由 / 分片分压 / sina failover / Yahoo 第四源 / 校准闭环）。本节是全链路复盘定稿，与正文冲突时以本节为准。

### 18.1 根因定性（对照实验实证链）

**「封禁」假设是错的**。同出口 IP 访问不同东财 CDN 边缘一好一坏 → 不是出口 IP 被封。完整根因链：

1. **家宽原生 IPv6 出口被东财拒**：CDN 的 v6 地址轮换不可控，OS 路由无法按域名治理 v6 目标；
2. **requests 按 macOS 系统地址序 v6 优先** → 必踩坏边缘；curl 手动 `-4` 能通 ≠ requests 能通（TLS 指纹 / 边缘选择差异）；
3. push2his K 线族曾整族拒连过一段时间（坏边缘扩散），后自愈。

机器环境事实（repo 记不了，长期留 memory）：飞连全量隧道（utun4，出口固定、重连不换 IP，Claude 流量依赖不能断）；数据源分流路由由用户 sudo 手工添加（61.129.129.196/199、116.162.209.83-85、114.94.20.92、114.94.161.4 → en0 网关），**电脑重启后失效需重加**。

**方法论沉淀：先定性再动手**。若按「IP 封禁」硬打（换代理 / 加大重试），方向全错——本次真正有效的动作全在边缘选择与出口协议栈层面。

### 18.2 韧性体系分层（全部落地）

| 层 | 组件 | 要点与坑 |
|---|---|---|
| 进程内网络治理 | **netfix**（soros-data-service） | IPv4 强制（AF_INET）+ eastmoney 域名族边缘 steering（EDGE_POOLS 好边缘池 + 探针失败轮换）。坑①：AF_INET sockaddr 必须 (host, port) **2 元组**，4 元组 create_connection TypeError；坑②：Python `except X as e` 出块 e 即删（作用域陷阱）。**EDGE_POOLS 与 OS 分流路由耦合——新增 EM 域名/IP 必须两处同步补** |
| OS 分流路由 | sudo 手工 route | 东财/baostock 网段走家宽出口，与 netfix 双保险；重启失效（见 18.1） |
| 封禁 vs 熔断分离 | **IPGuard** | CircuitBreaker 管秒级瞬态（60s open）；IPGuard 管小时级封禁：连接层失败 5 次/60s → ban，ban 期间请求不出门、failover 立即接管；探针 15→30→60min 退避自愈，真实成功即解封。`/ipguard` 端点独立于 /health（Kotlin Jackson strict 模式，health 模型不能混入新字段） |
| 多源分片分压 | shard pool | `int(code) % N` 稳态归属（**禁 hash()**——Python 进程间随机），只调顺序不改能力，failover 仍全源可用 |
| 源内 failover | akshare 双链 | EM(push2his) 失败自动转新浪 `stock_zh_a_daily`，归因 `akshare-sina`；不炸到 Router、不进 IPGuard 失败窗口。sina 口径：无涨跌幅列（raw 双拉计算）、turnover 小数×100、volume 已是股 |
| 第四源 | Yahoo（可选） | 见 18.3① |
| 质量闭环 | V5 + CalibrationJob | 见 18.3③④ |

### 18.3 四件套落地（commit `44d37c3`，TDD：Python 129 / Kotlin 399 全绿）

**① YahooAdapter（第四源，可选）**
- 选型穿透：yfinance 1.2.0 强制 curl_cffi chrome 指纹 → 被 Yahoo 边缘持续 429（喂普通 session 直接 YFDataException）→ **结论=绕开 SDK，直连 `v8/finance/chart` + 普通 requests + 浏览器 UA**。
- 口径：qfq factor=adjclose/close 缩放 OHLC、close=adjclose；change_percent 用 **RAW** close（拉取窗前扩 10 自然日取 prev close）；turnover 无源=0；amount=(H+L+C)/3×volume 估算；hfq 拒绝（ParameterError）；停牌 None 行跳过；ticker 映射 6/9→`.SS` 其余→`.SZ`。
- **本出口配额极紧**：突发 ~8 次请求即 403/429 冷却（几十分钟，query1/query2 共享）→ Yahoo 只宜做**校准/对拍腿**，绝不当主力拉取源。
- 可选源语义：Router missing-check 只硬查三核心源（baostock/akshare/mootdx）；注册后排 failover 序尾；未注册时 /health 的 yahoo 字段为 None。

**② 分片三源池**：默认 `baostock,akshare,yahoo`，`int(code) % 3` 稳态归属；600xxx/000xxx/00xxxx 分布随 %3。

**③ V5 校准三列**：`calibrated` BOOLEAN NOT NULL DEFAULT FALSE / `calibrated_source` VARCHAR(20) / `calibrated_at` TIMESTAMPTZ + 局部索引 `idx_stock_history_uncalibrated (code) WHERE calibrated = FALSE`；CHECK 扩 6 值（+AKSHARE_SINA/YAHOO）；契约测试索引数 44→45。**新增列不被回填覆盖的结构保证**：`merge_stage_to_main.sql` 的 DO UPDATE 是显式列清单（不含校准列）+ saveBatch read-modify-write（findByCodeAndTradeDate ?: new）。

**④ CalibrationJob**：cron `0 13,43 * * * *`（错峰）；回填 RUNNING 整轮让行（避免 COPY/merge 写竞争）；校准窗=未校准行**最新 ≤30 自然日**（确定式尾部推进，分散多天消化）；容差同 CrossValidateJob（close ±0.1% / volume ±1% / change_pct ±0.02pp）；**逐行只标记实际比对过的行**（read-modify-write）；任一差异 → 全部不标记 + data_quality_log(CALIBRATION_MISMATCH) + 钉钉 WARN；Python 空/故障 → 静默跳过（留候选池下轮再试，不产质量噪音）。

### 18.4 事故衍生处置：UNKNOWN 归因缺口

**根因双缺陷叠加**：sina failover 落地前的失败时期 Python 端回 UNKNOWN；且 `DataSourceType.fromPython` 只做大小写等值比对，"akshare-sina"（连字符）映射失败 → 30,255 行 UNKNOWN，存量累积至 180,200 行。
修复两处：`fromPython` 归一化（`'-'→'_'` + 忽略大小写）；经用户批准执行 relabel（2026-10-04，`UPDATE stock_history SET data_source='AKSHARE_SINA' WHERE data_source='UNKNOWN'` → 180,200 行）。
现分布：BAOSTOCK 1,153,842 / AKSHARE_SINA 181,378，零 UNKNOWN/MOOTDX。污染监控口径同步改为**只盯 UNKNOWN/MOOTDX 回升**（akshare-sina 是合法值，不误报）。

### 18.5 教训清单（复盘精华）

1. **先定性再动手**：故障表象（"被封了"）与真因（坏边缘 + v6 出口）可能完全无关，对照实验（-4/-6、--resolve 逐边缘、UA A/B）是定性唯一手段。
2. curl 通 ≠ requests 通（TLS 指纹/边缘选择）；SDK 能跑 ≠ 可控（yfinance 指纹强绑）——SDK 指纹/重试策略不可控时直连 API。
3. TokenBucket capacity 默认=rate，rate<1 时令牌永远攒不到 1.0 扣减阈值 → acquire 必超时；容量下限 `max(rate, 1.0)`。
4. Python 嵌套 `except X as e` 出块即删 e（作用域陷阱）。
5. AF_INET sockaddr 必须 (host, port) 2 元组。
6. 枚举值域变更必须连 DB CHECK 一起改（V5 CHECK 扩 6 值 + fromPython 归一化同批落地）。
7. 新增 EM 域名 → EDGE_POOLS + OS 分流路由两处同步（耦合清单，改一处必查另一处）。
8. 契约测试精确计数（索引 44→45、表 27）是防漂移手段，改 schema 必须同步改断言与命名字典。
9. 回填续传 to 必须用**交易日末日**：`findMaxTradeDate(code) >= to` 才跳过，to 填今天（非交易日）会全量重拉。
10. 外源铁律再验证：一律 TokenBucket + 抖动 + 批间停顿，禁止连续猛打（Yahoo 配额事件是最新实证，2026-10-03 用户明令）。

### 18.6 运行态与挂账

- **部署态**：uvicorn 四源注册（/health 四源 ok）、Spring V5 已应用（CHECK 6 值 + 校准列已验证）、回填补拉进行中（2021-10-01 → 2026-09-30，续传跳过已覆盖 code）。
- **挂账**：Yahoo 配额恢复后 E2E parse 验证（后台探针中）；qfq factor 漂移（存量问题，AdjustCheckStep §17.2 B6 覆盖）；钉钉 webhook 真实地址；search_key 拼音首字母；M3 校准。

---

## 十九、数据源拓宽与渠道化管理（2026-10-04 规划定稿，落地待穿透）

> 用户定调：**数据源 = 渠道**（对齐 Payment-X 渠道体系的设计观）。接入形态各异（HTTP API / socket 私有协议 / TDX 二进制 / 浏览器），渠道层归一成统一抽象「能否正确获取数据」；**先接入丰富池子，稳定性归渠道治理兜底**（IPGuard / 熔断 / failover）。数据源是策略、回测、情绪周期等一切下游的起点，渠道池宁多勿缺。

### 19.1 三源溯源结论（源码实锤，2026-10-04）

| 源 | 上游 | 性质 |
|---|---|---|
| baostock | `api.baostock.com` 自建服务器（私有二进制 socket） | 二道贩子（自建采集库，qfq 自算） |
| akshare | 纯聚合爬虫：EM 链→`push2his.eastmoney.com`、sina 链→新浪、**内置腾讯链** `stock_zh_a_hist_tx`→`proxy.finance.qq.com` | 三道贩子 |
| mootdx | 通达信行情服务器集群（consts.py ~20 个云上 IP），券商 Level-1 转发链路 | 行情转发商 |
| **关键洞察** | 全部链路上游汇到**交易所/少数转发商**（东财/新浪/腾讯/通达信）；真正源头级=交易所官网自身 | 沪深官网接入依据 |

### 19.2 日更多源分片池（回填完成后实施）

回填（一次性重负载）与日更（~5400 行/天轻负载）是两种负载，源选择逻辑不同。日更模式：**凡有当日记录的源全部接入分片池**，`int(code) % N` 摊派——每源每天仅扛 ~600-800 股，请求量小到任何源都不会触发封禁；单源被封只影响一个分片，failover 接管。

拟纳入池（实测后定稿）：深交所快照 / 上交所接口 / 东财 EM / 新浪 / 腾讯（akshare 内置链，集成成本最低档）/ baostock / mootdx / BrowserAdapter（兜底腿）。**Yahoo 不进日更池**（配额太紧，恒为校准腿，§18.3①）。

### 19.3 国内免费源调研定稿（2026-10-04，调研 Agent 全量实测 + 手工交叉验证）

完整报告：`docs/research/2026-10-04-free-domestic-sources.md`；测试台：`docs/tools/data-source-probe.html`。

**✅ 实测通过（候选池定稿）**：
1. **腾讯行情（A 级，优先落地）**：`proxy.finance.qq.com / web.ifzq.gtimg.cn / qt.gtimg.cn` 3/3 域全通、无鉴权、qfq 齐全（字段序 [date,open,close,high,low,volume,分红,turnover%,amount万元,占位]）。⚠ akshare 内置 `stock_zh_a_hist_tx` 对 sz000xxx 有 volume×100 bug → **自建 HTTP 解析**（仿现有指数兜底代码），勿直接调包。独立转发商，与东财/新浪不同源。
2. **上交所行情云 `yunhq.sse.com.cn:32042`（A 级，源头级）**：`GET /v1/sh1/dayk/{code}?select=date,open,high,low,close,volume,amount&begin=0&end=-1` **单次返回单股 IPO 首日至今全历史**（600000 实测 6400 条/377KB，1999-11-10 起）——覆盖深度优于 baostock。raw 不复权、涨跌幅自算；volume 疑似股（落地前与 baostock 交叉核对一次）。端口 32041 SSL 失败用 32042；Referer 必要性待对照实验；深市路径 `v1/sz1/dayk` 待补测。**定位=第五校准源（权威基准）+ 首次建仓灌历史**，非日常批量。
3. **深交所 CATALOGID=1110 A股列表（B 级）**：2904 只的代码/简称/上市日期/**总股本/流通股本（亿股）**/行业——**§17.1 B1（流通市值）挂账的股本数据源头级来源**，独立立项。

**❌ 实测否决**：雪球（强制 xq_a_token，curl 400）、网易（502 服务端下线坐实）、百度股市通（403 hit-risk 风控，两线程交叉一致，**挂账：加家宽分流表后复测**）、深交所个股日行情（仅单日快照无区间历史——定位改为深市日更入口+校准锚）。
**同源去重**：efinance/adata 日线主链 100% 同源东财 push2his，无增量不纳；Tushare 免费档 qfq 卡 2000 积分门槛外，列备胎。

**官网源口径**：raw 不复权 → 「raw 真值 + 复权因子推 qfq」（除权日本由 AdjustCheckStep §17.2 B6 重算，两体系咬合）；同时成为 CalibrationJob 的源头级对拍锚。

### 19.4 BrowserAdapter（浏览器取数渠道，规划）

**动机**：真实 Chromium 是最强伪装——yfinance 指纹墙（§18.3①）、Stooq Cloudflare JS 挑战（国外源实测被拦）、SSE Referer、雪球 Cookie 过期、东财坏边缘，全部由浏览器环境天然化解，反封禁能力最强。
**实现形态**：Playwright 无头浏览器作为 Python 服务的一个 Adapter 进渠道池，定位=**JS 挑战源专属腿 + 其他源全败时的最后兜底腿**（有内存/启动成本，不全量走它）。
**手工页面形态**：HTML 页以 `<script src>`/JSONP 读数仅限 JS 片段型源（新浪/腾讯），CORS 挡纯 JSON 源——只作应急兜底工具，非日更主链。

### 19.5 渠道管理台（HTML 已建 V1：docs/tools/data-source-probe.html）

- **V1 已交付**：全部渠道的请求配置卡片（URL 模板/Header/口径/风险注记）、参数化测试代码与日期区间、浏览器 no-cors 测连（网络可达+延迟）、一键复制 curl（权威验证，可带 UA/Referer/Cookie）。
- **V2 渠道运营台账（需后端三件事，落地待确认）**：页面上直接展示每渠道**最近一次成功时间 / 最近一次封禁时间 / 被封的出口 IP / 当前熔断与 IPGuard 状态**——对应后端：① FastAPI 开 CORS（file:// 页面读不到现服务，已实测无 CORS 头）② 渠道状态聚合端点（health+IPGuard+netfix 边缘态归一输出）③ IPGuard 事件历史落库（ban 时间+IP 记录，当前 `/ipguard` 只有瞬时态 `banned:{}`，无历史）。

### 19.6 国外免费源结论（2026-10-04 定稿，调研 Agent 实测）

**国外源对 A 股无增量价值，Yahoo 已是最优解，不再追加评估**。三层否决：① 覆盖层——Twelve Data/EODHD 把沪深锁付费档、Finnhub 国际行情 Enterprise 专属、FMP 仅美股；② 额度层——Alpha Vantage 25 req/天、EODHD 20 req/天，对 5000 股无意义；③ 可达层——Stooq 本机出口被 Cloudflare JS 挑战拦截。报告存档 `docs/research/2026-10-04-free-intl-sources.md`。
