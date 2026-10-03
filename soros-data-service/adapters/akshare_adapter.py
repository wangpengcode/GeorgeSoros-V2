"""AKShare Adapter（PLAN §5.4 V2 版 + §11.1 接口源探查结论 + 探针实测）。

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
"""

from __future__ import annotations

import logging
from typing import List

import akshare as ak

from adapters.base import BaseAdapter, SourceError
from constants import (
    AKSHARE_VOLUME_MULTIPLIER,
    AKSHARE_AMOUNT_MULTIPLIER,
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
