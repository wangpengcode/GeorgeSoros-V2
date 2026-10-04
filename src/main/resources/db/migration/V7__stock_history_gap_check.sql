-- =============================================================================
-- V7：stock_history_gap_check —— 回填验证空段台账（停牌防反复空拉，2026-10-04 定稿）
--
-- 语义：某 segment 拉取成功（HTTP 200 且该码不在 failed[]）但返回 0 行 → 记录；
--       BackfillClassifier 仅对 MID 中间洞排除「与已验证空段完全重合」的 segment（exact-match）。
-- 决策留痕：code 列型用 VARCHAR(20) 对齐命名字典（全库 code 统一 VARCHAR(20)），
--       与设计草图 "varchar(10)" 有意偏差——同名必同义，避免一处 10 一处 20 漂移。
-- =============================================================================

CREATE TABLE stock_history_gap_check (
    code           VARCHAR(20) NOT NULL,      -- 证券代码（裸数字 600000）
    seg_from       DATE NOT NULL,             -- 已验证空段起点（含）
    seg_to         DATE NOT NULL,             -- 已验证空段终点（含）
    rows_returned  INTEGER NOT NULL DEFAULT 0, -- 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空）
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- 验证时间
    PRIMARY KEY (code, seg_from, seg_to)
);

COMMENT ON COLUMN stock_history_gap_check.code IS '证券代码（裸数字 600000）';
COMMENT ON COLUMN stock_history_gap_check.seg_from IS '已验证空段起点（含）';
COMMENT ON COLUMN stock_history_gap_check.seg_to IS '已验证空段终点（含）';
COMMENT ON COLUMN stock_history_gap_check.rows_returned IS '该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空）';
COMMENT ON COLUMN stock_history_gap_check.checked_at IS '验证时间（timestamptz）';
