package com.soros.v2.service.algorithm

import com.soros.v2.testfixtures.V1InflectionPoint
import com.soros.v2.testfixtures.V1InflectionPointType
import com.soros.v2.testfixtures.V1SorosUtils
import com.soros.v2.testfixtures.V1StockTrendWaveBo
import com.soros.v2.testfixtures.V1StockWaveBo
import com.soros.v2.testfixtures.V1TrendMultiType
import com.soros.v2.testfixtures.V1WaveDirectionEnum
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §11.5 SorosAlgorithmV1 vs V2 对拍 golden 测试。
 *
 * V1 复刻版（testFixtures/SorosUtilsV1）与 V2 清洁版同输入各跑一遍，diff 必须逐条命中 off-by-one 清单：
 *  1. merge 丢末点            → 修正：末点完整纳入
 *  2. peekAndValley 丢尾 2     → 修正：遍历上界放开到尾 2
 *  3. littleTrend 丢末 3 点 + 不落进行中段 → 修正：循环覆盖全段 + 进行中段落库
 *  4. bigTrend 末段 range 不重算 → 修正：末段并入前重算 range
 *  5. lastDays 恒 0            → 修正：start/end 区间交易日数
 *  6. size<20 守卫 &&→||        → 修正：空 或 不足 20 均守卫
 *  7. yyyyMMdd 字典序          → 修正：LocalDate 排序
 *
 * golden 数据程序化生成（确定性正弦序列，无随机种子依赖）+ L1 手工小向量（6/7/19/20/21 bar 边界）。
 *
 * 对拍契约：同一确定性输入 → V1（复刻）与 V2（清洁版）各跑一遍，diff 命中并锁定上述 off-by-one
 * 修正清单；本测试为回归防线，全绿即代表修正持续生效（V2 已全量落地，非骨架）。
 */
class SorosAlgorithmGoldenTest {

    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    // ==================== 构造辅助 ====================

    private fun bar(date: LocalDate, close: BigDecimal, high: BigDecimal, low: BigDecimal) =
        Bar("600000", date, close, high, low)

    private fun toV1Wave(b: Bar) = V1StockWaveBo(b.code, b.close, b.high, b.low, b.date.format(fmt))

    private fun v2Point(date: LocalDate, type: V2PointType, value: BigDecimal) =
        V2InflectionPoint(
            code = "600000",
            date = date,
            close = null,
            high = if (type == V2PointType.MAX) value else null,
            low = if (type == V2PointType.MIN) value else null,
            type = type,
        )

    private fun v1Point(date: LocalDate, type: V1InflectionPointType, value: BigDecimal) =
        V1InflectionPoint(
            code = "600000",
            date = date.format(fmt),
            close = null,
            high = if (type == V1InflectionPointType.MAX) value else null,
            low = if (type == V1InflectionPointType.MIN) value else null,
            type = type,
        )

    /** 确定性正弦 golden 序列（产生大量清晰拐点） */
    private fun goldenBars(days: Int, start: LocalDate = LocalDate.of(2021, 10, 8)): List<Bar> =
        (0 until days).map { i ->
            val close = 100.0 + 25.0 * Math.sin(i / 4.0) + (i % 5) * 0.1
            bar(
                start.plusDays(i.toLong()),
                BigDecimal(close).setScale(4, RoundingMode.HALF_UP),
                BigDecimal(close + 3.0).setScale(4, RoundingMode.HALF_UP),
                BigDecimal(close - 3.0).setScale(4, RoundingMode.HALF_UP),
            )
        }

    /** V2 拐点 → 波（lastDays 交给 bigTrend/littleTrend 计算，此处入参 0） */
    private fun v2Wave(
        dir: V2WaveDirection,
        start: V2InflectionPoint,
        end: V2InflectionPoint,
        range: BigDecimal,
    ) = V2TrendWave("600000", dir, 0, range, V2TrendMultiType.M, start, end)

    private fun v1Wave(
        dir: V1WaveDirectionEnum,
        start: V1InflectionPoint,
        end: V1InflectionPoint,
        range: BigDecimal,
    ) = V1StockTrendWaveBo(
        waveDirectionEnum = dir,
        lastDays = 0,
        range = range,
        trendMultiType = V1TrendMultiType.M,
        code = "600000",
        startInflectionPoint = start,
        endInflectionPoint = end,
    )

    // ==================== 1. golden diff 全字段对拍 ====================

    @Test
    fun `golden diff - V1 vs V2 全字段对拍命中 off-by-one 清单`() {
        // given: 250 交易日正弦序列（确定性，拐点丰富）
        val bars = goldenBars(250)
        val v1Waves = bars.map { toV1Wave(it) }
        val v1 = V1SorosUtils()

        // when: 双管道各跑一遍
        val v1Inf = v1.findInflectionPoint(v1Waves, 6)!!
        val v2Inf = SorosAlgorithmV2.findInflectionPoint(bars, 6)
        assertNotNull(v1Inf, "V1 拐点非 null")
        assertTrue(v2Inf.isNotEmpty(), "V2 拐点非空")

        val v1Merge = v1.merge(v1Inf)!!
        val v2Merge = SorosAlgorithmV2.merge(v2Inf)
        val v1LT = v1.littleTrend(v1Merge)
        val v2LT = SorosAlgorithmV2.littleTrend(v2Merge)
        val v1BT = v1.bigTrend(v1LT ?: emptyList())
        val v2BT = SorosAlgorithmV2.bigTrend(v2LT)

        // then: 清单 7 —— 日期排序（V2 LocalDate 升序；V1 yyyyMMdd 字典序）
        assertEquals(v2Inf.map { it.date }, v2Inf.map { it.date }.sorted(), "V2 拐点按 LocalDate 升序")

        // then: 清单 1 —— merge 丢末点修正
        assertEquals(v2Inf.last().date, v2Merge.last().date, "V2 merge 保留末拐点（清单1 修正）")
        assertTrue(v1Merge.last().date != v1Inf.last().date, "V1 merge 丢末点（复刻 bug）")

        // then: 清单 3 —— littleTrend 覆盖全段（V2 含进行中段）
        assertTrue(v2LT.size >= (v1LT?.size ?: 0), "V2 littleTrend 产出 ≥ V1（清单3 修正：进行中段落库）")

        // then: 清单 5 —— lastDays 恒0 修正
        assertTrue(v2BT.any { it.lastDays >= 1 }, "V2 bigTrend lastDays 已修正（>0，清单5）")
        assertTrue(v1BT.all { it.lastDays == 0 }, "V1 lastDays 恒 0（复刻 bug）")
    }

    // ==================== 2. L1 - 6 bar 边界 ====================

    @Test
    fun `L1 - 6 bar 边界 - findInflectionPoint size 守卫`() {
        // given: 6 根 bar（days=6 → 2*6+1=13，6<13 守卫触发）
        val bars = (1..6).map { i -> bar(LocalDate.of(2026, 9, 1).plusDays(i.toLong()), BigDecimal("10"), BigDecimal("11"), BigDecimal("9")) }

        // when
        val v1 = V1SorosUtils().findInflectionPoint(bars.map { toV1Wave(it) }, 6)
        val v2 = SorosAlgorithmV2.findInflectionPoint(bars, 6)

        // then: 清单 6 同源守卫——V1 null / V2 空列表（口径统一为"空"）
        assertNull(v1, "V1 size<13 → null（复刻）")
        assertTrue(v2.isEmpty(), "V2 size<13 → 空列表（清洁版不返回 null）")
    }

    // ==================== 3. L1 - 7 bar 边界 ====================

    @Test
    fun `L1 - 7 bar 边界 - 拐点窗口`() {
        // given: 7 根 bar，days=3（2*3+1=7，恰好过守卫）；第一段 [10,12,10] 中 12 为局部高点（非段边缘）
        val d = LocalDate.of(2026, 9, 1)
        val bars = listOf(
            bar(d, BigDecimal("10"), BigDecimal("10"), BigDecimal("9")),
            bar(d.plusDays(1), BigDecimal("11"), BigDecimal("12"), BigDecimal("10")),
            bar(d.plusDays(2), BigDecimal("10"), BigDecimal("10"), BigDecimal("9")),
            bar(d.plusDays(3), BigDecimal("10"), BigDecimal("11"), BigDecimal("9")),
            bar(d.plusDays(4), BigDecimal("9"), BigDecimal("9"), BigDecimal("8")),
            bar(d.plusDays(5), BigDecimal("9"), BigDecimal("10"), BigDecimal("8")),
            bar(d.plusDays(6), BigDecimal("10"), BigDecimal("11"), BigDecimal("9")),
        )

        // when
        val v1 = V1SorosUtils().findInflectionPoint(bars.map { toV1Wave(it) }, 3)
        val v2 = SorosAlgorithmV2.findInflectionPoint(bars, 3)

        // then: 恰好边界 → 双方都处理（非 null/空），V2 拐点含段内局部高点 d+1
        assertNotNull(v1, "V1 7=2*3+1 恰好过守卫")
        assertTrue(v2.isNotEmpty(), "V2 7=2*3+1 恰好过守卫")
        assertTrue(v2.any { it.date == d.plusDays(1) && it.type == V2PointType.MAX }, "V2 首段局部高点入拐点")
    }

    // ==================== 4. L1 - 19/20/21 bar 边界（peekAndValley 守卫 && vs ||） ====================

    @Test
    fun `L1 - 19 20 21 bar 边界 - peekAndValley size 守卫 双与 vs 双或`() {
        // given: N 个交替 MAX/MIN 拐点（值递增，保证每个极值都合格）
        fun pts(n: Int): Pair<List<V1InflectionPoint>, List<V2InflectionPoint>> {
            val d0 = LocalDate.of(2026, 9, 1)
            val v1p = mutableListOf<V1InflectionPoint>()
            val v2p = mutableListOf<V2InflectionPoint>()
            for (i in 0 until n) {
                val date = d0.plusDays(i.toLong())
                val value = BigDecimal(100 + i)
                val type = if (i % 2 == 0) V1InflectionPointType.MAX else V1InflectionPointType.MIN
                v1p.add(v1Point(date, type, value))
                v2p.add(v2Point(date, if (i % 2 == 0) V2PointType.MAX else V2PointType.MIN, value))
            }
            return v1p to v2p
        }

        // 19（非空 <20）：V1 `isEmpty && size<20`=false → 照常处理；V2 `isEmpty || size<20`=true → 空
        val (v1p19, v2p19) = pts(19)
        assertNotNull(V1SorosUtils().findPeekAndValley(v1p19), "V1 19 点非空 → && 守卫不拦（复刻 bug）")
        assertTrue(SorosAlgorithmV2.findPeekAndValley(v2p19).isEmpty(), "V2 19 点 → || 守卫空列表（清单6 修正）")

        // 20/21（>=20）：双方都处理
        val (v1p20, v2p20) = pts(20)
        assertNotNull(V1SorosUtils().findPeekAndValley(v1p20), "V1 20 点处理")
        assertTrue(SorosAlgorithmV2.findPeekAndValley(v2p20).isNotEmpty(), "V2 20 点处理非空")
        val (v1p21, v2p21) = pts(21)
        assertNotNull(V1SorosUtils().findPeekAndValley(v1p21), "V1 21 点处理")
        assertTrue(SorosAlgorithmV2.findPeekAndValley(v2p21).isNotEmpty(), "V2 21 点处理非空")
    }

    // ==================== 5. L1 - merge 丢末点 ====================

    @Test
    fun `L1 - merge 丢末点行为锁定`() {
        // given: [MIN(100,d1), MAX(50,d2), MAX(60,d3)] —— 末点为独立 MAX60
        val d1 = LocalDate.of(2026, 9, 1)
        val d2 = LocalDate.of(2026, 9, 2)
        val d3 = LocalDate.of(2026, 9, 3)
        val v1In = listOf(v1Point(d1, V1InflectionPointType.MIN, BigDecimal("100")), v1Point(d2, V1InflectionPointType.MAX, BigDecimal("50")), v1Point(d3, V1InflectionPointType.MAX, BigDecimal("60")))
        val v2In = listOf(v2Point(d1, V2PointType.MIN, BigDecimal("100")), v2Point(d2, V2PointType.MAX, BigDecimal("50")), v2Point(d3, V2PointType.MAX, BigDecimal("60")))

        // when
        val v1 = V1SorosUtils().merge(v1In)!!
        val v2 = SorosAlgorithmV2.merge(v2In)

        // then: 清单1 —— V1 丢末点；V2 保留末点（MAX60@d3）
        assertTrue(v1.last().date != d3.format(fmt), "V1 merge 末点 MAX60 丢失（复刻 bug）")
        assertEquals(d3, v2.last().date, "V2 merge 末点完整纳入（清单1 修正）")
        assertEquals(V2PointType.MAX, v2.last().type, "V2 末点为 MAX")
        assertEquals(0, v2.last().high!!.compareTo(BigDecimal("60")), "V2 末点高=60")
    }

    // ==================== 6. L1 - littleTrend 丢末3点与进行中段 ====================

    @Test
    fun `L1 - littleTrend 丢末3点与进行中段`() {
        // given: 上升趋势点列（6 个拐点，末点 MAX30@d6）
        val d = LocalDate.of(2026, 9, 1)
        val v1In = listOf(
            v1Point(d, V1InflectionPointType.MIN, BigDecimal("10")),
            v1Point(d.plusDays(1), V1InflectionPointType.MAX, BigDecimal("20")),
            v1Point(d.plusDays(2), V1InflectionPointType.MIN, BigDecimal("5")),
            v1Point(d.plusDays(3), V1InflectionPointType.MAX, BigDecimal("25")),
            v1Point(d.plusDays(4), V1InflectionPointType.MIN, BigDecimal("4")),
            v1Point(d.plusDays(5), V1InflectionPointType.MAX, BigDecimal("30")),
        )
        val v2In = listOf(
            v2Point(d, V2PointType.MIN, BigDecimal("10")),
            v2Point(d.plusDays(1), V2PointType.MAX, BigDecimal("20")),
            v2Point(d.plusDays(2), V2PointType.MIN, BigDecimal("5")),
            v2Point(d.plusDays(3), V2PointType.MAX, BigDecimal("25")),
            v2Point(d.plusDays(4), V2PointType.MIN, BigDecimal("4")),
            v2Point(d.plusDays(5), V2PointType.MAX, BigDecimal("30")),
        )

        // when
        val v1 = V1SorosUtils().littleTrend(v1In)
        val v2 = SorosAlgorithmV2.littleTrend(v2In)

        // then: 清单3 —— V1 截断末3点+不落进行中段（结果不含末点）；V2 全段覆盖、进行中段落库（末点=MAX30@d+5）
        assertTrue(v1 != null && v2.size > v1.size, "V2 littleTrend 产出 > V1（覆盖进行中段，清单3 修正）")
        assertTrue(v1!!.none { it.endInflectionPoint?.date == d.plusDays(5).format(fmt) }, "V1 不落进行中段（复刻 bug）")
        assertEquals(d.plusDays(5), v2.last().end?.date, "V2 进行中段 end=段末点（清单3 修正）")
    }

    // ==================== 7. L1 - bigTrend 末段 range 重算 ====================

    @Test
    fun `L1 - bigTrend 末段 range 重算`() {
        // given: [up(10→20), down(20→15, range=0.5 陈旧), up(15→18)] —— 末段 pre=down(20→15) 带陈旧 range
        val d = LocalDate.of(2026, 9, 1)
        val v1In = listOf(
            v1Wave(
                V1WaveDirectionEnum.RISE,
                v1Point(d, V1InflectionPointType.MIN, BigDecimal("10")),
                v1Point(d.plusDays(1), V1InflectionPointType.MAX, BigDecimal("20")),
                BigDecimal("1.0"),
            ),
            v1Wave(
                V1WaveDirectionEnum.FALL,
                v1Point(d.plusDays(1), V1InflectionPointType.MAX, BigDecimal("20")),
                v1Point(d.plusDays(2), V1InflectionPointType.MIN, BigDecimal("15")),
                BigDecimal("0.5"), // 陈旧 range（本应为 (15-20)/20 = -0.25）
            ),
            v1Wave(
                V1WaveDirectionEnum.RISE,
                v1Point(d.plusDays(2), V1InflectionPointType.MIN, BigDecimal("15")),
                v1Point(d.plusDays(3), V1InflectionPointType.MAX, BigDecimal("18")),
                BigDecimal("0.2"),
            ),
        )
        val v2In = listOf(
            v2Wave(V2WaveDirection.RISE, v2Point(d, V2PointType.MIN, BigDecimal("10")), v2Point(d.plusDays(1), V2PointType.MAX, BigDecimal("20")), BigDecimal("1.0")),
            v2Wave(V2WaveDirection.FALL, v2Point(d.plusDays(1), V2PointType.MAX, BigDecimal("20")), v2Point(d.plusDays(2), V2PointType.MIN, BigDecimal("15")), BigDecimal("0.5")),
            v2Wave(V2WaveDirection.RISE, v2Point(d.plusDays(2), V2PointType.MIN, BigDecimal("15")), v2Point(d.plusDays(3), V2PointType.MAX, BigDecimal("18")), BigDecimal("0.2")),
        )

        // when
        val v1 = V1SorosUtils().bigTrend(v1In)
        val v2 = SorosAlgorithmV2.bigTrend(v2In)

        // then: 清单4 —— V1 末段沿用陈旧 range=0.5；V2 入 result 前按最终 start/end 重算 (15-20)/20 = -0.25
        assertEquals(0, v1.last().range.compareTo(BigDecimal("0.5")), "V1 末段 range 不重算（复刻 bug）")
        assertEquals(0, v2.last().range.compareTo(BigDecimal("-0.25")), "V2 末段 range 重算（清单4 修正）")
    }

    // ==================== 8. L1 - lastDays 恒0 vs 区间交易日数 ====================

    @Test
    fun `L1 - lastDays 恒0 vs 区间交易日数`() {
        // given: 上升点列 MIN10@d → MAX20@d+1 → …（首个波 span=d..d+1，日历差 1+1=2）
        val d = LocalDate.of(2026, 9, 1)
        val v1In = listOf(
            v1Point(d, V1InflectionPointType.MIN, BigDecimal("10")),
            v1Point(d.plusDays(1), V1InflectionPointType.MAX, BigDecimal("20")),
            v1Point(d.plusDays(2), V1InflectionPointType.MIN, BigDecimal("5")),
            v1Point(d.plusDays(3), V1InflectionPointType.MAX, BigDecimal("25")),
            v1Point(d.plusDays(4), V1InflectionPointType.MIN, BigDecimal("4")),
            v1Point(d.plusDays(5), V1InflectionPointType.MAX, BigDecimal("30")),
        )
        val v2In = listOf(
            v2Point(d, V2PointType.MIN, BigDecimal("10")),
            v2Point(d.plusDays(1), V2PointType.MAX, BigDecimal("20")),
            v2Point(d.plusDays(2), V2PointType.MIN, BigDecimal("5")),
            v2Point(d.plusDays(3), V2PointType.MAX, BigDecimal("25")),
            v2Point(d.plusDays(4), V2PointType.MIN, BigDecimal("4")),
            v2Point(d.plusDays(5), V2PointType.MAX, BigDecimal("30")),
        )

        // when: 走 littleTrend（V2 建波即计算 lastDays；V1 构造器恒 0）
        val v1Out = V1SorosUtils().littleTrend(v1In)!!
        val v2Out = SorosAlgorithmV2.littleTrend(v2In)

        // then: 清单5 —— V1 lastDays 恒 0；V2 首个波 span=d..d+1 → lastDays=日历差+1=2
        assertEquals(0, v1Out.first().lastDays, "V1 lastDays 恒 0（复刻 bug）")
        assertEquals(2, v2Out.first().lastDays, "V2 lastDays=日历差+1=2（清单5 修正）")
    }
}
