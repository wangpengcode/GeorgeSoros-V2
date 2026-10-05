# 回填重跑计划完全体 — Architect 设计产出（接口签名 / DTO / SQL / 迁移脚本草案）

> 用途：直接交给 Implementer / Test Writer 执行。只含设计，不含实现、不含测试代码。
> 依据：用户已定稿「回填重跑计划」设计（2026-10-04），不得更改方向。
> 现状快照：repo 现有 Flyway 最大版本号 = **V6**（`db/migration/V6__data_source_check_extension.sql`）→ 新迁移 = **V7**。
> 注意：`docs/arch/t10-integration-testing.md` **不存在**（已核实 docs/arch 目录不存在），TestContainers 集成测试规划改为参考现有 `src/test/kotlin/com/soros/v2/integration/BackfillSqlIntegrationTest.kt` 的既有模式（见 §7.3）。

---

## §0 决策摘要（穿透模拟结论）

1. **替代 BackfillJob.kt:262 的跳过过滤**：`chunk.filter { (findMaxTradeDateByCode(it.code) ?: LocalDate.MIN) < to }` 只看尾部且基准日错误（未考虑 expectedStart/中间洞）→ 整轮全量重拉空转。替换为「库内重跑计划」驱动：每票按分类规则产出缺失 `FetchSegment`，**拉取窗口 = 真实缺失区间**。
2. **items 请求模型**：`DailyBarsBatchRequest` 新增 `items: List<BatchItem>`（BatchItem = code + start_date + end_date），保留旧 `codes+startDate+endDate` 向后兼容。**请求批内 code 不得重复**（响应 `results` 按 code 键 → 同 code 多 segment 必须拆到不同请求，保证 segment ↔ 响应 data 一一对应，verified-empty 归因才准确）。
3. **已验证空段只排除 MID**：`stock_history_gap_check` 记录任何「HTTP 200 且不在 failed[] 但返回 0 行」的 segment；Planner 仅对 **MID** segment 排除完全重合空段（停牌防反复空拉）。HEAD/TAIL/NO_DATA 保留重拉机会（可能是源临时故障）。
4. **日历是硬依赖**：新计划依赖交易日吸附/开市日计数，`buildRerunPlan` 内防御性 `ensureLoaded()`，失败 → `BusinessException`（不再 fail-open，否则窗口全 null → 计划静默塌缩）。
5. **Python 侧**：batch 端点在 `soros-data-service/router.py`（非 main.py；main.py 挂载 router）。扩展 `models.DailyBarsBatchRequest` + `router.daily_bars_batch`，响应契约不变（`results` 按 code 键、`failed[]`）。后端超时 profile 判定（`requestRangeExceeds`）须兼容 items。

---

## §1 Kotlin 新类与 DTO 全签名

### 1.1 `domain/SegmentReason.kt`（新建 enum）

```kotlin
package com.soros.v2.domain

/**
 * 回填缺失段原因（BackfillPlanService 分类产出）。
 * - HEAD：头部缺失 [expectedStart, min_d-1]
 * - TAIL：尾部缺失 [下一开市日, expectedEnd]
 * - MID：中间洞（gaps-and-islands 产出，可能多条）
 * - NO_DATA：库内无此码
 */
enum class SegmentReason { HEAD, TAIL, MID, NO_DATA }
```

### 1.2 `service/backfill/dto/RerunPlanDtos.kt`（新建 data class）

```kotlin
package com.soros.v2.service.backfill.dto

import com.soros.v2.domain.SegmentReason
import java.time.LocalDate

/**
 * 单票库内覆盖快照（StockHistoryRepository.aggregateCoverageByCodes 的一行，分类纯函数输入）。
 * minD/maxD 同时为 null = 库内无此码（NO_DATA）。
 */
data class StockSpan(
    val code: String,
    val minD: LocalDate?,
    val maxD: LocalDate?,
    val nRows: Long,
)

/**
 * 缺失拉取段：单次 /daily-bars/batch item = (code, from, to)。
 * 不变量（铁律）：from/to 之间的开市日 = 该票真实缺失区间；全量已补齐票不得产出任何 segment。
 */
data class FetchSegment(
    val code: String,
    val from: LocalDate,
    val to: LocalDate,
    val reason: SegmentReason,
)

/**
 * 重跑计划（BackfillPlanService.buildRerunPlan 出参）。
 * segments 为扁平清单（一票可多条）；批打包由 BackfillClassifier.batchSegments 承担（distinct code/批）。
 */
data class RerunPlan(
    val from: LocalDate,
    val to: LocalDate,
    val totalCodes: Int,
    val completeCodes: Int,     // 全齐：零 segment 票数
    val zeroWindowCodes: Int,   // expectedStart > expectedEnd（窗口无开市日）零 segment 票数
    val segments: List<FetchSegment>,
)
```

### 1.3 `service/backfill/TradingDayLookup.kt`（新建接口，分类纯函数依赖注入）

```kotlin
package com.soros.v2.service.backfill

import java.time.LocalDate

/**
 * 交易日吸附/计数查询（BackfillClassifier 纯函数注入；生产=DbTradingDayLookup，测试=内存表 Fake）。
 */
interface TradingDayLookup {
    /** ≥ date 的首个开市日（含 date 本身；无 → null） */
    fun firstTradingDayOnOrAfter(date: LocalDate): LocalDate?
    /** ≤ date 的最后开市日（含 date 本身；无 → null） */
    fun lastTradingDayOnOrBefore(date: LocalDate): LocalDate?
    /** [start, end] 区间内开市日数（含端点） */
    fun countOpenDaysInclusive(start: LocalDate, end: LocalDate): Long
    /** date 后下一个开市日（严格大于；无 → null） */
    fun nextTradingDayAfter(date: LocalDate): LocalDate?
}
```

### 1.4 `service/backfill/BackfillClassifier.kt`（新建 object，纯函数可单测）

```kotlin
package com.soros.v2.service.backfill

import com.soros.v2.domain.SegmentReason
import com.soros.v2.service.backfill.dto.FetchSegment
import com.soros.v2.service.backfill.dto.StockSpan
import java.time.LocalDate

/**
 * 缺失段分类纯函数（无 I/O、无 Spring）。生产/测试共用，铁律用例必须在此层绿。
 */
object BackfillClassifier {

    /**
     * 期望窗口 A 计算（用户已确认口径）：
     * - 起点 = max(defaultStartDate, ipo_date)，吸附到 ≥ 起点的首个开市日；
     * - 终点 = ≤ to 的最后开市日；
     * - 起点 > 终点（窗口内无开市日）→ null（零 segment）。
     */
    fun expectedWindow(
        ipoDate: LocalDate?,             // stock_info.ipo_date（null=忽略，用 defaultStartDate）
        defaultStartDate: LocalDate,     // 回填起点（近 5 年=2021-10-01）
        to: LocalDate,                   // 回填终点
        cal: TradingDayLookup,
    ): Pair<LocalDate, LocalDate>? {
        val rawStart = maxOf(defaultStartDate, ipoDate ?: defaultStartDate)
        val start = cal.firstTradingDayOnOrAfter(rawStart) ?: return null
        val end = cal.lastTradingDayOnOrBefore(to) ?: return null
        return if (start > end) null else start to end
    }

    /**
     * 单票分类：库内覆盖 vs 期望窗口 → 缺失 segment 清单（0..N 条）。
     *
     * @param midSegments   中间洞 provider（= StockHistoryRepository.findMissingDateIslands 的包装；
     *                      仅当 nRows < countOpenDays 时调用）。返回缺失开市日 islands（升序）。
     * @param isVerifiedEmpty 与 stock_history_gap_check 完全重合判定（exact match code+seg_from+seg_to）
     */
    fun classifyStock(
        code: String,
        span: StockSpan,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
        cal: TradingDayLookup,
        midSegments: (String, LocalDate, LocalDate) -> List<Pair<LocalDate, LocalDate>>,
        isVerifiedEmpty: (String, LocalDate, LocalDate) -> Boolean,
    ): List<FetchSegment> {
        if (expectedStart > expectedEnd) return emptyList()
        val segments = mutableListOf<FetchSegment>()
        val minD = span.minD
        val maxD = span.maxD
        val nRows = span.nRows
        if (minD == null || maxD == null || nRows == 0L) {
            // 无数据：整窗一段
            segments += FetchSegment(code, expectedStart, expectedEnd, SegmentReason.NO_DATA)
            return segments
        }
        // 头缺：min_d > expectedStart
        if (minD > expectedStart) {
            segments += FetchSegment(code, expectedStart, minD.minusDays(1), SegmentReason.HEAD)
        }
        // 尾缺：max_d < expectedEnd，段起点吸附 maxD 后下一开市日（+1 吸附等价）
        if (maxD < expectedEnd) {
            val tailFrom = cal.nextTradingDayAfter(maxD) ?: return segments // 理论不可达：expectedEnd 在其后必有开市日
            segments += FetchSegment(code, tailFrom, expectedEnd, SegmentReason.TAIL)
        }
        // 中间洞：行数 < [minD,maxD] 开市日数
        val openDays = cal.countOpenDaysInclusive(minD, maxD)
        if (nRows < openDays) {
            val gaps = midSegments(code, minD, maxD)
                .filter { (f, t) -> !isVerifiedEmpty(code, f, t) }
            segments += gaps.map { FetchSegment(code, it.first, it.second, SegmentReason.MID) }
        }
        return segments
    }

    /**
     * segment 批打包（请求批 = /daily-bars/batch 一次调用）。
     * 约束：**同批内 code 不得重复**（响应 results 按 code 键，重复会造成数据归属歧义）；
     * 同 code 多 segment 自动拆到不同批（批容量略浪费可接受——绝大多数票 0/1 段）。
     */
    fun batchSegments(segments: List<FetchSegment>, batchSize: Int): List<List<FetchSegment>> {
        if (segments.isEmpty()) return emptyList()
        if (batchSize <= 0) return listOf(segments)   // 防御：非正批量按单批处理
        val batches = mutableListOf<List<FetchSegment>>()
        val current = mutableListOf<FetchSegment>()
        val codesInCurrent = mutableSetOf<String>()
        for (seg in segments) {
            if (current.size >= batchSize || seg.code in codesInCurrent) {
                batches += current.toList()
                current.clear()
                codesInCurrent.clear()
            }
            current += seg
            codesInCurrent += seg.code
        }
        if (current.isNotEmpty()) batches += current.toList()
        return batches
    }
}
```

### 1.5 `service/backfill/BackfillPlanService.kt`（新建接口）

```kotlin
package com.soros.v2.service.backfill

import com.soros.v2.service.backfill.dto.RerunPlan
import java.time.LocalDate

/**
 * 回填重跑计划（库内驱动，零外部请求；替代 BackfillJob 的 buildChunkPlan 全量重拉）。
 *
 * 前提：调用方已 prepareCalendar（ensureLoaded）。本方法内防御性再 ensureLoaded()，
 * 失败 → BusinessException（日历是计划的硬依赖，不再 fail-open）。
 */
interface BackfillPlanService {
    suspend fun buildRerunPlan(from: LocalDate, to: LocalDate): RerunPlan
}
```

### 1.6 `entity/StockHistoryGapCheck.kt` + `repository/StockHistoryGapCheckRepository.kt`（新建）

```kotlin
package com.soros.v2.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * stock_history_gap_check —— 回填验证空段台账（停牌防反复空拉；每行=某 code 某段已确认 0 行）
 */
@Entity
@Table(name = "stock_history_gap_check")
@IdClass(StockHistoryGapCheckId::class)
class StockHistoryGapCheck(
    /** 证券代码（裸数字 600000） */
    @Id
    @Column(name = "code", nullable = false)
    var code: String = "",
    /** 已验证空段起点（含） */
    @Id
    @Column(name = "seg_from", nullable = false)
    var segFrom: LocalDate = LocalDate.EPOCH,
    /** 已验证空段终点（含） */
    @Id
    @Column(name = "seg_to", nullable = false)
    var segTo: LocalDate = LocalDate.EPOCH,
    /** 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空） */
    @Column(name = "rows_returned", nullable = false)
    var rowsReturned: Int = 0,
    /** 验证时间（timestamptz） */
    @Column(name = "checked_at", nullable = false, updatable = false)
    var checkedAt: Instant = Instant.now(),
)

/** 复合主键（code+seg_from+seg_to） */
class StockHistoryGapCheckId(
    val code: String = "",
    val segFrom: LocalDate = LocalDate.EPOCH,
    val segTo: LocalDate = LocalDate.EPOCH,
) : java.io.Serializable
```

```kotlin
package com.soros.v2.repository

import com.soros.v2.entity.StockHistoryGapCheck
import com.soros.v2.entity.StockHistoryGapCheckId
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StockHistoryGapCheckRepository : JpaRepository<StockHistoryGapCheck, StockHistoryGapCheckId> {

    /** 是否存在与给定区间完全重合的已验证空段（MID 排除 exact-match） */
    @Query(
        "SELECT COUNT(g) > 0 FROM StockHistoryGapCheck g " +
            "WHERE g.code = :code AND g.segFrom = :from AND g.segTo = :to",
    )
    fun existsByCodeAndRange(@Param("code") code: String, @Param("from") from: LocalDate, @Param("to") to: LocalDate): Boolean

    /** 记录已验证空段（幂等 upsert：重复验证仅刷新 rows_returned/checked_at） */
    @Modifying
    @Query(
        value = """
            INSERT INTO stock_history_gap_check (code, seg_from, seg_to, rows_returned, checked_at)
            VALUES (:code, :from, :to, :rows, NOW())
            ON CONFLICT (code, seg_from, seg_to)
            DO UPDATE SET rows_returned = :rows, checked_at = NOW()
        """,
        nativeQuery = true,
    )
    fun upsertVerifiedEmpty(
        @Param("code") code: String,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("rows") rows: Int,
    )
}
```

> 注：表只有「记录空段 + exact-match 查询」两个用途，JPA 复合主键用 `@IdClass` 即可，无复杂关联。

### 1.7 `service/backfill/DbTradingDayLookup.kt`（新建 @Component，TradingDayLookup 生产实现）

```kotlin
package com.soros.v2.service.backfill

import com.soros.v2.repository.TradingCalendarRepository
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * TradingDayLookup 生产实现（trading_calendar 表直查；谓词语义与 §1.3 接口完全一致）。
 */
@Component
class DbTradingDayLookup(
    private val calendarRepository: TradingCalendarRepository,
) : TradingDayLookup {
    override fun firstTradingDayOnOrAfter(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateGreaterThanEqualOrderByTradeDateAsc(date)?.tradeDate
    override fun lastTradingDayOnOrBefore(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(date)?.tradeDate
    override fun countOpenDaysInclusive(start: LocalDate, end: LocalDate): Long =
        calendarRepository.countByTradeDateBetween(start, end)
    override fun nextTradingDayAfter(date: LocalDate): LocalDate? =
        calendarRepository.findFirstByTradeDateAfterOrderByTradeDateAsc(date)?.tradeDate
}
```

---

## §2 Repository 新增查询签名

### 2.1 `StockHistoryRepository`（追加两个 native 查询）

```kotlin
/**
 * 每票覆盖聚合（重跑计划分类输入；仅统计库内行，min/max 为全量 span）。
 * 返回 List 空 = 该码无数据（NO_DATA）。注意：只返回有行的 code，无行 code 不在结果集里。
 */
@Query(
    value = """
        SELECT h.code AS code, MIN(h.trade_date) AS min_d, MAX(h.trade_date) AS max_d, COUNT(*) AS n_rows
        FROM stock_history h
        WHERE h.code IN :codes
        GROUP BY h.code
    """,
    nativeQuery = true,
)
fun aggregateCoverageByCodes(@Param("codes") codes: Collection<String>): List<StockSpanProjection>

/**
 * 单票缺失开市日 gaps-and-islands（calendar 反连接；仅对 n_rows < 开市日数 的票执行）。
 * 输出升序的 [seg_from, seg_to] islands = 缺失交易日连续段。
 */
@Query(
    value = """
        WITH span AS (
            SELECT MIN(trade_date) AS min_d, MAX(trade_date) AS max_d
            FROM stock_history WHERE code = :code
        ),
        missing AS (
            SELECT c.trade_date AS d
            FROM trading_calendar c, span s
            WHERE c.trade_date BETWEEN s.min_d AND s.max_d
              AND NOT EXISTS (SELECT 1 FROM stock_history h
                              WHERE h.code = :code AND h.trade_date = c.trade_date)
        ),
        islands AS (
            SELECT d, d - (ROW_NUMBER() OVER (ORDER BY d))::int AS grp
            FROM missing
        )
        SELECT MIN(d) AS seg_from, MAX(d) AS seg_to
        FROM islands
        GROUP BY grp
        ORDER BY seg_from
    """,
    nativeQuery = true,
)
fun findMissingDateIslands(@Param("code") code: String): List<GapIslandProjection>

/** 每票覆盖聚合投影（别名=字段名；minD/maxD 非空——只有有行的 code 才返回） */
interface StockSpanProjection {
    val code: String
    val min_d: LocalDate?
    val max_d: LocalDate?
    val n_rows: Long
}

/** gaps-and-islands 输出投影 */
interface GapIslandProjection {
    val seg_from: LocalDate?
    val seg_to: LocalDate?
}
```

> 若 Spring Data 接口投影对 snake_case 属性（`min_d`/`n_rows`/`seg_from`）绑定失败，回退方案：改 `List<Array<Any>>` 并在 `BackfillPlanServiceImpl` 显式 `map`（Test Writer 在集成测试里暴露此点）。

### 2.2 `TradingCalendarRepository`（追加 3 个 derived 查询，供 DbTradingDayLookup）

```kotlin
/** ≥ date 的首个交易日（含 date；期望窗口起点吸附） */
fun findFirstByTradeDateGreaterThanEqualOrderByTradeDateAsc(date: LocalDate): TradingCalendar?
/** ≤ date 的最后交易日（含 date；期望窗口终点吸附） */
fun findFirstByTradeDateLessThanEqualOrderByTradeDateDesc(date: LocalDate): TradingCalendar?
/** 区间内开市日数（含端点；全齐判定 n_rows == 开市日数） */
fun countByTradeDateBetween(from: LocalDate, to: LocalDate): Long
```

---

## §3 `DailyBarsBatchRequest` 扩展（`service/dto/PythonApiModels.kt`）

现状：`PythonApiModels.kt:62-67`，字段风格 = `@JsonProperty("snake_case")`（已核对 `PythonDataServiceClientImpl.fetchDailyBarsBatch`：直接 `.bodyValue(request)`，Jackson 序列化 @JsonProperty 键名）。改动后：

```kotlin
/** Python /api/v1/daily-bars/batch 请求（§11.1；items 模式=重跑计划逐段窗口，codes+dates 模式=向后兼容） */
data class DailyBarsBatchRequest(
    @JsonProperty("codes") val codes: List<String> = emptyList(),
    @JsonProperty("start_date") val startDate: String? = null,
    @JsonProperty("end_date") val endDate: String? = null,
    @JsonProperty("adjust") val adjust: String = "qfq",
    @JsonProperty("items") val items: List<BatchItem> = emptyList(),
) {
    init {
        require(items.isNotEmpty() || (codes.isNotEmpty() && startDate != null && endDate != null)) {
            "必须提供 items 或 codes+start_date+end_date 二选一"
        }
        require(items.isEmpty() || (codes.isEmpty() && startDate == null && endDate == null)) {
            "items 与 codes+start_date+end_date 二选一，禁止混用"
        }
    }
}

/** items 单段（code + 独立拉取窗口） */
data class BatchItem(
    @JsonProperty("code") val code: String,
    @JsonProperty("start_date") val startDate: String,
    @JsonProperty("end_date") val endDate: String,
)
```

兼容性：现有唯一调用点 `BackfillJob.fetchWithRetry`（L345）`DailyBarsBatchRequest(codes=…, startDate=…, endDate=…, adjust="qfq")` 仍合法；`PythonDataServiceClientImpl` 接口方法签名不变。

**关联改造（非 BackfillJob）—— `PythonDataServiceClientImpl.requestRangeExceeds`（L157-164）**：items 模式下 `startDate` 为 null，`LocalDate.parse` 抛异常 → catch 返回 false → 误走 default 10s profile，大窗口必超时。必须改为：

```kotlin
internal fun requestRangeExceeds(request: DailyBarsBatchRequest, days: Long): Boolean = try {
    if (request.items.isNotEmpty()) {
        // 任一段超阈值即判定 backfill（保守）
        request.items.any {
            ChronoUnit.DAYS.between(LocalDate.parse(it.startDate), LocalDate.parse(it.endDate)) > days
        }
    } else {
        ChronoUnit.DAYS.between(LocalDate.parse(request.startDate), LocalDate.parse(request.endDate)) > days
    }
} catch (e: Exception) { /* 同现状：解析失败按默认 profile */ false }
```

---

## §4 Flyway 迁移 SQL 全文（新建 `V7__stock_history_gap_check.sql`）

```sql
-- =============================================================================
-- V7：stock_history_gap_check —— 回填验证空段台账（停牌防反复空拉，2026-10-04 定稿）
--
-- 语义：某 segment 拉取成功（HTTP 200 且该码不在 failed[]）但返回 0 行 → 记录；
--       BackfillClassifier 仅对 MID 中间洞排除「与已验证空段完全重合」的 segment（exact-match）。
-- 决策留痕：code 列型用 VARCHAR(20) 对齐命名字典（全库 code 统一 VARCHAR(20)），
--       与设计草图 "varchar(10)" 有意偏差——同名必同义，避免一处 10 一处 20 漂移。
-- =============================================================================

CREATE TABLE stock_history_gap_check (
    code           VARCHAR(20) NOT NULL,      -- 证券代码（裸数字 600000）
    seg_from       DATE NOT NULL,             -- 已验证空段起点（含）
    seg_to         DATE NOT NULL,             -- 已验证空段终点（含）
    rows_returned  INTEGER NOT NULL DEFAULT 0, -- 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空）
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- 验证时间
    PRIMARY KEY (code, seg_from, seg_to)
);

COMMENT ON COLUMN stock_history_gap_check.code IS '证券代码（裸数字 600000）';
COMMENT ON COLUMN stock_history_gap_check.seg_from IS '已验证空段起点（含）';
COMMENT ON COLUMN stock_history_gap_check.seg_to IS '已验证空段终点（含）';
COMMENT ON COLUMN stock_history_gap_check.rows_returned IS '该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空）';
COMMENT ON COLUMN stock_history_gap_check.checked_at IS '验证时间（timestamptz）';
```

---

## §5 BackfillJob.kt 改造点清单（只列改动点与行号，不写代码）

| # | 位置（当前行号） | 改动 |
|---|---|---|
| 1 | 构造器 L72-84 | 新增依赖：`private val planService: BackfillPlanService`、`private val gapCheckRepository: StockHistoryGapCheckRepository` |
| 2 | L113 | `val plan = buildChunkPlan()` → `val plan = planService.buildRerunPlan(from, to)` |
| 3 | L116-118 | 空库防御保留：`if (plan.totalCodes == 0) throw BusinessException("股票清单为空：请先 POST /api/v1/info/refresh …")` |
| 4 | L119-125 | 新增 `val batches = BackfillClassifier.batchSegments(plan.segments, chunkSize)`（chunkSize = `minOf(backfillProperties.batchSize, backfillProperties.maxCodesPerBatch)` 同 L144）；`totalBatches = batches.size`；log 加 segments 数 |
| 5 | L127 | `collectAllChunks(plan.chunks, …)` → `collectAllSegmentBatches(batches, from, to, …)` |
| 6 | L141-146 | **删除** `buildChunkPlan()`（含 `ChunkPlan` data class L597-601 一并删）；`loadValidStocks()` 保留（仍供 run() 空库防御复用或移到 planService） |
| 7 | L260-283 | `processChunk(chunk: List<StockInfo>, from, to)` → `processChunk(segments: List<FetchSegment>)`；**删除 L262 跳过过滤**（本任务根因）；响应为 null → 整批 failed（段数计）；非 null → 逐 segment 处理 |
| 8 | L265 | `fetchWithRetry(pending.map { it.code }, from, to)` → `fetchWithRetry(segments)` |
| 9 | L286-308 | `processOneStock(stock, response, rejectDetails)` → `processSegment(seg, response, rejectDetails)`：`response.results[seg.code]` 为空 且 `response.failed` 无此 code → **记录 verified-empty**（`gapCheckRepository.upsertVerifiedEmpty(seg.code, seg.from, seg.to, 0)`）；`failed` 含此 code → 计 failed（**不**记录）；空且既无 results 又无 failed（异常态，防御）→ 计 failed 不记录；非空 → 过滤到 `[seg.from, seg.to]` 窗口内（防御，Python 已按段窗口返回）后 `filterValidBars` + `copyMergeBatches` |
| 10 | L344-355 | `fetchWithRetry(codes, from, to)` → `fetchWithRetry(segments: List<FetchSegment>)`：`DailyBarsBatchRequest(items = segments.map { BatchItem(it.code, it.from.toString(), it.to.toString()) }, adjust = "qfq")`；重试语义不变 |
| 11 | L163-207 | `collectAllChunks` → `collectAllSegmentBatches`：批内结果语义同 `ChunkResult(succeeded, failed, rows)`，可原样复用 |
| 12 | run() 尾部 | `buildSummary` 增加 segments 统计（completeCodes/zeroWindowCodes/MID 排除数）可选进 digest，非强制 |

**关键语义确认（实现时不得偏离）**：
- 全齐票（`BackfillClassifier.classifyStock` 返回空）**不得产生任何 segment** → 零外部请求（铁律用例）；
- 拉取窗口必须等于真实缺失区间，禁止从 `from` 全量重拉（铁律用例）；
- 批内 code 唯一由 `batchSegments` 保证 → `results` 按 code 键无歧义。

---

## §6 Python 侧 batch 端点扩展接口（`soros-data-service/`）

现状已核实：端点在 `router.py:77 daily_bars_batch`（main.py 挂载 router，无需改 main.py）；请求模型在 `models.py:36 DailyBarsBatchRequest`。

### 6.1 `models.py` — `DailyBarsBatchRequest` 扩展

```python
class DailyBarsBatchItem(BaseModel):
    code: str = Field(..., description="证券代码：裸数字 6 位股票（600000）或带前缀指数（sh000001）")
    start_date: str = Field(..., description="该段起始日期 YYYY-MM-DD")
    end_date: str = Field(..., description="该段结束日期 YYYY-MM-DD（含）")

    @field_validator("code")
    @classmethod
    def _validate_code(cls, v: str) -> str:
        code = str(v).strip()
        if not is_valid_tradeable_code(code):
            raise ValueError(f"code 必须为裸数字 6 位股票或带前缀指数，收到: {code!r}")
        return code

    @field_validator("start_date", "end_date")
    @classmethod
    def _validate_dates(cls, v: str) -> str:
        return _validate_date(v)


class DailyBarsBatchRequest(BaseModel):
    codes: list[str] = Field(default_factory=list, description="证券代码列表（items 未提供时必填）")
    start_date: Optional[str] = Field(None, description="起始日期 YYYY-MM-DD（items 未提供时必填）")
    end_date: Optional[str] = Field(None, description="结束日期 YYYY-MM-DD（含；items 未提供时必填）")
    adjust: str = Field("qfq", description="复权方式：qfq / hfq / none")
    items: list[DailyBarsBatchItem] = Field(default_factory=list, description="逐段拉取窗口（重跑计划；与 codes+dates 二选一）")

    @model_validator(mode="after")
    def _validate_xor(self) -> "DailyBarsBatchRequest":
        if self.items:
            if self.codes or self.start_date or self.end_date:
                raise ValueError("items 与 codes+start_date+end_date 二选一，禁止混用")
            seen = set()
            for it in self.items:
                if it.code in seen:
                    raise ValueError(f"items 中 code 重复: {it.code}（响应按 code 键，同批不得重复）")
                seen.add(it.code)
            if not self.items:
                raise ValueError("items 不能为空")
        else:
            if not self.codes:
                raise ValueError("codes 不能为空（items 未提供时）")
            if not self.start_date or not self.end_date:
                raise ValueError("start_date/end_date 必填（items 未提供时）")
        return self

    @field_validator("adjust")
    @classmethod
    def _validate_adjust(cls, v: str) -> str:
        if v not in ("qfq", "hfq", "none"):
            raise ValueError(f"adjust 仅支持 qfq/hfq/none，收到: {v!r}")
        return v
```

### 6.2 `router.py` — `daily_bars_batch` 分支

```python
@router.post("/daily-bars/batch", response_model=DailyBarsBatchResponse)
def daily_bars_batch(req: DailyBarsBatchRequest):
    n_items = len(req.items) if req.items else len(req.codes)
    if n_items > settings.batch_max_codes:
        # 422 信封 ERROR_PARAM_INVALID（沿用现状，提示语改 "items/codes 数量 … 超过 batch_max_codes 上限"）
        ...
    # ── items 模式（重跑计划）：逐 item 独立窗口，响应仍按 code 键 ──
    if req.items:
        # 分组归属：仍走 data_router._shard_owner(it.code)，组内逐 item 串行 fetch_daily_bars(it.code, it.start_date, it.end_date, req.adjust)
        # 串行尾批：指数等无归属 code
        # 响应：results[code] = {source, count, data[]}；失败 item 进 failed[{code, reason}]
        return {"status": "ok", "results": results, "failed": failed}
    # ── 既有 codes+dates 路径（原逻辑原样保留）──
    ...
```

**响应契约（不变）**：`{status:"ok", results:{code:{source,count,data[]}}, failed:[{code,reason}]}`；每 item 的 0 行结果 = `results[code]` 的 `count=0, data=[]`（与 codes 路径一致，Python 侧无需新增 `error` 判别字段）。

---

## §7 测试文件规划

### 7.1 Kotlin 纯逻辑（`src/test/kotlin/com/soros/v2/service/backfill/BackfillClassifierTest.kt`，必须覆盖全部铁律）

Fake `TradingDayLookup`（内存 `Set<LocalDate>` 交易日表）。用例清单：

**expectedWindow**
1. ipo_date=null → 起点吸附：defaultStartDate=2021-10-01（周五，国庆）→ 2021-10-08（首个开市日）
2. ipo_date > defaultStartDate → 起点=ipo_date 吸附
3. ipo_date < defaultStartDate → 起点=defaultStartDate 吸附
4. to 为交易日 → 终点=to；to 非交易日 → 终点=≤to 最后开市日
5. 起点吸附后 > 终点（窗口内无开市日）→ null（零 segment）

**classifyStock — 铁律**
6. **全齐铁律**：minD ≤ expectedStart 且 maxD ≥ expectedEnd 且 nRows == countOpenDays(minD,maxD) → **零 segment**
7. **全量重拉禁止铁律**：全齐票不产出任何 segment（断言 segments.isEmpty()，外部请求=0 由 Job 层断言）
8. 无数据（span 全 null）→ NO_DATA [expectedStart, expectedEnd]
9. 头缺（minD > expectedStart）→ HEAD [expectedStart, minD-1]
10. 尾缺（maxD < expectedEnd）→ TAIL [nextTradingDay(maxD), expectedEnd]（+1 吸附语义：maxD=周五 → 段起点=下周一）
11. 头缺+尾缺同时 → 两条 segment（HEAD + TAIL）
12. 中间洞（nRows < countOpenDays）→ gaps-and-islands 多条 MID（升序、不相交、并集=缺失开市日）
13. 中间洞 + 已验证空段**完全重合**（exact-match）→ 该 MID segment 被排除
14. 中间洞 + 已验证空段**部分重合**（subset/overlap 但非 exact）→ 不排除（严格 exact-match）
15. 无数据 + 已验证空段完全重合 → 不排除（NO_DATA 不走 MID 排除，保留重拉机会）
16. expectedStart > expectedEnd → 零 segment（即使库内有行）

**batchSegments**
17. 空 → empty
18. 段数 ≤ batchSize → 单批
19. 段数 > batchSize → 多批，每批 ≤ batchSize
20. 同 code 多 segment → 拆到不同批（同批内 code 唯一）
21. batchSize ≤ 0 → 防御返回单批

### 7.2 Kotlin Job 层（`src/test/kotlin/com/soros/v2/job/BackfillJobTest.kt` 增补，沿用现有 FakePythonClient 模式）

22. run() 走 `buildRerunPlan`（Fake planService 返回全齐 RerunPlan）→ `pythonClient.fetchCalls == 0`（零外部请求铁律）
23. 有 segment → `fetchDailyBarsBatch` 收到 `request.items`（断言 BatchItem 列表），且 `request.codes` 为空
24. processSegment：results[code].data 空 且 code 不在 failed → `gapCheckRepository.upsertVerifiedEmpty(code, from, to, 0)` 被调用
25. processSegment：results[code] 空 且 code **在** failed → 计 failed，**不** upsert
26. fetchWithRetry：items 构建正确；SorosBaseException → 重试 maxRetriesPerBatch 轮
27. 空 segments → 跳过 collect 直接 recompute+verify（不炸）
28. 空库 totalCodes==0 → 抛 BusinessException（防御保留）
29. 中间洞 + gap_check 完全重合 → 请求 items 不含该段（Planner 排除生效，Job 层透传）

### 7.3 TestContainers 集成（`src/test/kotlin/com/soros/v2/integration/BackfillRerunPlanIntegrationTest.kt`）

> 参考现有 `BackfillSqlIntegrationTest.kt` 模式（TestContainers PG16 + Flyway migrate + 真实 SQL）；`docs/arch/t10-integration-testing.md` 不存在，不引用。

30. Flyway V7 后 `stock_history_gap_check` 表存在 + upsert 幂等 + exact-match 查询
31. `aggregateCoverageByCodes`：造码造行 → 聚合 min/max/n_rows 正确；无行 code 不在结果集
32. `findMissingDateIslands`：calendar 有交易日 A/B/C，stock_history 缺 B → 输出 [B,B]；缺 B/C 连续 → [B,C] 单岛
33. 端到端（Fake Python + 真实 DB）：BackfillJob.run 全齐票零请求、头/尾/洞票按段拉取、gap_check 落库、MID 排除生效

### 7.4 Python pytest（`soros-data-service/tests/` 新增 `test_batch_items.py`）

34. items 与 codes+dates 混用 → 422（PARAM_INVALID）
35. items 中 code 重复 → 422
36. items 多段（不同 code 不同窗口）→ results 按 code 键，data 范围各自正确
37. items 单段 0 行且非 failed → results[code] count=0 data=[]
38. items 单码失败 → failed[{code,reason}]，results 无该 code
39. len(items) > batch_max_codes → 422
40. items 空 + codes 路径 → 向后兼容（原契约测试不回归，`test_api_contract.py` 保持绿）

---

## §8 命名字典增册（`docs/design/naming-dictionary.md` 需补）

| 键/字段 | 含义 | 使用表/DTO |
|---|---|---|
| `seg_from` | 已验证空段起点（含） | stock_history_gap_check |
| `seg_to` | 已验证空段终点（含） | stock_history_gap_check |
| `rows_returned` | 该段拉取返回行数（HTTP 200 且非 failed 时 0 即验证空） | stock_history_gap_check |
| `checked_at` | 验证时间（timestamptz） | stock_history_gap_check |
| `reason` | 缺失段原因（HEAD/TAIL/MID/NO_DATA，响应非列名） | RerunPlan / FetchSegment |
| `items` | **已存在**（区间/列表响应数组）——batch 请求复用同义：每 item = {code,start_date,end_date} 拉取段；留痕「同名必同义：列表数组」 | /daily-bars/batch 请求 |

`code` 沿用 §五 `VARCHAR(20)` 语义；`start_date/end_date` 沿用 §3 区间端点特例。

---

## §9 字段完整性对照（Architect 完成前检查）

```
字段校验 — DailyBarsBatchRequest（items 扩展）
─────────────────────────────────────────────
PLAN/字典字段            DTO 字段              状态
codes / start_date / end_date / adjust      ✅ 保留 @JsonProperty snake_case   ✅
items: List<BatchItem>                      ✅ BatchItem(code/start_date/end_date) ✅
─────────────────────────────────────────────
结果：旧字段全保留，新增 items 无冲突

字段校验 — stock_history_gap_check
─────────────────────────────────────────────
schema（本任务草图）       Flyway V7             Entity 属性              状态
code varchar(10)           VARCHAR(20)留痕       code VARCHAR(20)        ✅（字典对齐决策）
seg_from date             ✅ seg_from DATE      ✅ segFrom LocalDate      ✅
seg_to date               ✅ seg_to DATE        ✅ segTo LocalDate        ✅
rows_returned int         ✅ INTEGER            ✅ rowsReturned Int       ✅
checked_at timestamptz    ✅ TIMESTAMPTZ        ✅ checkedAt Instant      ✅
PK(code,seg_from,seg_to)  ✅ PRIMARY KEY        ✅ @IdClass              ✅
─────────────────────────────────────────────
结果：5/5 列完整，1 处宽度决策留痕（§4）

字段校验 — StockHistoryRepository 新查询
─────────────────────────────────────────────
aggregateCoverageByCodes  → code/min_d/max_d/n_rows 投影
findMissingDateIslands    → seg_from/seg_to 投影（calendar 反连接，n_rows<开市日数才调用）
─────────────────────────────────────────────
结果：与 §2 签名一致
```

---

## §10 文件清单（Implementer 产出路径）

```
新增（Kotlin）：
1. src/main/kotlin/com/soros/v2/domain/SegmentReason.kt                     enum（§1.1）
2. src/main/kotlin/com/soros/v2/service/backfill/dto/RerunPlanDtos.kt       StockSpan/FetchSegment/RerunPlan（§1.2）
3. src/main/kotlin/com/soros/v2/service/backfill/TradingDayLookup.kt        接口（§1.3）
4. src/main/kotlin/com/soros/v2/service/backfill/BackfillClassifier.kt      object 纯函数（§1.4）
5. src/main/kotlin/com/soros/v2/service/backfill/BackfillPlanService.kt     接口（§1.5）
6. src/main/kotlin/com/soros/v2/entity/StockHistoryGapCheck.kt              实体（§1.6）
7. src/main/kotlin/com/soros/v2/repository/StockHistoryGapCheckRepository.kt 仓储（§1.6）
8. src/main/kotlin/com/soros/v2/service/backfill/DbTradingDayLookup.kt      @Component（§1.7）

修改（Kotlin）：
9. src/main/kotlin/com/soros/v2/repository/StockHistoryRepository.kt        追加 2 查询 + 2 投影（§2.1）
10. src/main/kotlin/com/soros/v2/repository/TradingCalendarRepository.kt     追加 3 derived 查询（§2.2）
11. src/main/kotlin/com/soros/v2/service/dto/PythonApiModels.kt              DailyBarsBatchRequest + BatchItem（§3）
12. src/main/kotlin/com/soros/v2/service/PythonDataServiceClientImpl.kt      requestRangeExceeds items 兼容（§3）
13. src/main/kotlin/com/soros/v2/job/BackfillJob.kt                          改造点 §5（不含实现体，方法体由 Implementer 填）

新增（SQL）：
14. src/main/resources/db/migration/V7__stock_history_gap_check.sql         迁移全文（§4）

修改（Python）：
15. soros-data-service/models.py   DailyBarsBatchRequest + DailyBarsBatchItem（§6.1）
16. soros-data-service/router.py   daily_bars_batch items 分支（§6.2）

修改（文档）：
17. docs/design/naming-dictionary.md                                        §8 增册

测试（Test Writer 产出）：
18. src/test/kotlin/com/soros/v2/service/backfill/BackfillClassifierTest.kt
19. src/test/kotlin/com/soros/v2/job/BackfillJobTest.kt（增补用例 22-29）
20. src/test/kotlin/com/soros/v2/integration/BackfillRerunPlanIntegrationTest.kt
21. soros-data-service/tests/test_batch_items.py
```

编译验证：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` → `./gradlew compileKotlin -q`；Python：`cd soros-data-service && python -m pytest tests/ -q`。
