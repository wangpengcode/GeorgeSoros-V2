package com.soros.v2.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * §13.3 MdcSupport（纯函数单测，无 Spring）。
 *
 * 契约：在指定 MDC 上下文中执行 block，结束后恢复原 MDC（值传 null 表示移除该键）；
 * MDC 为线程局部，JUnit 同线程内天然成立。
 */
class MdcSupportTest {

    @Test
    fun `testWithMdc setsAndRestoresEntries`() {
        // given: 外部无任何 MDC
        MDC.clear()

        // when: 在 MDC 上下文内执行
        MdcSupport.withMdc(
            MdcSupport.JOB to "daily-collect",
            MdcSupport.CODE to "600000",
            MdcSupport.PHASE to "fetch",
        ) {
            // then: block 内可见
            assertEquals("daily-collect", MDC.get(MdcSupport.JOB), "block 内 job 键应可见")
            assertEquals("600000", MDC.get(MdcSupport.CODE), "block 内 code 键应可见")
            assertEquals("fetch", MDC.get(MdcSupport.PHASE), "block 内 phase 键应可见")
        }

        // then: block 结束后恢复原值（此处原值=null）
        assertNull(MDC.get(MdcSupport.JOB), "block 结束 job 键应移除")
        assertNull(MDC.get(MdcSupport.CODE), "block 结束 code 键应移除")
    }

    @Test
    fun `testWithMdc restoresPreexistingValue`() {
        // given: 外部已有 code=x，需在 block 后恢复
        MDC.clear()
        MDC.put(MdcSupport.CODE, "000001")

        // when
        MdcSupport.withMdc(MdcSupport.CODE to "600000") {
            assertEquals("600000", MDC.get(MdcSupport.CODE), "block 内覆盖为 600000")
        }

        // then: 原值恢复
        assertEquals("000001", MDC.get(MdcSupport.CODE), "block 结束恢复原 MDC 值 000001")
        MDC.clear()
    }

    @Test
    fun `testWithMdc nullValueRemovesKey`() {
        // given: 已有键，传 null 表示移除
        MDC.clear()
        MDC.put(MdcSupport.PHASE, "fetch")

        // when
        MdcSupport.withMdc(MdcSupport.PHASE to null) {
            assertNull(MDC.get(MdcSupport.PHASE), "值传 null 应移除该键（block 内不可见）")
        }

        // then: 原值恢复
        assertEquals("fetch", MDC.get(MdcSupport.PHASE), "移除后 block 结束恢复原值 fetch")
        MDC.clear()
    }

    @Test
    fun `testWithMdc restoresEvenOnException`() {
        // given: 异常路径——block 抛异常也必须恢复 MDC（finally 保证）
        MDC.clear()
        MDC.put(MdcSupport.ATTEMPT, "1")

        // when & then: 异常透传，但 MDC 恢复
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            MdcSupport.withMdc(MdcSupport.ATTEMPT to "2") {
                assertEquals("2", MDC.get(MdcSupport.ATTEMPT), "block 内 attempt=2")
                throw IllegalStateException("boom")
            }
        }
        assertEquals("1", MDC.get(MdcSupport.ATTEMPT), "block 抛异常后仍恢复原 attempt=1")
        MDC.clear()
    }

    @Test
    fun `testWithMdc nestedContextIndependent`() {
        // given: 嵌套 withMdc——内层覆盖、外层恢复
        MDC.clear()

        // when
        MdcSupport.withMdc(MdcSupport.JOB to "outer") {
            MdcSupport.withMdc(MdcSupport.JOB to "inner") {
                assertEquals("inner", MDC.get(MdcSupport.JOB), "内层覆盖")
            }
            assertEquals("outer", MDC.get(MdcSupport.JOB), "内层结束恢复外层值")
        }

        // then
        assertNull(MDC.get(MdcSupport.JOB), "最外层结束全部移除")
    }
}
