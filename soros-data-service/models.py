"""请求/响应模型（PLAN §11.1 Python API 全契约 + §5.6 返回格式）。

JSON 键名严格对齐 PLAN §5.6 / §2.4 数据字典：
- daily-bars 每根 bar：date/code/open/high/low/close/volume/amount/change_percent/turnover/prev_close
- stock-list 每只股票：code/name/market/board/is_st/delisted
错误信封：顶层 {status:"error", error:{code,message}}；单股失败进 failed[]。
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field, field_validator

from constants import (
    BOARD_TYPES,
    is_stock_code,
    is_valid_tradeable_code,
)

# ──────────────────────────────────────────────────────────────────────────────
# /daily-bars/batch
# ──────────────────────────────────────────────────────────────────────────────
DATE_FORMAT = "%Y-%m-%d"


def _validate_date(value: str) -> str:
    try:
        datetime.strptime(value, DATE_FORMAT)
    except ValueError:
        raise ValueError(f"日期格式必须为 YYYY-MM-DD，收到: {value!r}")
    return value


class DailyBarsBatchRequest(BaseModel):
    codes: list[str] = Field(
        ...,
        description="证券代码列表：裸数字 6 位股票（600000）或带前缀指数（sh000001），可混收",
    )
    start_date: str = Field(..., description="起始日期 YYYY-MM-DD")
    end_date: str = Field(..., description="结束日期 YYYY-MM-DD（含）")
    adjust: str = Field("qfq", description="复权方式：qfq / hfq / none")

    @field_validator("codes")
    @classmethod
    def _validate_codes(cls, v: list[str]) -> list[str]:
        if not v:
            raise ValueError("codes 不能为空")
        cleaned = []
        for raw in v:
            code = str(raw).strip()
            if not is_valid_tradeable_code(code):
                raise ValueError(
                    f"code 必须为裸数字 6 位股票（600000）或带前缀指数（sh000001），收到: {code!r}"
                )
            cleaned.append(code)
        return cleaned

    @field_validator("start_date", "end_date")
    @classmethod
    def _validate_dates(cls, v: str) -> str:
        return _validate_date(v)

    @field_validator("adjust")
    @classmethod
    def _validate_adjust(cls, v: str) -> str:
        if v not in ("qfq", "hfq", "none"):
            raise ValueError(f"adjust 仅支持 qfq/hfq/none，收到: {v!r}")
        return v


class Bar(BaseModel):
    """单根日 K 线（PLAN §5.6 字段序与键名完全一致）。"""
    date: str
    code: str
    open: float
    high: float
    low: float
    close: float
    volume: float            # 股
    amount: float            # 元
    change_percent: float    # 不复权原始涨跌幅%
    turnover: float          # 换手率%
    prev_close: Optional[float] = None  # 除权后昨收（传输字段不落库；mootdx 不输出 → None）


class StockBarsResult(BaseModel):
    source: str
    count: int
    data: list[Bar]


class DailyBarsBatchResponse(BaseModel):
    status: str = "ok"
    results: dict[str, StockBarsResult]
    failed: list[dict] = Field(default_factory=list, description="[{code, reason}] 单股失败进 failed[]")


# ──────────────────────────────────────────────────────────────────────────────
# /stock-list
# ──────────────────────────────────────────────────────────────────────────────
class StockItem(BaseModel):
    code: str
    name: str
    market: str          # SH / SZ
    board: str           # MAIN / GEM / STAR
    is_st: bool          # 仅用于"识别并排除"
    delisted: bool       # 是否退市（缺失≠退市，人工确认才置 true）
    ipo_date: Optional[str] = None  # BaoStock query_stock_basic ipoDate（YYYY-MM-DD；缺失=null）


class StockListResponse(BaseModel):
    status: str = "ok"
    stocks: list[StockItem]


# ──────────────────────────────────────────────────────────────────────────────
# /health
# ──────────────────────────────────────────────────────────────────────────────
class HealthSources(BaseModel):
    baostock: str   # ok | degraded | down
    akshare: str
    mootdx: str


class HealthResponse(BaseModel):
    status: str                 # ok | degraded | down
    sources: HealthSources


# ──────────────────────────────────────────────────────────────────────────────
# /trading-calendar
# ──────────────────────────────────────────────────────────────────────────────
class CalendarResponse(BaseModel):
    dates: list[str]


# ──────────────────────────────────────────────────────────────────────────────
# /fundamentals（PLAN §11.1：AKShare stock_yjbb_em，report_date=季度末 YYYYMMDD）
# ──────────────────────────────────────────────────────────────────────────────
class FundamentalsRequest(BaseModel):
    report_date: str = Field(..., description="报告期季度末 YYYYMMDD（如 20241231）")

    @field_validator("report_date")
    @classmethod
    def _validate_report_date(cls, v: str) -> str:
        if len(v) != 8 or not v.isdigit():
            raise ValueError(f"report_date 必须为 YYYYMMDD（如 20241231），收到: {v!r}")
        return v


class FundamentalsStockItem(BaseModel):
    code: str               # 裸数字 6 位
    revenue: float          # 元（源亿元 ×1e8）
    net_profit: float       # 元


class FundamentalsResponse(BaseModel):
    status: str = "ok"
    stocks: list[FundamentalsStockItem]


# ──────────────────────────────────────────────────────────────────────────────
# /board-members（PLAN §4.8：东财板块成分，industry 每日 / concept 每周）
# ──────────────────────────────────────────────────────────────────────────────
class BoardMembersRequest(BaseModel):
    board_type: str = Field(..., description="industry / concept")

    @field_validator("board_type")
    @classmethod
    def _validate_board_type(cls, v: str) -> str:
        if v not in BOARD_TYPES:
            raise ValueError(f"board_type 仅支持 industry/concept，收到: {v!r}")
        return v


class BoardMembersResponse(BaseModel):
    status: str = "ok"
    boards: dict[str, list[str]]   # {板块名: [裸数字 codes...]}
    degraded: bool = False         # 任一板块成分拉取失败/降级 → true（§4.8 消费侧跳过清空，防误清全库）


# ──────────────────────────────────────────────────────────────────────────────
# /daily-bars/cross-validate（PLAN §11.2：双源交叉验证，mootdx 永不参与）
# ──────────────────────────────────────────────────────────────────────────────
class CrossValidateRequest(BaseModel):
    codes: list[str] = Field(..., description="证券代码：裸数字 6 位股票（指数/非股票不受支持）")
    start_date: str = Field(..., description="起始日期 YYYY-MM-DD")
    end_date: str = Field(..., description="结束日期 YYYY-MM-DD（含）")
    adjust: str = Field("qfq", description="复权方式：qfq / hfq / none")

    @field_validator("codes")
    @classmethod
    def _validate_codes(cls, v: list[str]) -> list[str]:
        if not v:
            raise ValueError("codes 不能为空")
        cleaned = []
        for raw in v:
            code = str(raw).strip()
            if not is_stock_code(code):
                raise ValueError(
                    f"cross-validate 仅支持股票裸数字 6 位（指数/非股票不受支持），收到: {code!r}"
                )
            cleaned.append(code)
        return cleaned

    @field_validator("start_date", "end_date")
    @classmethod
    def _validate_dates(cls, v: str) -> str:
        return _validate_date(v)

    @field_validator("adjust")
    @classmethod
    def _validate_adjust(cls, v: str) -> str:
        if v not in ("qfq", "hfq", "none"):
            raise ValueError(f"adjust 仅支持 qfq/hfq/none，收到: {v!r}")
        return v


class CrossSourceResult(BaseModel):
    source: str
    count: int
    data: list[Bar]
    error: Optional[str] = None  # 源故障文案；None=正常（部署穿透 2026-10-04：静默 0 行不可排查）


class CrossValidateResponse(BaseModel):
    status: str = "ok"
    results: dict[str, dict[str, CrossSourceResult]]  # {code: {source: result}}；源无数据 count=0 data=[]
    failed: list[dict] = Field(default_factory=list, description="[{code, reason}] 双源均失败进 failed[]")


# ──────────────────────────────────────────────────────────────────────────────
# 错误信封（PLAN §11.1）
# ──────────────────────────────────────────────────────────────────────────────
class ApiError(BaseModel):
    code: str
    message: str


class ErrorEnvelope(BaseModel):
    status: str = "error"
    error: ApiError
