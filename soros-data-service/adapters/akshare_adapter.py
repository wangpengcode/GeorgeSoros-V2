"""AKShare Adapter（PLAN §5.4 V2 版 + §11.1 接口源探查结论 + 探针实测 + Step 4 指数日K）。

实现要点：
- 输入/输出裸数字，直接用（PLAN §5.2）
- volume 手 ×100 → 股（探针实证：1474848 手 ×100 ≈ BaoStock 147484820 股；PLAN §5.4 代码片段
  遗漏乘子，§2.4 字典与探针实证为准，乘子收口在 constants.py AKSHARE_VOLUME_MULTIPLIER）
- amount=元、change_percent=涨跌幅（不复权）、turnover=换手率
- prev_close=昨收（stock_zh_a_hist 当前版本无"昨收"列 → None，§2.4 传输字段不落库）
- /stock-list 主源 stock_info_a_code_name → 备源 stock_zh_a_spot_em（§11.1 探查：主源有真实故障案例 Issue #5947）
- is_st = st_em ∪ stop_em 合并（任务指令；命名字典 is_st 语义"仅用于识别并排除"）
- 北交所过滤：83/87/43/920（§11.1）
- 交易日历 tool_trade_date_hist_sina（探针实测 PASS，一次全量）

指数日K（PLAN Step 4，探针 2026-10-03 选源结论，≤5 次请求实测）：
- **候选一（主源）：sina stock_zh_index_daily(symbol=sh000001)** —— 实测 PASS，
  返回全历史（8736 行至 2026-09-30），列 date/open/high/low/close/volume；无 amount → 填 0，
  无 change_percent/prev_close → 用 prev_close 现算（上一交易日 close）
- **兜底：腾讯 ifzq.gtimg.cn fqkline/get** —— 实测 PASS，day 数组 [date,open,close,high,low,volume]，
  范围可控（需前置窗口算 prev_close），volume=sina/100（手）→ ×100 对齐股口径
- **最后：东财 index_zh_a_hist** —— 本机实测 FAILED（push2 主域 ConnectionError，与协调者预警一致），
  保留为可移植性最后手段（其他网络可达 push2 时可用）
- 指数无"成交额"单值 → amount=0；无换手率 → turnover=0（PLAN §5.6 契约键仍 11 个）
- adjust 参数对指数不适用（指数点位已含成分调整），一律返回原始指数K，忽略 adjust
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import List

import akshare as ak
import requests

from adapters.base import BaseAdapter, SourceError
from constants import (
    AKSHARE_VOLUME_MULTIPLIER,
    AKSHARE_AMOUNT_MULTIPLIER,
    FUNDAMENTALS_AMOUNT_MULTIPLIER,
    BOARD_TYPE_INDUSTRY,
    SINA_INDEX_VOLUME_MULTIPLIER,
    TENCENT_INDEX_VOLUME_MULTIPLIER,
    to_index_em_symbol,
    is_north_exchange,
    derive_market,
    derive_board,
)

logger = logging.getLogger(__name__)


def _f(v) -> float:
    """安全转 float：None / NaN / 非法值 → 0.0。"""
    if v is None:
        return 0.0
    try:
        result = float(v)
    except (ValueError, TypeError):
        return 0.0
    if result != result:  # NaN
        return 0.0
    return result


class AkshareAdapter(BaseAdapter):
    source_name = "akshare"

    def __init__(self, circuit_breaker, rate_limiter):
        super().__init__(circuit_breaker, rate_limiter)
        self._supports_stock_list = True
        self._supports_is_st = True          # st_em / stop_em
        self._supports_delisted = False      # 退市状态由 baostock 提供
        self._supports_index = True          # 指数日K（sina→腾讯→东财 链）
        self._supports_fundamentals = True   # stock_yjbb_em（PLAN §11.1，仅 akshare）
        self._supports_board_members = True  # stock_board_industry/concept_*_em（PLAN §4.8，仅 akshare）
        self._board_members_degraded = False  # 板块成分拉取降级标记（单板块失败置 True，响应透传）

    # ---- 日 K 线（PLAN §5.4）----
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        df = ak.stock_zh_a_hist(
            symbol=code,
            period="daily",
            start_date=start.replace("-", ""),
            end_date=end.replace("-", ""),
            adjust=adjust,
        )
        if df is None or df.empty:
            return []
        bars = []
        for _, row in df.iterrows():
            bars.append({
                "date": str(row["日期"]),
                "code": code,                              # 直接用裸数字
                "open": _f(row["开盘"]),
                "high": _f(row["最高"]),
                "low": _f(row["最低"]),
                "close": _f(row["收盘"]),
                "volume": _f(row["成交量"]) * AKSHARE_VOLUME_MULTIPLIER,   # 手 → 股（探针实证）
                "amount": _f(row["成交额"]) * AKSHARE_AMOUNT_MULTIPLIER,   # 元
                "change_percent": _f(row["涨跌幅"]),                       # 不复权
                "turnover": _f(row["换手率"]) if "换手率" in row.index else 0,
                "prev_close": _f(row["昨收"]) if "昨收" in row.index else None,
            })
        return bars

    # ---- 股票列表 ----
    def _stock_list_df(self):
        """主源 stock_info_a_code_name → 备源 stock_zh_a_spot_em，归一为 code/name 两列。"""
        df = None
        primary_err = None
        try:
            df = ak.stock_info_a_code_name()
        except Exception as exc:  # noqa: BLE001
            primary_err = exc
            df = None
        if df is None or df.empty:
            try:
                df = ak.stock_zh_a_spot_em()
            except Exception as exc:  # noqa: BLE001
                raise SourceError(
                    f"akshare 股票列表失败: 主源 stock_info_a_code_name({primary_err}); "
                    f"备源 stock_zh_a_spot_em({exc})"
                ) from exc
        if "code" in df.columns and "name" in df.columns:
            return df[["code", "name"]].copy()
        if "代码" in df.columns and "名称" in df.columns:
            return df.rename(columns={"代码": "code", "名称": "name"})[["code", "name"]].copy()
        raise SourceError(f"akshare 股票列表列名未知: {list(df.columns)}")

    def _fetch_st_codes(self) -> set:
        """is_st 集合 = st_em ∪ stop_em 合并（任务指令；命名字典语义"仅用于识别并排除"）。

        st_em：ST/*ST 列表；stop_em：停牌列表。合并后标记为排除集。
        任一失败 → 该子集降级为空（不炸整批）。
        """
        codes: set = set()
        for name, fn in (("st_em", ak.st_em), ("stop_em", ak.stop_em)):
            try:
                df = fn()
                if df is None or df.empty or "代码" not in df.columns:
                    logger.warning("akshare %s 列名未知或为空，is_st 子集降级", name)
                    continue
                for v in df["代码"]:
                    s = str(v).strip().zfill(6)
                    if s.isdigit() and len(s) == 6:
                        codes.add(s)
            except Exception as exc:  # noqa: BLE001
                logger.warning("akshare %s 获取失败，is_st 子集降级: %s", name, exc)
        return codes

    def _sync_fetch_stock_list(self) -> List[dict]:
        df = self._stock_list_df()
        st_codes = self._fetch_st_codes()
        stocks = []
        for _, row in df.iterrows():
            code = str(row["code"]).strip().zfill(6)
            if is_north_exchange(code):
                continue
            stocks.append({
                "code": code,
                "name": str(row["name"]),
                "market": derive_market(code),
                "board": derive_board(code),
                "is_st": code in st_codes,
                "delisted": False,   # 退市标记由 DataRouter 合并（baostock）
            })
        return stocks

    # ---- 交易日历（tool_trade_date_hist_sina，探针实测 PASS）----
    def _sync_fetch_trading_calendar(self) -> List[str]:
        df = ak.tool_trade_date_hist_sina()
        if df is None or df.empty or "trade_date" not in df.columns:
            raise SourceError("akshare 交易日历为空或列名未知")
        return [str(d)[:10] for d in df["trade_date"].tolist()]

    # ══════════════════════════════════════════════════════════════════════════
    # 业绩报表 / 板块成分（PLAN Step 5a：仅 akshare，mootdx 永不参与）
    # ══════════════════════════════════════════════════════════════════════════

    def _sync_fetch_fundamentals(self, report_date: str) -> List[dict]:
        """AKShare stock_yjbb_em → [{code, revenue, net_profit}]。

        - 列名复合形式（PLAN §11.1 源码确认）：`营业总收入-营业总收入` / `净利润-净利润`；
        - 单位亿元 ×1e8 → 元（PLAN §11.1 / §2.4 fundamentals 单位=元）；
        - 单次调用返回全市场（akshare 内部自动分页抓全市场）。
        """
        df = ak.stock_yjbb_em(date=report_date)
        if df is None or df.empty:
            return []
        code_col = next((c for c in df.columns if c in ("股票代码", "代码")), None)
        if code_col is None:
            raise SourceError(f"stock_yjbb_em 列名未知: {list(df.columns)}")
        # 复合列名探底：以"营业总收入-" / "净利润-" 前缀为准（防 akshare 版本列名漂移）
        revenue_col = next((c for c in df.columns if c.startswith("营业总收入-")), None)
        net_profit_col = next((c for c in df.columns if c.startswith("净利润-")), None)
        stocks = []
        for _, row in df.iterrows():
            code = str(row[code_col]).strip().zfill(6)
            stocks.append({
                "code": code,
                "revenue": (_f(row[revenue_col]) if revenue_col else 0.0) * FUNDAMENTALS_AMOUNT_MULTIPLIER,
                "net_profit": (_f(row[net_profit_col]) if net_profit_col else 0.0) * FUNDAMENTALS_AMOUNT_MULTIPLIER,
            })
        return stocks

    def _sync_fetch_board_members(self, board_type: str) -> dict:
        """东财板块成分 → {板块名: [codes...]}（PLAN §4.8）。

        - 行业每日全量：stock_board_industry_name_em → stock_board_industry_cons_em（≈86 个）；
        - 概念每周全量：stock_board_concept_name_em → stock_board_concept_cons_em（≈400+ 个）；
        - 单板块成分失败降级跳过（不炸整批）；板块列表为空/列名未知抛 SourceError；
        - 只关注当前成分快照（覆盖写，不保留成分历史，§4.8）。
        """
        if board_type == BOARD_TYPE_INDUSTRY:
            name_fn = ak.stock_board_industry_name_em
            cons_fn = ak.stock_board_industry_cons_em
        else:
            name_fn = ak.stock_board_concept_name_em
            cons_fn = ak.stock_board_concept_cons_em
        self._board_members_degraded = False
        name_df = name_fn()
        if name_df is None or name_df.empty:
            raise SourceError(f"板块列表为空: {board_type}")
        name_col = "板块名称"
        if name_col not in name_df.columns:
            raise SourceError(f"板块列表列名未知: {list(name_df.columns)}")
        boards: dict = {}
        for _, row in name_df.iterrows():
            board_name = str(row[name_col]).strip()
            try:
                cons_df = cons_fn(symbol=board_name)
            except Exception as exc:  # noqa: BLE001
                logger.warning("板块 %s 成分获取失败: %s", board_name, exc)
                self._board_members_degraded = True
                continue
            if cons_df is None or cons_df.empty:
                continue
            codes = []
            for v in cons_df.get("代码", []):
                s = str(v).strip().zfill(6)
                if s.isdigit() and len(s) == 6:
                    codes.append(s)
            boards[board_name] = codes
        return boards

    # ══════════════════════════════════════════════════════════════════════════
    # 指数日K（PLAN Step 4，探针选源结论见类 KDoc）
    # ══════════════════════════════════════════════════════════════════════════

    def _sync_fetch_index_daily(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        """指数日K：候选一 新浪 → 兜底 腾讯 fqkline → 最后 东财（本机不可达，保留可移植性）。

        任一子源成功即返回；全部失败抛 SourceError（计入 akshare 熔断，防恒 "ok" 假象）。
        """
        errors = []

        # 候选一：新浪 stock_zh_index_daily（探针实测 PASS，全历史，含 2026-09）
        try:
            df = ak.stock_zh_index_daily(symbol=code)
            if df is not None and not df.empty:
                bars = self._sina_index_to_bars(df, code, start, end)
                if bars:
                    return bars
                errors.append("新浪: 区间内无数据")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"新浪: {exc}")
            logger.warning("指数源[新浪]失败: %s", exc)

        # 兜底：腾讯 ifzq.gtimg.cn fqkline（探针实测 PASS，范围可控，前置窗口算 prev_close）
        try:
            bars = self._tencent_index_to_bars(code, start, end)
            if bars:
                return bars
            errors.append("腾讯: 区间内无数据")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"腾讯: {exc}")
            logger.warning("指数源[腾讯]失败: %s", exc)

        # 最后：东财 index_zh_a_hist（本机 push2 主域断连 ConnectionError，探针确认不可达；
        # 保留为可移植性最后手段——其他网络可达 push2 时可用）
        try:
            df = ak.index_zh_a_hist(
                symbol=to_index_em_symbol(code),
                period="daily",
                start_date=start.replace("-", ""),
                end_date=end.replace("-", ""),
            )
            if df is not None and not df.empty:
                bars = self._em_index_to_bars(df, code, start, end)
                if bars:
                    return bars
                errors.append("东财: 区间内无数据")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"东财: {exc}")
            logger.warning("指数源[东财]失败: %s", exc)

        raise SourceError(
            "指数日K所有子源失败: " + "; ".join(errors) if errors else "指数日K无数据"
        )

    def _build_index_bars(self, code, dates, opens, highs, lows, closes, volumes, start, end):
        """统一构建指数 bar（11 字段契约）：change_percent 用 prev_close 现算，amount=0、turnover=0。

        prev_close = 上一交易日 close（含区间前一行，用于首 bar 涨跌幅）；输出按日期升序。
        """
        rows = sorted(zip(dates, opens, highs, lows, closes, volumes), key=lambda r: r[0])
        bars = []
        prev_close = None
        for d, o, h, l, c, v in rows:
            if not (start <= d <= end):
                prev_close = c
                continue
            change_pct = round(((c - prev_close) / prev_close * 100), 4) if prev_close else 0.0
            bars.append({
                "date": d,
                "code": code,                                   # 指数 code 带前缀（sh000001 特例）
                "open": o,
                "high": h,
                "low": l,
                "close": c,
                "volume": v,                                    # 股（sina 原生；tencent ×100 对齐）
                "amount": 0.0,                                  # 指数无成交额单值
                "change_percent": change_pct,                   # prev_close 现算（不复权）
                "turnover": 0.0,                                # 指数无换手率
                "prev_close": prev_close,                       # 传输字段，不落库
            })
            prev_close = c
        return bars

    def _sina_index_to_bars(self, df, code, start, end):
        """新浪 stock_zh_index_daily → 11 字段 bars（列 date/open/high/low/close/volume）。"""
        prev_window = (datetime.strptime(start, "%Y-%m-%d") - timedelta(days=30)).strftime("%Y-%m-%d")
        dates = [str(d)[:10] for d in df["date"]]
        mask = [prev_window <= d <= end for d in dates]
        return self._build_index_bars(
            code,
            [d for d, m in zip(dates, mask) if m],
            [float(v) for v, m in zip(df["open"], mask) if m],
            [float(v) for v, m in zip(df["high"], mask) if m],
            [float(v) for v, m in zip(df["low"], mask) if m],
            [float(v) for v, m in zip(df["close"], mask) if m],
            [float(v) * SINA_INDEX_VOLUME_MULTIPLIER for v, m in zip(df["volume"], mask) if m],
            start, end,
        )

    def _tencent_index_to_bars(self, code, start, end):
        """腾讯 ifzq.gtimg.cn fqkline/get → 11 字段 bars。

        day 数组元素序 [date, open, close, high, low, volume]（探针实测）。
        请求前置 90 天窗口以提供区间首 bar 的 prev_close；volume 手×100 对齐新浪股口径。
        """
        start_dt = datetime.strptime(start, "%Y-%m-%d") - timedelta(days=90)
        start_param = start_dt.strftime("%Y-%m-%d")
        url = (
            "https://ifzq.gtimg.cn/appstock/app/fqkline/get"
            f"?param={code},day,{start_param},{end},640,qfq"
        )
        resp = requests.get(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"},
            timeout=8,
        )
        resp.raise_for_status()
        j = resp.json()
        idx = (j.get("data") or {}).get(code) or {}
        day = idx.get("day") or idx.get("qfqday") or idx.get("hfqday") or []
        dates, opens, closes, highs, lows, vols = [], [], [], [], [], []
        for row in day:
            if not row or len(row) < 6:
                continue
            dates.append(str(row[0])[:10])
            opens.append(float(row[1]))
            closes.append(float(row[2]))
            highs.append(float(row[3]))
            lows.append(float(row[4]))
            vols.append(float(row[5]) * TENCENT_INDEX_VOLUME_MULTIPLIER)
        return self._build_index_bars(code, dates, opens, highs, lows, closes, vols, start, end)

    def _em_index_to_bars(self, df, code, start, end):
        """东财 index_zh_a_hist → 11 字段 bars（列 日期/开盘/收盘/最高/最低/成交量/成交额...）。"""
        dates = [str(d)[:10] for d in df["日期"]]
        return self._build_index_bars(
            code,
            dates,
            [float(v) for v in df["开盘"]],
            [float(v) for v in df["最高"]],
            [float(v) for v in df["最低"]],
            [float(v) for v in df["收盘"]],
            [float(v) for v in df["成交量"]],   # 东财指数成交量单位未探针校准，M3 一并校准
            start, end,
        )
