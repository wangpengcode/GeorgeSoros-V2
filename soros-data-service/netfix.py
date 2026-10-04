"""netfix —— 数据服务出网治理：IPv4 强制 + 东财边缘 steering（2026-10-04 封禁事件根因修复）。

根因证据链（同日对照实验，见 tests/test_netfix.py 模块注释）：
- 家宽原生 IPv6 出口被东财拒绝（curl -6 空响应；CDN v6 地址轮换，路由无法治理）
- 东财 CDN 边缘轮换且部分边缘拒连：同一出口 curl 到 101.42.128.173=200、
  101.226.30.221=空响应 → 是「边缘」问题而非「出口 IP 被封」
- requests/urllib3 按系统地址序尝试（macOS v6 优先）→ 数据服务踩 v6 坏边缘

修复口径（进程内，零系统改动）：
- IPv4 强制：过滤 socket.getaddrinfo 的 AF_INET6（只影响本服务）
- 边缘 steering：eastmoney.com 域名族固定解析到「已验证好边缘池」，
  IPGuard 探针失败 → rotate_edges() 轮换下一候选；默认池兜底池外 EM 子域
- v6-only/坏边缘快速失败而非悬挂（fail-safe）；默认启用，SOROS_DISABLE_IPV6=0 可关闭
"""

from __future__ import annotations

import logging
import socket
from typing import Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ── 已验证好边缘池（2026-10-04 对照实验实测 200；OS 静态路由已将这些 IP 指向家宽出口）──
EDGE_POOLS: Dict[str, List[str]] = {
    "push2his.eastmoney.com": ["61.129.129.199", "101.42.128.173"],   # K线历史
    "push2.eastmoney.com": ["61.129.129.196"],                        # 行情快照
    "datacenter-web.eastmoney.com": ["116.162.209.83", "116.162.209.84", "116.162.209.85"],  # 业绩/板块
}
# 池外 *.eastmoney.com 子域兜底（akshare 可能触达未枚举的 EM 域名，边缘为共享 CDN）
DEFAULT_EM_POOL: List[str] = ["61.129.129.196"]
_EM_SUFFIX = ".eastmoney.com"

_edge_index: Dict[str, int] = {}
_installed = False


def _pool_for(host: str) -> Optional[List[str]]:
    if host in EDGE_POOLS:
        return EDGE_POOLS[host]
    if host.endswith(_EM_SUFFIX):
        return DEFAULT_EM_POOL
    return None


def current_edge(host: str) -> Optional[str]:
    """该域名当前固定使用的边缘 IP（未池内域名返回 None）。"""
    pool = _pool_for(host)
    if not pool:
        return None
    idx = _edge_index.get(host, 0) % len(pool)
    return pool[idx]


def rotate_edges() -> None:
    """全部池内域名推进到下一候选边缘（越界回绕）。IPGuard 探针失败时调用。"""
    for host, pool in EDGE_POOLS.items():
        _edge_index[host] = (_edge_index.get(host, 0) + 1) % len(pool)
    logger.warning("netfix: 边缘轮换 %s", {h: current_edge(h) for h in EDGE_POOLS})


def _steer(getaddrinfo_fn: Callable[..., List[Tuple]]) -> Callable[..., List[Tuple]]:
    """边缘 steering 包装：池内域名返回固定边缘的 AF_INET 结果，其余透传。"""

    def resolve(host, port, *args, **kwargs):
        edge = current_edge(host) if isinstance(host, str) else None
        if edge is None or not isinstance(port, int) or port <= 0:
            return getaddrinfo_fn(host, port, *args, **kwargs)
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (edge, port, 0, 0))]

    return resolve


def apply_ipv4_filter(
    getaddrinfo_fn: Callable[..., List[Tuple]],
) -> Callable[..., List[Tuple]]:
    """返回仅保留 AF_INET 结果的 getaddrinfo 包装（纯函数，可测）。"""

    def ipv4_only(*args, **kwargs):
        return [r for r in getaddrinfo_fn(*args, **kwargs) if r[0] == socket.AF_INET]

    return ipv4_only


def disable_ipv4_stack_forcer() -> None:
    """安装全局出网治理（IPv4 强制 + 边缘 steering；幂等：重复调用不二次包装）。"""
    global _installed
    if _installed:
        return
    socket.getaddrinfo = _steer(apply_ipv4_filter(socket.getaddrinfo))
    _installed = True
    logger.info("netfix: 已启用进程内出网治理（IPv4 强制 + 东财边缘 steering %s）",
                {h: current_edge(h) for h in EDGE_POOLS})


def snapshot() -> dict:
    """观测：当前各域名固定边缘（/ipguard 端点透出）。"""
    return {"current_edges": {h: current_edge(h) for h in EDGE_POOLS}}
