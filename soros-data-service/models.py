"""请求/响应模型（PLAN §11.1 Python API 全契约 + §5.6 返回格式）。

JSON 键名严格对齐 PLAN §5.6 / §2.4 数据字典：
- daily-bars 每根 bar：date/code/open/high/low/close/volume/amount/change_percent/turnover/prev_close
- stock-list 每只股票：code/name/market/board/is_st/delisted
错误信封：顶层 {status:"error", error:{code,message}}；单股失败进 failed[]。
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field, field_validator, model_validator

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


class DailyBarsBatchItem(BaseModel):
    """items 单段：code + 独立拉取窗口（回填重跑计划逐段窗口模式，PLAN 2026-10-04 设计定稿）。

    每段一个 [code, start_date, end_date]，Python 侧按段窗口分别拉取；响应仍按 code 键聚合。
    铁律：请求批内 code 不得重复（同批重复造成 results 按 code 键归属歧义）→ 422。
    """

    code: str
    start_date: str = Field(..., description="起始日期 YYYY-MM-DD")
    end_date: str = Field(..., description="结束日期 YYYY-MM-DD（含）")

    @field_validator("code")
    @classmethod
    def _validate_code(cls, v: str) -> str:
        code = str(v).strip()
        if not is_valid_tradeable_code(code):
            raise ValueError(
                f"code 必须为裸数字 6 位股票（600000）或带前缀指数（sh000001），收到: {code!r}"
            )
        return code

    @field_validator("start_date", "end_date")
    @classmethod
    def _validate_dates(cls, v: str) -> str:
        return _validate_date(v)


class DailyBarsBatchRequest(BaseModel):
    """batch 请求二选一（XOR 铁律，路由层 422 语义校验，见 router.daily_bars_batch）：

    - items 模式：items=[{code, start_date, end_date}...] 逐段独立窗口（重跑计划）；
    - codes 模式：codes + start_date + end_date（向后兼容，原契约不变）。
    混用 / 两者皆空 / codes 模式缺日期 → 422 PARAM_INVALID；数量超 batch_max_codes → 422。
    """

    codes: list[str] = Field(
        default_factory=list,
        description="证券代码列表（codes+dates 模式）：裸数字 6 位股票（600000）或带前缀指数（sh000001）",
    )
    start_date: Optional[str] = Field(None, description="起始日期 YYYY-MM-DD（codes 模式必填）")
    end_date: Optional[str] = Field(None, description="结束日期 YYYY-MM-DD，含（codes 模式必填）")
    items: list[DailyBarsBatchItem] = Field(
        default_factory=list,
        description="逐段窗口模式：{code, start_date, end_date} 独立窗口（与 codes+dates 二选一）",
    )
    adjust: str = Field("qfq", description="复权方式：qfq / hfq / none")

    @field_validator("codes")
    @classmethod
    def _validate_codes(cls, v: list[str]) -> list[str]:
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
    def _validate_dates(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        return _validate_date(v)

    @field_validator("adjust")
    @classmethod
    def _validate_adjust(cls, v: str) -> str:
        if v not in ("qfq", "hfq", "none"):
            raise ValueError(f"adjust 仅支持 qfq/hfq/none，收到: {v!r}")
        return v

    @model_validator(mode="after")
    def _validate_xor(self) -> DailyBarsBatchRequest:
        """items 与 codes 二选一铁律（模型层兜底两者皆空；混用/数量超限由路由层 422 信封语义校验）。

        两者皆空 → 模型层即拒（ValidationError → FastAPI 422 信封 PARAM_INVALID）；
        items 或 codes 单边非空 → 放行（路由层继续 XOR 语义校验）。
        """
        if not self.codes and not self.items:
            raise ValueError("必须提供 items 或 codes+start_date+end_date 二选一")
        return self


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
    change_percent: Optional[float] = None  # 不复权原始涨跌幅%；新股/窗口首行无前收盘 → None（2021 后上市股回填实测）
    turnover: Optional[float] = None        # 换手率%；个别源停牌日可能缺
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
    yahoo: Optional[str] = None    # 可选源：未注册为 None（整体状态聚合忽略 None）
    tencent: Optional[str] = None # 第五源（可选）：未注册为 None
    sse: Optional[str] = None     # 第六源（可选）：未注册为 None


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
# /channels（渠道运营台账，CORS 页面展示：每源 最近成功/失败时间 + 封禁 + 熔断）
# 时间戳格式 "%Y-%m-%d %H:%M:%S"，与 /ipguard 现状一致（本地墙钟，非 ISO）。
# ──────────────────────────────────────────────────────────────────────────────
class ChannelBanned(BaseModel):
    """IPGuard 封禁快照（与 /ipguard 的 banned[source] 同构：{at, egress_ip}）。"""
    at: str
    egress_ip: Optional[str] = None


class ChannelStatus(BaseModel):
    source: str
    health: str                            # ok | degraded | down（CircuitBreaker.health 口径）
    last_success_at: Optional[str] = None  # 最近一次成功时间（进程态，重启即清；null=从未成功）
    last_failure_at: Optional[str] = None  # 最近一次失败时间（SourceError；null=从未失败）
    banned: Optional[ChannelBanned] = None  # 封禁中：{at, egress_ip}；未封禁 null
    breaker: str                           # closed | open | half_open（CircuitBreaker.state）


class ChannelsResponse(BaseModel):
    status: str = "ok"
    channels: list[ChannelStatus]


# ──────────────────────────────────────────────────────────────────────────────
# 错误信封（PLAN §11.1）
# ──────────────────────────────────────────────────────────────────────────────
class ApiError(BaseModel):
    code: str
    message: str


class ErrorEnvelope(BaseModel):
    status: str = "error"
    error: ApiError
