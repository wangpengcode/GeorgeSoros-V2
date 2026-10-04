-- =============================================================================
-- V5：stock_history 校准三列 + data_source CHECK 扩展（2026-10-04 第四源落地）
--
-- ① 校准三列（CalibrationJob 低频对拍；用户拍板「校准分散到多天，对每源都是低频」）
--    - calibrated        默认 FALSE，存量行视为未校准（滚动补校准）
--    - calibrated_source 校准对照源（DataSourceType 枚举名，varchar(20) 放得下 AKSHARE_SINA）
--    - calibrated_at     校准时间（TIMESTAMPTZ）
--    - 局部索引：未校准行常驻候选池查询（DISTINCT code WHERE calibrated=false）；
--      已校准行不进索引 → 索引随校准推进自动收缩
-- ② CHECK 扩展：新增 'AKSHARE_SINA'（akshare 内部 EM→新浪 failover 归因；
--    30,255 行 UNKNOWN 根因）与 'YAHOO'（第四源）。index_history 无 CHECK 约束，无需变更。
-- ③ 合并语义安全（穿透验证）：BackfillJob merge_stage_to_main.sql 的 DO UPDATE SET
--    为显式列清单（不含 calibrated*），回填重跑不会重置校准状态；
--    saveBatch 为 read-modify-write（findByCodeAndTradeDate ?: new），天然保留。
-- =============================================================================

ALTER TABLE stock_history ADD COLUMN calibrated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stock_history ADD COLUMN calibrated_source VARCHAR(20);
ALTER TABLE stock_history ADD COLUMN calibrated_at TIMESTAMPTZ;

CREATE INDEX idx_stock_history_uncalibrated
    ON stock_history (code)
    WHERE calibrated = FALSE;

ALTER TABLE stock_history DROP CONSTRAINT stock_history_data_source_check;

ALTER TABLE stock_history ADD CONSTRAINT stock_history_data_source_check
    CHECK (data_source IN ('BAOSTOCK', 'AKSHARE', 'AKSHARE_SINA', 'MOOTDX', 'YAHOO', 'UNKNOWN'));
