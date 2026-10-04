-- =============================================================================
-- V6：data_source CHECK 值域扩展 6→8（+TENCENT/SSE，2026-10-04 第五/第六渠道落地）
--
-- ① CHECK 扩展：新增 'TENCENT'（第五源，独立转发商，qfq/hfq 服务端自算，§19.3 ①）
--    与 'SSE'（第六源，上交所行情云 yunhq.sse.com.cn:32042，源头级校准腿，仅 raw，§19.3 ②）。
-- ② 枚举与 CHECK 同批变更纪律：Kotlin DataSourceType 枚举
--    （src/main/kotlin/com/soros/v2/domain/DataSourceType.kt）与本 CHECK 约束必须同一批次变更，
--    防止运行时代码枚举与 DB 约束漂移（V5 起 AKSHARE_SINA/YAHOO 同纪律；V6 加 TENCENT/SSE）。
-- ③ index_history 无 CHECK 约束，无需变更；StockHistory Entity 无新列，不动。
-- =============================================================================

ALTER TABLE stock_history DROP CONSTRAINT stock_history_data_source_check;

ALTER TABLE stock_history ADD CONSTRAINT stock_history_data_source_check
    CHECK (data_source IN ('BAOSTOCK', 'AKSHARE', 'AKSHARE_SINA', 'MOOTDX', 'YAHOO', 'TENCENT', 'SSE', 'UNKNOWN'));
