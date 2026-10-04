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
from ipguard import guard

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
        # 指数日K能力仅 akshare（sina/东财/腾讯链），mootdx 永不参与；
        # 业绩报表/板块成分仅 akshare（PLAN Step 5a），mootdx/baostock 均不参与）
        self._supports_stock_list = False
        self._supports_is_st = False
        self._supports_delisted = False
        self._supports_index = False
        self._supports_fundamentals = False
        self._supports_board_members = False

    @property
    def health_state(self) -> str:
        return self.circuit_breaker.health()

    @property
    def serving_source(self) -> str:
        """本次调用实际服务的子源标签（默认=source_name；akshare 内部 failover 覆盖）。

        Router 结果 source 如实透传 → stock_history.data_source 归因对拍可区分
        （如 akshare 内部 EM→新浪切换标注 akshare-sina，varchar(20) 放得下）。
        """
        return self.source_name

    # ---- 统一守卫入口：封禁检查 → 限流 → 熔断 → 调用 → 成败登记 ----
    def _call_guarded(self, sync_fn, *args):
        # IPGuard 封禁检查（先于一切）：banned = 小时级封禁语义，与熔断 60s 分离，
        # banned 期间不出请求（否则半开探针把负载打回被封 IP，越打越封）
        if guard.is_banned(self.source_name):
            raise SourceUnavailableError(f"{self.source_name}: IP被封禁，等待换IP（ipguard）")
        if not self.rate_limiter.acquire():
            raise SourceError(f"{self.source_name}: 限流等待超时")
        if not self.circuit_breaker.allow_request():
            raise SourceUnavailableError(f"{self.source_name}: 熔断 open")
        try:
            result = sync_fn(*args)
        except ParameterError:
            raise
        except Exception as exc:  # noqa: BLE001 - 源故障需兜底计入熔断
            guard.report_failure(self.source_name, exc)  # 连接层异常计入封禁窗口
            self.circuit_breaker.record_failure()
            raise SourceError(f"{self.source_name}: {exc}") from exc
        else:
            guard.report_success(self.source_name)
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

    # ---- 业绩报表（仅 akshare，mootdx 永不参与，PLAN §11.1）----
    def fetch_fundamentals(self, report_date: str) -> List[dict]:
        if not self._supports_fundamentals:
            raise SourceUnavailableError(f"{self.source_name}: 不支持业绩报表")
        return self._call_guarded(self._sync_fetch_fundamentals, report_date)

    def _sync_fetch_fundamentals(self, report_date: str) -> List[dict]:
        raise NotImplementedError

    # ---- 板块成分（仅 akshare，mootdx 永不参与，PLAN §4.8）----
    def fetch_board_members(self, board_type: str) -> dict:
        if not self._supports_board_members:
            raise SourceUnavailableError(f"{self.source_name}: 不支持板块成分")
        return self._call_guarded(self._sync_fetch_board_members, board_type)

    def _sync_fetch_board_members(self, board_type: str) -> dict:
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

    def _ordered_for_stock(self, code: str) -> List[BaseAdapter]:
        """多源分片分压（2026-10-04 设计穿透 92 分）：按 code 稳态归属源优先，其余按 router_order 兜底。

        - 分片只调整 failover 顺序、不改能力：归属源故障自然 failover（数据完整性 > 分压）
        - 稳态映射 int(code) % len(shard_sources)：禁 hash()（PYTHONHASHSEED 随机 →
          重启换归属 → 滚动窗口自愈会互相覆盖）
        - 指数/非纯数字 code 与单分片配置（<2 源）退化为原 router_order（零风险兜底）
        """
        from config import settings

        shard = list(settings.shard_sources)
        owner = None
        if code.isdigit() and len(shard) >= 2:
            owner = self.adapters.get(shard[int(code) % len(shard)])
        if owner is None:
            return list(self._bar_order)
        return [owner] + [a for a in self._bar_order if a is not owner]

    def _fetch_stock_daily(self, code: str, start: str, end: str, adjust: str) -> Tuple[Optional[dict], List[str]]:
        errors: List[str] = []
        for adapter in self._ordered_for_stock(code):
            try:
                bars = adapter.fetch_daily_bars(code, start, end, adjust)
                if bars:
                    return {"source": adapter.serving_source, "count": len(bars), "data": bars}, []
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
                    return {"source": adapter.serving_source, "count": len(bars), "data": bars}, []
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
        - 退市集合 + ipo_date 均取 baostock query_stock_basic（status != '1' / ipoDate 列）
        - 单次 query_stock_basic 同时产出 delisted 与 ipo_date（避免双拉）
        """
        # 1) baostock query_stock_basic 一次调用 → 退市集合 + ipo_date map（失败降级，不炸整批）
        delisted: set = set()
        ipo_date_map: dict = {}
        baostock = self.adapters[SOURCE_BAOSTOCK]
        try:
            for r in baostock.fetch_stock_basic_rows():
                if r.get("type") != "1":
                    continue
                bare = r["code"].split(".")[-1]
                if r.get("status") != "1":
                    delisted.add(bare)
                if r.get("ipo_date"):
                    ipo_date_map[bare] = r["ipo_date"]
        except (SourceError, SourceUnavailableError) as exc:
            logger.warning("stock-list: 退市状态/ipo_date 获取失败，降级（%s）", exc)

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

        # 3) 合并退市标记 + ipo_date + market/board 过滤
        result: List[dict] = []
        for item in stocks:
            code = item["code"]
            if market != MARKET_ALL and item["market"] != market:
                continue
            if board != BOARD_ALL and item["board"] != board:
                continue
            item["delisted"] = code in delisted
            item["ipo_date"] = ipo_date_map.get(code)
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
                "ipo_date": r.get("ipo_date"),
            })
        return stocks

    # ---- 交易日历 ----
    def fetch_trading_calendar(self) -> List[str]:
        # 主源 AKShare tool_trade_date_hist_sina（探针实测 PASS，一次全量）
        return self.adapters[SOURCE_AKSHARE].fetch_trading_calendar()

    # ---- 业绩报表（仅 akshare，mootdx 永不参与，PLAN §11.1）----
    def fetch_fundamentals(self, report_date: str) -> List[dict]:
        """业绩报表：仅 akshare（stock_yjbb_em）。mootdx/baostock 均无此能力。"""
        return self.adapters[SOURCE_AKSHARE].fetch_fundamentals(report_date)

    # ---- 板块成分（仅 akshare，mootdx 永不参与，PLAN §4.8）----
    def fetch_board_members(self, board_type: str) -> dict:
        """板块成分：仅 akshare（stock_board_industry/concept_*_em 链）。mootdx 永不参与。

        返回 {boards:{板块名:[codes]}, degraded:bool}——degraded 标记任一板块成分拉取失败
        （单板块失败降级跳过，消费侧据此跳过清空，防止部分源故障误清全库）。
        """
        adapter = self.adapters[SOURCE_AKSHARE]
        boards = adapter.fetch_board_members(board_type)
        return {
            "boards": boards,
            "degraded": bool(getattr(adapter, "_board_members_degraded", False)),
        }

    # ---- 双源交叉验证（baostock + akshare，mootdx 永不参与，PLAN §11.2）----
    def fetch_daily_bars_cross(
        self, codes: List[str], start: str, end: str, adjust: str
    ) -> Tuple[Dict[str, dict], List[dict]]:
        """§11.2 双源交叉验证：每 code 独立从 baostock/akshare 各抓一次，mootdx 永不参与。

        返回 (results, failed)：
        - results = {code: {source: {source, count, data[], error}}}；源无数据也占位
          {source, count:0, data:[], error:None}（方便 Kotlin 侧按共同日期对齐）；
          源故障时 error 带失败文案（部署穿透 2026-10-04：静默 0 行无法区分「无数据」与「故障」）
        - failed = [{code, reason}] 仅双源均 ParameterError（理论不发生，跨源参数一致）
        """
        sources = [self.adapters[SOURCE_BAOSTOCK], self.adapters[SOURCE_AKSHARE]]
        results: Dict[str, dict] = {}
        failed: List[dict] = []
        for code in codes:
            per_source: Dict[str, dict] = {}
            errors: List[str] = []
            for adapter in sources:
                entry = {"source": adapter.source_name, "count": 0, "data": [], "error": None}
                try:
                    bars = adapter.fetch_daily_bars(code, start, end, adjust)
                    entry["count"] = len(bars)
                    entry["data"] = bars
                except SourceUnavailableError as exc:
                    errors.append(f"{adapter.source_name}: {exc}")
                    entry["error"] = str(exc)
                except SourceError as exc:
                    errors.append(f"{adapter.source_name}: {exc}")
                    entry["error"] = str(exc)
                except ParameterError as exc:
                    errors.append(f"{adapter.source_name}: 参数错误 ({exc})")
                    entry["error"] = f"参数错误 ({exc})"
                    per_source[adapter.source_name] = entry
                    break
                per_source[adapter.source_name] = entry
            if per_source:
                results[code] = per_source
            else:
                failed.append({"code": code, "reason": "; ".join(errors) if errors else "双源均无数据"})
        return results, failed
