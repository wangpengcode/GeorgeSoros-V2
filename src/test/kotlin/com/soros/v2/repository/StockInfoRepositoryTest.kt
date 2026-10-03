package com.soros.v2.repository

import com.soros.v2.entity.StockInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * stock_info Repository 集成测试。
 *
 * 口径（PLAN §17.6 / V1 注释）：
 * - board CHECK(MAIN/GEM/STAR)；is_st 仅用于"识别并排除"（ST 全链路隔离铁律，禁止作为业务可选项）；
 * - delisted 缺失≠退市：人工确认才置 true（默认 false）；
 * - industry / concept_boards 为 JSON 数组（一股可属多行业，主行业=第一个，展示用），经 StringListJsonConverter 存取；
 * - search_key=小写 name+全拼+拼音首字母（PLAN 例：平潭发展 pingtanfazhan ptfz），
 *   GET /stock-search 入参 代码/名称/全拼/拼音首字母 均走 search_key + pg_trgm 模糊检索。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockInfoRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    private lateinit var repository: StockInfoRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    /** 构造一只正常股票（非 ST、未退市；search_key 含 code+小写全拼+拼音首字母，对齐 /stock-search 入参契约） */
    private fun buildNormalStock(
        code: String = "600000",
        name: String = "浦发银行",
        board: String = "MAIN",
        searchKey: String = "600000 浦发银行 pufayinhang pfyh",
        industry: List<String> = listOf("半导体", "AI算力"),
        conceptBoards: List<String> = listOf("人工智能", "算力租赁"),
    ) = StockInfo().apply {
        this.code = code
        this.name = name
        this.market = "SH"
        this.board = board
        this.isSt = false
        this.delisted = false
        this.ipoDate = LocalDate.of(1999, 11, 10)
        this.industry = industry
        this.conceptBoards = conceptBoards
        this.searchKey = searchKey
    }

    /** 构造一只 ST 股（ST 全链路隔离：只用于"识别并排除"） */
    private fun buildStStock(
        code: String,
        name: String,
        board: String,
        searchKey: String,
    ) = StockInfo().apply {
        this.code = code
        this.name = name
        this.market = if (board == "GEM") "SZ" else "SH"
        this.board = board
        this.isSt = true
        this.delisted = false
        this.searchKey = searchKey
    }

    // ==================== 正常流程 ====================

    @Test
    fun `testSaveWithIndustryList roundTrip via JsonConverter`() {
        // given: industry/concept_boards 为 List<String>，经 StringListJsonConverter 写 TEXT(JSON)
        repository.saveAndFlush(buildNormalStock())
        entityManager.clear()

        // when: 按 code 回读（code UNIQUE 至多 1 行）
        val found = repository.findByCode("600000")
            ?: fail("命中行不应为 null")

        // then: JSON 数组回读为 List，元素与顺序完整（防 converter 序列化/反序列化漂移）
        assertEquals(listOf("半导体", "AI算力"), found.industry, "industry JSON 数组回读相等（主行业=第一个，展示用）")
        assertEquals(listOf("人工智能", "算力租赁"), found.conceptBoards, "concept_boards JSON 数组回读相等")
        assertNotNull(found.id, "SERIAL 主键已回填")
    }

    @Test
    fun `testFindByIsStTrue returnsOnlyStRows`() {
        // given: 2 只 ST + 1 只非 ST（ST 全链路隔离铁律的消费入口）
        repository.saveAll(
            listOf(
                buildStStock("000587", "*ST金洲", "MAIN", "000587 stjinzhou stjz"),
                buildStStock("300023", "ST宝德", "GEM", "300023 stbaode stbd"),
                buildNormalStock(), // 600000 浦发银行，非 ST
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: ST 名单查询（采集/派生侧用它识别并排除）
        val stList = repository.findByIsStTrue()

        // then: 只回 is_st=true 的行，非 ST 不得混入（ST 隔离铁律）
        assertEquals(2, stList.size, "findByIsStTrue 只回 is_st=true 的行")
        assertTrue(stList.all { it.isSt }, "返回全为 ST")
        assertFalse(stList.any { it.code == "600000" }, "非 ST 浦发银行不得混入 ST 名单")
        assertTrue(
            stList.map { it.code }.toSet().containsAll(setOf("000587", "300023")),
            "两只 ST（000587/300023）都应命中",
        )
    }

    @Test
    fun `testFindBySearchKeyContainingIgnoreCase fullPinyinHit`() {
        // given: search_key=小写 name+全拼+拼音首字母
        repository.saveAndFlush(buildNormalStock())
        entityManager.clear()

        // when: 小写全拼命中（pg_trgm 模糊检索）
        val byPinyin = repository.findBySearchKeyContainingIgnoreCase("pufayinhang")
        // then
        assertEquals(1, byPinyin.size, "小写全拼 pufayinhang 应命中")
        assertEquals("600000", byPinyin[0].code, "命中浦发银行")

        // when: 大写全拼（IgnoreCase 不区分大小写）
        val byUpper = repository.findBySearchKeyContainingIgnoreCase("PUFAYINHANG")
        assertEquals(1, byUpper.size, "IgnoreCase 大写全拼应同样命中")
    }

    @Test
    fun `testFindBySearchKeyContainingIgnoreCase codeFragmentHit`() {
        // given
        repository.saveAndFlush(buildNormalStock())
        entityManager.clear()

        // when: 代码片段命中（/stock-search 入参含代码，如 6014→命中）
        val byCode = repository.findBySearchKeyContainingIgnoreCase("6000")

        // then
        assertEquals(1, byCode.size, "代码片段 6000 应命中（search_key 含 code，对齐入参契约）")
        assertEquals("600000", byCode[0].code, "命中浦发银行")
    }

    @Test
    fun `testFindBySearchKeyContainingIgnoreCase initialsHit`() {
        // given
        repository.saveAndFlush(buildNormalStock())
        entityManager.clear()

        // when: 拼音首字母命中（PLAN 例：pt → 平潭发展）
        val byInitials = repository.findBySearchKeyContainingIgnoreCase("pfyh")

        // then
        assertEquals(1, byInitials.size, "拼音首字母 pfyh 应命中")
        assertEquals("600000", byInitials[0].code, "命中浦发银行")
    }

    // ==================== 异常路径 ====================

    @Test
    fun `testFindBySearchKeyContainingIgnoreCase noMatch returnsEmpty`() {
        // given
        repository.saveAndFlush(buildNormalStock())
        entityManager.clear()

        // when: 无匹配关键词
        val empty = repository.findBySearchKeyContainingIgnoreCase("zzzzzzz")

        // then: 模糊搜索无命中返回空列表（非 null、非异常）
        assertTrue(empty.isEmpty(), "无匹配应返回空列表")
    }

    // ==================== 边界条件 ====================

    @Test
    fun `testFindByDelisted defaultFalse`() {
        // given: 1 只退市（人工确认置 true）+ 2 只未退市（delisted 默认 false）
        val delisted = buildNormalStock(
            code = "600005",
            name = "武钢股份",
            searchKey = "600005 武钢股份 wuganggu fen wggf",
        )
        delisted.delisted = true
        repository.saveAll(
            listOf(
                delisted,
                buildNormalStock(),
                buildNormalStock(
                    code = "600036",
                    name = "招商银行",
                    searchKey = "600036 招商银行 zhaoshangyinhang zsyh",
                ),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        // when: 按退市标记查
        val active = repository.findByDelisted(false)
        val delistedRows = repository.findByDelisted(true)

        // then: 默认 false 不混入退市行（缺失≠退市：人工确认才置 true）
        assertEquals(2, active.size, "findByDelisted(false) 应回 2 只未退市")
        assertTrue(active.all { !it.delisted }, "未退市列表不含 delisted=true 行")
        assertEquals(1, delistedRows.size, "findByDelisted(true) 应回 1 只退市")
        assertEquals("600005", delistedRows[0].code, "退市行=人工确认的 600005")
    }
}
