"""CircuitBreaker：每源独立，连续 N 次失败 → open 时长 → half-open 单探测（PLAN §11.1 韧性参数）。

状态机：
- closed：正常放行；连续失败达到阈值 → open
- open：拒绝请求；open 时长到期 → half_open（放行单探测）
- half_open：放行单探测；成功 → closed 复位；失败 → 重新 open

对外语义（health 端点，PLAN §11.1）：closed→ok，half_open 或有失败痕迹→degraded，open→down。
"""

from __future__ import annotations

import threading
import time

STATE_CLOSED = "closed"
STATE_OPEN = "open"
STATE_HALF_OPEN = "half_open"

# health 状态（PLAN §11.1：ok|degraded|down）
HEALTH_OK = "ok"
HEALTH_DEGRADED = "degraded"
HEALTH_DOWN = "down"


class CircuitBreaker:
    def __init__(self, name: str, failure_threshold: int = 5, open_timeout_seconds: int = 60):
        self.name = name
        self.failure_threshold = failure_threshold
        self.open_timeout_seconds = open_timeout_seconds
        self._state = STATE_CLOSED
        self._consecutive_failures = 0
        self._opened_at = 0.0
        self._lock = threading.Lock()

    @property
    def state(self) -> str:
        with self._lock:
            return self._state

    @property
    def consecutive_failures(self) -> int:
        with self._lock:
            return self._consecutive_failures

    def allow_request(self) -> bool:
        """是否放行当前请求。half-open 只放行单探测（调用方用 record_success/failure 决定去留）。"""
        with self._lock:
            if self._state == STATE_CLOSED:
                return True
            if self._state == STATE_OPEN:
                if time.monotonic() - self._opened_at >= self.open_timeout_seconds:
                    self._state = STATE_HALF_OPEN
                    return True
                return False
            # HALF_OPEN：单探测放行
            return True

    def record_success(self) -> None:
        with self._lock:
            self._consecutive_failures = 0
            if self._state == STATE_HALF_OPEN:
                self._state = STATE_CLOSED

    def record_failure(self) -> None:
        with self._lock:
            self._consecutive_failures += 1
            if self._state == STATE_HALF_OPEN:
                # 单探测失败 → 重新 open 并重启计时
                self._state = STATE_OPEN
                self._opened_at = time.monotonic()
            elif self._consecutive_failures >= self.failure_threshold:
                self._state = STATE_OPEN
                self._opened_at = time.monotonic()

    def health(self) -> str:
        """health 端点语义：ok / degraded / down（PLAN §11.1）"""
        with self._lock:
            if self._state == STATE_OPEN:
                return HEALTH_DOWN
            if self._state == STATE_HALF_OPEN or self._consecutive_failures > 0:
                return HEALTH_DEGRADED
            return HEALTH_OK

    def __repr__(self) -> str:  # pragma: no cover - 调试辅助
        return f"<CircuitBreaker {self.name} state={self._state} failures={self._consecutive_failures}>"
