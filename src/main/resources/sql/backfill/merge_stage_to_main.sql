-- =============================================================================
-- stage → 主表 幂等合并（PLAN §六.1 ②：ON CONFLICT (code, trade_date) DO UPDATE）
--
-- 调用方：BackfillJob（每批 COPY INTO stage 后执行，随后 TRUNCATE stage 进下一批）。
-- 幂等语义 = saveBatch 的 DO UPDATE 完全一致，可中断重跑（断点续传 = 幂等重跑 + 已覆盖代码跳过）。
-- 派生列（is_limit_up/is_limit_down/limit_up_streak/limit_down_streak）COPY 时以默认值
-- false/0 进入 stage，本合并原样带入主表；最终由 recompute_limit_streaks.sql 一次性补算。
-- =============================================================================
INSERT INTO stock_history (code, trade_date, open, close, high, low, volume, amount,
                           change_pct, turnover_rate, is_limit_up, is_limit_down,
                           limit_up_streak, limit_down_streak, data_source)
SELECT s.code, s.trade_date, s.open, s.close, s.high, s.low, s.volume, s.amount,
       s.change_pct, s.turnover_rate, s.is_limit_up, s.is_limit_down,
       s.limit_up_streak, s.limit_down_streak, s.data_source
FROM stock_history_stage s
ON CONFLICT (code, trade_date) DO UPDATE SET
    open             = EXCLUDED.open,
    close            = EXCLUDED.close,
    high             = EXCLUDED.high,
    low              = EXCLUDED.low,
    volume           = EXCLUDED.volume,
    amount           = EXCLUDED.amount,
    change_pct       = EXCLUDED.change_pct,
    turnover_rate    = EXCLUDED.turnover_rate,
    is_limit_up      = EXCLUDED.is_limit_up,
    is_limit_down    = EXCLUDED.is_limit_down,
    limit_up_streak  = EXCLUDED.limit_up_streak,
    limit_down_streak = EXCLUDED.limit_down_streak,
    data_source      = EXCLUDED.data_source;
