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

    def _is_all_sources_empty(errors: List[str]) -> bool:
        """errors 是否「所有源都是空结果」→ 停牌占位（results[code] count=0 data=[]，非 failed）。

        语义（重跑计划 verified-empty 铁律）：全源「空结果」= 该票该段无数据（停牌/未上市），
        非故障——Kotlin 侧据此记录 verified-empty，防反复空拉；任何源真实故障（SourceError）
        一律归 failed[]（绝不误判为停牌）。
        """
        if not errors:
            return False
        reason = errors[0]
        # _fetch_stock_daily 返回 [reason]：reason 形如 "All sources failed: baostock: 空结果; akshare: 空结果"
        if not reason.startswith("All sources failed"):
            return False
        parts = reason.split(": ", 1)[1].split("; ") if ": " in reason else []
        return bool(parts) and all(p.endswith("空结果") for p in parts)

    def _placeholder_source(errors: List[str]) -> str:
        """全源空结果占位的 source 标签：取 failover 序第一个源名（仅占位，非真实源归因）。"""
        try:
            return errors[0].split(": ", 1)[1].split("; ")[0].split(":")[0]
        except (IndexError, ValueError):
            return "baostock"

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
        """codes+dates 模式（向后兼容）：原契约行为零变化（多源分组并行 + 串行尾批）。"""
        # ── 多源分组并行（2026-10-04 设计定稿）──
        # 设计依据：并行只在源之间，源内节奏不变（防封禁铁律）——不同分片组并行互不干扰；
        # 组内逐只串行走 failover Router（现有语义），各源限流节奏各自独立（TokenBucket 每源独立）。
        # 分组归属与串行 failover 路径共用 DataRouter._shard_owner（单点判断，两处归属一致）。
        # 指数/非纯数字 code 无分片归属 → 归入串行尾批，主线程在所有 worker 完成后逐只拉取。
        groups: Dict[str, List[str]] = {}
        tail: List[str] = []
        for code in req.codes:
            owner = data_router._shard_owner(code)
            if owner is None:
                tail.append(code)
            else:
                groups.setdefault(owner.source_name, []).append(code)

        # worker：组内逐只串行调 failover Router（与现串行语义完全一致），各自收集 results/failed
        # （局部 dict/list 收集再合并，避免跨线程共享可变对象竞态；合并语义与原串行结果一致）
        def _worker(codes: List[str]) -> Tuple[Dict[str, dict], List[dict]]:
            local_results: Dict[str, dict] = {}
            local_failed: List[dict] = []
            for code in codes:
                result, errors = data_router.fetch_daily_bars(
                    code, req.start_date, req.end_date, req.adjust
                )
                if result is not None:
                    local_results[code] = result
                else:
                    local_failed.append(
                        {"code": code, "reason": errors[0] if errors else "All sources failed"}
                    )
            return local_results, local_failed

        results: Dict[str, dict] = {}
        failed: List[dict] = []
        if groups:
            # 非空组数 = worker 数：单组（或全部同归属）时 worker 数 1，组内串行天然等价现串行
            with ThreadPoolExecutor(max_workers=len(groups)) as pool:
                futures = [pool.submit(_worker, codes) for codes in groups.values()]
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

        # 串行尾批：指数等非分片代码，主线程逐只拉取（量小，串行防封禁）
        for code in tail:
            result, errors = data_router.fetch_daily_bars(
                code, req.start_date, req.end_date, req.adjust
            )
            if result is not None:
                results[code] = result
            else:
                failed.append(
                    {"code": code, "reason": errors[0] if errors else "All sources failed"}
                )
        return {"status": "ok", "results": results, "failed": failed}

    def _daily_bars_batch_items(req, data_router):
        """items 逐段窗口模式（重跑计划）：每 item 独立窗口拉取，并行只在源之间。

        - 分组归属与 codes 模式共用 _shard_owner（单点判断，两处归属一致）；组内逐段串行
          走 failover（防封禁铁律）；
        - 全源「空结果」→ results[code] count=0 data=[] 占位（停牌语义，非 failed），
          供 Kotlin 侧 verified-empty 记录（防反复空拉）；
        - 任何源真实故障 → failed[{code, reason}]（与 codes 模式 failed 语义一致）。
        """
        items = req.items
        groups: Dict[str, List[DailyBarsBatchItem]] = {}
        tail: List[DailyBarsBatchItem] = []
        for item in items:
            owner = data_router._shard_owner(item.code)
            if owner is None:
                tail.append(item)
            else:
                groups.setdefault(owner.source_name, []).append(item)

        def _worker_items(segments: List[DailyBarsBatchItem]) -> Tuple[Dict[str, dict], List[dict]]:
            local_results: Dict[str, dict] = {}
            local_failed: List[dict] = []
            for seg in segments:
                result, errors = data_router.fetch_daily_bars(
                    seg.code, seg.start_date, seg.end_date, req.adjust
                )
                if result is not None:
                    local_results[seg.code] = result
                elif _is_all_sources_empty(errors):
                    local_results[seg.code] = {
                        "source": _placeholder_source(errors),
                        "count": 0,
                        "data": [],
                    }
                else:
                    local_failed.append(
                        {"code": seg.code, "reason": errors[0] if errors else "All sources failed"}
                    )
            return local_results, local_failed

        results: Dict[str, dict] = {}
        failed: List[dict] = []
        if groups:
            with ThreadPoolExecutor(max_workers=len(groups)) as pool:
                futures = [pool.submit(_worker_items, segments) for segments in groups.values()]
                for (src, segments), fut in zip(groups.items(), futures):
                    try:
                        r, f = fut.result()
                    except Exception as exc:  # noqa: BLE001
                        logger.error("batch items worker(%s) 异常: %s", src, exc)
                        r, f = {}, [{"code": s.code, "reason": f"worker 异常: {exc}"} for s in segments]
                    results.update(r)
                    failed.extend(f)

        # 串行尾批：指数等非分片代码，主线程逐段拉取（量小，串行防封禁）
        for seg in tail:
            result, errors = data_router.fetch_daily_bars(
                seg.code, seg.start_date, seg.end_date, req.adjust
            )
            if result is not None:
                results[seg.code] = result
            elif _is_all_sources_empty(errors):
                results[seg.code] = {
                    "source": _placeholder_source(errors),
                    "count": 0,
                    "data": [],
                }
            else:
                failed.append(
                    {"code": seg.code, "reason": errors[0] if errors else "All sources failed"}
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
