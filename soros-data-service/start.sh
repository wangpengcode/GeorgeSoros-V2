#!/usr/bin/env bash
# soros-data-service 启动脚本（uvicorn 原生进程；Docker 只跑 PG，见 PLAN §13 部署拓扑）
# 用法：./start.sh   （前台运行；后台部署用 nohup ./start.sh >> ../logs/python-service.log 2>&1 &）
#
# 限速参数（TokenBucket 令牌/秒，含抖动，env 覆盖优先）：
#   2026-10-04 用户要求所有源请求间隔 ≥15s（防 IP 封禁铁律加码）：
#   全源 rate=0.06（间隔 ~16.7s+抖动）、yahoo/sse 0.05（~20s）。
#   多源并行兜底：4 路分片并行 ≈ 4 只/17s ≈ 14 只/分，剩余回填约 5h。
set -euo pipefail
cd "$(dirname "$0")"

export SOROS_RATE_BAOSTOCK="${SOROS_RATE_BAOSTOCK:-0.06}"
# yahoo 403 配额冷却期临时摘出分片池（3 路并行；恢复后改回 baostock,akshare,yahoo,tencent）
export SOROS_SHARD_SOURCES="${SOROS_SHARD_SOURCES:-baostock,akshare,tencent}"

exec .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
