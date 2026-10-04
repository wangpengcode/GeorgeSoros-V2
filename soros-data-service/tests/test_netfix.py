"""netfix（进程内 IPv4 强制）测试（2026-10-04 封禁根因：家宽原生 IPv6 出口被东财拒）。

证据链（同日对照实验）：
- curl -4 经飞连 → 200；curl -4 家宽 v4 → 200；curl -6 家宽原生 v6 → 空响应
- requests（urllib3 按系统地址序尝试，v6 优先）→ RemoteDisconnected（与封禁同签名）
- 结论：东财拒绝该家宽 IPv6 出口；数据服务进程内过滤 AF_INET6 即修复，
  无需系统级关 v6（Claude/其他应用的 v6 不受影响）
"""

from __future__ import annotations

import socket

import pytest

import netfix
from netfix import apply_ipv4_filter, disable_ipv4_stack_forcer


def _fake_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    """模拟系统解析：v6 在前（macOS 默认地址序，urllib3 依序尝试）。"""
    return [
        (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2409:8c1e::78c", 443, 0, 0)),
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("61.129.129.199", 443)),
    ]


def test_apply_ipv4_filter_drops_ipv6_keeps_ipv4():
    """过滤 AF_INET6、保留 AF_INET（相对顺序不变）。"""
    filtered = apply_ipv4_filter(_fake_getaddrinfo)
    results = filtered("push2his.eastmoney.com", 443)
    assert len(results) == 1
    assert results[0][0] == socket.AF_INET
    assert results[0][4] == ("61.129.129.199", 443)


def test_apply_ipv4_filter_all_ipv6_returns_empty():
    """纯 v6 解析结果 → 过滤后为空（连接快速失败而非打被封出口）。"""
    def v6_only(*a, **k):
        return [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2409::1", 443, 0, 0))]
    filtered = apply_ipv4_filter(v6_only)
    assert filtered("example.com", 443) == []


def test_disable_ipv4_stack_forcer_idempotent(monkeypatch):
    """全局安装幂等：重复调用不二次包装（否则层层嵌套、行为重复无害但脏）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(socket, "getaddrinfo", _fake_getaddrinfo)
    disable_ipv4_stack_forcer()
    first = socket.getaddrinfo
    disable_ipv4_stack_forcer()
    assert socket.getaddrinfo is first, "第二次安装不应再包装"
    results = socket.getaddrinfo("baostock.com", 443)
    assert all(r[0] == socket.AF_INET for r in results)


# ──────────────────────────────────────────────────────────────────────────────
# 东财边缘 steering（2026-10-04 实锤：东财 CDN 边缘轮换，部分边缘/v6 拒连——
# 同一出口 curl 到 101.42.128.173=200、101.226.30.221=空响应 → 是边缘问题不是出口被封）
# ──────────────────────────────────────────────────────────────────────────────

def test_steer_eastmoney_to_known_good_edge(monkeypatch):
    """eastmoney 域名族 → 固定解析到已验证好边缘（忽略 DNS 轮换到的坏边缘）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(socket, "getaddrinfo", _fake_getaddrinfo)
    disable_ipv4_stack_forcer()
    results = socket.getaddrinfo("push2his.eastmoney.com", 443, 0, socket.SOCK_STREAM)
    assert len(results) >= 1
    assert results[0][0] == socket.AF_INET
    assert results[0][4][0] in netfix.EDGE_POOLS["push2his.eastmoney.com"], "必须解析到好边缘池内 IP"


def test_steer_unknown_eastmoney_host_uses_default_pool(monkeypatch):
    """池外的 *.eastmoney.com 子域 → 用默认东财池（akshare 可能触达未枚举的 EM 域名）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(socket, "getaddrinfo", _fake_getaddrinfo)
    disable_ipv4_stack_forcer()
    results = socket.getaddrinfo("datacenter.eastmoney.com", 443, 0, socket.SOCK_STREAM)
    assert results[0][4][0] in netfix.DEFAULT_EM_POOL


def test_steer_non_eastmoney_passthrough(monkeypatch):
    """非东财域名不 steering（baostock 等正常解析，仅保留 v4 过滤）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(socket, "getaddrinfo", _fake_getaddrinfo)
    disable_ipv4_stack_forcer()
    results = socket.getaddrinfo("baostock.com", 443, 0, socket.SOCK_STREAM)
    assert results[0][4][0] == "61.129.129.199", "透传底层解析结果（fake 返回的 v4）"


def test_rotate_edges_advances_and_wraps(monkeypatch):
    """探针失败轮换：推进边缘索引，越界回绕（自愈循环不中断）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(netfix, "_edge_index", {})
    pool = netfix.EDGE_POOLS["push2his.eastmoney.com"]
    first = netfix.current_edge("push2his.eastmoney.com")
    netfix.rotate_edges()
    second = netfix.current_edge("push2his.eastmoney.com")
    assert first != second, "轮换后边缘必须变化"
    for _ in range(len(pool) - 1):
        netfix.rotate_edges()
    assert netfix.current_edge("push2his.eastmoney.com") == first, "越界回绕到队首"


def test_snapshot_reports_current_edges(monkeypatch):
    """snapshot() 暴露当前固定边缘（/ipguard 观测端点契约）。"""
    monkeypatch.setattr(netfix, "_installed", False)
    monkeypatch.setattr(netfix, "_edge_index", {})
    snap = netfix.snapshot()
    assert snap["current_edges"]["push2his.eastmoney.com"] in netfix.EDGE_POOLS["push2his.eastmoney.com"]
