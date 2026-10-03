"""全局异常处理器（PLAN §11.1 错误信封）。

FastAPI 默认的 422/404 错误体为 {"detail": ...}，与 §11.1 信封形
{status:"error", error:{code,message}} 冲突。本模块提供 app 级 exception_handler
注册入口（register_exception_handlers），生产入口 main.py 与测试 fixture
（tests/conftest.py）统一调用，保证真实服务与测试契约一致。

- RequestValidationError → 422 信封 PARAM_INVALID，message 从原始 errors 提炼
  （字段路径+原因），不泄漏堆栈
- HTTPException 404 → 404 信封 NOT_FOUND
- 非 404 HTTPException（如 405）→ 委托 FastAPI 默认 handler，保持原行为
"""

from __future__ import annotations

import logging
from typing import Any, Iterable, List

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from constants import ERROR_NOT_FOUND, ERROR_PARAM_INVALID
from models import ErrorEnvelope

logger = logging.getLogger(__name__)


def _loc_to_path(loc: Iterable[Any]) -> str:
    """pydantic errors loc → 人类可读字段路径，如 ('body','codes',0) → body.codes[0]."""
    parts: List[str] = []
    for item in loc:
        if isinstance(item, int):
            parts.append(f"[{item}]")
        else:
            parts.append(str(item))
    return ".".join(parts)


def _validation_message(exc: RequestValidationError) -> str:
    """从 FastAPI 原始 errors 提炼人类可读摘要（字段路径 + 原因），不泄漏堆栈。"""
    details = []
    for err in exc.errors():
        path = _loc_to_path(err.get("loc", ()))
        msg = err.get("msg", "校验失败")
        details.append(f"{path}: {msg}")
    return "参数校验失败: " + "; ".join(details) if details else "参数校验失败"


async def _request_validation_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    message = _validation_message(exc)
    logger.warning("请求参数校验失败: %s", message)
    return JSONResponse(
        status_code=422,
        content=ErrorEnvelope(
            error={"code": ERROR_PARAM_INVALID, "message": message}
        ).model_dump(),
    )


async def _http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    if exc.status_code == 404:
        logger.warning("未知路径 404: %s", request.url.path)
        return JSONResponse(
            status_code=404,
            content=ErrorEnvelope(
                error={"code": ERROR_NOT_FOUND, "message": f"路径不存在: {request.url.path}"}
            ).model_dump(),
        )
    from fastapi.exception_handlers import http_exception_handler

    return await http_exception_handler(request, exc)


def register_exception_handlers(app: FastAPI) -> None:
    """注册 §11.1 错误信封 handler（重复调用覆盖同键，幂等）。"""
    app.add_exception_handler(RequestValidationError, _request_validation_handler)
    app.add_exception_handler(StarletteHTTPException, _http_exception_handler)
