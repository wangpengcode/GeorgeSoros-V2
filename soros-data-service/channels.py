"""渠道运营台账聚合（GET /api/v1/channels，CORS 页面展示）。

每源输出（对齐 models.ChannelStatus）：
- source：数据源名（adapters 注册序：baostock/akshare/mootdx/yahoo/tencent/sse）
- health：ok | degraded | down（CircuitBreaker.health 口径，与 /health 同源）
- last_success_at / last_failure_at：DataRouter 渠道台账（进程态重启即清，见 adapters/base.py
  _mark_success/_mark_failure；last_success_at=成功取到数据，last_failure_at=SourceError）
- banned：{at, egress_ip} | null（IPGuard 实时封禁；封禁=小时级，与熔断 60s 分离）
- breaker：closed | open | half_open（CircuitBreaker.state 只读 property）

时间戳格式 "%Y-%m-%d %H:%M:%S"，与 /ipguard 现状一致（本地墙钟，非 ISO）。
"""

from __future__ import annotations

from adapters.base import DataRouter
from ipguard import IPGuard


def get_channels(data_router: DataRouter, guard: IPGuard) -> dict:
    """按数据源构造渠道台账（输入输出均为普通 dict，便于 TestClient/页面直读）。"""
    banned = guard.snapshot().get("banned", {})
    ledger = data_router.channel_ledger()
    channels = []
    for name, adapter in data_router.adapters.items():
        entry = ledger.get(name, {})
        channels.append({
            "source": name,
            "health": adapter.health_state,
            "last_success_at": entry.get("last_success_at"),
            "last_failure_at": entry.get("last_failure_at"),
            "banned": banned.get(name),
            "breaker": adapter.circuit_breaker.state,
        })
    return {"status": "ok", "channels": channels}
