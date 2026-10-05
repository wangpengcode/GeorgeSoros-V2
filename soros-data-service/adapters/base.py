"""Adapter 基类 + 多源均分流量 Router（PLAN §11.1；2026-10-05 均分流量定稿重构）。

- BaseAdapter：统一封装 限流 → 熔断 → 调用源实现 → 熔断成败登记
- 异常分类（防御性设计，任务要求「网络调用全部 try/except 分类」）：
    * ParameterError      —— 参数错误（非法代码/日期），跨源一致，不计熔断失败，直接抛
    * SourceError         —— 数据源故障（网络/API 异常/超时），计入熔断失败
    * SourceUnavailableError —— 熔断 open 或该源不支持该能力，不计熔断失败（未发起请求）
- DataRouter 均分流量语义（用户定稿：多源=均分流量防封禁，不是逐个烧穿源）：
    * 每票由「健康源轮转」分配一个源，单票单源，绝不 inline failover；
      分配源故障 → 本轮放弃，等 stock_info 下一轮扫描自然重试（届时可能换源）
    * 健康过滤 _healthy_candidates：封禁（IPGuard banned）/ 熔断 open / 驱逐冷却中
      （连续 2 次 SourceError → 300s 冷却）/ 复权能力缺失 → 移出候选；half_open 保留
    * 空票偏好（K=2 verified-empty 配套）：同 code 已投过空票的源优先排除，换源验证空；
      全部候选都投过票 → 回退全候选；取到数据即清空票
    * 空结果 = 源确认无数据的正面证据 → 返回 count=0 占位（带 empty_sources），
      非 failed；真实故障才 failed
- 可选源（yahoo/tencent/sse）：Router 缺席不报错（missing-check 只硬查核心三源），
  注册后加入轮转候选参与均分。
"""

from __future__ import annotations

import abc
import logging
import threading
import time
from datetime import datetime
from typing import Dict, List, Optional, Sequence, Tuple

from circuit_breaker import STATE_OPEN
from constants import (
    SOURCE_AKSHARE,
    SOURCE_BAOSTOCK,
    SOURCE_MOOTDX,
    SOURCE_YAHOO,
    SOURCE_TENCENT,
    SOURCE_SSE,
    DATA_SOURCES,
    BOARD_ALL,
    MARKET_ALL,
    ADJUST_HFQ,
    ADJUST_QFQ,
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


class CapabilityError(ParameterError):
    """复权能力型参数错误：该源不支持此复权口径，但其他源可能支持。

    failover 对它应继续尝试后续源（区别于非法代码/日期等真·跨源一致的
    参数错误）；不计熔断失败（verifier MEDIUM-1 修复）。
    """


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
        # 同源在途请求互斥锁（2026-10-04 改动 2 设计定稿）：任何时刻同一源最多 1 个在途请求——
        # baostock 模块级单例 socket 并发会串包；failover 跨线程碰同一源时排队。
        # 锁用在 _call_guarded 包裹「实际调 adapter 方法」那一小段（含登录/查询），
        # TokenBucket 限流等待在锁外（慢等 30s 不占着别的线程的源）。
        # DataRouter 构造时按 source_name 把各源锁登记进 _adapter_locks（key 与台账一致），
        # 独立使用（测试/桩）时每实例一把锁，天然互斥。
        self._call_lock = threading.Lock()

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
        # 锁内：仅「实际调 adapter 方法」这一小段（含登录/查询）；锁外：封禁检查/限流等待/熔断检查。
        # 顺序依据（2026-10-04 改动 2 设计定稿）：先桶后调 → 锁包住真正发请求处，TokenBucket
        # 等待在锁外——限流慢等（最多 30s）不占锁，其它线程仍可排队碰同一源。
        # 锁=源级（BaseAdapter._call_lock，DataRouter._adapter_locks 登记同一对象）：
        #   baostock 模块级单例 socket 并发会串包；failover 跨线程碰同一源时排队。
        with self._call_lock:
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
# DataRouter（均分流量 Router，2026-10-05 定稿；原 failover 语义废弃）
# ──────────────────────────────────────────────────────────────────────────────
class DataRouter:
    """多源均分流量 Router。

    - 股票日K：每票由健康源轮转分配一个源，单票单源，绝不 inline failover（防烧源铁律）；
      分配源故障 → 本轮放弃，等上游（stock_info 水位扫描）下一轮自然重试
    - 指数日K：仅 akshare（内部 sina→腾讯→东财 链，探针选源结论见 akshare_adapter KDoc），
      mootdx 永不参与（PLAN Step 4）
    - stock-list / is_st / 退市状态：只认 baostock/akshare，mootdx 永不参与
    - 单票失败/无健康源不炸整批：进 failed[]（batch 端点），本轮不发起外部请求
    - daily-bars/batch 分组并行（router.py）：每票先 _assign_source，按分配源分组并行
      （并行只在源之间），组内逐只串行（防封禁铁律）；指数 code 归串行尾批
    - 同源在途互斥（2026-10-04 改动 2）：_adapter_locks 按 source_name 登记各源
      adapter._call_lock，_call_guarded 锁住实际请求（TokenBucket 等待在锁外）
    """

    # 源级驱逐参数（均分流量定稿）：连续 2 次真实 SourceError → 移出轮换 300s。
    # 阈值 2 远快于熔断 5 次——failover 时代靠熔断太慢（烧穿下游），轮转时代必须
    # 快速把死源移出候选，否则轮转每 N 票撞它一次。
    EVICTION_FAILURES = 2
    EVICTION_COOLDOWN_SECONDS = 300.0

    def __init__(self, adapters: Sequence[BaseAdapter]):
        self.adapters: Dict[str, BaseAdapter] = {a.source_name: a for a in adapters}
        # 核心三源硬依赖；yahoo/tencent/sse 可选源（缺席不报错——分压/校准增强，非正确性依赖）
        missing = [s for s in DATA_SOURCES if s not in self.adapters]
        if missing:
            raise ValueError(f"缺少数据源 adapter: {missing}")
        # 轮转候选基序（router_order 过滤已注册源；指数/stock-list 子序不变）
        self._bar_order = [self.adapters[n] for n in self._resolve_order()]
        # 指数日K只走 akshare（mootdx 永不参与）
        self._index_order = [self.adapters[SOURCE_AKSHARE]]
        # stock-list 只走 baostock/akshare
        self._list_order = [self.adapters[n] for n in (SOURCE_BAOSTOCK, SOURCE_AKSHARE) if n in self.adapters]
        # 渠道台账（/channels 观测）：{source: {last_success_at, last_failure_at}}，
        # 进程态重启即清——与 IPGuard「banned 不持久化」同一设计哲学（docstring 见 _mark_*）
        self._source_ledger: Dict[str, dict] = {}
        # ── 均分流量轮转状态（进程态，重启即清；统一 _state_lock 保护）──
        self._state_lock = threading.Lock()
        self._rotation_cursor = 0                  # 全局轮转游标（均分，非 code 归属）
        self._evicted_until: Dict[str, float] = {} # {source: monotonic 冷却到期时刻}
        self._fail_streak: Dict[str, int] = {}     # {source: 连续 SourceError 次数}
        self._empty_votes: Dict[str, set] = {}     # {code: {source...}} 跨轮空票（K=2 判据）
        self._channel_counters: Dict[str, dict] = {}  # {source: {total_calls, empty_results, failures}}
        # 可注入时钟（测试替换实例属性，不碰全局 time.monotonic）
        self._clock = time.monotonic
        # 同源在途请求互斥锁（改动 2）：{source_name: threading.Lock}，按 adapter.source_name
        # 惰性登记（key 与台账 key 一致）；锁本体 = 各源 adapter._call_lock（每源唯一实例 → 每源
        # 一把锁），_call_guarded 用它包裹实际请求，TokenBucket 等待在锁外。
        self._adapter_locks: Dict[str, threading.Lock] = {}
        for a in self.adapters.values():
            self._lock_for(a.source_name)

    def _lock_for(self, source: str) -> threading.Lock:
        """按 source_name 惰性取源级调用锁（不存在则登记）。key=adapter.source_name（与台账 key 一致）。

        锁本体是 BaseAdapter._call_lock（DataRouter 每源唯一实例 → 每源一把锁，_call_guarded
        包裹实际请求用同一对象）；本方法只做登记/取用，供观测与外部协调取同一锁，不在此处加锁
        （避免与 _call_guarded 内同一把锁二次获取死锁——threading.Lock 非重入）。
        """
        lock = self._adapter_locks.get(source)
        if lock is None:
            adapter = self.adapters.get(source)
            lock = adapter._call_lock if adapter is not None else threading.Lock()
            self._adapter_locks[source] = lock
        return lock

    def _resolve_order(self) -> list:
        from config import settings

        order = list(settings.router_order)
        # 合法源域：核心三源 + 可选源（yahoo/tencent/sse——注册即进序尾，缺席不报错）
        valid = {
            SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX,
            SOURCE_YAHOO, SOURCE_TENCENT, SOURCE_SSE,
        }
        order = [n for n in order if n in valid and n in self.adapters]
        # 兜底：确保核心三源都在（防止配置缺漏）；可选源仅在已注册时追加到序尾
        for n in (SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX,
                  SOURCE_YAHOO, SOURCE_TENCENT, SOURCE_SSE):
            if n not in order and n in self.adapters:
                order.append(n)
        return order

    # ---- 渠道台账（/channels 观测）----
    # 语义（与 /channels 响应契约对齐）：
    # - last_success_at：该源最近一次「成功取到数据」（日K/指数=非空结果；其余端点=调用无异常）
    # - last_failure_at：该源最近一次「真实请求已发出但源故障」（SourceError）
    # banned/open（SourceUnavailableError，未发请求）与 ParameterError（调用方参数错误）
    #   不计入台账——前者归封禁/熔断状态展示（/channels banned/breaker 字段），后者非源故障。
    # 进程态：重启即清（与 IPGuard「banned 不持久化」同一设计哲学），docstring 注明。
    def _mark_success(self, source: str) -> None:
        """记录源最近一次成功时间。线程安全：FastAPI 线程池并发写，
        dict.setdefault + 赋值在 GIL 下原子；观测读数，允许 last-write-wins 良性竞态。"""
        entry = self._source_ledger.setdefault(
            source, {"last_success_at": None, "last_failure_at": None}
        )
        entry["last_success_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    def _mark_failure(self, source: str) -> None:
        """记录源最近一次失败时间（SourceError=真实请求已发出且源故障）。"""
        entry = self._source_ledger.setdefault(
            source, {"last_success_at": None, "last_failure_at": None}
        )
        entry["last_failure_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    def channel_ledger(self) -> Dict[str, dict]:
        """渠道台账只读副本（/channels 聚合读取）：{source: {last_success_at, last_failure_at}}。

        返回浅拷贝，避免端点侧与写侧共享可变 dict。
        先做 C 级原子快照（dict()）再拷贝内部 dict：避免端点并发 _mark_* 的
        setdefault 在 Python 层迭代期间新增 key 触发 "dictionary changed size
        during iteration"（verifier M1）。
        """
        return {k: dict(v) for k, v in dict(self._source_ledger).items()}

    # ---- daily-bars/batch 单股 ----
    def fetch_daily_bars(
        self, code: str, start: str, end: str, adjust: str, source: Optional[str] = None
    ) -> Tuple[Optional[dict], List[str]]:
        """返回 (result, errors)。result 为 {source, count, data[], empty_sources?} 或 None。

        - 指数 code（sh000001）走指数路径（仅 akshare，mootdx 永不参与）
        - source=None：健康源轮转分配（单票单源）；source 指定：钉死该源（batch 分组回填用）
        - 分配源空结果 → count=0 占位（带 empty_sources 空票）非 None；真实故障 → None
        """
        if is_index_code(code):
            return self._fetch_index_daily(code, start, end, adjust)
        return self._fetch_stock_daily(code, start, end, adjust, source=source)

    # ── 均分流量轮转（2026-10-05 定稿）──
    def _healthy_candidates(self, adjust: str) -> List[BaseAdapter]:
        """健康候选 = router_order ∩ （未封禁 ∩ 非驱逐冷却 ∩ 非熔断 open ∩ 支持该复权口径）。

        - banned / 熔断 open：未发请求即排除（banned 期间出请求=把负载打回被封 IP）
        - 驱逐冷却（连续 2 次 SourceError → 300s）：快速把死源移出轮换，不每 N 票撞它
        - 复权能力（_supports_adjust_qfq/hfq，声明缺失视为支持）：能力型排除在分配时完成，
          不到得了调用层（failover 时代靠 CapabilityError 逐源试，轮转时代无 failover 可言）
        - half_open 保留：allow_request 单探针放行，成功即闭合——排除它反而堵死自愈入口
        """
        now = self._clock()
        candidates: List[BaseAdapter] = []
        for adapter in self._bar_order:
            name = adapter.source_name
            if guard.is_banned(name):
                continue
            evicted_until = self._evicted_until.get(name)
            if evicted_until is not None and now < evicted_until:
                continue
            if adapter.circuit_breaker.state == STATE_OPEN:
                continue
            if adjust in (ADJUST_QFQ, ADJUST_HFQ) and not getattr(
                adapter, f"_supports_adjust_{adjust}", True
            ):
                continue
            candidates.append(adapter)
        return candidates

    def _assign_source(self, adjust: str, code: str) -> Optional[BaseAdapter]:
        """健康源轮转分配：全局游标取模候选数（均分流量；此取模是轮转游标，非 code 归属）。

        空票偏好（K=2 verified-empty 配套）：同 code 已投过空票的源在还有其他候选时
        优先排除（换源验证空，不再撞已确认无数据的源）；全部候选都投过票 → 回退全候选。
        无健康候选 → None（调用方本轮放弃，零外部请求）。
        """
        candidates = self._healthy_candidates(adjust)
        if not candidates:
            return None
        with self._state_lock:
            votes = self._empty_votes.get(code)
            if votes and len(candidates) > 1:
                preferred = [a for a in candidates if a.source_name not in votes]
                if preferred:
                    candidates = preferred
            idx = self._rotation_cursor % len(candidates)
            self._rotation_cursor += 1
        return candidates[idx]

    def _record_call_failure(self, source: str) -> None:
        """源级驱逐计数：连续 SourceError 达阈值 → 移出轮换 EVICTION_COOLDOWN_SECONDS。"""
        with self._state_lock:
            entry = self._channel_counters.setdefault(
                source, {"total_calls": 0, "empty_results": 0, "failures": 0}
            )
            entry["failures"] += 1
            streak = self._fail_streak.get(source, 0) + 1
            self._fail_streak[source] = streak
            if streak >= self.EVICTION_FAILURES:
                self._evicted_until[source] = self._clock() + self.EVICTION_COOLDOWN_SECONDS
                self._fail_streak[source] = 0  # 冷却到期后重新计数
                logger.warning(
                    "源 %s 连续 %d 次故障，移出轮换冷却 %ds（恢复后自动回归候选）",
                    source, streak, int(self.EVICTION_COOLDOWN_SECONDS),
                )

    def _record_call_success(self, source: str) -> None:
        """成功清零连续失败计数（非累计 2 次即驱逐；成功是最强的健康证据）。"""
        with self._state_lock:
            self._fail_streak[source] = 0

    def _count_call(self, source: str) -> None:
        """total_calls：真实请求发出才计数（banned/驱逐/open 未发请求不计）。"""
        with self._state_lock:
            entry = self._channel_counters.setdefault(
                source, {"total_calls": 0, "empty_results": 0, "failures": 0}
            )
            entry["total_calls"] += 1

    def _count_empty(self, source: str) -> None:
        with self._state_lock:
            entry = self._channel_counters.setdefault(
                source, {"total_calls": 0, "empty_results": 0, "failures": 0}
            )
            entry["empty_results"] += 1

    def channel_counters(self) -> Dict[str, dict]:
        """渠道调用计数只读副本（/channels 聚合读取）：{source: {total_calls, empty_results, failures}}。"""
        with self._state_lock:
            return {k: dict(v) for k, v in self._channel_counters.items()}

    def _fetch_stock_daily(
        self, code: str, start: str, end: str, adjust: str, source: Optional[str] = None
    ) -> Tuple[Optional[dict], List[str]]:
        """单票单源拉取（均分流量定稿，无 failover）。

        - source=None → _assign_source 轮转分配；分配失败（无健康源）→ 本轮放弃（零请求）
        - 分配源空结果 → count=0 占位 {source, count:0, data:[], empty_sources:[...]}（非 None）：
          空结果 = 源确认无数据的正面证据（K=2 verified-empty 的票），绝不冒充失败
        - 分配源真实故障（SourceError）→ 计驱逐 + 本轮放弃（None）；失败票等上游下轮重扫
        - 显式 source 未注册 → (None, ["{source}: 源未注册"])
        """
        if source is not None:
            adapter = self.adapters.get(source)
            if adapter is None:
                return None, [f"{source}: 源未注册"]
        else:
            adapter = self._assign_source(adjust, code)
            if adapter is None:
                return None, ["无健康源，本轮不发起外部调用"]

        name = adapter.source_name
        errors: List[str] = []
        try:
            self._count_call(name)
            bars = adapter.fetch_daily_bars(code, start, end, adjust)
        except SourceUnavailableError as exc:
            errors.append(f"{name}: {exc}")     # banned/open：未发请求，归封禁/熔断展示
        except SourceError as exc:
            self._mark_failure(name)
            self._record_call_failure(name)
            errors.append(f"{name}: {exc}")
        except CapabilityError as exc:
            errors.append(f"{name}: 不支持该复权 ({exc})")  # 声明缺失兜底，不计台账/驱逐
        except ParameterError as exc:
            errors.append(f"{name}: 参数错误 ({exc})")
        else:
            if bars:
                self._mark_success(name)        # 非空结果=成功取到数据
                self._record_call_success(name)
                with self._state_lock:
                    self._empty_votes.pop(code, None)  # 取到数据，清空票
                return {"source": adapter.serving_source, "count": len(bars), "data": bars}, []
            # 空结果：记空票（跨轮按源累计）+ 占位（K=2 verified-empty 的判据载体）
            self._count_empty(name)
            with self._state_lock:
                votes = self._empty_votes.setdefault(code, set())
                votes.add(name)
                empty_sources = sorted(votes)
            logger.info(
                "fetch_daily_bars 空结果: code=%s window=[%s,%s] adjust=%s source=%s empty_sources=%s",
                code, start, end, adjust, adapter.serving_source, empty_sources,
            )
            return {
                "source": adapter.serving_source,
                "count": 0,
                "data": [],
                "empty_sources": empty_sources,
            }, []
        return None, errors

    def _fetch_index_daily(self, code: str, start: str, end: str, adjust: str) -> Tuple[Optional[dict], List[str]]:
        """指数日K路径：仅 akshare（内部 sina→腾讯→东财 链），mootdx 永不参与（PLAN Step 4）。"""
        errors: List[str] = []
        for adapter in self._index_order:
            try:
                bars = adapter.fetch_index_daily(code, start, end, adjust)
                if bars:
                    self._mark_success(adapter.source_name)  # 非空结果=成功取到数据
                    return {"source": adapter.serving_source, "count": len(bars), "data": bars}, []
                errors.append(f"{adapter.source_name}: 空结果")
            except SourceUnavailableError as exc:
                errors.append(f"{adapter.source_name}: {exc}")
            except SourceError as exc:
                self._mark_failure(adapter.source_name)
                errors.append(f"{adapter.source_name}: {exc}")
            except CapabilityError as exc:
                errors.append(f"{adapter.source_name}: 不支持该复权 ({exc})")
                # 能力型缺失跨源不一致：后续源可能支持，继续 failover（verifier MEDIUM-1）
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
        except SourceError as exc:
            self._mark_failure(SOURCE_BAOSTOCK)
            logger.warning("stock-list: 退市状态/ipo_date 获取失败，降级（%s）", exc)
        except SourceUnavailableError as exc:  # banned/open：未发请求，归封禁/熔断展示
            logger.warning("stock-list: 退市状态/ipo_date 获取失败，降级（%s）", exc)
        else:
            self._mark_success(SOURCE_BAOSTOCK)

        # 2) 股票列表（AKShare 主源；若 akshare 整体失败则尝试 baostock 兜底）
        stocks: List[dict] = []
        akshare = self.adapters[SOURCE_AKSHARE]
        try:
            stocks = akshare.fetch_stock_list()
        except (SourceError, SourceUnavailableError) as exc:
            logger.warning("stock-list: akshare 失败，尝试 baostock 兜底（%s）", exc)
            if isinstance(exc, SourceError):
                self._mark_failure(SOURCE_AKSHARE)  # 真实请求已发出且故障才计失败
            try:
                stocks = self._baostock_stock_list(baostock)
                self._mark_success(SOURCE_BAOSTOCK)
            except Exception as inner:  # noqa: BLE001
                self._mark_failure(SOURCE_BAOSTOCK)
                raise SourceError(f"stock-list 所有源失败: akshare({exc}); baostock({inner})") from inner
        else:
            self._mark_success(SOURCE_AKSHARE)

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
        adapter = self.adapters[SOURCE_AKSHARE]
        try:
            dates = adapter.fetch_trading_calendar()
        except SourceError as exc:
            self._mark_failure(SOURCE_AKSHARE)
            raise
        self._mark_success(SOURCE_AKSHARE)  # 空列表是源的正常答复（非故障）
        return dates

    # ---- 业绩报表（仅 akshare，mootdx 永不参与，PLAN §11.1）----
    def fetch_fundamentals(self, report_date: str) -> List[dict]:
        """业绩报表：仅 akshare（stock_yjbb_em）。mootdx/baostock 均无此能力。"""
        adapter = self.adapters[SOURCE_AKSHARE]
        try:
            result = adapter.fetch_fundamentals(report_date)
        except SourceError as exc:
            self._mark_failure(SOURCE_AKSHARE)
            raise
        self._mark_success(SOURCE_AKSHARE)
        return result

    # ---- 板块成分（仅 akshare，mootdx 永不参与，PLAN §4.8）----
    def fetch_board_members(self, board_type: str) -> dict:
        """板块成分：仅 akshare（stock_board_industry/concept_*_em 链）。mootdx 永不参与。

        返回 {boards:{板块名:[codes]}, degraded:bool}——degraded 标记任一板块成分拉取失败
        （单板块失败降级跳过，消费侧据此跳过清空，防止部分源故障误清全库）。
        """
        adapter = self.adapters[SOURCE_AKSHARE]
        try:
            boards = adapter.fetch_board_members(board_type)
        except SourceError as exc:
            self._mark_failure(SOURCE_AKSHARE)
            raise
        self._mark_success(SOURCE_AKSHARE)
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
                    self._mark_success(adapter.source_name)  # 空 data=「无数据」合法占位（§11.2），非故障
                except SourceUnavailableError as exc:
                    errors.append(f"{adapter.source_name}: {exc}")
                    entry["error"] = str(exc)
                except SourceError as exc:
                    self._mark_failure(adapter.source_name)
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
