---
name: implement-dev
description: 本项目稳定版（源自 Payment-X 个人演进版适配；验证过的优化同步回 ~/.claude/skills/）。Implement a service or method using Test-First AI Coding workflow. Reads design docs (PLAN.md/schema.sql), generates interfaces, writes tests first, then generates implementation that passes tests.
argument-hint: [ServiceName.methodName or service-name]
user-invocable: true
allowed-tools: Read, Write, Edit, Glob, Grep, Bash, Agent, AskUserQuestion, TaskCreate, TaskUpdate, TaskList
---

# /implement-dev 调度 Skill（GeorgeSoros-V2 项目版）

> **版本说明**：本项目稳定版，源自 Payment-X 个人演进版适配；验证过的优化同步回 `~/.claude/skills/`。依赖本项目级 `.claude/agents/`（architect/test-writer/implementer/verifier）与 `docs/PLAN.md`、`docs/design/`。

你是 GeorgeSoros-V2 项目的 AI Coding 工作流调度器。你的职责是按照 Test-First 流程编排多个 Agent 完成代码实现。

**项目语境**：Kotlin 2.0.21 + Spring Boot 3.4.1 + JDK 21 + Gradle（Kotlin DSL）+ JPA/Hibernate + PostgreSQL 16 + Flyway + kotlinx-coroutines；设计 SoT 为 `docs/PLAN.md`（API 契约 §17.5、字段对拍 §17.6）+ `docs/design/schema.sql`（DDL）+ `docs/design/naming-dictionary.md`（命名总册）。

## 输入解析

用户输入：`/implement-dev $ARGUMENTS`

**解析规则：**
- `--resume` → 恢复模式：读取 `.claude/workflows/` 下未完成的任务，让用户选择继续
- `ServiceName.methodName`（含点号）→ 方法级任务：实现单个方法
- `service-name`（无点号）→ 服务级任务：拆分为多个方法任务逐个执行

## 环境预检

执行任何步骤前，先确认环境可用：

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

如果编译失败，先修复工程问题再继续业务开发。

## 工作流状态

当前工作流状态：
!`cat .claude/workflows/current.json 2>/dev/null || echo '{"status":"no_active_workflow"}'`

## 执行流程

### 恢复模式（--resume）

1. 扫描 `.claude/workflows/*.json`，找到 status != "completed" 的任务
2. 展示列表，让用户选择
3. 从 `currentStep` 继续执行

### 服务级任务

1. 读取 `docs/PLAN.md` 定位该服务对应的章节（模块划分见 §三 项目结构，接口契约见 §17.5/§17.6）
2. 从 PLAN.md 中提取该服务的所有方法列表
3. 分析方法间依赖，确定执行顺序
4. 用 AskUserQuestion 让用户确认任务列表和顺序
5. 创建工作流状态文件 `.claude/workflows/{task-id}.json`
6. 逐个方法执行方法级流程

### 方法级任务

按以下步骤顺序执行，每步调用对应的 Agent：

#### Step 0: 实现状态预检

在进入 Step 1 之前，**调度器直接执行**（不调用 Agent），验证方法是否真正已实现。

**⚠️ 核心原则：方法签名存在 ≠ 方法已实现。必须检查方法体。**

**检查三层：**

1. **方法存在性** — Impl 文件中是否有此方法？
2. **方法体真实性** — 方法体是否有真实业务逻辑？（不是 stub）
3. **调用链完整性** — 方法内部调用的其他 Service 方法是否也已真正实现？

**0.1 定位 Impl 文件**

```bash
impl_file=$(find src/main/kotlin -name "{service}Impl.kt" -path "*/service/*")
# 回测模块：find soros-backtest/src/main/kotlin -name "{service}Impl.kt"
```

如果 Impl 文件不存在 → 状态 = `missing`，直接进入 Step 1。

**0.2 读取方法体，判断是否 stub**

从 Impl 文件中读取目标方法的完整方法体（从方法签名到对应闭合花括号）。

**以下任意一条命中 → 判定为 stub（未实现）：**

| 命中模式 | 说明 |
|---------|------|
| 方法体只有 `TODO("Not yet implemented")` / `TODO()` | Kotlin 默认 stub |
| 方法体包含 `NotImplementedError` | 标准库 stub |
| 方法体只有 `return null` 或 `return emptyList()` | 空返回 |
| 方法体包含 `// TODO` 或 `// FIXME` | 标记未实现 |
| 方法体内无任何业务语句（只有 log 语句或空） | 空方法 |

**以上全未命中 → 方法体有真实逻辑，继续检查调用链。**

**0.3 检查内部调用链（深度验证）**

从方法体中提取所有方法调用，对每个调用检查其实现状态：

```
对方法体中的每个 xxx.method(...) 调用：

1. 同 ServiceImpl 内调用（this.method 或直接 method）
   → 读取同文件中该方法的方法体，按 0.2 的规则检查是否 stub

2. 注入的依赖调用（构造器注入字段的方法调用）
   → 定位被调用类的 Impl 文件
   → 读取对应方法体，按 0.2 的规则检查是否 stub

3. 外部 HTTP 调用（PythonDataServiceClient / RestClient / LLM 网关）
   → 检查是否有真实实现且方法体不是 stub
   → 或者该方法被设计为 Mock（单元测试中模拟）→ 标注为 "Mock 依赖"，不算未实现
```

**0.4 输出预检报告**

```
📋 实现状态预检
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
方法：{Service}.{method}

[Level 1] 方法存在性：    ✅ 存在于 {Service}Impl.kt
[Level 2] 方法体真实性：  ✅ 有真实业务逻辑（23 行代码）
[Level 3] 调用链完整性：
  ├── cycleService.confirmCycle()      → ✅ 已实现
  ├── pythonDataServiceClient.getKline() → 🔶 Mock 依赖（HTTP 客户端）
  └── notifyService.sendDingTalk()     → ❌ 未实现（方法体为 TODO）
判定：⚠️ 部分实现（依赖链中有未实现方法）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**0.5 根据判定决定下一步**

| 判定结果 | 动作 |
|---------|------|
| 全部 ✅（三层通过） | 报告"已完全实现"，AskUserQuestion 问用户是否重新实现 |
| Level 1 ❌（missing） | 正常进入 Step 1 |
| Level 2 ❌（stub） | 正常进入 Step 1 |
| Level 3 有 ❌（部分依赖未实现） | 列出未实现的依赖方法，AskUserQuestion 让用户决定：先实现依赖方法，还是当前方法用 Mock 处理 |

**服务级任务的预检：** 对服务级任务，在拆分任务列表时，对每个方法先执行 Step 0 预检。在任务列表中展示每个方法的实现状态（✅已实现 / ⚠️部分实现 / ❌未实现），让用户确认哪些需要实现、哪些跳过。

#### Step 1: Architect Agent

调用 `.claude/agents/architect.md` agent，传入：
- 任务描述：要实现的服务名和方法名
- 设计出处：PLAN.md 对应 § 章节（API 契约/时序图/schema 表）

等待 Agent 完成后：
1. 更新状态文件 steps.architect.status = "completed"
2. 记录生成的文件列表
3. 自动进入 Step 1.5（无需人工确认 Architect 产出）
4. 如果 Agent 报告编译失败或明显问题 → AskUserQuestion 请求人工介入

#### Step 1.5: Flyway & 数据层预检

在进入测试编写前，必须确认方法所依赖的数据层完整。**此步骤由调度器直接执行，不需要调用 Agent。**

**1.5.1 识别所需表**

从 Step 1 Architect 产出的 Entity 文件中，提取所有涉及的表名：

```bash
# 从 Entity 文件的 @Table 注解中提取表名
grep -rh '@Table(name' src/main/kotlin/com/soros/v2/entity/ | grep -o '"[^"]*"'
```

**1.5.2 检查 Flyway 迁移是否存在**

对每张表，检查 `src/main/resources/db/migration/` 下是否有对应建表 SQL：

```bash
grep -rl "CREATE TABLE.*{table_name}" src/main/resources/db/migration/ 2>/dev/null
```

**1.5.3 检查 Entity 和 Repository 是否存在**

```bash
# Entity 文件
find src/main/kotlin -name "*Entity.kt" -path "*/entity/*"

# Repository 文件
find src/main/kotlin -name "*Repository.kt" -path "*/repository/*"
```

**1.5.4 缺失处理（补生成顺序）**

| 缺失项 | 处理方式 |
|--------|---------|
| Flyway 迁移不存在 | **从 schema.sql 对应表生成迁移**（schema.sql 是 DDL 唯一 SoT），调用 Architect Agent 只传 SQL 相关任务 |
| Entity 文件不存在 | 调用 Architect Agent 补生成 Entity（KDoc 与 DDL 列注释同文铁律） |
| Repository 文件不存在 | 调用 Architect Agent 补生成 Repository（继承 JpaRepository，添加业务键查询方法） |

补生成后，重新执行 1.5.2~1.5.3 确认完整性。

**1.5.5 输出预检报告**

```
📋 数据层预检报告
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
方法：SentimentService.confirmCycle

所需表：sentiment_cycle
  Flyway 迁移：V3__add_sentiment_cycle.sql     ✅
  Entity 文件：SentimentCycleEntity.kt          ✅
  Repository 文件：SentimentCycleRepository.kt  ✅

结果：3/3 项完整，可以进入测试编写 ✅
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

有缺失项 → 补生成后重新输出报告，全部 ✅ 后进入 Step 2。

#### Step 2: Test Writer Agent

调用 `.claude/agents/test-writer.md` agent，传入：
- Step 1 生成的文件路径列表
- 设计出处（PLAN.md § 章节，含状态机/约束规则）

等待 Agent 完成后：
1. 更新状态文件 steps.test-writer.status = "completed"
2. 进入 Step 2.5 检查

#### Step 2.5: 检查是否需要 Implementer

Test Writer 完成后，先运行测试检查是否全部通过：

```bash
# 注意：brew 的 openjdk@21 是 keg-only，未注册进 macOS JVM 目录，
# `/usr/libexec/java_home -v 21` 会静默回退到 26 —— 禁止用 java_home，必须用显式路径
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test --tests "com.soros.v2.service.{domain}.{TestClassName}" -q
```

- **全部通过** → Architect 已在 Step 1 直接实现了代码（常见于无状态服务：
  校验器、口径换算、URL 拼接等），**跳过 Step 3 Implementer**，直接进入 Step 4 Verifier
- **有失败** → 正常进入 Step 3 Implementer

#### Step 3: Implementer Agent

调用 `.claude/agents/implementer.md` agent，传入：
- 测试文件路径
- Step 1 生成的接口/DTO/Entity/Repository 文件路径
- 目标模块（主应用 or soros-backtest）

Agent 内部会运行测试并自动重试（同时补 TestContainers+MockMvc 集成测试）。等待结果：
- 测试通过 → 更新状态，进入 Step 4
- 3 次失败 → **用 AskUserQuestion 请求人工介入**，展示失败信息

#### Step 4: Verifier Agent

调用 `.claude/agents/verifier.md` agent，传入：
- 目标模块名
- 所有生成的文件路径

Agent 运行完整验证后返回报告。
- 全部通过 → 更新状态 currentStep = "completed"，输出完成摘要
- 有问题 → 展示问题，让用户决定是否需要修复

## 状态文件格式

创建/更新 `.claude/workflows/{task-id}.json`：

```json
{
  "taskId": "{service}-{method}-{timestamp}",
  "description": "实现 {Service}.{method}",
  "targetModule": "main | soros-backtest",
  "targetService": "{service}",
  "targetMethod": "{method}",
  "createdAt": "{ISO timestamp}",
  "currentStep": "architect|data-precheck|test-writer|implementer|verifier|completed",
  "steps": {
    "architect": {
      "status": "pending|in_progress|completed",
      "humanApproved": false,
      "generatedFiles": []
    },
    "data-precheck": {
      "status": "pending|in_progress|completed",
      "requiredTables": [],
      "missingItems": [],
      "allComplete": false
    },
    "test-writer": {
      "status": "pending|in_progress|completed",
      "humanApproved": false,
      "generatedFiles": []
    },
    "implementer": {
      "status": "pending|in_progress|completed",
      "retryCount": 0,
      "lastError": null
    },
    "verifier": {
      "status": "pending|in_progress|completed",
      "report": null
    }
  }
}
```

## 重要规则

1. **确认策略：只在关键点确认** — Architect 和 Test Writer 产出**不逐步确认**，自动进入下一步。仅在 Implementer 3 次重试仍失败时请求人工介入
2. **状态必须持久化** — 每个步骤完成后立刻写入状态文件
3. **失败不静默** — 任何步骤失败都要清晰告知用户原因和选项
4. **一次一个方法** — 即使是服务级任务，也是逐个方法走完整流程
5. **Implementer 按需调用** — Test Writer 完成后先运行测试，全部通过则跳过 Implementer
6. **Verifier Bug 立即修复** — CRITICAL/MEDIUM 级 bug 在进入下一步前由调度器直接修复
7. **测试文件独立** — 同一 ServiceImpl 的多个方法使用独立测试文件
8. **枚举优先于字符串** — 见下方「枚举使用规范」
9. **命名字典先查册** — 新字段/新 JSON 键先查 `docs/design/naming-dictionary.md`，册没有先增册再用名；禁止实现时擅自命名

## 枚举使用规范

**铁律：schema.sql CHECK 约束或 PLAN.md 定义的值域字段，代码中必须使用对应的 Kotlin enum，禁止硬编码字符串。**

### Step 1 Architect 阶段

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

### Step 3 Implementer 阶段

Implementer 生成实现代码时，必须：

1. Repository 查询条件中使用枚举（@Param 传 enum 或 name），而非裸字符串
2. 状态比较使用枚举：`if (cycle.status != CycleStatus.DEAD)` 而非 `!= "DEAD"`
3. DTO 赋值使用枚举
4. 新增的字符串常量如果属于枚举值域，必须通过枚举引用

### 项目领域铁律（全流程生效，Verifier 按此验收）

| 铁律 | 内容 |
|------|------|
| Entity KDoc 同文 | Entity 每字段 KDoc = schema.sql 列注释同文（以 schema.sql 为源） |
| 命名字典先查册 | 新字段先查 `naming-dictionary.md`，册没有先增册再用名 |
| ST/*ST 隔离 | `is_st` 仅用于"识别并排除"，禁止作为业务可选项 |
| 行情口径 | OHLC 存 qfq；change_pct 用不复权口径；涨停 = 原始 change_pct + board 阈值 |
| 单位 | volume=股、amount=元；akshare 手 ×100 |
| LLM 网关 | 遵守 PLAN §12.4.1 三铁律（过滤 type=="text"；glm 带 thinking.budget_tokens=1024；按 error.type 分流重试） |
| 集成测试 | 每个服务方法实现时同步补 TestContainers(PG16)+MockMvc 集成测试 |
| 数据层补生成顺序 | Flyway 迁移缺→从 schema.sql 生成迁移；Entity/Repository 缺→Architect 补生成 |
| 金额类型 | 金额/比例一律 BigDecimal，禁用 Double |
