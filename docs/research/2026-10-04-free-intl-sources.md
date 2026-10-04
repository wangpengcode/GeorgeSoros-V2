# 国外免费数据源调研：对 A 股日线数据有无增量价值

> 调研日期：2026-10-04
> 背景：第四源 YahooAdapter（直连 `v8/finance/chart`）已落地，但本机出口对 Yahoo 每 IP 配额极紧（突发 ~8 次即 403/429，冷却几十分钟），需评估是否存在**配额独立于 Yahoo 边缘**、能分担/备份的国外免费源。
> 方法：WebSearch 查各源 2026 年最新免费档现状 + 本机 curl 实测可达性（**铁律：每源 ≤2-3 请求，间隔 `sleep 3`，禁止连续猛打**，已遵守）。本机网络：macOS，飞连 VPN 全量隧道（utun4，固定出口 IP），下列实测结果均为该出口的真实表现，不代表所有网络环境。

## 结论先行

**国外免费源对本项目 A 股日线数据没有增量价值。Yahoo（已落地的第四源）已经是国外免费源里的最优解，不建议再接入新源。**

逐源一句话结论：

| 源 | 结论 | 核心原因 |
|---|---|---|
| Stooq | **C 不适用** | 本机出口实测被 Cloudflare JS Challenge 拦截，curl/requests 直接拿不到数据；且其 A 股覆盖本身就稀疏（`.cn` 符号规则存在但官方历史库仅收录约 11 个中国代码量级） |
| Twelve Data | **C 不适用** | A 股（XSHG/XSHE）锁在 Venture+ 付费档，免费 Basic 档（800 credits/天）不含中国交易所 |
| Alpha Vantage | **C 不适用** | 免费档仅 25 请求/天，对 ~5000 只股票的日更+回填毫无意义；A 股支持本身也无官方清单、行为不稳定 |
| Finnhub | **C 不适用** | 官方文档明确国际市场实时行情为 Enterprise 专属；免费档非美股price数据 403 |
| Tiingo / FMP / Marketstack | **C 不适用（额度不够）** | Tiingo 免费档理论上含中国 EOD 但 1000 次/天选不出有效单只校准增量；FMP 免费档明确仅美股；Marketstack 免费档仅 100 次/月，覆盖再广也无法支撑 5000 只日更 |
| EODHD | **C 不适用** | 免费档 20 次/天，1 年历史，demo key 对 A 股符号直接 403；额度级别与 Alpha Vantage 同类问题 |
| Investing.com / investpy | **C 不适用** | investpy 自 2022-10 起已失效（Investing.com 启用 Cloudflare V2，官方声明"不提供公开 API"），无在维护的替代库 |

**判断维度回顾**：
1. 真覆盖 A 股日线——除 Yahoo 外，免费档普遍把中国交易所锁进付费层（Twelve Data/FMP），或覆盖稀疏/不稳定（Stooq/Alpha Vantage/EODHD）。
2. 免费额度够不够用——本项目 ~5000 股 × 日更 + 历史回填，这些源的免费额度（25~1000 次/天量级）相对 5000 只股票的体量**全部不够**，连做"每日抽样校准"都勉强。
3. 本机出口实测可达性——Stooq 被 bot-protection 直接挡死；其余源因额度/鉴权问题未能跑出真实数据（demo key 均不支持任意符号）。
4. 与 Yahoo 的差异化价值——没有一个源的配额与 Yahoo 的 query1/query2 边缘完全独立且同时满足①②③，所以即使纸面覆盖 A 股，也不构成"新增一条独立血管"的价值，只是多一个同样脆弱甚至更脆弱的点。

---

## 1. Stooq（stooq.com 免费 CSV）

- **接口**：`https://stooq.com/q/d/l/?s={symbol}&i=d`（`i=d|w|m|q|y`，可加 `d1/d2` 日期范围）。无需鉴权。
- **A 股符号规则**：Stooq 用统一的 **`.cn`** 后缀标注中国内地个股（无论沪/深），与 Yahoo 的 `.ss`/`.sz` 不同，例如 `600000.cn`、`000001.cn`。指数另有 `^` 前缀（如 `^shc` = 上证综指）。
- **覆盖**：官方 bulk 历史库页面（`stooq.com/db/h/`）显示中国代码量级很小（个位到十位数量级），与欧美/波兰相比覆盖极薄；个股 CSV 端点理论上可逐只请求，但覆盖深度未经证实能到位 A 股全市场。
- **免费额度**：无显式 QPS 文档，个人用途（Stooq 条款限制商业使用）。
- **本机实测结果**：

  ```
  curl -s -m 15 "https://stooq.com/q/d/l/?s=600000.cn&i=d"
  → HTTP 200，但 body 不是 CSV，而是 Cloudflare 风格 JS PoW 挑战页：
  "This site requires JavaScript to verify your browser..."
  ```

  换浏览器 UA 重试（第 2 次请求，`sleep 3` 后）结果相同——说明这不是 UA 指纹问题，而是**站点级 JS 挑战**，普通 `curl`/`requests` 客户端无法越过，必须上无头浏览器才能拿到数据。
- **结论：C 不适用**。本机出口拿不到任何数据（纯 HTTP 客户端被挡死），且即便能拿到，A 股覆盖本身也薄，不值得为此引入浏览器自动化依赖。

## 2. Twelve Data（免费 Basic 档）

- **接口**：`https://api.twelvedata.com/time_series?symbol=...&interval=1day&apikey=...`
- **免费 Basic 档额度**：800 credits/天，8 credits/分钟。
- **A 股覆盖**：官方 exchange 页面明确标注 **Shanghai (XSHG)、Shenzhen (XSHE) 均为「Venture+」专属**，Basic 免费档只开放 15 个交易所（以美股/外汇/加密为主），不含中国。`/exchanges` 元数据接口本身免费可查，但拉价格数据需要付费升级。
- **本机实测结果**：

  ```
  curl "https://api.twelvedata.com/time_series?symbol=600519.SS&interval=1day&outputsize=5&apikey=demo"
  → HTTP 401 {"code":401,"message":"The 'demo' API key is only used for initial familiarity..."}
  ```

  demo key 不支持任意符号测试；但该结果不影响结论——官方文档已明确 XSHG/XSHE 锁在 Venture 付费档，免费 Basic 档申请真实 key 也拉不到 A 股。
- **结论：C 不适用**。

## 3. Alpha Vantage（免费档）

- **接口**：`https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=...&apikey=...`
- **免费额度**：**25 请求/天**，5 请求/分钟（历史上从 500→100→25 一路缩减）。开源项目可申请无限免费（需审核）。
- **A 股覆盖**：官方文档样例里有"Shanghai Stock Exchange"的 ticker 示例，理论上支持，但**官方从未发布交易所/符号清单**，需靠 `SYMBOL_SEARCH` 自己摸，常见后缀是 `.SHH`（沪）/`.SHZ`（深）。已知失败模式：符号不存在时返回空的 Global Quote 对象而非报错，容易在不知不觉中耗尽当日额度。
- **本机实测结果**：

  ```
  curl "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=600519.SHH&apikey=demo"
  → HTTP 200 {"Information": "The demo API key is for demo purposes only..."}
  ```

  demo key 只认文档里固定的示例符号（如 IBM），无法用来验证真实 A 股符号——这本身也是其可用性差的一个体感证据（连验证都要先注册）。
- **额度算术**：25 次/天 × 365 天 ≈ 9000 次/年，对比 ~5000 只股票的一次全市场快照都做不到，**连"每日抽样几十只做校准"都占去大半天额度**，对日更/回填毫无意义。
- **结论：C 不适用**。

## 4. Finnhub / Tiingo / Financial Modeling Prep / Marketstack（快速排除，未逐个实测）

按任务要求，这组"大多只覆盖美股"的源只做文档确认，不逐个 curl（结论已足够清晰，省出请求配额给更有希望的源）。

| 源 | 免费额度 | A 股覆盖 | 结论 |
|---|---|---|---|
| **Finnhub** | 60 calls/分钟 | `/quote` 官方文档写明"国际市场实时行情仅 Enterprise 客户，经合作方 feed 提供"；社区反馈非美股资产已收费（GitHub issue 回复："data not part of your access, requires subscription"），免费 key 调用非美股价格接口返回 403 | **C 不适用** |
| **Tiingo** | 500 次/小时、1000 次/天 | 官方 EOD 产品页写"覆盖美股与中国市场"，理论上可含 SSE/SZSE，但**只是 EOD**，且社区反馈免费额度对"全市场批量拉取"吃紧（Reddit 用户反馈拉全 NASDAQ 一年数据就撞额度） | **C 不适用（额度撑不住全市场）** |
| **Financial Modeling Prep** | 250 次/天，500MB/30天带宽 | 官方自己写明："免费档仅限美股交易所"（be aware that the free plan is limited to U.S. exchanges only），中国交易所需 46+ 交易所的付费档 | **C 不适用** |
| **Marketstack** | **100 次/月**（当前官方页面口径，部分旧资料写 1000/月有出入，以官方当前页为准） | 免费档不按交易所限制，`exchanges` 列表含 XSHG（上交所）、XSHE（深交所），即覆盖"纸面可用" | **C 不适用（额度维度否决）**：100 次/月连 1 只股票的日更都撑不满一个月，覆盖再广也无意义 |

## 5. EODHD（免费档）

- **接口**：`https://eodhd.com/api/eod/{symbol}?api_token=...&fmt=json`；A 股交易所代码为 `SHG`（上交所）、`SHE`（深交所），如 `600000.SHG`。
- **免费额度**：**20 请求/天**，仅 1 年历史，分红/拆股数据需额外联系客服开通，商业用途需付费 B2B 档。
- **A 股覆盖**：文档存在矛盾——交易所页面本身列出 SHG/SHE 的历史数据产品（但标注"付费起价 $19.99"），官网首页营销文案又写"免费档含美股的 EOD 历史数据和有限基本面"，暗示基本面数据限美股，价格数据对任意 ticker 理论上同一端点可用。
- **本机实测结果**：

  ```
  curl "https://eodhd.com/api/eod/600000.SHG?api_token=demo&fmt=json&period=d"
  → HTTP 403 Forbidden
  curl "https://eodhd.com/api/eod/000001.SHE?api_token=demo&fmt=json&period=d"
  → HTTP 403 Forbidden
  ```

  demo key 仅支持固定示例符号（AAPL.US 等），对 A 股符号直接 403，无法验证真实免费档行为；但即便注册真实 key 能拉到，**20 次/天**对 ~5000 只股票也是杯水车薪，结论不受影响。
- **结论：C 不适用**。

## 6. Investing.com / 英为财情（非官方接口调研，未做爬虫攻防）

- **官方立场**：Investing.com 官方支持文章明确声明**不提供公开 API**（"due to the terms of our contractual agreements with our data providers"）。
- **非官方库现状**：`investpy`（GitHub `alvarobartt/investpy`）自 **2022-10** 起失效，README 置顶声明"由于 Investing.com 更改了其 API 保护机制（启用 Cloudflare V2），数据无法访问"；维护者当时推荐的临时替代 `investiny` 同样自 2022 年后停止维护。社区（Reddit r/webscraping 2025-11 帖、Portfolio Performance 论坛）近期仍在反馈同样的 Cloudflare 拦截和历史数据爬取失败。
- **结论：C 不适用**。无在维护的接入路径，且官方明确反对第三方抓取，不值得投入爬虫对抗成本。

## 7. 调研中发现的其他候选（均为国内源，排除于本次范围外）

搜索过程中反复出现的"免费 A 股数据"推荐实际全部是**国内源**：AKShare、Tushare、BaoStock、openbb-tushare、TickDB、AllTick、Infoway 等。这些不属于本次「国外免费源」调研范围（且 AKShare/新浪已是项目现有三源之一），仅记录以说明：业界对 A 股数据的公认免费解法本来就是中文/本土聚合源，而不是西方金融数据 API 的免费档——这与本次调研"国外免费源对 A 股无增量价值"的结论互相印证。

---

## 总体结论

**直白回答：国外免费源对 A 股数据没有增量价值。Yahoo（第四源）已经是国外免费源里能找到的最优解，不建议新增。**

理由分三层：

1. **覆盖层**：西方金融数据 API 的免费档几乎清一色把"国际/非美股"锁进付费层（Twelve Data Venture、FMP 美股限定、Finnhub Enterprise），或覆盖本身稀疏/不稳定（Stooq、Alpha Vantage、EODHD）。这是商业模式决定的——A 股数据对这些厂商是"高价值国际数据"，天然不会放进免费档。
2. **额度层**：即便某源纸面覆盖 A 股（Tiingo、Marketstack、EODHD），免费额度（100~1000 次/天或/月量级）相对本项目 ~5000 只股票的日更+历史回填体量，**连做"抽样校准腿"都勉强**，更谈不上替代或分担 Yahoo。
3. **可达层**：本机出口对 Stooq 的 curl 实测直接被 Cloudflare JS 挑战拦死，纯 HTTP 客户端拿不到任何数据；其余源因额度/鉴权限制未能跑出真实 A 股数据，demo key 全部只认文档里的固定示例符号，这也从侧面印证"这些源根本没把低成本/免费场景下的中国市场访问当作一等场景"。

**与 Yahoo 对比**：Yahoo 的问题是"配额紧"（突发 8 次即冷却），但好处是**真覆盖、真数据格式熟悉、已有工程实现**。上述候选源没有一个同时满足"真覆盖 A 股 + 免费额度够用 + 本机可达"，所以不存在"用 X 源分担 Yahoo 配额压力"的现实路径。

**对项目的实际建议**：维持现状——Yahoo 仅作校准/对拍腿（PLAN §18.3①已定），不升级为主力源；国外免费源方向到此为止，不再追加评估或接入。真正要缓解"数据源单点脆弱"的问题，应继续在**国内三源（东财/新浪/mootdx）+ 分片分压 + failover**这条已验证的路径上做工程加固，而不是寄望于再找一个国外免费源。
