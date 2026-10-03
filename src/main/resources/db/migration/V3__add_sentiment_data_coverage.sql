-- =============================================================================
-- V3：sentiment_cycle 增加 data_coverage 列（§13.4 数据覆盖标记）
-- PLAN §13.4：failedCodes 占比 >10% 时照常派生，但 sentiment_cycle 行标
-- data_coverage=PARTIAL + 钉钉提示。schema.sql 已同步（DDL 唯一 SoT）。
-- =============================================================================

ALTER TABLE sentiment_cycle ADD COLUMN data_coverage VARCHAR(10) NOT NULL DEFAULT 'FULL';
ALTER TABLE sentiment_cycle ADD CONSTRAINT chk_sentiment_cycle_data_coverage
    CHECK (data_coverage IN ('FULL','PARTIAL'));

COMMENT ON COLUMN sentiment_cycle.data_coverage IS '数据覆盖（FULL=全量正常 / PARTIAL=采集失败率>10%，§13.4）';
