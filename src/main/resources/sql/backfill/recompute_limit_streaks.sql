-- =============================================================================
-- 派生列补算（PLAN §六.6，BackfillJob 合并完成后一次性执行）
--
-- 背景：COPY 两段式只搬原始列，is_limit_up/is_limit_down/limit_up_streak/
-- limit_down_streak 的正常派生路径在 saveBatch（§4.8），COPY 不经过 → 必须补算。
--
-- 口径（与 saveBatch 派生路径逐条对齐，保证增量/回填两路径产出一致）：
-- ① 涨停/跌停标记：按 stock_info.board 阈值（MAIN=9.9 / GEM/STAR=19.9，LimitUpDetector），
--    判定用不复权 change_pct（§2.4 铁律，不回算自 qfq OHLC）；board 缺失按主板 9.9。
-- ② IPO 守卫：row_number≤5 且距 ipo_date ≤14 自然日（≈5 交易日上界；仅回填窗口覆盖
--    IPO 首 5 日的新股生效，老股数据起点远晚于 ipo_date 天然不命中）→
--    is_limit_up/is_limit_down=false、limit_up_streak/limit_down_streak=0。
-- ③ 连板/跌停连板：gaps-and-islands 窗口函数（grp = 非涨停运行计数，组内 row_number）；
--    停牌无行天然断板（无行即 grp 边界），与 saveBatch 的"前一交易日定位+停牌日无行→baseStreak=0"一致。
-- ④ 只补算 trade_date < 今日（§17.2：当日行情未终，留给 saveBatch 增量路径；不覆盖当日派生）。
-- =============================================================================

-- ① is_limit_up / is_limit_down（按 board 阈值；不复权 change_pct 判定）
UPDATE stock_history h
SET is_limit_up = CASE
        WHEN COALESCE(si.board, 'MAIN') IN ('GEM', 'STAR') THEN h.change_pct >= 19.9
        ELSE h.change_pct >= 9.9
    END,
    is_limit_down = CASE
        WHEN COALESCE(si.board, 'MAIN') IN ('GEM', 'STAR') THEN h.change_pct <= -19.9
        ELSE h.change_pct <= -9.9
    END
FROM stock_info si
WHERE si.code = h.code
  AND h.change_pct IS NOT NULL
  AND h.trade_date < CURRENT_DATE;

-- ② IPO 首 5 日守卫（距 ipo_date ≤5 交易日 + ≤14 自然日；老股不命中，新股首 5 日强制清零）
-- 穿透修正（Step 6 Verifier）：原 ROW_NUMBER() OVER (PARTITION BY code ORDER BY trade_date)
-- 按「已落库行」计数，在「回填起点前数日内 IPO / IPO+14 天内早期行缺失」时 rn 偏小→超额守卫，
-- 与 Kotlin LimitStreakComputer「自 ipo 起前 5 个交易日」口径不一致（抽查误报根因）。本版 rn =
-- 交易日历 [ipo_date, trade_date] 计数（含端点），与 recentTradingDays(barDate,5) 严格等价；
-- 交易日历缺失时 rn=0≤5 仍守卫，与 Kotlin fiveBack.size<5 守卫语义一致（防御降级）。
WITH ipo AS (
    SELECT code, ipo_date FROM stock_info WHERE ipo_date IS NOT NULL
),
ranked AS (
    SELECT h.id, h.code, h.trade_date, i.ipo_date,
           (SELECT COUNT(*) FROM trading_calendar tc
            WHERE tc.trade_date BETWEEN i.ipo_date AND h.trade_date) AS rn
    FROM stock_history h
    JOIN ipo i ON i.code = h.code
    WHERE h.trade_date < CURRENT_DATE
)
UPDATE stock_history x
SET is_limit_up = false,
    is_limit_down = false,
    limit_up_streak = 0,
    limit_down_streak = 0
FROM ranked r
WHERE x.id = r.id
  AND r.rn <= 5
  AND r.trade_date <= (r.ipo_date + INTERVAL '14 days')::date;

-- ③ limit_up_streak（gaps-and-islands + 停牌断板；基于补算后/守卫后的 is_limit_up）
-- 穿透修正（2026-10-04）：PLAN §六.6 字面 SQL 的 grp = SUM(CASE WHEN is_limit_up THEN 0 ELSE 1 END)
-- 对「停牌无行」不会自增——停牌是日历缺口不是非涨停行，缺口两侧的涨停会被误归同一组而继续连板，
-- 与 saveBatch（前一交易日历日无行→baseStreak=0→断板）不一致。本版改为显式断板：
--   reset = 前一行非涨停 或 前一交易日历日（calendar）无行（停牌/首根）→ 开新组
--   prev_cal_date = 该 bar 前一交易日历日（trading_calendar MAX < trade_date）
WITH marked AS (
    SELECT h.id, h.code, h.trade_date, h.is_limit_up,
           LAG(h.trade_date)  OVER (PARTITION BY h.code ORDER BY h.trade_date) AS prev_bar_date,
           LAG(h.is_limit_up) OVER (PARTITION BY h.code ORDER BY h.trade_date) AS prev_bar_limit_up,
           (SELECT MAX(tc.trade_date) FROM trading_calendar tc WHERE tc.trade_date < h.trade_date) AS prev_cal_date
    FROM stock_history h
    WHERE h.trade_date < CURRENT_DATE
),
flagged AS (
    SELECT id, code, trade_date, is_limit_up,
           CASE WHEN prev_bar_limit_up AND prev_bar_date = prev_cal_date THEN 0 ELSE 1 END AS reset
    FROM marked
),
grp AS (
    SELECT id, code, trade_date, is_limit_up,
           SUM(reset) OVER (PARTITION BY code ORDER BY trade_date) AS grp
    FROM flagged
)
UPDATE stock_history x
SET limit_up_streak = s.new_streak::smallint
FROM (
    SELECT id,
           CASE WHEN is_limit_up
                THEN ROW_NUMBER() OVER (PARTITION BY code, grp ORDER BY trade_date)
                ELSE 0 END AS new_streak
    FROM grp
) s
WHERE x.id = s.id;

-- ③b limit_down_streak（跌停镜像，§4.9 崩塌池派生；断板口径同 ③）
WITH marked AS (
    SELECT h.id, h.code, h.trade_date, h.is_limit_down,
           LAG(h.trade_date)    OVER (PARTITION BY h.code ORDER BY h.trade_date) AS prev_bar_date,
           LAG(h.is_limit_down) OVER (PARTITION BY h.code ORDER BY h.trade_date) AS prev_bar_limit_down,
           (SELECT MAX(tc.trade_date) FROM trading_calendar tc WHERE tc.trade_date < h.trade_date) AS prev_cal_date
    FROM stock_history h
    WHERE h.trade_date < CURRENT_DATE
),
flagged AS (
    SELECT id, code, trade_date, is_limit_down,
           CASE WHEN prev_bar_limit_down AND prev_bar_date = prev_cal_date THEN 0 ELSE 1 END AS reset
    FROM marked
),
grp AS (
    SELECT id, code, trade_date, is_limit_down,
           SUM(reset) OVER (PARTITION BY code ORDER BY trade_date) AS grp
    FROM flagged
)
UPDATE stock_history x
SET limit_down_streak = s.new_streak::smallint
FROM (
    SELECT id,
           CASE WHEN is_limit_down
                THEN ROW_NUMBER() OVER (PARTITION BY code, grp ORDER BY trade_date)
                ELSE 0 END AS new_streak
    FROM grp
) s
WHERE x.id = s.id;
