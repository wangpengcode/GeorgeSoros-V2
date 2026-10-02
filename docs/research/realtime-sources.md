# GeorgeSoros-V2 盘中实时行情数据源调研（设计输入）

> 调研日期：2026-10-02（国庆假期，A 股休市；涉及盘口的实测项以休市状态为准，已标注）
> 方法：AKShare/mootdx/tdxpy 源码逐字 Read（本地快照 `/tmp/soros-v2-design/src/`）+ 东财接口 curl 实测（2026-10-02）+ 官方文档 WebFetch + GitHub issues/社区交叉验证。
> 所有字段名逐字来自源码或实测返回；不确定项一律标 **未核实**。
> 详细附录（含全部推理过程与更多引用）：`part1-zt-pool.md` / `part2-spot-min.md` / `part3-ratelimit.md` / `part4-mootdx.md`（同目录）。

## 0. 结论速览

| 能力 | 结论 | 关键限制 |
|---|---|---|
| 涨停池 6 接口（push2ex） | 涨停池字段全齐（首次/最后封板时间、炸板次数、封板资金、连板数、涨停统计），date 参数可查历史 | **东财服务端仅保留约 30 天**（实测边界 20260909 有 / 20260901 空）→ 长历史必须每日落库自建 |
| 全市场快照 `stock_zh_a_spot_em` | 一次调用返回沪深京全 A（23 列，含最新价/涨跌幅/昨收） | 内部 ~55-60 页分页循环，**单次耗时 30-90s**，同批数据时点不同步；**无盘口、无涨停/跌停价** |
| 五档盘口（东财） | `stock_bid_ask_em` 单只可取买卖五档价量 + **涨停/跌停价** | 仅单只查询，全市场五档不可行（~5400 次 HTTP/轮） |
| 分钟线 `stock_zh_a_hist_min_em` | period 1/5/15/30/60 | **1 分钟只给近 5 个交易日且不复权**；5 分钟以上服务端也只保留近几个月（具体月数未核实）→ 长历史 1 分钟必须每日增量自建库 |
| mootdx 实时 | 能力对路：TCP 直连（无需通达信客户端）、quotes 含完整五档、80 只/次 | **2026-07 起公共节点实时行情大面积失效（issue #157 仍 open）+ 项目停更 2 年**，当前只能作实验性备源 |
| 限频/封 IP | 东财 2025-04 起 IP 级限频；~50 并发即拒、~200 只历史请求即断、封禁实测约 5h、换 IP 即解 | AKShare 涨停池/分钟线接口**零防护裸请求（无 UA、无 timeout）**，业务侧必须补齐 |

---

## 1. 东财涨停池系列（AKShare `stock_zt_pool_*_em`）

源码：`akshare/stock_feature/stock_ztb_em.py`（main 分支；本地快照 `/tmp/soros-v2-design/src/stock_ztb_em.py`）。
字段中文列名逐字抄自源码 columns 映射；东财原始 key（fbt/lbt/zbc 等）**经 2026-10-02 curl 实测 `push2ex.eastmoney.com` 真实返回逐一确认**。

### 1.0 总览

| 函数 | 池 | Endpoint（push2ex.eastmoney.com） | pagesize | sort | 源码日期校验 | 实测行数（20260930） |
|---|---|---|---|---|---|---|
| stock_zt_pool_em | 涨停股池 | /getTopicZTPool | 10000 | fbt:asc | 无 | tc=52 |
| stock_zt_pool_previous_em | 昨日涨停股池 | /getYesterdayZTPool | 5000 | zs:desc | 无 | tc=57 |
| stock_zt_pool_strong_em | 强势股池 | /getTopicQSPool | 5000 | zdp:desc | 无 | tc=199 |
| stock_zt_pool_sub_new_em | 次新股池 | /getTopicCXPooll（末尾双 l） | 5000 | ods:asc | 无 | tc=163 |
| stock_zt_pool_zbgc_em | 炸板股池 | /getTopicZBPool | 5000 | fbt:asc | **仅最近 30 自然日**（超期 ValueError） | tc=12 |
| stock_zt_pool_dtgc_em | 跌停股池 | /getTopicDTPool | 10000 | fund:asc | **仅最近 30 自然日**（超期 ValueError） | tc=9 |

公共参数：`ut="7eea3edcaed734bea9cbfc24409ed989"`、`dpt="wz.ztzt"`、`Pageindex="0"`（恒为 0，**无翻页，一次取回全部**；pagesize 远大于单日实际行数，可视为不截断）。
**date 参数：`str` YYYYMMDD（如 "20241008"），支持查历史日期**，服务端返回 `qdate` 标注实际数据日期。历史深度见 §1.7。
3 个共用原始字段（akshare 均丢弃）：`m`=市场代码（0=深含创业板，1=沪含科创板）、`ztf`=涨停标志 "1"/"0"、总市值原始 key 实测为 `tshare`。

### 1.1 stock_zt_pool_em（涨停股池）——核心接口

```python
def stock_zt_pool_em(date: str = "20241008") -> pd.DataFrame
```

返回字段（17 原始 → 15 输出列）：

| akshare 列名 | 东财 key | 含义（实测确认） |
|---|---|---|
| 序号 | (index) | 1 起连续编号 |
| 代码 | c | 6 位代码 |
| 名称 | n | 股票名称 |
| 最新价 | p | 原始值 /1000 还原 |
| 涨跌幅 | zdp | % |
| 成交额 | amount | 成交额 |
| 流通市值 | ltsz | 流通市值 |
| 总市值 | tshare | 总市值 |
| 换手率 | hs | % |
| 连板数 | lbc | 连续涨停板数 |
| 首次封板时间 | fbt | HHMMSS，zfill(6) |
| 最后封板时间 | lbt | HHMMSS，zfill(6) |
| 封板资金 | fund | 封板资金（封单额） |
| 炸板次数 | zbc | 炸板（开板）次数 |
| 所属行业 | hybk | 行业板块 |
| 涨停统计 | zttj | dict{days,ct} → "days/ct"（如 "3/2"） |

**关键字段确认：首次封板时间 ✅ / 最后封板时间 ✅ / 炸板次数 ✅ / 封板资金 ✅ / 连板数 ✅ / 涨停统计 ✅ —— 六项全齐。**

### 1.2 stock_zt_pool_zbgc_em（炸板股池）

```python
def stock_zt_pool_zbgc_em(date: str = "20241011") -> pd.DataFrame
```

语义：当日触及过涨停且当前未封板。⚠️ 源码硬校验仅最近 30 自然日（超期抛 ValueError）。

| akshare 列名 | 东财 key | 备注 |
|---|---|---|
| 序号 / 代码 / 名称 | (index)/c/n | |
| 最新价 | p | /1000 |
| 涨停价 | ztp | /1000 |
| 涨跌幅 | zdp | % |
| 成交额 | amount | |
| 流通市值 / 总市值 | ltsz / tshare | |
| 换手率 | hs | % |
| 首次封板时间 | fbt | ✅ |
| 炸板次数 | zbc | ✅ |
| 振幅 | zf | % |
| 涨速 | zs | % |
| 涨停统计 | zttj | ✅ "days/ct" |
| 所属行业 | hybk | |

关键确认：首次封板时间 ✅、炸板次数 ✅、涨停统计 ✅；最后封板时间 ❌、封板资金 ❌、连板数 ❌。

### 1.3 stock_zt_pool_dtgc_em（跌停股池）

```python
def stock_zt_pool_dtgc_em(date: str = "20241011") -> pd.DataFrame
```

⚠️ 源码硬校验仅最近 30 自然日；⚠️ 无 `data is None` 保护（空返回抛 TypeError，调用方需容错）。

| akshare 列名 | 东财 key | 备注 |
|---|---|---|
| 序号 / 代码 / 名称 | (index)/c/n | |
| 最新价 | p | /1000 |
| 涨跌幅 | zdp | % |
| 成交额 | amount | |
| 流通市值 / 总市值 | ltsz / tshare | |
| 动态市盈率 | pe | |
| 换手率 | hs | % |
| 封单资金 | fund | 跌停封单额 ✅ |
| 最后封板时间 | lbt | ✅ |
| 板上成交额 | fba | |
| 连续跌停 | days | 连续跌停天数 |
| 开板次数 | oc | 等价"炸板次数" ✅ |
| 所属行业 | hybk | |

关键确认：最后封板时间 ✅、封单资金 ✅、开板次数 ✅、连续跌停 ✅；首次封板时间 ❌、涨停统计 ❌（跌停池无此概念）。

### 1.4 stock_zt_pool_strong_em（强势股池）

```python
def stock_zt_pool_strong_em(date: str = "20241231") -> pd.DataFrame
```

语义：创 60 日新高 或 近期多次涨停。

| akshare 列名 | 东财 key | 备注 |
|---|---|---|
| 序号 / 代码 / 名称 | (index)/c/n | |
| 最新价 | p | /1000 |
| 涨停价 | ztp | /1000 |
| 涨跌幅 | zdp | % |
| 成交额 | amount | |
| 流通市值 / 总市值 | ltsz / tshare | |
| 换手率 | hs | % |
| 是否新高 | nh | 1→"是" |
| 入选理由 | cc | 1→"60日新高"，2→"近期多次涨停"，3→两者兼有 |
| 量比 | lb | |
| 涨速 | zs | % |
| 涨停统计 | zttj | ✅ "days/ct" |
| 所属行业 | hybk | |

关键确认：无任何封板时间/炸板次数/封板资金/连板数字段；特色为 是否新高/入选理由/量比。

### 1.5 stock_zt_pool_previous_em（昨日涨停股池）

```python
def stock_zt_pool_previous_em(date: str = "20240415") -> pd.DataFrame
```

语义：date 传"昨日交易日"，取该日收盘时涨停的股票池。⚠️ 老日期空返回时源码列名赋值会崩（Issue #5077：`Length mismatch: Expected axis has 1 elements, new values have 17`），调用方需判空容错。

| akshare 列名 | 东财 key | 备注 |
|---|---|---|
| 序号 / 代码 / 名称 | (index)/c/n | |
| 最新价 | p | /1000 |
| 涨停价 | ztp | /1000 |
| 涨跌幅 | zdp | % |
| 成交额 | amount | |
| 流通市值 / 总市值 | ltsz / tshare | |
| 换手率 | hs | % |
| 振幅 | zf | % |
| 涨速 | zs | % |
| 昨日封板时间 | yfbt | ⚠️ 语义为昨日首次封板时间 |
| 昨日连板数 | ylbc | ✅ |
| 所属行业 | hybk | |
| 涨停统计 | zttj | ✅ "days/ct" |

关键确认：封板时间 ⚠️ 仅"昨日封板时间"；最后封板时间 ❌、炸板次数 ❌、封板资金 ❌；昨日连板数 ✅、涨停统计 ✅。

### 1.6 stock_zt_pool_sub_new_em（次新股池，附加项）

```python
def stock_zt_pool_sub_new_em(date: str = "20241231") -> pd.DataFrame
```

语义：上市一年以内且中断连续一字涨停板。⚠️ 源码缺 `data is None` 保护（空返回抛 TypeError）。

| akshare 列名 | 东财 key | 备注 |
|---|---|---|
| 序号 / 代码 / 名称 | (index)/c/n | |
| 最新价 | p | /1000 |
| 涨停价 | ztp | /1000；>100000 置 pd.NA（占位值 1000000000） |
| 涨跌幅 | zdp | % |
| 成交额 | amount | |
| 流通市值 / 总市值 | ltsz / tshare | |
| 转手率 | hs | 源码列名即"转手率"（=换手率） |
| 开板几日 | ods | 距开板天数 |
| 开板日期 | od | %Y%m%d |
| 上市日期 | ipod | %Y%m%d；==0 置 NaT |
| 是否新高 | nh | 1→"是" |
| 涨停统计 | zttj | ✅ |
| 所属行业 | hybk | |
| （丢弃） | o | 实测恒为 1，含义 **未核实** |

### 1.7 历史回溯深度（关键结论）

**东财服务端对涨停池专题仅保留约 30 天。** 实测（2026-10-02，getTopicZTPool 逐日探测）：

| date | tc | 说明 |
|---|---|---|
| 20260930 | 52 | 最近交易日正常 |
| 20260910 / 20260909 | 48 / 48 | 有数据（~22 天前） |
| **20260901** | **0** | **空（~31 天前）——边界** |
| 20260831 及更早（含 20250801/20241008） | 0 | 全部为空，akshare 默认示例日期已取不到 |

- 昨日涨停池（20260909 tc=73 / 20260901 tc=0）与次新股池（158/0）**实测同一 30 天边界**；强势/炸板/跌停池未逐一测边界，同为 push2ex 涨停专题判定一致（**未核实**）。
- 源码层面仅 zbgc/dtgc 有 30 自然日硬校验；**其余 4 个接口传老日期静默返回空 DataFrame**（previous 池还会触发 #5077 崩溃）。
- 文档/社区证据：官方文档与知乎专栏统一注记"该接口只能获取近期的数据"（未给明确天数）；CSDN 称"只有最近半个月的涨停板数据，需要自行存历史"；社区口径"半个月~1 个多月"与实测 30 天一致。
- **设计含义：涨停池长历史唯一免费路径 = 每日定时采集落库自建（保留东财原始口径）；替代源 Tushare `limit_list_d`（2020 起）/`limit_list_ths`（2023-11 起）/`kpl_list`（开盘啦，需 5000 积分）。**

来源：
- 源码 https://github.com/akfamily/akshare/blob/main/akshare/stock_feature/stock_ztb_em.py （本地快照逐行 Read）
- 文档 https://akshare.akfamily.xyz/data/stock/stock.html
- 实测 curl push2ex.eastmoney.com 各 endpoint（2026-10-02，date=20260930 及边界探测）
- Issues：https://github.com/akfamily/akshare/issues/5077 （previous 池老日期崩溃）、https://github.com/akfamily/akshare/issues/5284
- 社区：https://zhuanlan.zhihu.com/p/969697358 、https://zhuanlan.zhihu.com/p/505425999 、https://blog.csdn.net/myqijin/article/details/144425164 、https://developer.cloud.tencent.com/article/2752717

---

## 2. 全市场实时快照 `stock_zh_a_spot_em`

源码：`akshare/stock_feature/stock_hist_em.py` L15-121（同文件有 stock_sh/sz/bj_a_spot_em 分市场变体，字段与逻辑相同，仅 fs 收窄）。

### 2.1 返回字段（23 列，逐字抄源码）

> 序号、代码、名称、最新价、涨跌幅、涨跌额、成交量、成交额、振幅、最高、最低、今开、昨收、量比、换手率、市盈率-动态、市净率、总市值、流通市值、涨速、5分钟涨跌、60日涨跌幅、年初至今涨跌幅

f 编号对照：最新价=**f2**、涨跌幅=**f3**、涨跌额=f4、成交量=f5（手）、成交额=f6（元）、振幅=f7、换手率=f8、市盈率-动态=f9、量比=f10、5分钟涨跌=f11、代码=f12、名称=f14、最高=f15、最低=f16、今开=f17、昨收=**f18**、总市值=f20、流通市值=f21、涨速=f22、市净率=f23、60日涨跌幅=f24、年初至今涨跌幅=f25。

**关键确认：**
- ✅ 有：最新价、涨跌幅、昨收（可算日内涨跌与强弱）、涨速、5分钟涨跌。
- ❌ **无：买一/卖一价量、五档盘口**（clist 快照不含盘口，f19~f40 未请求）。
- ❌ **无：涨停价、跌停价**（f51/f52 未请求）→ 需走 `stock_bid_ask_em`（§2.4）或池接口 ztp 字段（仅涨停相关池）或按昨收自算（各板块涨跌幅限制规则本次未核实）。

### 2.2 是否单请求返回全市场

**一次函数调用返回沪深京全 A，但非单 HTTP 请求**：内部经 `fetch_paginated_data` 分页循环，`pz=100`/页，先取首页拿 total 再循环 ~55-60 页，页间 `sleep(uniform(0.5,1.5))`，最后 concat + 按涨跌幅降序。**单次调用约 30-90 秒**（社区实测 83.54s，Issue #6986）。
2025 年起东财强制单页上限 100（传 200 只回 100，曾致旧版 akshare 截断：#5803/#5866/#6064/#7346），当前 main 分支已用 pz=100 规避。

> ⚠️ **未核实**：本次调研期间 pz=5 单样本实测返回 `total=59271` 且首页混入板块行（f12="BK1627"），与社区"~5400 全 A"口径不符，随后被限流未能复验。**落地前必须用 akshare 完整跑一次，核对行数与代码集**（fs 参数是否混入板块/非 A 品种待确认）。

### 2.3 Endpoint 与实时性

- Endpoint：`https://82.push2.eastmoney.com/api/qt/clist/get`（82 为分节点，16/72/81/90 等为镜像）；`ut=bd1d9ddb04089700cf9c27f6f7426281`、`fltt=2`、`invt=2`。
- fs 市场过滤（源码逐字）：`"m:0 t:6,m:0 t:80,m:1 t:2,m:1 t:23,m:0 t:81 s:2048"` = 深主板+创业板+沪主板+科创板+北交所（沪深京全 A）。
- 实时性：东财 push2 为 **Level-1 快照（交易所 3 秒一档）**，社区称端到端 1~3s"准实时"（无官方保证，秒级数字属社区口径）；**全市场遍历非原子**——分页期间各股时点不同，同一 DataFrame 内时间戳不同步；若被 302 到 `push2delay.eastmoney.com` 则为 **~15 分钟延迟行情**。

### 2.4 五档盘口补充：`stock_bid_ask_em`（单只）

源码：`akshare/stock/stock_ask_bid_em.py`。Endpoint `https://push2.eastmoney.com/api/qt/stock/get`，`secid={1 if 代码 6 开头 else 0}.{symbol}`。

- **单只查询**，返回长表（item/value 两列），含：**买一~买五价量**（f19/f17/f15/f13/f11 价，f20/f18/f16/f14/f12 量×100）、**卖一~卖五价量**（f31/f33/f35/f37/f39 价，f32/f34/f36/f38/f40 量×100）、最新 f43、均价 f71、涨幅 f170、涨跌 f169、总手 f47、金额 f48、换手 f168、量比 f50、最高 f44、最低 f45、今开 f46、昨收 f60、**涨停 f51、跌停 f52**、外盘 f49、内盘 f161。
- 休市实测（2026-10-02，secid=0.000001）：最新价/涨停 12.49/跌停 10.22 有值；买一/卖一为 None（休市盘口清空）。**盘中盘口字段是否必有值未核实**（源码字段映射明确）。
- ⚠️ secid 只按"是否 6 开头"二分，**北交所（8/4/920 开头）判定可能不准**（未核实）。
- **结论：盘中自选池（几十只）单只盘口+涨停跌停价可走此接口；全市场五档不可行**（~5400 次 HTTP/轮）。全市场盘口方向：东财 push2 批量 secids 接口自行封装（**未核实**，akshare 无现成接口）或商业 L1/L2 源。

来源：
- 源码 https://github.com/akfamily/akshare/blob/main/akshare/stock_feature/stock_hist_em.py 、https://github.com/akfamily/akshare/blob/main/akshare/stock/stock_ask_bid_em.py 、akshare/utils/func.py（fetch_paginated_data）
- 文档 https://akshare.akfamily.xyz/data/stock/stock.html
- Issues：https://github.com/akfamily/akshare/issues/6986 （83.54s/时点不同步）、/5803 /5866 /6064 /7346 /5810（分页截断）
- 社区：https://forum.trae.cn/t/topic/95939 （准实时 1~3s）、https://datacube.foundersc.com/document/41?doc_id=10655 （L1 3 秒快照）、https://sive.antv.antgroup.com/skills/MKjpgNDy9gnYzEJZxnl4 （push2delay 15min）、https://cloud.tencent.com/developer/article/2696683 （≥30s 轮询建议）

---

## 3. 反爬与频率（东财接口）

> 依据强度标注：**实测案例**=有具体次数/时长可复现报告；**社区共识**=多来源一致经验；**推测**=无直接数据外推。

### 3.1 已知风险点

| 风险 | 说明 | 来源 |
|---|---|---|
| IP 级频率限制已生效 | efinance 维护者确认**自 2025-04 起东财针对 IP 限频**，日 K 与全市场实时行情类接口受影响；AKShare 同源同壁 | efinance discussions/216 |
| 高并发触发封 IP | 并发 ~50 请求即被拒；**一次全市场快照内部就是 ~50 个分页请求，本身即高并发风险点** | akshare #6986、#6061 |
| 历史/分钟线请求量上限 | 请求 ~200 只后 `Connection aborted / RemoteDisconnected` | akshare #6214 |
| 反爬升级：滑动验证码 | 频繁访问弹滑块 | akshare #6239 |
| 封禁恢复 | 唯一实测**约 5 小时解禁**，解禁后立即再拉又触发；**换 IP 即时解**（最可靠恢复手段） | akshare #6100 |
| RemoteDisconnected 常态化 | 大量 issue；akshare 已合并重试补丁 #7333/#7311 | #6658 等 |
| 极端情形 | #7396 标题"东财的接口基本都不可用"——高峰期大面积故障真实存在 | #7396 |

**官方口径：无。** 直接提问限频阈值的 #6990/#4542 均无维护者实质回复；东财从不公告反爬策略。所有间隔都是社区经验。

### 3.2 AKShare 源码防护现状（逐行核对，akshare 1.18.88 与 main 一致）

| 接口 | 调用方式 | UA | timeout | 重试/退避 | Session |
|---|---|---|---|---|---|
| stock_zh_a_spot_em | request_with_retry + fetch_paginated_data | **无** | 15s | **有**：3 次重试、指数退避 1s*2^n+抖动、页间 sleep 0.5-1.5s | 每次新建，禁用连接池 |
| stock_zh_a_hist_min_em | 裸 requests.get | **无** | 15s | 无 | 无 |
| stock_zt_pool_*_em ×5 | 裸 requests.get | **无** | **无（不传）** | 无 | 无 |

全库未设任何自定义 User-Agent/Referer/Cookie——`python-requests` 默认 UA 是显著爬虫指纹。**业务侧必须统一出口补 UA/timeout/重试。**

### 3.3 社区经验数值

| 来源 | 数值 | 强度 |
|---|---|---|
| efinance #215 | 每 5 分钟取 50 只不被封，更频繁弹验证码 | 实测案例 |
| akshare #6100 | 高并发→封 IP ~5h 解禁 | 实测案例 |
| akshare #6214 | ~200 只历史请求即断连 | 实测案例 |
| akshare #6986 | ~50 并发请求被拒 | 实测案例 |
| akshare #6239 | 抓 500 行 sleep 10 分钟再继续 | 社区共识 |
| akshare #5762 | 每请求 sleep 4s 稳定 | 社区共识 |
| CSDN | 东财约 ≤60 req/min（≈1 req/s），超出 429；免费接口建议 ≥3s 间隔 | 社区共识 |
| 腾讯云盯盘教程 | 60s+ 间隔+抖动+重试稳定跑一个月；"被封的基本是几秒一次的暴力轮询" | 实测案例 |
| efinance #216 | 一天 ~1000 次实时行情请求才触发限流 | 推测 |

封禁时长：5h 为唯一实测；30 分钟/数天/永久说法均二手**未核实**。换 IP 普遍即时解。

### 3.4 建议轮询间隔（单公网 IP、交易时段）

| 接口 | 建议间隔 | 依据强度 | 备注 |
|---|---|---|---|
| 全市场快照 spot_em | **60-120s（取 90s+随机抖动）** | 社区共识+推测 | 单次调用内部 ~50 页请求，物理上限 ~1 次/分钟；**严禁并发多个快照任务**；低于 60s 会与上次调用重叠 |
| 涨停池 5 接口（push2ex） | **每接口 15-30s，5 接口错峰**（60s 内均摊 ≈ 每 12s 一个请求） | 推测（无 push2ex 专项数据，按 push2 同域经验外推） | 单次调用仅 1 请求，风险低于快照；发现异常退到每接口 60s |
| 个股分钟线 hist_min_em（push2his） | **每只 30-60s+抖动；聚合 ≤30 次/分、≤200 只/天** | 实测案例+社区共识（#6214、CSDN 60req/min） | 仅对精选自选股，不做全市场扫；**日 K 批量补数与盘中轮询不同日同 IP 混跑**（计数累加） |

工程约束（建议进方案）：统一出口补浏览器 UA/Referer + timeout 10-15s + 指数退避重试 3 次；全局令牌桶 ≤1 req/2s（快照的 50 连发安排在其他请求空窗期）；RemoteDisconnected/429/滑块 → 熔断降级（连续 N 次失败暂停 X 分钟）+ 换 IP 恢复。

### 3.5 未核实项

东财官方限频阈值；统一封禁时长；#6990/#4542 维护者是否回复过；"2025-04 起 IP 限频"仅 efinance 单源；push2ex 与 push2 是否同一套限频（同体系概率高）；滑块触发阈值。

来源：
- https://github.com/Micro-sheep/efinance/discussions/216 、/215
- https://github.com/akfamily/akshare/issues/6986 、/6061 、/6214 、/6239 、/6100 、/6658 、/7333 、/7311 、/7396 、/6990 、/4542 、/5762
- https://cloud.tencent.com/developer/article/2671369 、https://zhuanlan.zhihu.com/p/2040434270821410109
- 本地源码核对：/tmp/soros-v2-design/src/{stock_hist_em.py, stock_ztb_em.py, akshare_utils_func.py} + 本机 akshare 1.18.88 site-packages

---

## 4. mootdx 实时能力

> 方法：mootdx master（0.11.7）+ 底层协议库 tdxpy 0.2.7 全量源码 Read。**mootdx 底层是 tdxpy（同作者），不是 pytdx。**

### 4.1 项目现状

- 仓库 https://github.com/mootdx/mootdx （MIT，star 2400，open issues 99）；文档站 https://www.mootdx.com ；readthedocs https://mootdx.readthedocs.io ；镜像 https://gitee.com/ibopo/mootdx
- 最新版 PyPI 0.11.7（2024-05-04）；最后 commit 2024-07-16，**已休眠 2 年+**。
- 扩展市场（期货等）源码自带警告"扩展市场行情接口已经失效"——只用 std（沪深股票）。

### 4.2 实时行情 quotes：批量上限与字段

**批量上限 80 只/次（确凿证据链）**：`mootdx/quotes.py` L182 直接 `client.get_security_quotes(symbol)` **无任何分批切片**；tdxpy 把全部股票塞进单个 TCP 包（每股 7 字节）；80 只是**通达信服务端协议硬限制**（pytdx issue #77 作者 rainx 亲证"应该都是80"，>80 只静默截断为 80 行，不报错）。**>80 只必须调用方自己按 80 分批。北交所代码不支持**（tdxpy 直接 warn 并返回 None）。

quotes 返回字段（逐字抄 tdxpy parser OrderedDict）：

| 字段 | 含义 | 字段 | 含义 |
|---|---|---|---|
| market / code | 市场/代码 | price | **现价** |
| last_close | 昨收（涨跌幅需自算 price/last_close-1） | open / high / low | 今开/最高/最低 |
| vol / cur_vol | 总量 / 当前量 | amount | 成交额 |
| s_vol / b_vol | 内外盘语义 **未核实**（字段名照抄） | servertime | 服务器时间 |
| **bid1..bid5 / ask1..ask5** | **买一~五 / 卖一~五价** | **bid_vol1..5 / ask_vol1..5** | **五档量** |
| reversed_bytes9 | 涨速（/100） | active1/2 | 活跃标记 |

**含完整买卖五档价量**；无现成涨跌幅/换手率/量比列（需自算）；无 datetime 列。

### 4.3 分钟线 bars 与当日分时

- `StdQuotes.bars(symbol, frequency=9, start=0, offset=800)`：frequency 映射 '5m'=0、'15m'=1、'30m'=2、'1h'=3、'days'=4、'week'=5、'mon'=6、'ex_1m'=7、**'1m'=8**、'day'=9（默认）、'3mon'=10、'year'=11。
- **单次上限 800 根**（consts MAX_KLINE_COUNT=800）；`start` 为从最新往回的偏移根数，可 800 步进翻页取更早。**远端 1 分钟 K 线可翻多深由服务端决定，未核实**（完整分钟历史通常只在本地 vipdoc 文件）。
- 返回列：open/close/high/low/vol/amount/year/month/day/hour/minute/datetime（datetime 为索引）。
- **当日分时**：`StdQuotes.minute(symbol)` = `get_history_minute_time_data(market, code, 今天)`，返回逐分钟 {price, vol}，**无时间戳列**（~240 点，时间轴需按索引自生成）；历史任意日 `minutes(symbol, date='YYYYMMDD')`。原始当日接口 `get_minute_time_data` 未对股票暴露，且官方文档备注"网友反馈此接口数据有误，不建议使用"。

### 4.4 部署要求

- **不需要通达信客户端**：纯 TCP 直连通达信行情服务器（默认端口 7709），tdxpy 为纯 socket 实现。Windows/MacOS/**Linux 可跑（自带 Dockerfile）**，Python ≥3.8。
- IP 池：`mootdx/consts.py` HQ_HOSTS ~38 个 + tdxpy ~28 个硬编码公共节点；`bestip=True` 测速（0.7s/节点）写 `~/.mootdx/config.json`。**过期风险高**（EX_HOSTS 大部分已注释失效，HQ 列表被 #157 大量证伪）。
- ⚠️ `factory(multithread=True)` 参数在现行 StdQuotes 被**静默丢弃**；`heartbeat=True`（10s 心跳）与 `auto_retry=True` 有效。

### 4.5 稳定性（重大风险）

- 内置：tdxpy 请求级自动重连（socket 异常 disconnect+connect+重发，退避 0.1/0.5/1/2s）+ 心跳保活；但 **quotes/bars 对"返回空数据"无重试**，掉线后首笔空返回需自己兜底。
- **重大风险：https://github.com/mootdx/mootdx/issues/157 —— 2026-07-17/20 起几乎所有公共节点实时行情返回 0 行 / head_buf 异常，疑似通达信服务端协议更新；pytdx 同样中招；K 线/日线/财务不受影响。截至 2026-09-11 仍"全军覆没"，issue 至今 open。**
- **结论：截至 2026-10-02，mootdx 实时行情（quotes/当日分时）作唯一数据源风险极高；只能作实验性备源，接入前必须 bestip 实测连通性。**

### 4.6 与东财 HTTP 对比

| 维度 | mootdx（TDX TCP） | akshare（东财 HTTP） |
|---|---|---|
| 传输 | Socket 长连接，毫秒级 | HTTP 轮询，数十~数百 ms |
| 五档盘口 | ✅ 80 只/次批量 | 快照无；bid_ask_em 仅单只 |
| 全市场扫描 | 需按 80 分批（~68 次 TCP/轮） | 一次调用全市场（30-90s，无盘口） |
| 稳定性 | 公共节点 2026-07 起大面积失效（#157） | 反爬限流，批量易封 IP |
| 运维 | 需维护 IP 池/心跳/重连/bestip | 零运维 pip 即用 |
| 北交所 | ❌ 不支持 | ✅ 支持 |

### 4.7 未核实项

1 分钟 K 线服务端可翻页深度；s_vol/b_vol 语义；80 只截断在当前 tdxpy 全节点一致性；本次未做真实行情连接复测（休市+节点风险，连通性结论以 #157 社区反馈为准）。

来源：
- https://github.com/mootdx/mootdx 、https://www.mootdx.com 、https://mootdx.readthedocs.io 、https://pypi.org/pypi/mootdx/json
- 源码本地落盘：/tmp/soros-v2-design/src/mootdx/ 、/tmp/soros-v2-design/tdxpy-0.2.7/
- https://github.com/mootdx/mootdx/issues/157 （节点失效）、https://github.com/rainx/pytdx/issues/77 （80 只上限）
- https://blog.gitcode.com/1631306d880a4ab8093702ff77cb3a9a.html 、https://cloud.tencent.com/developer/article/2659335 （重连实践）
- https://blog.csdn.net/gitblog_00949/article/details/152768566 、https://zhuanlan.zhihu.com/p/2023293142783238466 （对比讨论）

---

## 5. 分钟线历史 `stock_zh_a_hist_min_em`

源码：`akshare/stock_feature/stock_hist_em.py` L1042-1167。

### 5.1 参数

| 参数 | 类型/默认 | 说明 |
|---|---|---|
| symbol | "000001" | 6 位代码；market_code = 6 开头→1 否则 0（北交所判定存疑，同 §2.4） |
| start_date / end_date | "1979-09-01 09:32:00" / "2222-01-01 09:32:00" | **先抓全量再本地切片**（对 1 分钟无意义，见下） |
| period | '5' | {'1','5','15','30','60'} 字符串 |
| adjust | '' | {'', 'qfq', 'hfq'} |

### 5.2 历史深度（关键结论）

- **period='1'：只能取近 5 个交易日，且不复权。** 三重证据：①源码 `period=="1"` 分支走 `push2his.eastmoney.com/api/qt/stock/trends2/get` 且**硬编码 `"ndays": "5"`**，start/end 仅对返回的 5 天做本地切片；②官方文档原文"1 分钟数据返回近 5 个交易日数据且不复权"；③Issue #5971。
- **period∈{5,15,30,60}：走 `push2his.../kline/get`，源码 `beg="0", end="20500000"` 意图全量，但东财服务端只保留近几个月**（官方文档"只能获取近期的分时数据"；社区称"最近几个月，太久远会失败"）。**具体月数未核实**——建议落地时实测 000001 period=5 的最早时间戳。
- **设计含义：1 分钟长历史唯一免费路径 = 从上线首日起每日增量落库自建**（社区先例 VeKiner/akshare-stock-data-fetcher 每日定时抓取入库）；付费替代 Tushare `stk_mins`（10 年+）；Baostock 免费但仅 2015 至今且无 1 分钟。

### 5.3 返回字段（1 分钟与 5 分钟+不同）

| period='1'（trends2，8 列） | period∈{5,15,30,60}（kline，11 列） |
|---|---|
| 时间、开盘、收盘、最高、最低、成交量、成交额、**均价** | 时间、开盘、收盘、最高、最低、**涨跌幅、涨跌额**、成交量、成交额、**振幅、换手率** |

### 5.4 复权

- period='1'：trends2 分支不传 fqt，**adjust 被完全忽略（不复权）**。
- period≥5：fqt={'':0,'qfq':1,'hfq':2} 生效；但东财分时复权基准与日线口径有差异（**具体算法未核实**，回测需留意）。

### 5.5 `stock_zh_a_hist_pre_min_em`（当日分时，含盘前）

源码同文件 L1170-1222。参数 symbol、start_time="09:00:00"、end_time="15:50:00"（当日时段切片）。走 `push2.eastmoney.com/api/qt/stock/trends2/get`，`ndays=1`、**`iscr=1`（含盘前集合竞价段）**。返回 8 列：时间、开盘、收盘、最高、最低、成交量、成交额、**最新价**（注意与 period='1' 的"均价"列不同）。
定性：**当日分时（含 9:15 集合竞价），非历史回看接口**。适用：盘前竞价高开探测、当日分时均价线。

### 5.6 已知故障

Issue #6098：period='1' 正常但 5/15/30/60 报 ConnectionError（东财反爬相关）；批量跑全市场分钟线易封 IP。

来源：
- 源码 https://github.com/akfamily/akshare/blob/main/akshare/stock_feature/stock_hist_em.py （本地 Read）
- 文档 https://akshare.akfamily.xyz/data/stock/stock.html
- https://github.com/akfamily/akshare/issues/5971 、/6098
- https://blog.csdn.net/weixin_29197699/article/details/158303857 （分钟线只取最近几个月）
- https://github.com/VeKiner/akshare-stock-data-fetcher （每日增量自建方案）
- https://tushare.pro/document/2?doc_id=370 、https://www.baostock.com/mainContent?file=stockKData.md （替代源）

---

## 6. 对本系统的建议

### 6.1 数据源分工

| 用途 | 首选源 | 备选/补充 | 说明 |
|---|---|---|---|
| 市场情绪层（涨停/炸板/跌停/强势/昨日涨停池） | **东财 push2ex（akshare 涨停池 5 接口）** | 每日落库自建历史 | 唯一免费带封板时间/炸板次数/封板资金/连板数/涨停统计的源；服务端仅留 ~30 天，**必须每日采集落库** |
| 全市场粗筛（涨跌幅/涨速/量比排行） | **东财 push2 clist（stock_zh_a_spot_em）** | 分市场变体 stock_sh/sz/bj_a_spot_em | 单次 30-90s 非原子，只做粗筛不做择时依据 |
| 自选池精盯：五档盘口 + 涨停/跌停价 | **东财 stock_bid_ask_em（单只轮询）** | mootdx quotes（80 只/次批量 TCP） | bid_ask 是唯一免费同时给五档+涨停跌停价的东财通道；自选池控制在几十只。mootdx 因 #157 节点失效**先实测连通再决定是否接入，不进主链路** |
| 盘中当日分时/竞价 | **stock_zh_a_hist_pre_min_em**（含集合竞价） | stock_zh_a_hist_min_em(period='1')（近 5 日） | 竞价高开探测用 pre_min 的 9:15-9:25 段 |
| 1 分钟线长历史 | **每日增量自建库**（收盘后抓当日数据落 PG） | Tushare stk_mins（付费） | 东财只回溯 5 个交易日，无免费长历史可挖，晚一天上线少一天数据 |
| 5 分钟+线 | stock_zh_a_hist_min_em(period≥5) | 每日增量落库 | 服务端仅留近几个月，回测需长历史同样要自建 |

### 6.2 轮询间隔建议（单公网 IP，交易时段 9:15-15:00）

| 任务 | 间隔 | 依据强度 |
|---|---|---|
| 涨停池 5 接口 | 每接口 15-30s，5 接口错峰均摊（≈每 12s 一个请求；涨停池主接口可给到 15s） | 推测（无 push2ex 专项数据） |
| 全市场快照 | 90s + 随机抖动（下限 60s，严禁并发/重叠） | 社区共识+推测 |
| 自选池盘口 bid_ask_em | 单只 5-10s 起步、聚合 ≤30 次/分（按 push2his 实测经验外推，**推测**，需盘中灰度试探） | 推测 |
| 自选池分钟线 hist_min_em | 每只 30-60s+抖动；聚合 ≤30 次/分、≤200 只/天 | 实测案例+社区共识 |
| 历史补数（日 K/分钟线批量） | 只在收盘后跑，**与盘中轮询不同日同 IP 混跑** | 实测案例（#6214 计数累加） |

### 6.3 工程防护（必做，AKShare 涨停池/分钟线接口零防护）

1. **统一 HTTP 出口层**：浏览器 UA/Referer、timeout 10-15s（涨停池源码连 timeout 都没有）、指数退避重试 3 次；或绕开 akshare 直连 push2/push2ex/push2his（endpoint 与参数本文件已全部记录，可自控重试与并发）。
2. **全局令牌桶** ≤1 req/2s（快照的 ~50 连发安排在其他请求空窗期）。
3. **熔断降级**：RemoteDisconnected/429/滑块 → 指数退避（上限 300s）+ 连续 N 次失败暂停轮询；换 IP 即时解封（实测封禁 ~5h 自然解）。
4. **判空容错**：涨停池老日期静默返回空 DataFrame；previous 池老日期触发 #5077 列名崩溃；sub_new/dtgc 池空返回抛 TypeError——采集器统一 try/except+判空。
5. **每日落库任务**（15:05 后）：涨停池 6 接口当日数据、自选股 1 分钟线、（可选）全市场 5 分钟线——这是长历史的唯一来源，任务失败要告警补跑（30 天窗口内可补）。

### 6.4 落地前必测清单（本次调研遗留的未核实项）

1. 交易日实测 `stock_zh_a_spot_em()` 完整跑一次：核对行数与代码集（**total=59271 且混入板块行的疑点必须查清**，可能需按 f12 正则过滤 `^\d{6}$`）。
2. 盘中实测 `stock_bid_ask_em`：五档价量是否有值、北交所代码 secid 是否正确。
3. 实测 period=5 分钟线最早时间戳（确认"近几个月"具体深度）。
4. 强势/炸板/跌停池 30 天保留边界逐一确认（当前按同域推断）。
5. 若考虑 mootdx：先 `bestip` + quotes 连通性实测（#157 风险），通过才纳入备选链路。
6. 盘中灰度试探 push2ex 涨停池实际安全间隔（从 30s 起步逐步收紧，观察断连率）。
