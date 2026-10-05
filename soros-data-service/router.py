"""FastAPI 路由（PLAN §11.1 Python API 全契约，Step 5a 扩展至 7 端点）。

端点（统一前缀 /api/v1 由 main.py 挂载；根路径亦挂载以兼容冒烟 curl /health）：
- GET  /health                      → {status, sources:{baostock/akshare/mootdx: ok|degraded|down}}
- GET  /stock-list                  → {status, stocks:[{code,name,market,board,is_st,delisted,ipo_date}]}
- POST /daily-bars/batch            → {status, results:{code:{source,count,data[]}}, failed:[{code,reason}]}
- GET  /trading-calendar            → {dates:[...]}
- POST /fundamentals                → {status, stocks:[{code,revenue,net_profit}]}（PLAN §11.1）
- POST /board-members               → {status, boards:{板块名:[codes]}}（PLAN §4.8）
- POST /daily-bars/cross-validate   → {status, results:{code:{source:{source,count,data[]}}}, failed}（PLAN §11.2）

错误信封（PLAN §11.1）：顶层 {status:"error", error:{code,message}}；单股失败进 failed[]。
校验：batch/cross-validate 的 codes 数量 ≤ settings.batch_max_codes（默认 1000），超限 422
信封形 PARAM_INVALID（错误码单点 constants.py ERROR_* 区段）。
"""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Tuple

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

from adapters.base import DataRouter
from config import settings
from constants import (
    ERROR_BOARD_MEMBERS_FAILED,
    ERROR_CROSS_VALIDATE_FAILED,
    ERROR_FUNDAMENTALS_FAILED,
    ERROR_PARAM_INVALID,
    ERROR_STOCK_LIST_FAILED,
    ERROR_TRADING_CALENDAR_FAILED,
    is_index_code,
)
from health import get_health
from models import (
    BoardMembersRequest,
    BoardMembersResponse,
    CalendarResponse,
    CrossValidateRequest,
    CrossValidateResponse,
    DailyBarsBatchItem,
    DailyBarsBatchRequest,
    DailyBarsBatchResponse,
    ErrorEnvelope,
    FundamentalsRequest,
    FundamentalsResponse,
    HealthResponse,
    StockListResponse,
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

    def _param_invalid(message: str) -> JSONResponse:
        """422 信封形 PARAM_INVALID（§11.1 错误信封 + constants.py 错误码单点）。"""
        return JSONResponse(
            status_code=422,
            content=ErrorEnvelope(
                error={"code": ERROR_PARAM_INVALID, "message": message}
            ).model_dump(),
        )

    @router.post("/daily-bars/batch", response_model=DailyBarsBatchResponse)
    def daily_bars_batch(req: DailyBarsBatchRequest):
        items_mode = bool(req.items)
        codes_mode = bool(req.codes)

        # 二选一铁律（XOR）：混用 / 两者皆空 / codes 模式缺日期 → 422 PARAM_INVALID
        if items_mode and codes_mode:
            return _param_invalid("items 与 codes+start_date+end_date 二选一，禁止混用")
        if not items_mode and not codes_mode:
            return _param_invalid("必须提供 items 或 codes+start_date+end_date 二选一")
        if codes_mode and (req.start_date is None or req.end_date is None):
            return _param_invalid("codes 模式必须提供 start_date/end_date")

        # 批内 code 唯一（items 模式）：同批重复 → 422（results 按 code 键归属歧义）
        if items_mode:
            item_codes = [item.code for item in req.items]
            if len(item_codes) != len(set(item_codes)):
                return _param_invalid("items 批内 code 不得重复（同批重复造成归属歧义）")

        # 批大小上限（两模式共用同一 batch_max_codes 上限）
        n = len(req.items) if items_mode else len(req.codes)
        if n > settings.batch_max_codes:
            logger.warning(
                "batch %s 超上限: %d > %d",
                "items" if items_mode else "codes", n, settings.batch_max_codes,
            )
            return _param_invalid(
                f"{'items' if items_mode else 'codes'} 数量 {n} 超过 batch_max_codes 上限 "
                f"{settings.batch_max_codes}"
            )

        if items_mode:
            return _daily_bars_batch_items(req, data_router)
        return _daily_bars_batch_codes(req, data_router)

    def _daily_bars_batch_codes(req, data_router):
        """codes+dates 模式（向后兼容）：每票先轮转分配源，按分配源分组并行 + 串行尾批。

        - 均分流量（2026-10-05 定稿）：每票由 data_router._assign_source 轮转分配一个健康源，
          分组与执行单点一致（worker 回传 source=钉死该源，不再轮转/不再换源）；
        - 无健康源 → 直接 failed（零外部请求，本轮放弃等下轮重扫），不进 worker；
        - 指数 code → 归串行尾批（走指数路径，仅 akshare）；
        - 分配源空结果 → fetch 层返回 count=0 占位（带 empty_sources）进 results（非 failed）；
          分配源真实故障 → failed[{code, reason}]（reason 归因被分配源）。
        """
        groups: Dict[str, List[str]] = {}
        tail: List[str] = []
        failed: List[dict] = []
        for code in req.codes:
            if is_index_code(code):
                tail.append(code)
                continue
            adapter = data_router._assign_source(req.adjust, code)
            if adapter is None:
                failed.append({"code": code, "reason": "无健康源，本轮不发起外部调用"})
            else:
                groups.setdefault(adapter.source_name, []).append(code)

        # worker：组内逐只串行、钉死分配源（与分组同一次分配结果），各自收集 results/failed
        # （局部 dict/list 收集再合并，避免跨线程共享可变对象竞态）
        def _worker(codes: List[str], src: str) -> Tuple[Dict[str, dict], List[dict]]:
            local_results: Dict[str, dict] = {}
            local_failed: List[dict] = []
            for code in codes:
                result, errors = data_router.fetch_daily_bars(
                    code, req.start_date, req.end_date, req.adjust, source=src
                )
                if result is not None:
                    local_results[code] = result
                else:
                    local_failed.append(
                        {"code": code, "reason": errors[0] if errors else f"{src}: 本轮失败"}
                    )
            return local_results, local_failed

        results: Dict[str, dict] = {}
        if groups:
            # 非空组数 = worker 数：并行只在源之间，组内串行（防封禁铁律）
            with ThreadPoolExecutor(max_workers=len(groups)) as pool:
                futures = [pool.submit(_worker, codes, src) for src, codes in groups.items()]
                for (src, codes), fut in zip(groups.items(), futures):
                    try:
                        r, f = fut.result()
                    except Exception as exc:  # noqa: BLE001
                        # worker 非预期异常不炸整批：该组全部 code 降级进 failed
                        # （防御性兜底，正常不可达——_fetch_stock_daily 异常兜口完整）
                        logger.error("batch worker(%s) 异常: %s", src, exc)
                        r, f = {}, [{"code": c, "reason": f"worker 异常: {exc}"} for c in codes]
                    results.update(r)
                    failed.extend(f)

        # 串行尾批：指数 code，主线程逐只拉取（量小，串行防封禁）
        for code in tail:
            result, errors = data_router.fetch_daily_bars(
                code, req.start_date, req.end_date, req.adjust
            )
            if result is not None:
                results[code] = result
            else:
                failed.append(
                    {"code": code, "reason": errors[0] if errors else "指数源失败"}
                )
        return {"status": "ok", "results": results, "failed": failed}

    def _daily_bars_batch_items(req, data_router):
        """items 逐段窗口模式（重跑计划）：每 item 独立窗口拉取，按分配源分组并行。

        - 均分流量（2026-10-05 定稿）：每票先 _assign_source 轮转分配一个健康源，按分配源
          分组（worker 回传 source=钉死分配源）；无健康源 → failed（零外部请求）；
        - 分配源空结果 → results[code] count=0 占位（带 empty_sources 空票，K=2 verified-empty
          判据载体，供 Kotlin 侧 ≥2 源确认空后记 verified-empty 并推水位）；
        - 分配源真实故障 → failed[{code, reason}]（reason 归因被分配源）。
        """
        items = req.items
        groups: Dict[str, List[DailyBarsBatchItem]] = {}
        tail: List[DailyBarsBatchItem] = []
        for item in items:
            if is_index_code(item.code):
                tail.append(item)
                continue
            adapter = data_router._assign_source(req.adjust, item.code)
            if adapter is None:
                continue  # 无健康源在下方统一收口（item 级 failed 去重到分组循环外）
            groups.setdefault(adapter.source_name, []).append(item)

        def _worker_items(segments: List[DailyBarsBatchItem], src: str) -> Tuple[Dict[str, dict], List[dict]]:
            local_results: Dict[str, dict] = {}
            local_failed: List[dict] = []
            for seg in segments:
                result, errors = data_router.fetch_daily_bars(
                    seg.code, seg.start_date, seg.end_date, req.adjust, source=src
                )
                if result is not None:
                    local_results[seg.code] = result
                else:
                    local_failed.append(
                        {"code": seg.code, "reason": errors[0] if errors else f"{src}: 本轮失败"}
                    )
            return local_results, local_failed

        results: Dict[str, dict] = {}
        failed: List[dict] = []
        # 无健康源统一收口（与 codes 模式同文案；零外部请求）
        assigned_codes = {item.code for segs in groups.values() for item in segs} | {
            item.code for item in tail
        }
        for item in items:
            if item.code not in assigned_codes:
                failed.append({"code": item.code, "reason": "无健康源，本轮不发起外部调用"})
        if groups:
            with ThreadPoolExecutor(max_workers=len(groups)) as pool:
                futures = [pool.submit(_worker_items, segs, src) for src, segs in groups.items()]
                for (src, segments), fut in zip(groups.items(), futures):
                    try:
                        r, f = fut.result()
                    except Exception as exc:  # noqa: BLE001
                        logger.error("batch items worker(%s) 异常: %s", src, exc)
                        r, f = {}, [{"code": s.code, "reason": f"worker 异常: {exc}"} for s in segments]
                    results.update(r)
                    failed.extend(f)

        # 串行尾批：指数 item，主线程逐段拉取（量小，串行防封禁）
        for seg in tail:
            result, errors = data_router.fetch_daily_bars(
                seg.code, seg.start_date, seg.end_date, req.adjust
            )
            if result is not None:
                results[seg.code] = result
            else:
                failed.append(
                    {"code": seg.code, "reason": errors[0] if errors else "指数源失败"}
                )
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

    @router.post("/fundamentals", response_model=FundamentalsResponse)
    def fundamentals(req: FundamentalsRequest):
        """PLAN §11.1：季度末报告期业绩报表（AKShare stock_yjbb_em，单位亿元×1e8→元）。"""
        try:
            stocks = data_router.fetch_fundamentals(req.report_date)
        except Exception as exc:  # noqa: BLE001
            logger.error("fundamentals 失败: %s", exc)
            return JSONResponse(
                status_code=503,
                content=ErrorEnvelope(
                    error={"code": ERROR_FUNDAMENTALS_FAILED, "message": str(exc)}
                ).model_dump(),
            )
        return {"status": "ok", "stocks": stocks}

    @router.post("/board-members", response_model=BoardMembersResponse)
    def board_members(req: BoardMembersRequest):
        """PLAN §4.8：板块成分快照（industry 每日 / concept 每周；东财 stock_board_*_em 链）。"""
        try:
            payload = data_router.fetch_board_members(req.board_type)
        except Exception as exc:  # noqa: BLE001
            logger.error("board-members 失败: %s", exc)
            return JSONResponse(
                status_code=503,
                content=ErrorEnvelope(
                    error={"code": ERROR_BOARD_MEMBERS_FAILED, "message": str(exc)}
                ).model_dump(),
            )
        return {"status": "ok", **payload}

    @router.post("/daily-bars/cross-validate", response_model=CrossValidateResponse)
    def daily_bars_cross_validate(req: CrossValidateRequest):
        """PLAN §11.2：双源交叉验证（baostock + akshare，mootdx 永不参与；只观测不修正）。"""
        if len(req.codes) > settings.batch_max_codes:
            logger.warning(
                "cross-validate codes 超上限: %d > %d", len(req.codes), settings.batch_max_codes
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
        try:
            results, failed = data_router.fetch_daily_bars_cross(
                req.codes, req.start_date, req.end_date, req.adjust
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("daily-bars/cross-validate 失败: %s", exc)
            return JSONResponse(
                status_code=503,
                content=ErrorEnvelope(
                    error={"code": ERROR_CROSS_VALIDATE_FAILED, "message": str(exc)}
                ).model_dump(),
            )
        return {"status": "ok", "results": results, "failed": failed}

    return router
