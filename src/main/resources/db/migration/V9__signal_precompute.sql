-- =============================================================================
-- V9：信号预计算层 DDL 补充（SignalPrecomputeJob，§12.4.1 / §19.11.1 穿透定稿）
-- 1. market_daily 增加 data_coverage 列（§19.11.1 决策 5：21:30 兜底 cron 跳过条件依赖）
--    ——与 sentiment_cycle.data_coverage（V3）同口径，CHECK 值域 FULL/PARTIAL
-- 2. signal_daily 注释纠正（§19.11.1 决策 2/6）：
--    - cost_dev = PLAN 版符号 (close−avg_cost)/avg_cost×100（旧 DDL 注释 avg/close−1 作废）
--    - c90 端点 = p5/p95、c70 端点 = p15/p85（与集中度公式对齐）
--    - code 注释删去"指数=带前缀"句（本期不落指数行，指数条件走回测 B 类现算）
-- 3. sector_daily.board 注释纠正（§19.11.1 决策 1）：industry 主口径（概念不落表条件现算，市场板另置）
-- schema.sql 已同步（DDL 唯一 SoT）；本迁移幂等（ADD COLUMN/CONSTRAINT IF NOT EXISTS + COMMENT 天然覆盖）。
-- =============================================================================

-- ---- market_daily.data_coverage（幂等；命名约束同 V3 风格） ----
ALTER TABLE market_daily ADD COLUMN IF NOT EXISTS data_coverage VARCHAR(10) NOT NULL DEFAULT 'FULL';
-- PostgreSQL 无 ADD CONSTRAINT IF NOT EXISTS（仅 ADD COLUMN 支持），用 DO 块幂等建约束（命名约束同 V3 风格）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_market_daily_data_coverage') THEN
        ALTER TABLE market_daily ADD CONSTRAINT chk_market_daily_data_coverage CHECK (data_coverage IN ('FULL','PARTIAL'));
    END IF;
END $$;
COMMENT ON COLUMN market_daily.data_coverage IS '数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%，§13.4）';

-- ---- signal_daily 注释纠正（COMMENT ON COLUMN 天然幂等覆盖） ----
COMMENT ON COLUMN signal_daily.code IS '证券代码（股票=裸数字 600000；本期不落指数行）';
COMMENT ON COLUMN signal_daily.cost_dev IS '成本偏离% = (close−avg_cost)/avg_cost×100（qfq 重对基免疫）';
COMMENT ON COLUMN signal_daily.c90_low IS '90% 成本区间下沿（p5 分位，qfq 坐标）';
COMMENT ON COLUMN signal_daily.c90_high IS '90% 成本区间上沿（p95 分位，qfq 坐标）';
COMMENT ON COLUMN signal_daily.c90_conc IS '90% 集中度（东财口径 (p95−p5)/(p95+p5)×100，qfq 坐标）';
COMMENT ON COLUMN signal_daily.c70_low IS '70% 成本区间下沿（p15 分位，qfq 坐标）';
COMMENT ON COLUMN signal_daily.c70_high IS '70% 成本区间上沿（p85 分位，qfq 坐标）';
COMMENT ON COLUMN signal_daily.c70_conc IS '70% 集中度（(p85−p15)/(p85+p15)×100，qfq 坐标）';

-- ---- sector_daily.board 注释纠正（industry 主口径） ----
COMMENT ON COLUMN sector_daily.board IS '行业（industry 主口径，概念不落表条件现算，市场板另置；§19.11.1 决策 1）';

-- ---- sector_daily.avg_chg_pct_all（§19.11.1 决策 3：板块全成员均涨，词表 #9「板块涨幅榜前列」） ----
ALTER TABLE sector_daily ADD COLUMN IF NOT EXISTS avg_chg_pct_all NUMERIC(10,4);
COMMENT ON COLUMN sector_daily.avg_chg_pct_all IS '板块全成员平均涨幅%（词表 #9「板块涨幅榜前列」，§19.11.1 决策 3）';
