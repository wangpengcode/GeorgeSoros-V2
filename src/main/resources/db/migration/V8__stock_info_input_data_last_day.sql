-- =============================================================================
-- V8: stock_info 增加数据导入水位 input_data_last_day（2026-10-05 用户定稿）
--
-- 语义：该票数据已核对/导入到的最近交易日；= 最新开市日 → 回填跳过该票（零外部请求），
--       落后 → 从水位线后首个开市日断点续传导入。
-- 初始值一次性回填 = stock_history 各票 max(trade_date)：库内已有的历史数据视同已导入核对
-- 至该日；无任何行的票保持 NULL → 首轮整窗拉取，验证空后由 Job 推进。
-- 运行期维护：BackfillJob（段成功落库/整段验证空后推进）、StockHistoryServiceImpl.saveBatch
-- （增量导入成功后推进）；单调不减（禁止回拨）。
-- =============================================================================
ALTER TABLE stock_info ADD COLUMN input_data_last_day DATE;

COMMENT ON COLUMN stock_info.input_data_last_day IS
    '数据导入水位：该票已核对到的最近交易日；NULL=从未导入；成功导入或整段验证空后推进（单调不减）';

UPDATE stock_info s
SET input_data_last_day = agg.max_d
FROM (
    SELECT code, MAX(trade_date) AS max_d
    FROM stock_history
    GROUP BY code
) agg
WHERE s.code = agg.code;
