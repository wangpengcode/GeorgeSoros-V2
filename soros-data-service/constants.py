"""常量单点定义（PLAN §2.4 第④层 代码单点层）。

魔法值（数据源名、单位换算系数、代码格式转换、板块/市场推导）只允许在本模块定义，
业务代码一律引用本模块，禁止魔法字符串散落。对应 Kotlin 侧 enum
（Board / DataSourceType / QualityIssueType）+ 命名字典 docs/design/naming-dictionary.md。

对外契约铁律（PLAN §5.6 / §2.4）：
- code 一律裸数字 "600000"（不带 sh/sz 前缀）
- volume 统一单位=股；amount 统一单位=元
- change_percent 用原始不复权口径（数据源原始值）
"""

from __future__ import annotations

# ──────────────────────────────────────────────────────────────────────────────
# 数据源名称（PLAN §2.4 data_source 枚举允许值：BAOSTOCK / AKSHARE / MOOTDX / UNKNOWN）
# ──────────────────────────────────────────────────────────────────────────────
SOURCE_BAOSTOCK = "baostock"
SOURCE_AKSHARE = "akshare"
SOURCE_MOOTDX = "mootdx"
SOURCE_UNKNOWN = "unknown"
DATA_SOURCES = (SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX)

# ──────────────────────────────────────────────────────────────────────────────
# 单位换算系数（PLAN §2.4：volume 统一单位=股、amount=元）
#
# M0 探针实测（docs/research/probe-units-v2.md，2026-10-03）：
#   - BaoStock volume 原生=股（amount/volume≈收盘价 9.48，判定为股）、amount=元、preclose 有值；
#     另 query_history_k_data_plus 的 tradestatus/isST 字段确认存在。
#   - AKShare 成交量=手（×100 换算正确：1474848 手 ×100 ≈ BaoStock 的 147484820 股）、
#     成交额=元、qfq 与不复权列名完全一致。
#   - mootdx 未测（本机无可用节点）——单位换算假设=手 ×100，M3 接入时实测校准。
# ──────────────────────────────────────────────────────────────────────────────
BAOSTOCK_VOLUME_MULTIPLIER = 1      # 原生=股，无需换算
AKSHARE_VOLUME_MULTIPLIER = 100     # 手 → 股（探针实证）
MOOTDX_VOLUME_MULTIPLIER = 100      # 手 → 股（待 M3 实测校准）
BAOSTOCK_AMOUNT_MULTIPLIER = 1      # 元
AKSHARE_AMOUNT_MULTIPLIER = 1       # 元
MOOTDX_AMOUNT_MULTIPLIER = 1        # 元

# ──────────────────────────────────────────────────────────────────────────────
# 代码格式转换（PLAN §5.2）：对外统一裸数字，内部适配各数据源格式
# ──────────────────────────────────────────────────────────────────────────────
SH_PREFIXES = ("6", "9")  # 沪市：6xx 主板/科创板、9xx B股（B 股不采集，但前缀判断沿用）
SZ_PREFIXES = ("0", "3")  # 深市：0xx 主板、3xx 创业板


def to_baostock_code(code: str) -> str:
    """"600000" → "sh.600000"（BaoStock 内部格式，PLAN §5.2）"""
    prefix = "sh" if code.startswith(SH_PREFIXES) else "sz"
    return f"{prefix}.{code}"


def from_baostock_code(bs_code: str) -> str:
    """row[1] "sh.600000" → 去前缀裸数字 "600000"（PLAN §5.2）"""
    return bs_code.split(".")[-1]


def to_mootdx_market(code: str) -> int:
    """mootdx market 判定（PLAN §5.5）：沪=1、深=0"""
    return 1 if code.startswith(SH_PREFIXES) else 0


# AKShare / mootdx 输入直接用裸数字（恒等转换），无需额外函数。

# ──────────────────────────────────────────────────────────────────────────────
# 北交所过滤（PLAN §11.1 接口源探查结论）
# 默认含北交所，按代码前缀排除：旧号段 83/87/43 + 2025-10-09 起新号段 920
# ──────────────────────────────────────────────────────────────────────────────
NORTH_EXCHANGE_PREFIXES = ("83", "87", "43", "920")


def is_north_exchange(code: str) -> bool:
    return code.startswith(NORTH_EXCHANGE_PREFIXES)


# ──────────────────────────────────────────────────────────────────────────────
# 市场 / 板块推导（PLAN §2.4 board 枚举：MAIN 主板 / GEM 创业板 / STAR 科创板）
# 北交所、B 股不采集；调用方需先做北交所过滤再推导。
# ──────────────────────────────────────────────────────────────────────────────
MARKET_SH = "SH"
MARKET_SZ = "SZ"
BOARD_MAIN = "MAIN"
BOARD_GEM = "GEM"
BOARD_STAR = "STAR"
BOARD_ALL = "all"
MARKET_ALL = "all"


def derive_market(code: str) -> str:
    """6/9 开头 → SH，否则 SZ（PLAN §5.3 前缀规则）"""
    return MARKET_SH if code.startswith(SH_PREFIXES) else MARKET_SZ


def derive_board(code: str) -> str:
    """688 → STAR；300/301 → GEM；其余 → MAIN（PLAN §11.1 board 推导）"""
    if code.startswith("688"):
        return BOARD_STAR
    if code.startswith(("300", "301")):
        return BOARD_GEM
    return BOARD_MAIN


# 复权方式（PLAN §5.3 adjustflag 映射）
ADJUST_QFQ = "qfq"
ADJUST_HFQ = "hfq"
ADJUST_NONE = "none"
ADJUST_VALUES = (ADJUST_QFQ, ADJUST_HFQ, ADJUST_NONE)
BAOSTOCK_ADJUST_MAP = {ADJUST_QFQ: "2", ADJUST_HFQ: "1", ADJUST_NONE: "3"}

# ──────────────────────────────────────────────────────────────────────────────
# 错误码（PLAN §11.1 错误信封 error.code 允许值）
# 全局异常处理器（handlers.py）与 router.py 统一引用本区段，禁止散落硬编码。
# ──────────────────────────────────────────────────────────────────────────────
ERROR_PARAM_INVALID = "PARAM_INVALID"
ERROR_NOT_FOUND = "NOT_FOUND"
ERROR_STOCK_LIST_FAILED = "STOCK_LIST_FAILED"
ERROR_TRADING_CALENDAR_FAILED = "TRADING_CALENDAR_FAILED"
