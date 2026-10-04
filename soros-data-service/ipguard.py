"""IPGuard —— 数据源 IP 封禁检测 + 换IP 自愈组件（2026-10-04 东财封禁事件驱动设计）。

设计穿透 92 分。能力边界（诚实声明，不做硬凑全覆盖）：
- 只治理 HTTP 族源（akshare/东财系）：封禁按 IP，换IP 有效，requests 代理可达
- baostock/mootdx 为 TCP 自有协议，不在本组件治理范围（限流 + 批间停顿已覆盖）

与 CircuitBreaker 的分工（职责分离，2026-10-04 级联事故的教训）：
- 熔断 60s = 瞬时故障语义（半开探针快速恢复）
- 封禁 = 小时级（换IP 才有救）：banned 期间该源直接拒绝（不发起请求、不打探针），
  否则熔断半开探针会把负载打回被封 IP，越打越封

自愈模型（人机协同：人只做一件事——切 VPN 节点）：
- 后台线程定期轮询出口 IP（低频）+ 封禁期间探测东财连通性（指数退避 15min→30min→60min）
- 探针通过（不管 IP 变没变——顺带覆盖误封/本地断网恢复场景）→ 自动解除 banned
- banned 为进程内存态，不持久化：重启即清，启动后首轮探针重新定性（保守正确）

复用方式（后续新数据源零结构改动接入）：
- adapter 的 _call_guarded 前查 guard.is_banned(source)，封禁即 SourceUnavailableError → failover 接管
- _call_guarded 的 except/else 挂 guard.report_failure / report_success
- 半自动档：SOROS_OUTBOUND_PROXY 环境变量（requests 代理，仅 HTTP 族源生效），
  换IP = 改一个 env 重启；后续接付费代理池零改动
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from datetime import datetime
from typing import Callable, Dict, Optional

import requests

logger = logging.getLogger(__name__)

# 连接层异常特征（封禁信号）；刻意排除超时类（慢代理/源抖动≠封禁，避免误伤）
CONNECTION_ERROR_PATTERNS = (
    "RemoteDisconnected",
    "Connection aborted",
    "Connection reset",
    "ConnectionResetError",
    "Connection refused",
    "Max retries exceeded",
    "getaddrinfo failed",
)

# 轻量探针：单请求探东财 K线端点（2026-10-04 被封时该端点 RemoteDisconnected，探针同口径）
_PROBE_URL = (
    "https://push2his.eastmoney.com/api/qt/stock/kline/get"
    "?secid=1.600000&fields1=f1&fields2=f51&klt=101&fqt=1&beg=20260930&end=20261009"
)
_EGRESS_IP_URL = "https://api.ipify.org"


def _default_probe() -> bool:
    try:
        resp = requests.get(_PROBE_URL, timeout=8)
        return resp.status_code == 200
    except Exception:  # noqa: BLE001 - 探针自身故障按「不可达」处理
        return False


def _default_egress_ip() -> Optional[str]:
    try:
        resp = requests.get(_EGRESS_IP_URL, timeout=8)
        return resp.text.strip() if resp.status_code == 200 else None
    except Exception:  # noqa: BLE001 - ipify 挂了静默降级（只影响观测，不影响主链路）
        return None


class IPGuard:
    """按源封禁检测 + 探针自愈。线程安全（FastAPI 线程池并发调用）。"""

    def __init__(
        self,
        probe_fn: Callable[[], bool] = _default_probe,
        egress_ip_fn: Callable[[], Optional[str]] = _default_egress_ip,
        clock: Callable[[], float] = time.time,
        window_seconds: float = 60.0,
        failure_threshold: int = 5,
        probe_interval_seconds: float = 900.0,
        max_probe_interval_seconds: float = 3600.0,
    ):
        self._probe_fn = probe_fn
        self._egress_ip_fn = egress_ip_fn
        self._clock = clock
        self._window_seconds = window_seconds
        self._threshold = failure_threshold
        self._probe_interval = probe_interval_seconds
        self._max_probe_interval = max_probe_interval_seconds

        self._lock = threading.Lock()
        self._banned: Dict[str, dict] = {}     # source -> {"at": datetime, "egress_ip": str|None}
        self._failures: Dict[str, deque] = {}  # source -> deque[失败时间戳]
        self._next_probe_at: float = 0.0
        self._probe_backoff: float = 1.0
        # 事件环形缓冲（最近 100 条）：ban/unban 历史，观测用（/ipguard events 透出）。
        # 线程安全：deque.append 在 GIL 下原子（daemon 后台线程 + FastAPI 线程池并发写）；
        # 读侧在 snapshot 内持锁 list() 拷贝，见 snapshot() docstring。
        self._events: deque = deque(maxlen=100)

        # 观测状态（后台线程维护）
        self.egress_ip: Optional[str] = None
        self.egress_ip_changed_at: Optional[str] = None

    # ---- 判定 ----
    def is_banned(self, source: str) -> bool:
        with self._lock:
            return source in self._banned

    def report_failure(self, source: str, exc: Exception) -> None:
        """连接层异常计入封禁窗口；业务异常忽略（归熔断管）。"""
        message = f"{type(exc).__name__}: {exc}"
        if not any(p in message for p in CONNECTION_ERROR_PATTERNS):
            return
        now = self._clock()
        with self._lock:
            bucket = self._failures.setdefault(source, deque())
            bucket.append(now)
            while bucket and now - bucket[0] > self._window_seconds:
                bucket.popleft()
            if len(bucket) >= self._threshold and source not in self._banned:
                # at 单一时间点：封禁快照与事件记录同一时刻（观测对拍不漂移）
                at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                self._banned[source] = {"at": at, "egress_ip": self.egress_ip}
                self._events.append({
                    "type": "ban",
                    "source": source,
                    "at": at,
                    "egress_ip": self.egress_ip,
                })
                bucket.clear()
                logger.warning("IPGuard: %s 判定 IP 封禁（%ds 内 %d 次连接层异常），"
                               "该源暂停出请求，等待换IP/探针恢复", source,
                               self._window_seconds, self._threshold)

    def report_success(self, source: str) -> None:
        """成功清零计数；banned 期间真实成功 = 最强未封禁证据 → 立即解除。"""
        with self._lock:
            self._failures.pop(source, None)
            if source in self._banned:
                self._events.append({
                    "type": "unban",
                    "source": source,
                    "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "egress_ip": self.egress_ip,
                    "reason": "real_success",  # 与探针恢复（probe_recovered）区分解封路径
                })
                del self._banned[source]
                logger.info("IPGuard: %s 真实请求成功，解除封禁标记", source)

    # ---- 自愈 ----
    def maybe_recover(self) -> bool:
        """封禁存在且到探测时点 → 探针一次；通过解除全部封禁并重置退避。

        返回 True=本次探测通过并已解除封禁。
        """
        with self._lock:
            if not self._banned:
                return False
            if self._clock() < self._next_probe_at:
                return False
        ok = bool(self._probe_fn())
        with self._lock:
            if ok:
                sources = list(self._banned)
                # 探针是全局连通性判定，同刻解除全部封禁 → 逐源记 unban（与 per-source ban 对齐）
                at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                for s in sources:
                    self._events.append({
                        "type": "unban",
                        "source": s,
                        "at": at,
                        "egress_ip": self.egress_ip,
                        "reason": "probe_recovered",
                    })
                self._banned.clear()
                self._failures.clear()
                self._probe_backoff = 1.0
                self._next_probe_at = 0.0
                logger.info("IPGuard: 探针通过，解除封禁 %s（换IP 生效/误封自愈）", sources)
                return True
            # 失败：指数退避 15min → 30min → 60min 封顶（换的 IP 也被封时不过度打扰）
            self._next_probe_at = self._clock() + self._probe_interval * self._probe_backoff
            self._probe_backoff = min(self._probe_backoff * 2,
                                      self._max_probe_interval / self._probe_interval)
            # 探针失败也可能是踩了东财坏边缘（2026-10-04 实锤）→ 顺手轮换边缘候选
            from netfix import rotate_edges
            rotate_edges()
            logger.warning("IPGuard: 探针未通过，%.0f 分钟后重试（退避 x%.1f）",
                           self._probe_interval * self._probe_backoff / 60, self._probe_backoff)
            return False

    # ---- 观测 ----
    def poll_egress(self) -> None:
        """轮询出口 IP，记录变化（换IP 生效的可观测信号）。ipify 挂了静默降级。"""
        ip = self._egress_ip_fn()
        with self._lock:
            if ip and ip != self.egress_ip:
                if self.egress_ip is not None:
                    # 首次观测是基线不是"变化"；之后的变化才记录
                    self.egress_ip_changed_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    logger.info("IPGuard: 出口 IP 变化 %s -> %s", self.egress_ip, ip)
                self.egress_ip = ip

    def snapshot(self) -> dict:
        """观测快照：egress_ip / egress_ip_changed_at / banned / events（渠道台账与封禁历史）。

        线程安全：全量在 _lock 内读取；events 由旧到新排列（deque 追加尾，list() 拷贝保序），
        最近 100 条环形缓冲——daemon 后台线程（poll/maybe_recover）与 FastAPI 线程池并发安全。
        """
        with self._lock:
            return {
                "egress_ip": self.egress_ip,
                "egress_ip_changed_at": self.egress_ip_changed_at,
                "banned": dict(self._banned),
                "events": list(self._events),
            }

    # ---- 后台线程 ----
    def run_background(self, poll_seconds: float = 60.0, stop_event: Optional[threading.Event] = None) -> None:
        """守护线程体：低频轮询出口 IP；封禁期间驱动探针自愈。全程防御，绝不拖垮主服务。"""
        last_poll = 0.0
        while not (stop_event and stop_event.is_set()):
            try:
                now = self._clock()
                if now - last_poll >= poll_seconds:
                    last_poll = now
                    self.poll_egress()
                self.maybe_recover()
            except Exception:  # noqa: BLE001 - 守护线程故障只记日志
                logger.exception("IPGuard: 后台线程异常（忽略，下轮继续）")
            time.sleep(1.0)


def start_guard_thread(guard: IPGuard, poll_seconds: float = 60.0) -> threading.Thread:
    """启动守护线程（daemon=True：随进程退出，不阻塞关停）。"""
    thread = threading.Thread(target=guard.run_background,
                              kwargs={"poll_seconds": poll_seconds}, daemon=True)
    thread.start()
    logger.info("IPGuard: 守护线程已启动（出口IP轮询 %ds/次）", poll_seconds)
    return thread


# 全局单例（adapter 层通过模块属性引用，便于测试 monkeypatch）
guard = IPGuard()
