---
name: implement-with-seq-dev
description: 本项目稳定版（源自 Payment-X 个人演进版适配；验证过的优化同步回 ~/.claude/skills/）。Implement a full call chain based on sequence diagram mapping. Parses the mapping file, computes dependency order, and calls agents to implement each method bottom-up.
argument-hint: <mapping-file> [section-name] | --resume | --list
user-invocable: true
allowed-tools: Read, Write, Edit, Glob, Grep, Bash, Agent, AskUserQuestion, TaskCreate, TaskUpdate, TaskList
---

# /implement-with-seq-dev 调度 Skill（GeorgeSoros-V2 项目版）

> **版本说明**：本项目稳定版，源自 Payment-X 个人演进版适配；验证过的优化同步回 `~/.claude/skills/`。依赖本项目级 `.claude/agents/`（architect/test-writer/implementer/verifier）、`.claude/seq-mappings/` 与 `docs/PLAN.md`。

你是 GeorgeSoros-V2 项目的 **全链路实现调度器**。你的职责是根据时序图映射文件，按依赖顺序逐方法调用 Agent 完成实现。

**项目语境**：Kotlin 2.0.21 + Spring Boot 3.4.1 + JDK 21 + Gradle（Kotlin DSL）+ JPA/Hibernate + PostgreSQL 16 + Flyway；Gradle 模块 = 主应用（根模块 `com.soros.v2`，含 entity/repository/service/job/controller）+ `soros-backtest/`（回测引擎子模块）+ `soros-data-service/`（Python 微服务，独立测试体系）。设计 SoT：`docs/PLAN.md` + `docs/design/schema.sql` + `docs/design/naming-dictionary.md`。

## 输入解析

用户输入：`/implement-with-seq-dev $ARGUMENTS`

**解析规则：**
- `--list` → 列出所有映射文件及其 section
- `--resume` → 恢复上次中断的工作流
- `<mapping-file> [section-name]` → 执行指定映射文件的指定 section
  - mapping-file：`.claude/seq-mappings/` 下的 yaml 文件名（不含路径前缀）或完整路径
  - section-name：可选，只实现指定 section；不填则实现所有 section

## 环境预检

```bash
# JDK 21（本机当前只有 JDK 26；M1 首日需 brew install openjdk@21 或下载 Temurin 21）
# JAVA_HOME 禁止硬编码 26
# 注意：brew 的 openjdk@21 是 keg-only，未注册进 macOS JVM 目录，
# `/usr/libexec/java_home -v 21` 会静默回退到 26 —— 禁止用 java_home，必须用显式路径
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

确认项目能编译（Gradle 自举，无预装 framework 步骤）：
```bash
./gradlew build -x test -q
```

## 映射文件格式

映射文件位于 `.claude/seq-mappings/` 目录，YAML 格式：

```yaml
name: 情绪周期日更链路
source: docs/PLAN.md#§4.9
sections:
  - name: "一、收盘派生"
    steps:
      - id: step-01
        seq: "Job->>SVC: deriveSentiment"
        target: sentiment.SentimentCycleJob.runDaily
        module: main
        service: SentimentService
        method: deriveSentiment
        depends_on: []

      - id: step-02
        seq: "SVC->>REPO: saveCycle"
        target: sentiment.SentimentService.saveCycle
        module: main
        service: SentimentService
        method: saveCycle
        depends_on: [step-01]
```

**关键字段：**
- `id`：步骤唯一标识（用于状态追踪和 depends_on 引用）
- `seq`：时序图原文（仅用于展示）
- `target`：`域缩写.Class.methodName`（仅用于展示）
- `module`：Gradle 模块名 —— `main`（主应用）或 `soros-backtest`（回测子模块）
- `service`：Kotlin 接口名
- `method`：Kotlin 方法名
- `depends_on`：本步骤依赖的其他步骤 id 列表

## 执行流程

### --list 模式

1. 扫描 `.claude/seq-mappings/*.yaml`
2. 输出表格：

```
可用映射文件：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
文件                        Sections
sentiment-daily.yaml        一、收盘派生 | 二、人工确认 | ...
intraday-monitor.yaml       一、池快照 | 二、事件检测 | ...
backtest-run.yaml           一、回测执行
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### --resume 模式

1. 扫描 `.claude/workflows/seq-*.json`，找到 status != "completed" 的任务
2. 展示列表，让用户选择
3. 从 `currentStepIndex` 继续执行

### 正常执行模式

#### Phase 1: 加载 & 解析

1. 读取指定的映射文件
2. 如果指定了 section-name，只取该 section 的 steps；否则取所有 sections
3. 验证所有 `depends_on` 引用的 id 都存在

#### Phase 2: 检查实现状态（深度验证）

**⚠️ 核心原则：方法签名存在 ≠ 方法已实现。必须检查方法体内容。**

对每个 step，执行三层检查（**不能用简单 grep 替代**）：

**Level 1 — 方法存在性：**

```bash
# 定位 Impl 文件（module=main 用根模块路径；module=soros-backtest 用子模块路径）
impl_file=$(find src/main/kotlin -name "{service}Impl.kt" -path "*/service/*")
```

- Impl 文件不存在 → 状态 = `missing`（需要先 scaffold）
- Impl 文件存在 → 继续 Level 2

**Level 2 — 方法体真实性：**

读取 Impl 文件中目标方法的**完整方法体**（从方法签名到闭合花括号），判断是否为 stub。

**以下任意一条命中 → 判定为 stub：**

| 命中模式 | 说明 |
|---------|------|
| 方法体只有 `TODO("Not yet implemented")` / `TODO()` | Kotlin 默认 stub |
| 包含 `NotImplementedError` | 标准库 stub |
| 方法体只有 `return null` 或 `return emptyList()` | 空返回 |
| 包含 `// TODO` 或 `// FIXME` | 标记未实现 |
| 方法体内无任何业务语句（只有 log 语句或空） | 空方法 |

- 命中任一 stub 模式 → 状态 = `pending`
- 全未命中 → 方法体有真实逻辑，继续 Level 3

**Level 3 — 调用链完整性：**

从方法体中提取所有方法调用，逐个检查：

```
对方法体中的每个 xxx.method(...) 调用：

1. 同 ServiceImpl 内调用（this.method 或直接 method）
   → 读取同文件中该方法的方法体，按 Level 2 规则检查是否 stub

2. 注入的依赖调用（构造器注入字段的方法调用）
   → 定位被调用类的 Impl 文件
   → 读取对应方法体，按 Level 2 规则检查是否 stub

3. 外部 HTTP 调用（PythonDataServiceClient / RestClient / LLM 网关）
   → 检查是否有 ApiImpl/ClientImpl 且方法体不是 stub
   → 或者该方法被设计为 Mock（单元测试中模拟）→ 标注为 "Mock 依赖"
```

**综合判定：**

| Level 1 | Level 2 | Level 3 | 最终状态 | Phase 3 处理 |
|---------|---------|---------|---------|-------------|
| ❌ missing | — | — | `missing` | 需要先 scaffold |
| ✅ | ❌ stub | — | `pending` | 需要实现 |
| ✅ | ✅ 真实 | 全部 ✅ | `done` | 跳过 |
| ✅ | ✅ 真实 | 有 ❌ | `partial` | 展示给用户决定 |

**输出预检报告：**

```
📋 实现状态预检
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
序号  状态  步骤                                        详情
 1    ✅   sentiment.SentimentService.deriveSentiment  方法体真实，调用链完整
 2    ❌   sentiment.SentimentService.saveCycle        stub（TODO）
 3    ⚠️   sentiment.SentimentCycleJob.runDaily        方法体真实，但依赖 notifyService.push 未实现
 4    ❌   notify.DingTalkNotifier.pushDaily           stub（TODO）
...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ 已实现: 1  ⚠️ 部分实现: 1  ❌ 未实现: 10  ❓ 缺失: 0
```

`partial` 的步骤，用 AskUserQuestion 问用户：
- "此方法依赖 X 和 Y 尚未实现，要先实现依赖还是当前方法用 Mock？"

#### Phase 3: 计算实现顺序

基于 `depends_on` 构建 DAG，做拓扑排序：

1. 过滤掉状态为 `done` 的步骤
2. 对剩余步骤做拓扑排序
3. 如果存在循环依赖 → 报错并展示循环链

#### Phase 4: 用户确认

用 AskUserQuestion 展示实现计划：

```
📋 实现计划 — 情绪周期日更链路 > 一、收盘派生
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
序号  状态  步骤                                          依赖
 1    ✅   sentiment.SentimentService.deriveSentiment    -
 2    ⏳   sentiment.SentimentService.saveCycle          -
 3    ⏳   sentiment.CycleStatusMachine.advance          -
 4    ⏳   notify.DingTalkNotifier.pushDaily             -
 5    ⚠️   sentiment.SentimentCycleJob.runDaily          1-4 (依赖中有未实现方法)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ 已实现: 1  ⏳ 待实现: 4  ⚠️ 部分实现: 1  ⏭️ 跳过: 0

确认开始实现？
```

用户选择：
- 确认 → 进入 Phase 5
- 调整顺序/跳过某些步骤 → 修改计划后重新确认
- 取消 → 退出

#### Phase 5: 逐方法实现

创建状态文件 `.claude/workflows/seq-{name}-{timestamp}.json`

对排序后的每个 pending 步骤：

**5.1 更新状态文件**
```json
{
  "currentStepIndex": 3,
  "currentStepId": "step-04",
  "steps": {
    "step-01": { "status": "completed" },
    "step-02": { "status": "completed" },
    "step-03": { "status": "completed" },
    "step-04": { "status": "in_progress" }
  }
}
```

**5.2 调用 Architect Agent**

```
Agent(.claude/agents/architect.md, prompt:
  "实现 {service}.{method}
   模块: {module}
   设计出处: {source}（PLAN.md § 章节）
   映射步骤: {seq}
   依赖的已实现方法: {已完成的 depends_on 列表}")
```

等待完成 → 自动进入下一步（无需人工确认 Architect 产出）

> **确认策略：** Architect 和 Test Writer 的产出**不逐步确认**，仅在 Section 全部完成后统一展示结果。只有出错时才暂停请求人工介入。

**5.3 收集生成文件路径（分类存储）**

Architect Agent 完成后，将产出文件按类型分类，存入状态文件 `steps.{id}.generatedFiles`。

**必须收集的文件类型：**

| 类型 | 说明 | 下游用途 |
|------|------|---------|
| `interfaces` | Service 接口、Client 接口 | 后续步骤的 Implementer 读取方法签名 |
| `dtos` | 请求/响应 DTO（@JsonProperty snake_case） | Test Writer 构造测试数据、Implementer 使用 |
| `mappers` | 映射器（扩展函数/Mapper 类） | Implementer 注入并调用，Test Writer 用真实实例 |
| `validators` | 集中校验逻辑 | Implementer 委托调用 |
| `entities` | JPA Entity（KDoc 同文 DDL 注释） | Implementer 使用数据结构 |
| `repositories` | Spring Data Repository 接口 | Implementer 调用查询方法 |
| `enums` | Kotlin enum（值域） | Implementer 引用 |
| `sql` | Flyway 迁移文件 | 数据库建表 |

```json
"steps": {
  "s05-sentiment-derive": {
    "status": "completed",
    "generatedFiles": {
      "interfaces": [
        "src/main/kotlin/com/soros/v2/service/sentiment/SentimentService.kt"
      ],
      "dtos": [
        "src/main/kotlin/com/soros/v2/service/sentiment/dto/CycleSummaryResponse.kt"
      ],
      "mappers": [
        "src/main/kotlin/com/soros/v2/service/sentiment/mapper/CycleMapper.kt"
      ],
      "validators": [],
      "entities": [
        "src/main/kotlin/com/soros/v2/entity/SentimentCycleEntity.kt"
      ],
      "repositories": [
        "src/main/kotlin/com/soros/v2/repository/SentimentCycleRepository.kt"
      ],
      "enums": [
        "src/main/kotlin/com/soros/v2/domain/CycleStatus.kt"
      ],
      "sql": [
        "src/main/resources/db/migration/V3__add_sentiment_cycle.sql"
      ]
    }
  }
}
```

**目的：** 当后续编排方法（如 s13）的 Implementer 需要调用这些方法时，
可以直接读取接口文件获取方法签名、注入正确的 Mapper/Validator，不需要自己猜。

**5.4 调用 Test Writer Agent**

```
Agent(.claude/agents/test-writer.md, prompt:
  "为 {service}.{method} 编写单元测试
   接口文件: {architect 生成的 interfaces + dtos}
   数据结构: {architect 生成的 entities + repositories}
   映射器: {architect 生成的 mappers}（测试中用真实实例）
   校验器: {architect 生成的 validators}（测试中 mock 或真实实例）
   设计出处: {source}
   业务规则: 从时序图步骤 {seq} 提取的约束

   ⚠️ 下游契约测试要求：
   如果被测方法构建 DTO 传递给下游 Service，必须：
   1. 追踪 DTO 传递链，找到下游消费者对 DTO 哪些字段做了 null/isBlank 校验
   2. 为每个下游必需字段写 assertNotNull + argumentCaptor 断言
   3. 添加独立的下游契约测试方法（test{Method} downstreamContract {field} must not be null）
   4. 断言集合 = 下游必需字段集合，不是源 DTO 已有字段集合")
```

> **测试文件命名：** 同一 ServiceImpl 有多个方法时，每个方法使用独立的测试文件，
> 避免冲突。命名格式：`{Service}Test.kt`（首个方法）或
> `{Service}{Method}Test.kt`（后续方法）。

**5.4.1 检查是否需要 Implementer**

Test Writer 完成后，先运行测试检查是否全部通过：

```bash
# 注意：brew 的 openjdk@21 是 keg-only，未注册进 macOS JVM 目录，
# `/usr/libexec/java_home -v 21` 会静默回退到 26 —— 禁止用 java_home，必须用显式路径
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test --tests "com.soros.v2.service.{domain}.{TestClassName}" -q
```

- **全部通过** → Architect 已直接实现（常见于无状态服务：校验器、口径换算、URL 拼接等），
  **跳过 Implementer**，直接进入 5.6 Verifier
- **有失败** → 正常进入 5.5 Implementer

等待完成 → 自动进入 Implementer（无需人工确认测试用例）

**5.5 调用 Implementer Agent**

调用前，如果当前步骤有 `depends_on`，收集所有依赖步骤的 **接口和 DTO 文件**：

```
dependencyInterfaces = []
dependencyMappers = []
dependencyValidators = []
for depId in currentStep.depends_on:
    dependencyInterfaces += state.steps[depId].generatedFiles.interfaces
    dependencyInterfaces += state.steps[depId].generatedFiles.dtos
    dependencyMappers += state.steps[depId].generatedFiles.mappers
    dependencyValidators += state.steps[depId].generatedFiles.validators
```

传入 Implementer 的 prompt：

```
Agent(.claude/agents/implementer.md, prompt:
  "实现 {service}.{method} 使所有测试通过
   模块: {module}
   测试文件: {test writer 生成的文件}
   接口文件: {architect 生成的 interfaces + dtos}
   映射器: {architect 生成的 mappers}
   校验器: {architect 生成的 validators}
   数据结构: {architect 生成的 entities + repositories}

   依赖方法文件（已实现，请直接 import 使用）：
   接口和 DTO:
   {dependencyInterfaces 逐行列出}
   映射器:
   {dependencyMappers 逐行列出}
   校验器:
   {dependencyValidators 逐行列出}

   ⚠️ 实现规则提醒：
   - 对象转换必须通过映射器（扩展函数/Mapper），禁止内联字段拷贝
   - 参数校验必须委托集中校验逻辑，禁止内联校验
   - 异常必须用业务异常类型（SorosException + 错误码枚举）
   - 配置字段必须处理 null 降级
   - LLM 网关调用遵守 PLAN §12.4.1 三铁律")
```

> 如果没有 depends_on（叶子节点），则不传依赖文件。

- 测试通过 → 进入下一步
- 3 次失败 → AskUserQuestion 请求人工介入

**5.6 调用 Verifier Agent**

```
Agent(.claude/agents/verifier.md, prompt:
  "验证 {module} 模块
   新增/修改的文件: {文件列表}

   ⚠️ DTO 传播链检查：
   对每个构建 DTO 传递给下游的方法，验证：
   1. 映射器包含所有下游必需字段（查下游方法的 null/isBlank 校验）
   2. 测试中有 assertNotNull 断言覆盖这些字段
   缺失字段 → CRITICAL
   ⚠️ 领域铁律检查：ST 隔离 / 行情口径 / 单位 / Entity KDoc 同文 / 命名字典")
```

**5.6.1 Verifier 发现 Bug 的处理流程**

Verifier 可能发现 CRITICAL 或 MEDIUM 级别的 bug（如字段映射遗漏、口径混用、单位错误）。
处理流程：

1. **CRITICAL bug**（功能完全失效/领域铁律违反）→ **必须在进入下一步前修复**
   - 直接 Edit 修复实现代码（通常是一行改动）
   - 更新测试文件中记录 bug 行为的用例（改为断言正确行为）
   - 运行测试确认全部通过
   - 不需要重新调用 Implementer Agent（调度器直接修复）

2. **MEDIUM bug**（降级不可用、KDoc 不同文、命名字典缺册）→ 同上，在下一步前修复

3. **LOW / WARNING**（命名不一致建议、内联转换建议提取映射器）→
   记录到完成报告中，不阻塞流程

**5.7 更新状态为 completed，进入下一个步骤**

> **⚠️ 状态文件管理铁律：** 每个步骤的 Verifier 通过后，**必须立即**更新状态文件，
> 将当前步骤标记为 `completed`，下一步骤标记为 `in_progress`。
> 不要批量更新！如果中途崩溃，未更新的步骤会从 `pending` 重新开始，
> 但代码已实现 → Phase 2 深度验证会标记为 `done` 并跳过，不会重复工作。
> 但状态文件准确能避免不必要的 Phase 2 扫描。

#### Phase 6: Section 完成报告 & 确认

所有步骤完成后输出：

```
✅ Section 实现完成
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
映射文件: sentiment-daily.yaml
Section:  一、收盘派生
已实现:   5/5 个方法
耗时:     约 30 分钟

实现清单:
 1. ✅ sentiment.SentimentService.deriveSentiment   15 tests passed
 2. ✅ sentiment.SentimentService.saveCycle         8 tests passed
 3. ✅ sentiment.CycleStatusMachine.advance         6 tests passed
 ...
 5. ✅ sentiment.SentimentCycleJob.runDaily         10 tests passed

生成文件统计:
  接口: 5  DTO: 8  映射器: 5  校验器: 2
  Entity: 2  Repository: 2  Enum: 1  Flyway: 1  测试: 5（含集成测试）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

用 AskUserQuestion 让用户确认：
- "Section 全部完成，是否继续下一个 Section？"
- 如果只有一个 Section → "全部完成，是否结束？"
- 用户要求检查某个方法的细节 → 展示该方法的 Architect/Test Writer/Implementer 产出

**Phase 6.1: 补充测试工件（可选）**

Section 完成后，如果涉及以下场景，应主动更新相关测试工件：

| 场景 | 更新内容 |
|------|---------|
| 涉及钉钉推送 | 用 `docs/seed/` 种子快照作 fixture 补推送内容断言 |
| 新增 API 端点 | §17.5 契约核对 + MockMvc 集成测试覆盖该端点 |
| 新增值域/枚举 | schema.sql CHECK 约束同步 + enum 补值 + 字典增册 |
| 新增 JSONB 键 | naming-dictionary.md 第四节（JSONB 键约束）增册 |

## 状态文件格式

`.claude/workflows/seq-{name}-{timestamp}.json`：

```json
{
  "taskId": "seq-sentiment-daily-{timestamp}",
  "mappingFile": ".claude/seq-mappings/sentiment-daily.yaml",
  "section": "一、收盘派生",
  "createdAt": "{ISO timestamp}",
  "status": "in_progress|completed|paused",
  "currentStepIndex": 3,
  "totalSteps": 5,
  "executionOrder": ["s01-...", "s02-...", "s03-..."],
  "steps": {
    "s01-sentiment-derive": {
      "status": "completed",
      "target": "sentiment.SentimentService.deriveSentiment",
      "startedAt": "...",
      "completedAt": "...",
      "generatedFiles": {
        "interfaces": ["src/main/kotlin/.../SentimentService.kt"],
        "dtos": ["src/main/kotlin/.../dto/CycleSummaryResponse.kt"],
        "mappers": ["src/main/kotlin/.../mapper/CycleMapper.kt"],
        "validators": [],
        "entities": ["src/main/kotlin/.../entity/SentimentCycleEntity.kt"],
        "repositories": ["src/main/kotlin/.../repository/SentimentCycleRepository.kt"],
        "enums": ["src/main/kotlin/.../domain/CycleStatus.kt"],
        "sql": ["src/main/resources/db/migration/V3__add_sentiment_cycle.sql"]
      }
    },
    "s02-save-cycle": {
      "status": "in_progress",
      "target": "sentiment.SentimentService.saveCycle",
      "startedAt": "...",
      "currentAgent": "test-writer",
      "generatedFiles": {
        "interfaces": [], "dtos": [], "mappers": [], "validators": [],
        "entities": [], "repositories": [], "enums": [], "sql": []
      }
    }
  }
}
```

## ⚠️ TDD 铁律（必须严格遵守）

**本 Skill 的核心原则是严格的 Test-Driven Development。每一步都必须通过 Agent 调度完成，禁止调度器直接写代码。**

### 禁止行为（违反即流程无效）

| ❌ 禁止 | 正确做法 |
|---------|---------|
| 调度器直接 Edit/Write 实现代码 | 调用 Implementer Agent 写实现 |
| 调度器直接 Write 测试代码 | 调用 Test Writer Agent 写测试 |
| 同时写测试和实现（伪 TDD） | 先 Test Writer → 运行测试 FAIL → 再 Implementer |
| 跳过 Architect Agent 直接写代码 | 先 Architect Agent 生成接口/DTO/Entity/Repository |
| 跳过 Verifier Agent | 每步完成后必须调用 Verifier Agent |
| 手动修复 bug 不调 Agent | 小 bug 可直接 Edit，但必须运行测试确认 |

### 每步严格执行顺序

```
Phase 5.2: Architect Agent     → 生成枚举/接口/DTO/Entity/Repository/Flyway
Phase 5.4: Test Writer Agent   → 写测试（只写测试，不碰实现）
Phase 5.4.1: 运行测试           → 确认全部 FAIL（红阶段）
Phase 5.5: Implementer Agent   → 写实现让测试通过（绿阶段）+ 补集成测试
Phase 5.6: Verifier Agent      → 全量验证（含领域铁律）
```

**铁律：测试必须在实现之前存在且处于 FAIL 状态。** 如果 Test Writer 完成后测试就全部通过了（Architect 已直接实现），可以跳过 Implementer，但这必须由运行测试来确认，不能由调度器预判。

### 简单方法的例外

以下类型的方法可以简化流程（但仍需先写测试）：

- **纯校验器**（如 RequestValidator）：无外部依赖，逻辑简单
- **纯口径换算**（如手→股 ×100、集中度公式）：无状态
- **URL/键名拼接**（如 JSONB 键组装）：纯字符串操作

即使是简单方法，也必须：Test Writer → 运行测试 FAIL → Implementer（或直接实现后运行测试确认通过）

## 重要规则

1. **设计文档是最终 Source of Truth** — 任何不确定的地方，回到 `docs/PLAN.md` / `docs/design/schema.sql` 查找答案。重点参考：
   - **时序图/编排步骤** — 确定调用链、步骤顺序、参数传递（映射文件 source 指明 § 章节）
   - **API 契约（§17.5）与字段对拍（§17.6）** — 确定接口签名、入参/出参字段、业务规则
   - **schema.sql** — 确定表结构、CHECK 约束、列口径
   - 当映射文件与设计文档冲突时，以设计文档为准
2. **映射文件是执行计划** — 不猜测时序图步骤对应哪个 Kotlin 方法，严格读取映射文件确定执行顺序和目标方法
3. **确认策略：只在关键点确认** — 整个流程只需 3 次确认：
   - **开始时**（Phase 4）：确认实现计划（步骤列表、顺序、跳过项）
   - **Section 完成后**（Phase 6）：确认整组结果（通过率、文件清单）
   - **失败时**（Phase 5.5）：Implementer 3 次重试仍失败，请求人工介入
   - ⚠️ Architect 和 Test Writer 产出**不逐步确认**，自动进入下一步
4. **状态必须持久化** — 每个步骤完成后立刻写入状态文件，支持中断恢复
5. **失败不静默** — 任何步骤失败都要清晰告知用户原因和选项（跳过/重试/手动修复）
6. **已实现的方法自动跳过** — 必须通过三层深度验证（方法存在性 → 方法体真实性 → 调用链完整性）判断是否真正已实现，禁止仅用 grep 判断
7. **按依赖顺序执行** — 叶子节点先实现，调用方后实现
8. **Implementer 按需调用** — Test Writer 完成后先运行测试，如果全部通过（Architect 已直接实现），跳过 Implementer 直接进入 Verifier
9. **Verifier Bug 立即修复** — CRITICAL/MEDIUM 级 bug 在进入下一步前由调度器直接修复（Edit 实现 + 更新测试），不累积到 Section 结束
10. **测试文件独立** — 同一 ServiceImpl 的多个方法使用独立测试文件（如 `XxxTest.kt` + `XxxUpdateTest.kt`），避免冲突
11. **枚举优先于字符串** — 见下方「枚举使用规范」
12. **定时任务规范** — Job 用 @Scheduled 薄壳（只做调度 + 日志），业务逻辑委托给 Service 方法（不内联业务逻辑）；协程 scope 有异常传播策略；失败重试有上限且告警

## 枚举使用规范

**铁律：schema.sql CHECK 约束或 PLAN.md 定义的值域字段，代码中必须使用对应的 Kotlin enum，禁止硬编码字符串。**

### Architect Agent 阶段（Phase 5.2）

Architect Agent 生成 Entity/DTO 时，必须：

1. **对照 schema.sql 的 CHECK 约束**，识别所有值域受限的列
2. **检查 `com/soros/v2/domain/` 目录**，确认是否已有对应的枚举类
3. **如果不存在**，在 Architect 产出中新增枚举类（放 `domain/` 包下）
4. **枚举命名必须带业务含义**：
   - ✅ `CycleStatus`、`ConfigStatus`、`PoolId`
   - ❌ `StatusEnum`（太泛）、`YesNoEnum`（无业务含义）
5. **同名但语义不同的状态值必须用不同枚举**：
   - `CycleStatus`(RISING/BROKEN/SUSPENDED/DEAD) 用于 dragon_cycle.status
   - `ConfigStatus`(DRAFT/ACTIVE/FROZEN) 用于 strategy_config.status
   - `PoolId`(ZT/ZB/DT/STRONG/PREV) 用于池标识

### Implementer Agent 阶段（Phase 5.5）

Implementer 生成实现代码时，必须：

1. Repository 查询条件中使用枚举，而非裸字符串
2. 状态比较使用枚举：`if (cycle.status != CycleStatus.DEAD)` 而非 `!= "DEAD"`
3. DTO 赋值使用枚举
4. 新增的字符串常量如果属于枚举值域，必须通过枚举引用

### 项目领域铁律（全流程生效，Verifier 按此验收）

| 铁律 | 内容 |
|------|------|
| Entity KDoc 同文 | Entity 每字段 KDoc = schema.sql 列注释同文（以 schema.sql 为源） |
| 命名字典先查册 | 新字段先查 `naming-dictionary.md`，册没有先增册再用名；JSONB 内部键同受册管 |
| ST/*ST 隔离 | `is_st` 仅用于"识别并排除"，禁止作为业务可选项 |
| 行情口径 | OHLC 存 qfq；change_pct 用不复权口径；涨停 = 原始 change_pct + board 阈值 |
| 单位 | volume=股、amount=元；akshare 手 ×100 |
| LLM 网关 | 遵守 PLAN §12.4.1 三铁律（过滤 type=="text"；glm 带 thinking.budget_tokens=1024；按 error.type 分流重试） |
| 集成测试 | 每个服务方法实现时同步补 TestContainers(PG16)+MockMvc 集成测试 |
| 数据层补生成顺序 | Flyway 迁移缺→从 schema.sql 生成迁移；Entity/Repository 缺→Architect 补生成 |
| 金额类型 | 金额/比例一律 BigDecimal，禁用 Double |
| 对外 API 键名 | snake_case，与命名字典一致；Kotlin 属性与 JSON 键可不同（@JsonProperty 显式映射），禁止顺手统一 |
