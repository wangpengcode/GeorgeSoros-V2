#!/usr/bin/env bash
# soros-data-service 启动脚本（uvicorn 原生进程；Docker 只跑 PG，见 PLAN §13 部署拓扑）
# 用法：./start.sh   （前台运行；后台部署用 nohup ./start.sh >> ../logs/python-service.log 2>&1 &）
#
# 限速参数（TokenBucket 令牌/秒，含抖动，env 覆盖优先）：
#   SOROS_RATE_BAOSTOCK 5→2：baostock 为日 K 主源，5 年回填连续打 5/s 偏激进，
#   2026-10-04 用户要求降速防 IP 封禁（外部数据间歇性获取铁律）。
#   回填节奏随之下调：50 股/批约 55s→~140s，105 批全程 ~2h→~4h。
set -euo pipefail
cd "$(dirname "$0")"

export SOROS_RATE_BAOSTOCK="${SOROS_RATE_BAOSTOCK:-2.0}"

exec .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
