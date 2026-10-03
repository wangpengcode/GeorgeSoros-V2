"""Adapter 基类 + 多源 failover Router（PLAN §11.1）。

- BaseAdapter：统一封装 限流 → 熔断 → 调用源实现 → 熔断成败登记
- 异常分类（防御性设计，任务要求「网络调用全部 try/except 分类」）：
    * ParameterError      —— 参数错误（非法代码/日期），跨源一致，不计熔断失败，直接抛
    * SourceError         —— 数据源故障（网络/API 异常/超时），计入熔断失败
    * SourceUnavailableError —— 熔断 open 或该源不支持该能力，不计熔断失败（未发起请求）
- DataRouter：Router 顺序 baostock → akshare → mootdx（PLAN §11.1）；
  stock-list/is_st/退市状态永不走 mootdx。
"""

from __future__ import annotations

import abc
import logging
from typing import Dict, List, Optional, Sequence, Tuple

from constants import (
    SOURCE_AKSHARE,
    SOURCE_BAOSTOCK,
    SOURCE_MOOTDX,
    DATA_SOURCES,
    BOARD_ALL,
    MARKET_ALL,
    is_north_exchange,
    is_index_code,
    derive_market,
    derive_board,
)

logger = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────────────────────
# 异常分类
# ──────────────────────────────────────────────────────────────────────────────
class AdapterError(Exception):
    """适配器错误基类。"""


class ParameterError(AdapterError):
    """参数错误（非法代码/日期/复权方式）。跨源一致，不计熔断失败。"""


class SourceError(AdapterError):
    """数据源故障（网络/API 异常/超时/登录失败）。计入熔断失败。"""


class SourceUnavailableError(AdapterError):
    """源不可用（熔断 open）或源不支持该能力。不计熔断失败。"""


# ──────────────────────────────────────────────────────────────────────────────
# BaseAdapter
# ──────────────────────────────────────────────────────────────────────────────
class BaseAdapter(abc.ABC):
    source_name = "base"

    def __init__(self, circuit_breaker, rate_limiter):
        self.circuit_breaker = circuit_breaker
        self.rate_limiter = rate_limiter
        # 能力标记（Router 决策依据，PLAN §11.1：stock-list/is_st/退市永不走 mootdx；
        # 指数日K能力仅 akshare（sina/东财/腾讯链），mootdx 永不参与）
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        self._supports_index = False

    @property
    def health_state(self) -> str:
        return self.circuit_breaker.health()

    # ---- 统一守卫入口：限流 → 熔断 → 调用 → 成败登记 ----
    def _call_guarded(self, sync_fn, *args):
        if not self.rate_limiter.acquire():
            raise SourceError(f"{self.source_name}: 限流等待超时")
        if not self.circuit_breaker.allow_request():
            raise SourceUnavailableError(f"{self.source_name}: 熔断 open")
        try:
            result = sync_fn(*args)
        except ParameterError:
            raise
        except Exception as exc:  # noqa: BLE001 - 源故障需兜底计入熔断
            self.circuit_breaker.record_failure()
            raise SourceError(f"{self.source_name}: {exc}") from exc
        else:
            self.circuit_breaker.record_success()
            return result

    # ---- 日 K 线 ----
    def fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        return self._call_guarded(self._sync_fetch_daily_bars, code, start, end, adjust)

    @abc.abstractmethod
    def _sync_fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        ...

    # ---- 股票列表（仅 baostock/akshare，mootdx 永不参与）----
    def fetch_stock_list(self) -> List[dict]:
        if not self._supports_stock_list:
            raise SourceUnavailableError(f"{self.source_name}: 不支持 stock-list（仅 baostock/akshare）")
        return self._call_guarded(self._sync_fetch_stock_list)

    def _sync_fetch_stock_list(self) -> List[dict]:
        raise NotImplementedError

    # ---- 退市状态（仅 baostock，mootdx 永不参与）----
    def fetch_delisted_codes(self) -> set:
        if not self._supports_delisted:
            raise SourceUnavailableError(f"{self.source_name}: 不支持退市状态")
        return self._call_guarded(self._sync_fetch_delisted_codes)

    def _sync_fetch_delisted_codes(self) -> set:
        raise NotImplementedError

    # ---- 指数日K（PLAN Step 4：仅 akshare，mootdx 永不参与）----
    def fetch_index_daily(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        if not self._supports_index:
            raise SourceUnavailableError(f"{self.source_name}: 不支持指数日K")
        return self._call_guarded(self._sync_fetch_index_daily, code, start, end, adjust)

    def _sync_fetch_index_daily(self, code: str, start: str, end: str, adjust: str) -> List[dict]:
        raise NotImplementedError

    # ---- 交易日历 ----
    def fetch_trading_calendar(self) -> List[str]:
        return self._call_guarded(self._sync_fetch_trading_calendar)

    def _sync_fetch_trading_calendar(self) -> List[str]:
        raise NotImplementedError


# ──────────────────────────────────────────────────────────────────────────────
# DataRouter（failover Router，PLAN §11.1）
# ──────────────────────────────────────────────────────────────────────────────
class DataRouter:
    """多源 failover Router。

    - 股票日K顺序：baostock → akshare → mootdx，首个成功即返回
    - 指数日K：仅 akshare（内部 sina→腾讯→东财 链，探针选源结论见 akshare_adapter KDoc），
      mootdx 永不参与（PLAN Step 4）；baostock 指数K未做探针验证，暂不启用（未来增强可加）
    - stock-list / is_st / 退市状态：只认 baostock/akshare，mootdx 永不参与
    - 单源失败不炸整批：单股失败进 failed[]
    """

    def __init__(self, adapters: Sequence[BaseAdapter]):
        self.adapters: Dict[str, BaseAdapter] = {a.source_name: a for a in adapters}
        missing = [s for s in DATA_SOURCES if s not in self.adapters]
        if missing:
            raise ValueError(f"缺少数据源 adapter: {missing}")
        # 股票日线 failover 顺序
        self._bar_order = [self.adapters[n] for n in self._resolve_order()]
        # 指数日K只走 akshare（mootdx 永不参与）
        self._index_order = [self.adapters[SOURCE_AKSHARE]]
        # stock-list 只走 baostock/akshare
        self._list_order = [self.adapters[n] for n in (SOURCE_BAOSTOCK, SOURCE_AKSHARE) if n in self.adapters]

    def _resolve_order(self) -> list:
        from config import settings

        order = list(settings.router_order)
        valid = {SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX}
        order = [n for n in order if n in valid]
        # 兜底：确保三源都在（防止配置缺漏）
        for n in (SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX):
            if n not in order:
                order.append(n)
        return order

    # ---- daily-bars/batch 单股 ----
    def fetch_daily_bars(self, code: str, start: str, end: str, adjust: str) -> Tuple[Optional[dict], List[str]]:
        """返回 (result, errors)。result 为 {source, count, data[]} 或 None。

        指数 code（sh000001）走指数路径（仅 akshare，mootdx 永不参与）。
        """
        if is_index_code(code):
            return self._fetch_index_daily(code, start, end, adjust)
        return self._fetch_stock_daily(code, start, end, adjust)

    def _fetch_stock_daily(self, code: str, start: str, end: str, adjust: str) -> Tuple[Optional[dict], List[str]]:
        errors: List[str] = []
        for adapter in self._bar_order:
            try:
                bars = adapter.fetch_daily_bars(code, start, end, adjust)
                if bars:
                    return {"source": adapter.source_name, "count": len(bars), "data": bars}, []
                errors.append(f"{adapter.source_name}: 空结果")
            except SourceUnavailableError as exc:
                errors.append(f"{adapter.source_name}: {exc}")
            except SourceError as exc:
                errors.append(f"{adapter.source_name}: {exc}")
            except ParameterError as exc:
                errors.append(f"{adapter.source_name}: 参数错误 ({exc})")
                break  # 参数错误跨源一致，无需继续尝试
        reason = "All sources failed: " + "; ".join(errors) if errors else "All sources failed"
        return None, [reason]

    def _fetch_index_daily(self, code: str, start: str, end: str, adjust: str) -> Tuple[Optional[dict], List[str]]:
        """指数日K路径：仅 akshare（内部 sina→腾讯→东财 链），mootdx 永不参与（PLAN Step 4）。"""
        errors: List[str] = []
        for adapter in self._index_order:
            try:
                bars = adapter.fetch_index_daily(code, start, end, adjust)
                if bars:
                    return {"source": adapter.source_name, "count": len(bars), "data": bars}, []
                errors.append(f"{adapter.source_name}: 空结果")
            except SourceUnavailableError as exc:
                errors.append(f"{adapter.source_name}: {exc}")
            except SourceError as exc:
                errors.append(f"{adapter.source_name}: {exc}")
            except ParameterError as exc:
                errors.append(f"{adapter.source_name}: 参数错误 ({exc})")
                break  # 参数错误跨源一致，无需继续尝试
        reason = "All index sources failed: " + "; ".join(errors) if errors else "All index sources failed"
        return None, [reason]

    # ---- stock-list ----
    def fetch_stock_list(self, market: str = MARKET_ALL, board: str = BOARD_ALL) -> List[dict]:
        """只走 baostock/akshare（mootdx 永不参与，PLAN §11.1）。

        - 列表主源 AKShare（内部 stock_info_a_code_name → stock_zh_a_spot_em 备源）
        - 退市集合 baostock query_stock_basic（status != '1'）
        """
        # 1) 退市状态（baostock；失败则降级为全 False，不炸整批）
        delisted: set = set()
        baostock = self.adapters[SOURCE_BAOSTOCK]
        try:
            delisted = baostock.fetch_delisted_codes()
        except (SourceError, SourceUnavailableError) as exc:
            logger.warning("stock-list: 退市状态获取失败，delisted 全部置 False（%s）", exc)

        # 2) 股票列表（AKShare 主源；若 akshare 整体失败则尝试 baostock 兜底）
        stocks: List[dict] = []
        akshare = self.adapters[SOURCE_AKSHARE]
        try:
            stocks = akshare.fetch_stock_list()
        except (SourceError, SourceUnavailableError) as exc:
            logger.warning("stock-list: akshare 失败，尝试 baostock 兜底（%s）", exc)
            try:
                stocks = self._baostock_stock_list(baostock)
            except Exception as inner:  # noqa: BLE001
                raise SourceError(f"stock-list 所有源失败: akshare({exc}); baostock({inner})") from inner

        # 3) 合并退市标记 + market/board 过滤
        result: List[dict] = []
        for item in stocks:
            code = item["code"]
            if market != MARKET_ALL and item["market"] != market:
                continue
            if board != BOARD_ALL and item["board"] != board:
                continue
            item["delisted"] = code in delisted
            result.append(item)
        return result

    def _baostock_stock_list(self, baostock: BaseAdapter) -> List[dict]:
        """baostock query_stock_basic 兜底列表（type=='1' 股票，status=='1' 上市中）。"""
        rows = baostock.fetch_stock_basic_rows()
        stocks = []
        for r in rows:
            code = r["code"]                      # "sh.600000"
            bs_type, status = r.get("type"), r.get("status")
            if bs_type != "1":                    # 只收股票（type==1），剔除指数/基金/可转债
                continue
            bare = code.split(".")[-1]
            if is_north_exchange(bare):
                continue
            stocks.append({
                "code": bare,
                "name": r["code_name"],
                "market": derive_market(bare),
                "board": derive_board(bare),
                "is_st": False,
                "delisted": status != "1",
            })
        return stocks

    # ---- 交易日历 ----
    def fetch_trading_calendar(self) -> List[str]:
        # 主源 AKShare tool_trade_date_hist_sina（探针实测 PASS，一次全量）
        return self.adapters[SOURCE_AKSHARE].fetch_trading_calendar()
