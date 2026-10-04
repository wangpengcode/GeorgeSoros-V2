"""TokenBucket 每源独立限流（PLAN §11.1：baostock 5 rps / AKShare 2 rps + 抖动 / mootdx 3 rps）。

- try_acquire()：非阻塞尝试取一个令牌
- acquire(timeout)：阻塞式取令牌（同步 adapter 用），超时返回 False
- 抖动：获取成功后随机 sleep [0, jitter)，打散请求防同步突发（AKShare 专配）
线程安全：令牌桶以单调时钟为 refill 基准，锁保护状态。
"""

from __future__ import annotations

import random
import threading
import time


class TokenBucket:
    def __init__(self, rate: float, capacity: float | None = None, jitter: float = 0.0, name: str = ""):
        self.rate = float(rate)
        # 容量下限 1.0：容量 < 1（如 rate=0.5）时令牌永远攒不到扣减阈值，acquire 必超时
        self.capacity = float(capacity if capacity is not None else max(self.rate, 1.0))
        self.jitter = float(jitter)
        self.name = name
        self._tokens = self.capacity
        self._last = time.monotonic()
        self._lock = threading.Lock()

    def _refill(self) -> None:
        now = time.monotonic()
        elapsed = now - self._last
        self._tokens = min(self.capacity, self._tokens + elapsed * self.rate)
        self._last = now

    def try_acquire(self) -> bool:
        """非阻塞：有令牌立即扣减返回 True，否则 False。"""
        with self._lock:
            self._refill()
            if self._tokens >= 1.0:
                self._tokens -= 1.0
                return True
            return False

    def acquire(self, timeout: float = 30.0) -> bool:
        """阻塞式：直到取到令牌或超时。成功后应用抖动。"""
        deadline = time.monotonic() + timeout
        while not self.try_acquire():
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.05)
        if self.jitter > 0:
            time.sleep(random.uniform(0.0, self.jitter))
        return True

    def reset(self) -> None:
        with self._lock:
            self._tokens = self.capacity
            self._last = time.monotonic()

    def __repr__(self) -> str:  # pragma: no cover - 调试辅助
        return f"<TokenBucket {self.name} rate={self.rate} jitter={self.jitter}>"
