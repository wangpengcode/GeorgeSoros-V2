# GeorgeSoros-V2 国内免费 A 股数据源调研：第五源候选

> 调研日期：2026-10-04（国庆假期，A 股休市至 10-08；K 线实测取最近交易日 2026-09-30 数据）
> 目标：现有四源体系（baostock 自建服务器 / akshare[东财EM→新浪] / mootdx 通达信云集群 / yahoo）之外，寻找第五源候选，拓宽数据来源，不焊死在单一源上。
> 方法：WebSearch/WebFetch 查接口文档与社区逆向资料 → 本机 curl 实测连通性 → akshare 包源码逐字 Read/grep（本地 `.venv` 快照，版本 1.18.88）→ adata/efinance 源码交叉核验。
> **铁律（全程遵守）**：探测间歇性——多数源每源 ≤3 次请求、交易所类接口每接口 ≤2 次、请求间隔 `sleep 3`，禁止连续猛打；curl 均未用 `2>/dev/null` 静默 stderr。
> 本机环境：macOS + 飞连 VPN 全隧道（utun4）+ 数据源域名家宽分流（手工 `route add`）；**本次候选域名多数未在分流表中，实测走的是 VPN 隧道出口**——"本机不可用"≠"方案本身不可行"，个别源（百度）已在结论中标注需换出口复测。
> 性质标签：上交所/深交所标【源头级/交易所直连】，与 baostock/akshare/mootdx/yahoo/腾讯/雪球/百度/网易/Tushare 这类【转发商/聚合代理】区分——即使覆盖面有限，权威性更高，适合作校准基准而非替换现有主力源。

## 0. 结论速览

| 源 | 性质 | 实测结果 | qfq | volume单位 | 集成难度 | 推荐级 |
|---|---|---|---|---|---|---|
| **腾讯行情**（`proxy.finance.qq.com`/`ifzq.gtimg.cn`/`qt.gtimg.cn`） | 转发商 | ✅ 3/3 全通，无需鉴权 | ✅ 支持 | 手→需×100对齐股 | 低（可仿现有腾讯指数兜底自建HTTP解析） | **A** |
| **上交所** `yunhq.sse.com.cn:32042` 个股日K | 【源头级/交易所直连】 | ✅ 实测600000单次返回IPO首日(1999-11-10)至今6400条全历史 | ❌ 仅原始价 | 疑似股（未逐字确认） | 低（直连HTTP风格，类yahoo_adapter） | **A** |
| akshare内部 `stock_zh_a_hist_tx`（腾讯，与上条同属腾讯但不同接入域） | 转发商 | 可直接 `ak.` 调用 | ✅ | 已内部换算（但sz000xxx有bug） | 极低 | **A**（与上述腾讯行情结论互证，建议走自建HTTP而非直接信任该函数） |
| **深交所** CATALOGID=1110「A股列表」（股本/流通盘） | 【源头级/交易所直连】 | ✅ 结构化JSON，按日期可查总股本/流通股本 | 不适用 | 不适用 | 中（独立"基础信息"场景，非日线） | **B** |
| **Tushare 免费档**（120积分） | 转发商（官方授权） | 仅`daily`非复权可用，`adj_factor`需2000分 | ❌（免费档） | 手→需×100 | 中（人工积分申请，非全自动） | **B** |
| **百度股市通**（`finance.pae.baidu.com`） | 转发商 | ❌ 3/3（另一会话再测1次）均403 `hit risk`风控 | ❌ 无复权参数 | 未验证 | 中高（需养Cookie过风控） | **C**（待家宽出口复测） |
| **雪球**（`stock.xueqiu.com`） | 转发商 | ❌ 3/3 均400，强制`xq_a_token`无法curl获取 | 未验证 | 未验证 | 高（需浏览器模拟登录保活） | **C** |
| **网易财经**（`quotes.money.163.com`） | 转发商 | ❌ 3/3 均502，后端已下线 | ❌（历史不复权） | 股 | 不适用（服务端关闭） | **C** |
| **深交所** `api/report` 个股日行情 | 【源头级/交易所直连】 | ⚠️ 结构+实测均证实仅单日快照，无区间查询 | ❌ | 万股 | 不适用（无历史能力） | **C** |
| adata 内部百度子类（`stock_market_baidu.py`） | 转发商 | 与上面百度股市通同一域名，1次实测同样403 | 声称有但代码标TODO | 未验证 | 中高（需移植源码+养共享Cookie） | **C**（与百度股市通合并观察） |
| efinance / adata 日线主链 | 转发商 | 100%同域push2his.eastmoney.com，与现有EM链同源 | 同EM | 同EM | 不适用（无增量价值） | 不建议纳入（同源去重） |

---

## 1. 腾讯行情（`proxy.finance.qq.com` / `web.ifzq.gtimg.cn` / `qt.gtimg.cn`）

### 1.1 接口地址与鉴权

| 用途 | Endpoint | 鉴权 |
|---|---|---|
| 个股/指数日K（新版，含成交额/换手率） | `https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get` | 无 |
| 个股/指数日K（旧版，字段精简） | `https://web.ifzq.gtimg.cn/appstock/app/fqkline/get`（与现有代码指数兜底用的 `ifzq.gtimg.cn` 同后端） | 无 |
| 实时快照（五档盘口+涨跌幅） | `https://qt.gtimg.cn/q=<市场前缀><代码>` | 无 |

参数格式：`param=代码,day,开始日期,结束日期,数量,复权方式`（`qfq`/`hfq`/空）。akshare 内部 `stock_zh_a_hist_tx`（`akshare/stock_feature/stock_hist_tx.py`）走的正是 `newfqkline/get`。

### 1.2 本机实测（2026-10-04 13:24–13:26）

| # | 时间 | 请求 | 结果 |
|---|---|---|---|
| R1 | 13:24:19 | `proxy.finance.qq.com newfqkline/get?param=sz000001,day,2026-09-01,2026-10-04,10,qfq` | HTTP 200，`qfqday:[["2026-09-30","11.36","11.57","11.65","11.33","1045357.00"]...]`，字段序 `[date,open,close,high,low,volume,分红dict,turnover%,amount万元,占位]` |
| R2 | 13:25:26 | `web.ifzq.gtimg.cn fqkline/get` 同参数 | HTTP 200，volume=949626 与R1完全一致（证明同一后端，仅字段精简） |
| R3 | 13:25:42 | `qt.gtimg.cn/q=sz000001` | HTTP 200，GBK编码，`v_sz000001="51~平安银行~000001~11.57~...~1.94~..."`，idx32=涨跌幅% |

### 1.3 数据口径

- **qfq**：✅ 支持，`adjust="qfq"` 直返前复权价
- **涨跌幅**：日K接口本身无此列（需自算 `(close-prev_close)/prev_close`），实时快照 `qt.gtimg.cn` 有现成字段
- **volume**：原始为"手"，交叉验证（amount÷收盘价反算）确认须 **×100** 对齐股口径
- **⚠️ akshare 自带函数的坑**：`ak.stock_zh_a_hist_tx` 源码对 `sz000xxx` 前缀的 volume×100 判断逻辑有误（`startswith("sz000")` 会误伤 000001 平安银行等整批深市主板股票，导致这些代码不乘100），直接调用该现成函数会产出单位不一致的数据，**不可当黑盒使用**

### 1.4 配额/封禁风险

3次请求+2×sleep3全放行，无429/403；响应头无显式限流字段。现有代码 `_tencent_index_to_bars`（`akshare_adapter.py`）已在生产路径用它做指数兜底（2026-10-03探针PASS），有长期可用先例。仍需 TokenBucket 防御，不可裸奔高频。

### 1.5 集成难度

可直接复用 `BaseAdapter._call_guarded`（限流→熔断→调用→登记）架构。**推荐路径**：仿照现有 `_tencent_index_to_bars` 自建 HTTP 解析（而非直接调 `ak.stock_zh_a_hist_tx`），完全掌控字段解析、规避上述 volume 坑，且不受 akshare 版本升级影响。实时接口需显式 GBK 解码。

### 1.6 结论

**推荐级 A——建议尽快纳入**。三个 endpoint 全部免鉴权直连成功，qfq/volume/amount字段齐全，架构复用成本低，是东财 WAF 封禁事件后风险最低、工程量最小的"第五条腿"候选。

---

## 2. 雪球（`stock.xueqiu.com`）

接口：`v5/stock/chart/kline.json`，`type=before/after/normal` 对应 qfq/hfq/不复权。

**实测（2026-10-04 13:26）**：3/3 请求全部 HTTP 400（`error_code:400016`）。无Cookie直连400；访问 `xueqiu.com/` 仅拿到WAF反爬cookie `acw_tc`，拿不到页面JS生成的 `xq_a_token`；带 acw_tc+Referer 再测仍400。

数据口径未能验证（接口不可达）。按三方资料，正常响应应含 `chg`/`percent`/`volume`/`amount` 等字段，但**未经本机证实，不可采信**。

**集成难度**：需额外建一套 `xq_a_token` 获取/保活机制（headless浏览器模拟登录或人工定期抓取），且token过期会被熔断器误判为"源故障"而非"鉴权材料失效"，现有 Adapter 框架完全未覆盖这一维度，集成成本明显高于腾讯。

**结论：C——本机出口下不可用**，且阻塞点不是限流/熔断能解决的问题，暂不建议投入。

---

## 3. 百度股市通（`finance.baidu.com` / `finance.pae.baidu.com`）

### 3.1 接口（社区逆向，文档层面声称匿名可访问）

K线接口 `GET /selfselect/getstockquotation`（`group=quotation_kline_ab`，`eprop=dayK`），实时 `GET /vapi/v1/getquotation`。字段含 `ratio`(涨跌幅)、`volume`、`amount`、`preClose`、MA5/10/20，**文档未发现任何qfq/复权参数**。

### 3.2 本机实测（2026-10-04 13:35）+ 独立交叉验证

| # | 时间 | 请求 | 结果 |
|---|---|---|---|
| 1 | 13:35:06 | 带UA+Referer+Accept 日K | HTTP 403 `{"code":403,"isCaptchaEnabled":true,"msg":"hit risk"}` |
| 2 | 13:35:21 | 实时行情，同Header | HTTP 403，同样 `hit risk` |
| 3 | 13:35:42 | 加 `BAIDUID` Cookie + Origin | HTTP 403，仍 `hit risk` |

**独立交叉验证**：另一调研线程通过 `adata` 开源项目的 `stock_market_baidu.py` 子类复测（该库为过风控硬编码了一个全库用户共享的固定Cookie），同样**裸请求即被拦截**（`isCaptchaEnabled:true`），与本节结论互相印证——不是Header配置问题，是IP/环境级风控。

**关键未证实项**：本机访问走的是飞连VPN隧道出口（该域名未入家宽分流表），换家宽直连IP后结果未知，这是本次调研最大的不确定项。

### 3.3 结论

**推荐级 C（现状不可用，待复测）**。理论数据质量不差（含MA、涨跌幅、昨收等），唯一卡点是出口IP风控，建议后续把域名纳入家宽分流表后用≤3次请求复测一次；若复测仍403则彻底归档，不再投入（反复请求本身会加重风控标记）。即使复测通过，仍需自行计算qfq（无复权参数），且需要自建"会话预热+Cookie维护"逻辑，超出现有Adapter的"HTTP封装+重试"模式，集成成本中高。

---

## 4. 网易财经（`quotes.money.163.com/service/chddata.html`）

**实测（2026-10-04 13:36）**：HTTP与HTTPS均 **502 Bad Gateway**（`nginx`边缘仍在，但转发不到任何上游——典型"后端服务已下线"特征，非鉴权/限流）；对照组 `money.163.com` 根域名200正常，排除本机网络/DNS问题。

字段文档齐全（`PCHG`涨跌幅、`VOTURNOVER`成交量股），**但不支持qfq**（AKShare官方文档确认该源不复权）。

**结论：C——彻底关闭**，坐实"传闻已关闭"，判定为服务端永久下线而非临时故障，无转圜空间，不建议再投入适配开发。

---

## 5. 上交所（SSE）—— `yunhq.sse.com.cn` 个股日K【源头级/交易所直连】

### 5.1 关键澄清：任务原设想的域名并非行情宿主

`biz.sse.com.cn` 是CA证书/会员门户，`query.sse.com.cn` 的 `commonQuery.do` 通用查询网关主要服务公告/报表类结构化查询，均**未找到承载个股历史日K的证据**。真实承载个股日K历史的是**上交所行情云主机 `yunhq.sse.com.cn`**（官网"行情走势"页面背后的真实数据源）。

### 5.2 实测（2026-10-04 13:34，带Referer `http://www.sse.com.cn/`）

| # | 请求 | 结果 |
|---|---|---|
| 1/2 | 端口32041 | curl exit 35（SSL连接失败，基础设施问题，非反爬拒绝） |
| 2/2 | 端口32042，`GET /v1/sh1/dayk/600000?select=date,open,high,low,close,volume,amount&begin=0&end=-1` | **HTTP 200，377KB**，`{"code":"600000","total":6400,...}` |

样例：最早 `[19991110,29.5,29.8,27.0,27.75,174085055,4859102435]`，最新 `[20260930,9.22,9.49,9.16,9.48,147484820,1386209937]`。

### 5.3 正面回答核心问题

- **历史覆盖深度**：✅ **深**——单次调用返回 **IPO首日（1999-11-10）至今全历史，6400个交易日**，无需分页，覆盖深度优于baostock（部分品种仅2015年起），与akshare全历史持平但更省调用次数。
- **Referer反爬强度**：⚠️ **未证实**——2次配额用于探测可用端口（32041失败→32042成功），未做"不带Referer"对照实验，社区文档称不需要但本次无法证伪/证实，标注为待补测项。
- **qfq有无**：❌ **无**，`select`参数仅支持原始OHLCV字段，未发现`adjust`/`fq`类参数，1999年IPO开盘价与近期收盘价同数量级无跳跃缩放痕迹，判定为原始价。

### 5.4 数据口径

涨跌幅需自算；volume单位未逐字确认文档，但按换手率量级反推倾向判断为"股"（建议落地前与baostock/akshare同日同代码交叉核对一次）；amount=元。

### 5.5 配额/集成评估

未见公开限频文档，建议按东财经验外推（≥3s间隔起步）。可直接复用现有Adapter模式（参照`yahoo_adapter.py`直连HTTP风格），需补`ParameterError`（不支持qfq/hfq时抛出，类yahoo先例），需独立TokenBucket/CircuitBreaker实例。**定位建议**：不适合日常批量增量拉取（单次全历史377KB，更适合"首次建仓灌历史"或长周期对拍校准），作为**第五校准源**（权威基准，用于偏差检测）接入现有多源分片/V5校准体系，而非替换现有failover顺序。深市路径（`v1/sz1/dayk/{code}`）本次未验证，需单独排期补测。

### 5.6 结论

**【源头级/交易所直连】推荐级 A**——建议尽快纳入，优先用途：①长历史权威基准 ②IPO至今全历史一次性灌库。局限：无qfq、Referer必要性与深市路径待补测。

---

## 6. 深交所（SZSE）—— `www.szse.cn/api/report`【源头级/交易所直连】

### 6.0 核心结论先行

**深交所 `api/report/ShowReport` 报表体系本质是"按单一交易日+可选代码过滤"的快照式报表接口，不是逐日时间序列接口。** 结构证据（schema只有`txtBeginDate`单日期字段+无配对结束日期+隐藏`txtHistoryMaxDate`边界字段+`pagesize:30`横向列表设计）与实测（CATALOGID=1815_stock两次尝试均`recordcount:0`）一致指向：**没有支持起止日期区间、一次性返回多日OHLCV的个股历史接口**。对日线历史回补**没有价值**，只能算当日/短周期快照补充源。

### 6.1 实测记录与已排除编号

| CATALOGID | 结果 | 结论 |
|---|---|---|
| `1815_stock` | HTTP 200，metadata正常，但2次尝试（`txtDate=`试探、`txtDMorJC+txtBeginDate+txtEndDate`试探）均`recordcount:0` | 「股票行情」，单日期字段设计，无区间查询能力 |
| `1803` | HTTP 200，`recordcount:0` | 「市场总貌」，非个股接口 |
| `1815`（无`_stock`后缀） | 报`noalert`错误 | 后缀必需 |
| `1110` | HTTP 200，有效数据 | 「A股列表」，见§6.2（非日K，但对项目有用） |
| `PAGESIZE`参数 | 验证无效，固定20/页 | 真实分页参数名未进一步验证（可能`PAGENO`/`tab1pagesize`） |

`cols`字段定义：`jyrq/zqdm/zqjc/qss/ks/zg/zd/ss/sdf(涨跌幅%)/cjgs(成交量万股)/cjje(成交金额万元)/syl1`，涨跌幅字段齐全但volume单位是**万股**（需×10000对齐股口径）；无qfq相关字段（判断原始价）。

### 6.2 附加价值发现：CATALOGID=1110「A股列表」（股本/流通盘数据，非日K）

字段：`bk`(板块)/`agdm`(代码)/`agjc`(简称)/`agssrq`(上市日期)/**`agzgb`(总股本亿股)**/**`agltgb`(流通股本亿股)**/`sshymc`(行业)。**`txtQueryDate`按历史日期查询有效**（响应`subname`回显指定日期），即可按日期取历史股本结构快照。

这正好匹配项目当前「流通市值 UNKNOWN 挂账」缺口（约18万行待relabel，需要历史各期流通股本数据才能补算历史流通市值）——**该接口可作为流通股本历史数据的候选源之一**，建议另立项评估（不计入本次日K源评估结论）。

### 6.3 结论

- **个股日K场景**：【源头级/交易所直连】**推荐级 C（不可用）**——结构+实测均证实仅单日快照，无区间回补能力，接入成本（新增Adapter+处理CATALOGID枚举）与收益（baostock/akshare已覆盖）不成正比，不建议为日线场景接入专门Adapter。
- **A股列表/股本场景（CATALOGID=1110）**：**推荐级 B（备用）**——可按历史日期查总股本/流通股本，建议作为窄范围能力（类似现有`fetch_board_members`的"仅特定源承载"模式）单独评估，用于补算历史流通市值挂账，不计入日线主力源池。

---

## 7. Tushare 免费档（仅文档调研）

### 7.1 积分体系现状（2026-10查证）

| 积分档位 | 频次(次/分) | 接口范围 | 年费 |
|---|---:|---|---:|
| 120（注册+完善资料） | 50 | 仅非复权日线`daily`，其他基本不可调 | 0元 |
| 2000以上 | 200 | `adj_factor`复权因子等解锁 | 200元 |
| 5000以上 | 500 | 常规数据无上限 | 500元 |

免费积分途径：注册(+50)、GitHub issue(+5~50)、发文章(+100)、学生认证(约+2000)、教师认证(约+5000)。积分有效期1年。

### 7.2 对本项目的实用价值

`daily`（120分可用）**非复权**，vol单位=手（需×100）；`adj_factor`（qfq的必要条件）**需2000分起**，免费档卡在本项目"qfq优先"需求的硬门槛之外。免费档能拿到的东西，baostock/akshare已免费且无积分限制覆盖。

### 7.3 稳定性风险

2025-08-18发生过因IDC机房合作方纠纷导致的主服务器大面积断网事件，服务暂停近一周（财联社/新浪财经报道）。平台自述"无资本背书"，稳定性弱于交易所官网与baostock。

### 7.4 结论

**推荐级 B（备胎观察）**——免费档不足以满足qfq需求，需认证或付费（200元/年起不高）才解锁2000分；2025年有真实停运记录。若未来需要财务数据（任务后续需求），可评估付费2000分档，但需人工走积分申请流程，无法像现有四源一样全自动化接入。

---

## 8. akshare 生态内部未用源盘点

对本地 `.venv`（akshare 1.18.88）逐一 grep 核实底层域名后汇总：

| 函数名 | 底层域名/数据源 | 使用状态 | 备注 |
|---|---|---|---|
| `stock_zh_a_hist` | 东财EM `push2his.eastmoney.com` | 已用（主链） | |
| `stock_zh_a_daily` | 新浪 `finance.sina.com.cn` | 已用（failover） | |
| `stock_zh_a_hist_tx` | 腾讯 `proxy.finance.qq.com` | **未用 ⭐全新候选** | 与我们手写的指数兜底 `ifzq.gtimg.cn` 是不同域名/接入层，见第1节 |
| `stock_zh_a_cdr_daily` | 新浪（同`stock_zh_a_daily`模板） | 未用，非候选 | 仅覆盖极少数CDR标的，无增量价值 |
| `stock_zh_a_hist_163`（网易） | 曾存在，1.4.59~1.5.99 | **当前版本已不存在该函数** | 与第4节网易彻底关闭的结论互相印证 |
| `stock_zh_a_hist_min_em` | 东财（分钟线） | 未用，非日线候选 | 同源EM，1分钟仅近5交易日 |
| `stock_zh_ah_daily`/`stock_zh_ah_spot` | 腾讯 `stockapp.finance.qq.com` | 未用，非候选 | A+H比价场景，非通用个股日线 |
| `stock_zh_ab_comparison_em` | 东财 `push2.eastmoney.com`（实时非历史） | 未用，非候选 | |

**唯一全新候选**：`stock_zh_a_hist_tx`（腾讯），字段 `date/open/close/high/low/volume/turnover/amount`，与第1节腾讯行情实测结论互证一致，**推荐级A**，但需规避该函数在`sz000xxx`上的volume×100判断bug（见§1.3），建议落地走自建HTTP解析而非直接调包。

---

## 9. adata / efinance 独立源去重

- **efinance**：`get_quote_history()` 源码确认100%走 `push2his.eastmoney.com/api/qt/stock/kline/get`，与现有EM链**完全同源**，GitHub Discussion #216反映其用户也在2025-04遭遇东财限流，与本项目东财封禁事件根因一致。**无新增候选**。
- **adata**：日线主接口`StockMarket.get_market()`同样只调东财同一endpoint，**无新增候选**。但其内部拆分的厂商子类里，`stock_market_baidu.py` 独立封装了**百度股市通**（`finance.pae.baidu.com`），且adata自己都没把它接入日线fallback链（只在分时/五档场景兜底）。实测该子类硬编码的共享Cookie同样被风控拦截——与第3节百度股市通结论**互相印证**，合并归入该节C级观察项，不单独计入新候选。

**结论：efinance/adata去重后无新增可落地候选**；唯一有价值的发现（百度股市通的独立性）已与第3节合并评估。

---

## 10. 优先级排序

| 优先级 | 源 | 理由 |
|---|---|---|
| **1** | **腾讯行情**（自建HTTP解析 `proxy.finance.qq.com`/`ifzq.gtimg.cn`） | 实测3/3全通、无鉴权、qfq齐全、架构复用成本最低，是东财WAF封禁后风险最小的第三条腿；akshare内部`stock_zh_a_hist_tx`与之互证 |
| **2** | **上交所 `yunhq.sse.com.cn`（第五校准源）** | 【源头级】IPO至今全历史单次拉取，权威性最高，适合接入现有V5校准体系做偏差检测基准；局限是无qfq、Referer/深市路径待补测 |
| **3（观察）** | **深交所 CATALOGID=1110 A股列表** | 非日线场景，但对「流通市值UNKNOWN挂账」缺口有直接价值，建议单独立项评估（不是本次日K任务的产出） |
| 备胎 | Tushare免费档 | qfq能力卡在2000分门槛外，需人工操作，非紧急 |
| 待复测 | 百度股市通 | 需先把域名加入家宽分流表排除VPN出口干扰因素，再决定是否放弃 |
| 不可用/不建议 | 雪球、网易财经、深交所个股日K、efinance/adata日线主链 | 雪球鉴权成本过高；网易服务端已关闭；深交所个股日K无历史区间能力；efinance/adata日线与现有EM链同源无增量价值 |

**对拓宽五源体系的直接建议**：优先落地腾讯行情（低成本、高确定性），其次评估上交所作为校准基准源接入现有多源分片/校准体系；深交所A股列表与Tushare付费档可作为后续"基础信息/财务数据"需求的独立候选池，不与日线行情源混为一谈。
