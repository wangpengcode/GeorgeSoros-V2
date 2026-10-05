"""盘中数据源（§19.13.2 盘中监控页 P1-P3 数据面）。

设计要点：
- 与日K DataRouter 管线（多源轮转/熔断）解耦：盘中是低频实时快照，akshare 单源覆盖
  （东财池子/spot/五档），不参与日K三源均分语义
- 限流红线：每个底层 ak 调用前 acquire() 一次 TokenBucket（失败即抛，绝不无令牌猛打外部源），
  桶参数独立于日K源（rate_intraday + 抖动，config.py 单点）
- 归一化：akshare 中文列 → 裸数字 snake_case 键（与既有 adapter 同风格：输入/输出裸数字）
  - 成交量 手×100 → 股（与日K AKSHARE_VOLUME_MULTIPLIER 口径一致）
- 异常：外部调用失败原样上抛，由 router 层统一收口 503 INTRADAY_FAILED 错误信封
"""

from __future__ import annotations

import json
import logging
from typing import Dict, List

import akshare as ak
import requests

from adapters.base import AdapterError
from config import settings
from constants import AKSHARE_VOLUME_MULTIPLIER
from rate_limiter import TokenBucket

logger = logging.getLogger(__name__)

# 新浪 Market_Center 分页硬上限（5571 票 / 100 每页 ≈ 56 页；上限仅作空页判定失效的死循环防线）
MAX_SINA_SPOT_PAGES = 120
SINA_PAGE_NUM = 100
_SINA_HEADERS = {"Referer": "https://finance.sina.com.cn"}


def _f(v) -> float:
    """安全转 float：None / NaN / 非法值 → 0.0。"""
    if v is None:
        return 0.0
    try:
        return float(v)
    except (ValueError, TypeError):
        return 0.0


def _s(v) -> str:
    """安全转 str：None → 空串；数字代码防科学计数法漂移（全部按原样文本化）。"""
    if v is None:
        return ""
    return str(v).strip()


class IntradaySource:
    """盘中快照源：涨停/跌停/炸板池 + 全市场 spot + 单票五档。"""

    source_name = "intraday-akshare"

    def __init__(self, rate_limiter: TokenBucket | None = None, sina_rate_limiter: TokenBucket | None = None):
        self.rate_limiter = rate_limiter or TokenBucket(
            rate=settings.rate_intraday,
            jitter=settings.intraday_jitter_seconds,
            name="intraday",
            acquire_timeout_seconds=settings.rate_acquire_timeout_seconds,
        )
        # 新浪备用源独立桶（东财被拒连时兜底；0.5 rps + 抖动，56 页全量 sweep ≈ 2 分钟）
        self.sina_rate_limiter = sina_rate_limiter or TokenBucket(
            rate=settings.rate_intraday_sina,
            jitter=settings.intraday_sina_jitter_seconds,
            name="intraday-sina",
            acquire_timeout_seconds=settings.rate_acquire_timeout_seconds,
        )

    def _acquire(self, what: str) -> None:
        """取令牌，失败 → AdapterError（router 层收口 503；绝不无令牌发起外部调用）。"""
        if not self.rate_limiter.acquire():
            raise AdapterError(f"intraday 限流等待超时，本轮放弃: {what}")

    def _sina_acquire(self, what: str) -> None:
        if not self.sina_rate_limiter.acquire():
            raise AdapterError(f"intraday-sina 限流等待超时，本轮放弃: {what}")

    def _sina_get(self, url: str) -> requests.Response:
        """新浪 GET：带 Referer（否则 401），限流内发起，非 200 抛错。"""
        self._sina_acquire(url.split("?", 1)[0])
        resp = requests.get(url, timeout=15, headers=_SINA_HEADERS)
        if resp.status_code != 200:
            raise AdapterError(f"sina HTTP {resp.status_code}: {url[:80]}")
        return resp

    # ── 池子（涨停/跌停/炸板） ──────────────────────────────────────────────

    def fetch_pools(self, date_yyyymmdd: str) -> Dict[str, List[dict]]:
        """三池快照：{limit_up, limit_down, broken}；date_yyyymmdd 形如 20260930。"""
        specs = [
            ("limit_up", ak.stock_zt_pool_em),
            ("limit_down", ak.stock_zt_pool_dtgc_em),
            ("broken", ak.stock_zt_pool_zbgc_em),
        ]
        payload: Dict[str, List[dict]] = {}
        for name, fn in specs:
            self._acquire(f"pool/{name}")
            try:
                df = fn(date=date_yyyymmdd)
            except Exception as exc:  # noqa: BLE001
                # 单池失败不炸整端点：该池置空（盘中监控页显示空池可接受）
                logger.warning("盘中池 %s(%s) 拉取失败: %s", name, date_yyyymmdd, exc)
                payload[name] = []
                continue
            payload[name] = [self._norm_pool_row(row) for _, row in df.iterrows()] if df is not None and not df.empty else []
        return payload

    def _norm_pool_row(self, row) -> dict:
        return {
            "code": _s(row.get("代码")),
            "name": _s(row.get("名称")),
            "change_pct": _f(row.get("涨跌幅")),
            "latest_price": _f(row.get("最新价")),
            "amount": _f(row.get("成交额")),
            "turnover_rate": _f(row.get("换手率")),
            "lianban": int(_f(row.get("连板数", row.get("连续跌停", 0)))),
            "first_time": _s(row.get("首次封板时间")),
            "broken_count": int(_f(row.get("炸板次数", row.get("开板次数", 0)))),
            "industry": _s(row.get("所属行业")),
        }

    # ── 全市场 spot 快照 ────────────────────────────────────────────────────

    def fetch_spot(self) -> List[dict]:
        """全市场实时快照；主源东财（成交量 手×100→股），拒连降级新浪分页（已是股口径）。"""
        self._acquire("spot")
        try:
            df = ak.stock_zh_a_spot_em()
        except Exception as exc:  # noqa: BLE001
            logger.warning("盘中 spot 东财失败，降级新浪备用源: %s", exc)
            return self._fetch_spot_sina()
        if df is None or df.empty:
            return []
        return [
            {
                "code": _s(row.get("代码")),
                "name": _s(row.get("名称")),
                "latest_price": _f(row.get("最新价")),
                "change_pct": _f(row.get("涨跌幅")),
                "volume": int(_f(row.get("成交量")) * AKSHARE_VOLUME_MULTIPLIER),
                "amount": _f(row.get("成交额")),
                "turnover_rate": _f(row.get("换手率")),
            }
            for _, row in df.iterrows()
        ]

    def _fetch_spot_sina(self) -> List[dict]:
        """新浪 Market_Center hs_a 全市场分页兜底（空页或页数硬上限终止）。"""
        stocks: List[dict] = []
        for page in range(1, MAX_SINA_SPOT_PAGES + 1):
            url = (
                "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                f"Market_Center.getHQNodeData?page={page}&num={SINA_PAGE_NUM}"
                "&sort=changepercent&asc=0&node=hs_a&symbol=&_s_r_a=page"
            )
            rows = json.loads(self._sina_get(url).text)
            if not rows:
                break
            for row in rows:
                stocks.append({
                    "code": _s(row.get("code")),
                    "name": _s(row.get("name")),
                    "latest_price": _f(row.get("trade")),
                    "change_pct": _f(row.get("changepercent")),
                    "volume": int(_f(row.get("volume"))),  # 新浪已是股口径
                    "amount": _f(row.get("amount")),
                    "turnover_rate": _f(row.get("turnoverratio")),
                })
        return stocks

    # ── 单票五档 ───────────────────────────────────────────────────────────

    def fetch_bid_ask(self, code: str) -> dict:
        """五档盘口：主源东财 item/value，拒连降级新浪 hq（GBK，买1→买5/卖1→卖5）。"""
        self._acquire(f"bid-ask/{code}")
        try:
            df = ak.stock_bid_ask_em(symbol=code)
        except Exception as exc:  # noqa: BLE001
            logger.warning("盘中 bid-ask 东财失败，降级新浪备用源: %s", exc)
            return self._fetch_bid_ask_sina(code)
        buy_prices: Dict[int, float] = {}
        buy_vols: Dict[int, float] = {}
        sell_prices: Dict[int, float] = {}
        sell_vols: Dict[int, float] = {}
        if df is not None and not df.empty:
            for _, row in df.iterrows():
                item = _s(row.get("item"))
                value = _f(row.get("value"))
                for i in range(1, 6):
                    if item == f"买{i}价":
                        buy_prices[i] = value
                    elif item == f"买{i}量":
                        buy_vols[i] = value
                    elif item == f"卖{i}价":
                        sell_prices[i] = value
                    elif item == f"卖{i}量":
                        sell_vols[i] = value
        # 空档补 0.0（停牌/异常票档位可能不足 5）
        bids = [[buy_prices.get(i, 0.0), buy_vols.get(i, 0.0)] for i in range(1, 6)]
        asks = [[sell_prices.get(i, 0.0), sell_vols.get(i, 0.0)] for i in range(1, 6)]
        return {"code": code, "bids": bids, "asks": asks}

    def _fetch_bid_ask_sina(self, code: str) -> dict:
        """新浪 hq.sinajs.cn 单票五档（GBK；10买一量,11买一价,… 20卖一量,21卖一价,…）。"""
        symbol = self._sina_symbol(code)
        resp = self._sina_get(f"https://hq.sinajs.cn/list={symbol}")
        text = resp.content.decode("gbk", errors="replace")
        start = text.find('"')
        end = text.rfind('"')
        if start < 0 or end <= start:
            raise AdapterError(f"sina 五档响应格式异常: {code}")
        fields = text[start + 1:end].split(",")
        bids = [
            [_f(fields[11 + 2 * (i - 1)]), _f(fields[10 + 2 * (i - 1)])] for i in range(1, 6)
        ]
        asks = [
            [_f(fields[21 + 2 * (i - 1)]), _f(fields[20 + 2 * (i - 1)])] for i in range(1, 6)
        ]
        return {"code": code, "bids": bids, "asks": asks}

    @staticmethod
    def _sina_symbol(code: str) -> str:
        """代码→新浪前缀：6 开头 sh；0/3 开头 sz；4/8/92 开头 bj（北交所）。"""
        if code.startswith("6"):
            return f"sh{code}"
        if code.startswith(("4", "8", "92")):
            return f"bj{code}"
        return f"sz{code}"
