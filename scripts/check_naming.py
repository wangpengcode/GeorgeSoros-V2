#!/usr/bin/env python3
"""命名字典 CI 校验（§2.4 铁律的机器兜底）。

双向 diff：
  schema.sql 全部列名  ↔  docs/design/naming-dictionary.md 第五节全字段枚举表
任何一侧多出/缺失即 exit 1 —— 新列必须「先增册再用名」。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "docs/design/schema.sql"
DICT = ROOT / "docs/design/naming-dictionary.md"

# ---- schema.sql 列名 ----
sql = SCHEMA.read_text(encoding="utf-8")
schema_cols = set()
for tname, body in re.findall(r"CREATE TABLE\s+(\w+)\s*\((.*?)\n\);", sql, re.S):
    for line in body.split("\n"):
        m = re.match(
            r"\s*(\w+)\s+(BIGSERIAL|SERIAL|BIGINT|INT|INTEGER|SMALLINT|VARCHAR|CHAR|TEXT"
            r"|BOOLEAN|DATE|TIMESTAMP|NUMERIC|JSONB)",
            line, re.I,
        )
        if m:
            schema_cols.add(m.group(1))

# ---- 命名字典第五节枚举表（| `col` | ... |）----
dict_cols = set()
in_section5 = False
for line in DICT.read_text(encoding="utf-8").split("\n"):
    if line.startswith("## 五、"):
        in_section5 = True
        continue
    if in_section5 and line.startswith("## "):
        break
    if in_section5:
        m = re.match(r"^\|\s*`(\w+)`\s*\|", line)
        if m:
            dict_cols.add(m.group(1))

missing_in_dict = sorted(schema_cols - dict_cols)
phantom_in_dict = sorted(dict_cols - schema_cols)

ok = True
if missing_in_dict:
    ok = False
    print("❌ schema.sql 有列未增册（先增册再用名）：", ", ".join(missing_in_dict))
if phantom_in_dict:
    ok = False
    print("❌ 命名字典有幻影字段（schema.sql 已无此列，字典过期）：", ", ".join(phantom_in_dict))
if ok:
    print(f"✅ 命名一致性通过：schema.sql {len(schema_cols)} 列 ↔ 字典枚举 {len(dict_cols)} 字段完全对齐")
sys.exit(0 if ok else 1)
