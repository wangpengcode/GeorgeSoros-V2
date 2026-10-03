"""/health 聚合（PLAN §11.1：{status, sources: {baostock/akshare/mootdx: ok|degraded|down}}）。

status 聚合规则：
- 任一源 down → 整体 down
- 任一源 degraded（且无 down）→ 整体 degraded
- 全部 ok → 整体 ok
"""

from __future__ import annotations

from typing import Dict

from constants import DATA_SOURCES
from adapters.base import DataRouter

HEALTH_OK = "ok"
HEALTH_DEGRADED = "degraded"
HEALTH_DOWN = "down"


def get_health(data_router: DataRouter) -> Dict:
    sources: Dict[str, str] = {}
    for name in DATA_SOURCES:
        adapter = data_router.adapters[name]
        sources[name] = adapter.health_state

    states = set(sources.values())
    if HEALTH_DOWN in states:
        status = HEALTH_DOWN
    elif HEALTH_DEGRADED in states:
        status = HEALTH_DEGRADED
    else:
        status = HEALTH_OK
    return {"status": status, "sources": sources}
