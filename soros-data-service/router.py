"""FastAPI 路由（PLAN §11.1 Python API 全契约，本 Step 范围 4 端点）。

端点（统一前缀 /api/v1 由 main.py 挂载；根路径亦挂载以兼容冒烟 curl /health）：
- GET  /health            → {status, sources:{baostock/akshare/mootdx: ok|degraded|down}}
- GET  /stock-list        → {status, stocks:[{code,name,market,board,is_st,delisted}]}
- POST /daily-bars/batch  → {status, results:{code:{source,count,data[]}}, failed:[{code,reason}]}
- GET  /trading-calendar  → {dates:[...]}

错误信封（PLAN §11.1）：顶层 {status:"error", error:{code,message}}；单股失败进 failed[]。
校验：/daily-bars/batch 的 codes 数量 ≤ settings.batch_max_codes（默认 1000），超限 422
信封形 PARAM_INVALID（错误码单点 constants.py ERROR_* 区段）。
"""

from __future__ import annotations

import logging
from typing import Dict, List

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

from adapters.base import DataRouter
from config import settings
from constants import (
    ERROR_PARAM_INVALID,
    ERROR_STOCK_LIST_FAILED,
    ERROR_TRADING_CALENDAR_FAILED,
)
from health import get_health
from models import (
    DailyBarsBatchRequest,
    DailyBarsBatchResponse,
    CalendarResponse,
    HealthResponse,
    StockListResponse,
    ErrorEnvelope,
)

logger = logging.getLogger(__name__)


def create_router(data_router: DataRouter) -> APIRouter:
    router = APIRouter()

    @router.get("/health", response_model=HealthResponse)
    def health():
        return get_health(data_router)

    @router.get("/stock-list", response_model=StockListResponse)
    def stock_list(
        market: str = Query("all", description="all / SH / SZ"),
        board: str = Query("all", description="all / MAIN / GEM / STAR"),
    ):
        try:
            stocks = data_router.fetch_stock_list(market=market, board=board)
        except Exception as exc:  # noqa: BLE001
            logger.error("stock-list 失败: %s", exc)
            return JSONResponse(
                status_code=503,
                content=ErrorEnvelope(error={"code": ERROR_STOCK_LIST_FAILED, "message": str(exc)}).model_dump(),
            )
        return {"status": "ok", "stocks": stocks}

    @router.post("/daily-bars/batch", response_model=DailyBarsBatchResponse)
    def daily_bars_batch(req: DailyBarsBatchRequest):
        if len(req.codes) > settings.batch_max_codes:
            logger.warning(
                "batch codes 超上限: %d > %d", len(req.codes), settings.batch_max_codes
            )
            return JSONResponse(
                status_code=422,
                content=ErrorEnvelope(
                    error={
                        "code": ERROR_PARAM_INVALID,
                        "message": (
                            f"codes 数量 {len(req.codes)} 超过 batch_max_codes 上限 "
                            f"{settings.batch_max_codes}"
                        ),
                    }
                ).model_dump(),
            )
        results: Dict[str, dict] = {}
        failed: List[dict] = []
        # 单股失败不炸整批：每只股独立走 failover Router，失败进 failed[]
        for code in req.codes:
            result, errors = data_router.fetch_daily_bars(code, req.start_date, req.end_date, req.adjust)
            if result is not None:
                results[code] = result
            else:
                failed.append({"code": code, "reason": errors[0] if errors else "All sources failed"})
        return {"status": "ok", "results": results, "failed": failed}

    @router.get("/trading-calendar", response_model=CalendarResponse)
    def trading_calendar():
        try:
            dates = data_router.fetch_trading_calendar()
        except Exception as exc:  # noqa: BLE001
            logger.error("trading-calendar 失败: %s", exc)
            return JSONResponse(
                status_code=503,
                content=ErrorEnvelope(error={"code": ERROR_TRADING_CALENDAR_FAILED, "message": str(exc)}).model_dump(),
            )
        return {"dates": dates}

    return router
