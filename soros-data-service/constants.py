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
# V5 增 AKSHARE_SINA（akshare 内部 EM→新浪 failover 归因）/ YAHOO（第四源）；
# V6 增 TENCENT（第五源，独立转发商）/ SSE（第六源，上交所行情云，源头级）
# ──────────────────────────────────────────────────────────────────────────────
SOURCE_BAOSTOCK = "baostock"
SOURCE_AKSHARE = "akshare"
SOURCE_MOOTDX = "mootdx"
SOURCE_YAHOO = "yahoo"
SOURCE_TENCENT = "tencent"
SOURCE_SSE = "sse"
SOURCE_UNKNOWN = "unknown"
# 核心三源（Router/health 硬依赖）；yahoo/tencent/sse 为可选源（缺席不报错，
# 注册后进 failover 序尾——见 DataRouter._resolve_order「已注册」追加语义）
DATA_SOURCES = (SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX)
# 股票日K全量源域（含可选源；分片池/合法名校验用）
BAR_SOURCES = (SOURCE_BAOSTOCK, SOURCE_AKSHARE, SOURCE_MOOTDX, SOURCE_YAHOO, SOURCE_TENCENT, SOURCE_SSE)

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
# Tencent（第五源，2026-10-04 实测）：字段序 [..., volume(手), ..., amount(万元)]
TENCENT_VOLUME_MULTIPLIER = 100     # 手 → 股
TENCENT_AMOUNT_MULTIPLIER = 1e4     # 万元 → 元
# SSE（第六源，上交所行情云，2026-10-04 实测）：volume=股（与 baostock 一字不差）、amount=元
SSE_VOLUME_MULTIPLIER = 1           # 原生=股，无需换算
SSE_AMOUNT_MULTIPLIER = 1           # 元

# ──────────────────────────────────────────────────────────────────────────────
# 指数日K单位换算（探针 2026-10-03，见 docs/research 无专门文档，实测数据源结构）
#   - sina stock_zh_index_daily：全历史，列 date/open/high/low/close/volume，volume 视为股（1）
#   - tencent ifzq.gtimg.cn fqkline：range 可控，day 数组 [date,open,close,high,low,volume]，
#     volume = sina/100（探针 2026-09-30 sina=41456024700 / tencent=414560247）→ 手 ×100 对齐股
#   - 指数无"成交额"单值 → amount=0；无换手率 → turnover=0（PLAN §5.6 契约键仍 11 个）
# ──────────────────────────────────────────────────────────────────────────────
SINA_INDEX_VOLUME_MULTIPLIER = 1
TENCENT_INDEX_VOLUME_MULTIPLIER = 100

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


def to_tencent_symbol(code: str) -> str:
    """"600000" → "sh600000"（腾讯 fqkline symbol，§19.3 ① 实测：带市场前缀）"""
    prefix = "sh" if code.startswith(SH_PREFIXES) else "sz"
    return prefix + code


def to_sse_market(code: str) -> str:
    """上交所行情云 market 段（§19.3 ② 实测）：6/9 开头 → sh1，其余 → sz1。"""
    return "sh1" if code.startswith(SH_PREFIXES) else "sz1"


# AKShare / mootdx 输入直接用裸数字（恒等转换），无需额外函数。

# ──────────────────────────────────────────────────────────────────────────────
# 证券代码值口径判定（PLAN §2.4 显式特例）
# 股票 = 裸数字 600000；指数 = 带前缀 sh000001（仅 stock_index/index_history 两表）
# 对外 API（daily-bars/batch codes）两种形态混收，见 models.py 校验器。
# ──────────────────────────────────────────────────────────────────────────────
INDEX_PREFIXES = ("sh", "sz")


def is_stock_code(code: str) -> bool:
    """裸数字 6 位股票代码（600000）。"""
    return len(code) == 6 and code.isdigit()


def is_index_code(code: str) -> bool:
    """带前缀指数代码（sh000001 / sz399001），显式特例。"""
    return (
        len(code) == 8
        and code.startswith(INDEX_PREFIXES)
        and code[2:].isdigit()
        and len(code[2:]) == 6
    )


def is_valid_tradeable_code(code: str) -> bool:
    """daily-bars/batch 可采代码：股票裸数字 或 指数带前缀。"""
    return is_stock_code(code) or is_index_code(code)


def to_index_em_symbol(code: str) -> str:
    """带前缀指数 sh000001 → 东财 index_zh_a_hist 符号（去前缀 000001）。"""
    return code[2:]

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

# ──────────────────────────────────────────────────────────────────────────────
# 业绩报表 / 板块归属 / 交叉验证（PLAN Step 5a 新增能力，2026-10-03）
# ──────────────────────────────────────────────────────────────────────────────
# 业绩报表单位换算（PLAN §11.1：AKShare stock_yjbb_em 单位亿元 → 元）
FUNDAMENTALS_AMOUNT_MULTIPLIER = 1e8

# 板块归属（PLAN §4.8：行业每日 / 概念每周，东财 stock_board_*_em 链）
BOARD_TYPE_INDUSTRY = "industry"
BOARD_TYPE_CONCEPT = "concept"
BOARD_TYPES = (BOARD_TYPE_INDUSTRY, BOARD_TYPE_CONCEPT)

# 新增端点错误码（PLAN §11.1 错误信封 error.code 单点区段）
ERROR_FUNDAMENTALS_FAILED = "FUNDAMENTALS_FAILED"
ERROR_BOARD_MEMBERS_FAILED = "BOARD_MEMBERS_FAILED"
ERROR_CROSS_VALIDATE_FAILED = "CROSS_VALIDATE_FAILED"
