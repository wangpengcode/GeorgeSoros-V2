-- =============================================================================
-- GeorgeSoros-V2 Flyway V4：stock_history_stage 中转表（PLAN §六.1 COPY 两段式）
--
-- 设计定稿（2026-10-04）：
-- - UNLOGGED：不写 WAL（更快、崩溃自清），COPY 两段式只做瞬态中转；
-- - 结构 = stock_history 数据列全同（含派生列，默认 false/0），但：
--     * 去掉 id（主键 BIGSERIAL 不参与 COPY/merge，INSERT SELECT 显式列名即可）
--     * 不带 UNIQUE/CHECK 约束（约束拖慢 COPY；主表约束在 merge 时兜底）
-- - 生命周期（BackfillJob）：COPY INTO stage → INSERT ... ON CONFLICT (code,trade_date)
--   DO UPDATE → TRUNCATE stage → 下一批；崩溃自清，幂等可重跑。
-- - 派生列（is_limit_up/limit_down + 两 streak）COPY 时不写值，靠 merge 后窗口 SQL 补算
--   （PLAN §六.6：COPY 不经过 saveBatch 派生路径，必须补算）。
-- =============================================================================
CREATE UNLOGGED TABLE stock_history_stage (
    code               VARCHAR(20) NOT NULL,   -- 600000（裸数字，不带 sh/sz）
    trade_date         DATE NOT NULL,          -- 交易日
    open               NUMERIC(12,4),          -- qfq
    close              NUMERIC(12,4),          -- qfq
    high               NUMERIC(12,4),          -- qfq
    low                NUMERIC(12,4),          -- qfq
    volume             BIGINT,                 -- 统一单位=股
    amount             NUMERIC(20,4),          -- 成交额（元）
    change_pct         NUMERIC(10,4),          -- 涨跌幅%（不复权口径）
    turnover_rate      NUMERIC(10,4),          -- 换手率%
    is_limit_up        BOOLEAN DEFAULT FALSE,  -- 涨停（按原始 change_pct + board 阈值判定）
    is_limit_down      BOOLEAN DEFAULT FALSE,  -- 跌停
    limit_up_streak    SMALLINT DEFAULT 0,     -- 连板数（首板=1，0=非涨停/断板；§4.8 派生）
    limit_down_streak  SMALLINT DEFAULT 0,     -- 跌停连板（§4.9 崩塌池，镜像派生）
    data_source        VARCHAR(20) DEFAULT 'UNKNOWN', -- 数据来源（failover 可见性）
    created_at         TIMESTAMP DEFAULT NOW() -- 行创建时间
);
-- 列注释与 stock_history 同文（铁律：stage 表也须注释，与主表 KDoc 对齐）
COMMENT ON COLUMN stock_history_stage.code IS '600000（裸数字，不带 sh/sz）';
COMMENT ON COLUMN stock_history_stage.trade_date IS '交易日';
COMMENT ON COLUMN stock_history_stage.open IS '前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用';
COMMENT ON COLUMN stock_history_stage.close IS '前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用';
COMMENT ON COLUMN stock_history_stage.high IS '前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用';
COMMENT ON COLUMN stock_history_stage.low IS '前复权 qfq；涨停判定与 change_pct 用不复权口径，坐标永不混用';
COMMENT ON COLUMN stock_history_stage.volume IS '统一单位=股（AKShare/mootdx 手×100，探针实测校准）';
COMMENT ON COLUMN stock_history_stage.amount IS '成交额（元）';
COMMENT ON COLUMN stock_history_stage.change_pct IS '涨跌幅%（不复权口径）';
COMMENT ON COLUMN stock_history_stage.turnover_rate IS '换手率%';
COMMENT ON COLUMN stock_history_stage.is_limit_up IS '涨停（按原始 change_pct + board 阈值判定）';
COMMENT ON COLUMN stock_history_stage.is_limit_down IS '跌停';
COMMENT ON COLUMN stock_history_stage.limit_up_streak IS '连板数（首板=1，0=非涨停/断板；§4.8 派生）';
COMMENT ON COLUMN stock_history_stage.limit_down_streak IS '跌停连板（§4.9 崩塌池，镜像派生）';
COMMENT ON COLUMN stock_history_stage.data_source IS '数据来源（failover 可见性）';
COMMENT ON COLUMN stock_history_stage.created_at IS '行创建时间';
