package com.soros.v2.service.strategy

import com.soros.v2.entity.StrategyConfig
import com.soros.v2.entity.StrategyConfigHistory
import com.soros.v2.exception.BusinessException
import com.soros.v2.repository.StrategyConfigHistoryRepository
import com.soros.v2.repository.StrategyConfigRepository
import com.soros.v2.service.strategy.dto.StrategyCreateRequest
import com.soros.v2.service.strategy.dto.StrategyUpdateRequest
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * §19.13.3 StrategyServiceImpl 版本递增/history 链/回滚契约测试（mock repo，无 TestContainers）。
 *
 * 契约（§19.13.3 a 期 CRUD + G3 定稿）：
 * - create：结构化 JSON → 服务端生成 YAML（无自由 YAML 文本，§12.9 决策 1），DRAFT 态 version=1，
 *   落 strategy_config + strategy_config_history（version=1 快照）；name 唯一冲突 → BusinessException；
 * - update：version+1，新 yaml 落 strategy_config + strategy_config_history（新版本快照），历史链不断；
 * - rollback（G3）：目标版本 yaml 复制为新版本（当前 version+1）写回 strategy_config，
 *   同时落一条 strategy_config_history 指向旧内容（目标版本 yaml，新版本号快照），历史链可无限回退；
 *   目标版本不存在 → BusinessException 含"不存在"（→404）；
 * - history：版本列表升序（findByConfigIdOrderByVersionAsc）；
 * - getYaml：当前 strategy_config.yaml 全文。
 *
 * ⚠️ 空壳（TDD 红阶段）：StrategyServiceImpl / StrategyConfig / StrategyConfigHistory / 两个 repo 尚未创建，
 * 本文件编译失败即预期红；Implementer 按本契约创建后可编译并应全绿。
 *
 * 签名契约（Implementer 创建时对齐）：
 * - StrategyServiceImpl(configRepository: StrategyConfigRepository, historyRepository: StrategyConfigHistoryRepository)
 * - StrategyConfigRepository: findById(id): Optional<StrategyConfig>、findByName(name): StrategyConfig?、
 *   existsByName(name): Boolean、save(entity): StrategyConfig、findAll()
 * - StrategyConfigHistoryRepository: save(entity): StrategyConfigHistory、
 *   findByConfigIdAndVersion(configId: Long, version: Int): StrategyConfigHistory?、
 *   findByConfigIdOrderByVersionAsc(configId: Long): List<StrategyConfigHistory>
 * - DTO：StrategyCreateRequest(name, conditions, alertEnabled=false, note?=null)；
 *   StrategyUpdateRequest(conditions, alertEnabled=false, note?=null)；StrategyDetailDto(id,name,yaml,version,status,alertEnabled,note?)
 */
class StrategyServiceTest {

    private lateinit var configRepository: StrategyConfigRepository
    private lateinit var historyRepository: StrategyConfigHistoryRepository
    private lateinit var service: StrategyServiceImpl

    @BeforeEach
    fun setUp() {
        configRepository = Mockito.mock(StrategyConfigRepository::class.java)
        historyRepository = Mockito.mock(StrategyConfigHistoryRepository::class.java)
        service = StrategyServiceImpl(configRepository, historyRepository)
    }

    // ==================== 测试数据构造 ====================

    private fun config(
        id: Long = 1L,
        name: String = "打龙头回调",
        yaml: String = "strategy: da-long-hui-tou\nsignals:\n  buy:\n    - source: limit_up_streak",
        version: Int = 3,
        status: String = "DRAFT",
        alertEnabled: Boolean = false,
        note: String? = "打龙头回调策略",
    ) = StrategyConfig().apply {
        this.id = id
        this.name = name
        this.yaml = yaml
        this.version = version
        this.status = status
        this.alertEnabled = alertEnabled
        this.note = note
    }

    private fun historyRow(
        configId: Long = 1L,
        yaml: String = "yaml-v1",
        version: Int = 1,
    ) = StrategyConfigHistory().apply {
        this.configId = configId
        this.yaml = yaml
        this.version = version
    }

    private fun createReq() = StrategyCreateRequest(
        name = "打龙头回调",
        conditions = listOf(
            com.soros.v2.service.strategy.dto.ConditionInput(
                condId = "buy_0",
                side = "BUY",
                source = "limit_up_streak",
                op = "between",
                value = listOf(3, 7),
            ),
        ),
        alertEnabled = false,
        note = "打龙头回调策略",
    )

    private fun updateReq() = StrategyUpdateRequest(
        conditions = listOf(
            com.soros.v2.service.strategy.dto.ConditionInput(
                condId = "buy_0",
                side = "BUY",
                source = "limit_up_streak",
                op = "between",
                value = listOf(2, 6),
            ),
        ),
        alertEnabled = true,
        note = "调整板数区间",
    )

    // ==================== 正常流程：create ====================

    @Test
    fun `testCreate successVersion1DraftAndHistorySnapshot`() {
        // given: name 唯一 + save 返回传入实体并模拟 DB 生成 id=1（版本由服务层设置）
        Mockito.`when`(configRepository.existsByName("打龙头回调")).thenReturn(false)
        Mockito.`when`(configRepository.save(Mockito.any(StrategyConfig::class.java)))
            .thenAnswer { inv ->
                (inv.getArgument(0) as StrategyConfig).also { it.id = 1L }
            }
        Mockito.`when`(historyRepository.save(Mockito.any(StrategyConfigHistory::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        val result = service.create(createReq())

        // then: DRAFT 态 version=1，yaml 由服务端生成（结构化 JSON → YAML，含条件 source）
        assertEquals(1L, result.id, "id 回传")
        assertEquals("打龙头回调", result.name, "name 回传")
        assertEquals(1, result.version, "新建 version=1")
        assertEquals("DRAFT", result.status, "新建 DRAFT 态（§12.9 决策 1 无自由 YAML 文本）")
        assertNotNull(result.yaml, "yaml 由服务端生成，非 null")
        assertTrue(result.yaml!!.contains("limit_up_streak"), "yaml 含条件信号源（生成口径）")

        // then: 每次变更落 strategy_config_history（version=1 快照，历史链起点）
        val historyCaptor = ArgumentCaptor.forClass(StrategyConfigHistory::class.java)
        Mockito.verify(historyRepository).save(historyCaptor.capture())
        assertEquals(1, historyCaptor.value.version, "create 落 history version=1")
        assertEquals(1L, historyCaptor.value.configId, "history 指向 config_id")
        assertNotNull(historyCaptor.value.yaml, "history yaml 快照非 null")
    }

    @Test
    fun `testCreate duplicateNameRejectedBusinessException`() {
        // given: name 已存在（strategy_config.name UNIQUE）
        Mockito.`when`(configRepository.existsByName("打龙头回调")).thenReturn(true)

        // when & then: 重复名 → BusinessException（控制器侧 → 422/409 信封由实现定，服务层不落库）
        val ex = assertThrows(BusinessException::class.java) {
            service.create(createReq())
        }
        assertTrue(ex.message!!.contains("打龙头回调"), "消息指明冲突策略名")
        Mockito.verify(configRepository, Mockito.never()).save(Mockito.any(StrategyConfig::class.java))
    }

    // ==================== 正常流程：update（version+1 + history 链） ====================

    @Test
    fun `testUpdate successVersionIncrementedAndHistoryAppended`() {
        // given: 当前 v3（yaml-v3），save 返回传入实体
        val current = config(yaml = "yaml-v3", version = 3)
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(current))
        Mockito.`when`(configRepository.save(Mockito.any(StrategyConfig::class.java)))
            .thenAnswer { it.getArgument(0) }
        Mockito.`when`(historyRepository.save(Mockito.any(StrategyConfigHistory::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        val result = service.update(1L, updateReq())

        // then: version 3 → 4，yaml 重新生成，alert_enabled/note 更新
        assertEquals(4, result.version, "update 后 version+1（v3 → v4）")
        assertEquals(true, result.alertEnabled, "alert_enabled 更新透传")
        assertNotNull(result.yaml, "yaml 重新生成")

        // then: 落 strategy_config_history 新版本快照（v4），历史链不断
        val historyCaptor = ArgumentCaptor.forClass(StrategyConfigHistory::class.java)
        Mockito.verify(historyRepository).save(historyCaptor.capture())
        assertEquals(4, historyCaptor.value.version, "update 落 history version=4（新版本快照）")
    }

    @Test
    fun `testUpdate notFoundThrowsBusinessException404`() {
        // given: id 不存在
        Mockito.`when`(configRepository.findById(999L)).thenReturn(Optional.empty())

        // when & then: BusinessException 含"不存在"（→404）
        val ex = assertThrows(BusinessException::class.java) {
            service.update(999L, updateReq())
        }
        assertTrue(ex.message!!.contains("不存在"), "id 不存在 → 消息含「不存在」")
    }

    // ==================== 正常流程：rollback（G3） ====================

    @Test
    fun `testRollback successCopiesTargetVersionYamlAsNewVersionAndHistory`() {
        // given: 当前 v3（yaml-v3）；history 有 v1 快照（yaml-v1）→ 回滚到 v1
        val current = config(yaml = "yaml-v3", version = 3)
        val target = historyRow(configId = 1L, yaml = "yaml-v1", version = 1)
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(current))
        Mockito.`when`(historyRepository.findByConfigIdAndVersion(1L, 1)).thenReturn(target)
        Mockito.`when`(configRepository.save(Mockito.any(StrategyConfig::class.java)))
            .thenAnswer { it.getArgument(0) }
        Mockito.`when`(historyRepository.save(Mockito.any(StrategyConfigHistory::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when: 回滚到 v1
        val result = service.rollback(1L, 1)

        // then: 目标版本 yaml 复制为新版本（当前 v3 → 新 v4）写回 strategy_config
        assertEquals(4, result.version, "回滚后 version=当前+1（v3 → v4）")
        assertEquals("yaml-v1", result.yaml, "目标版本 yaml 复制为新版本内容")

        // then: 落一条 strategy_config_history 指向旧内容（目标版本 yaml，新版本号快照），历史链不断可无限回退
        val historyCaptor = ArgumentCaptor.forClass(StrategyConfigHistory::class.java)
        Mockito.verify(historyRepository).save(historyCaptor.capture())
        assertEquals(4, historyCaptor.value.version, "回滚落 history version=4")
        assertEquals("yaml-v1", historyCaptor.value.yaml, "history 指向旧内容（目标版本 yaml）")
    }

    @Test
    fun `testRollback unknownVersionThrowsBusinessException404`() {
        // given: 当前 v3，目标版本 99 不存在
        val current = config(version = 3)
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(current))
        Mockito.`when`(historyRepository.findByConfigIdAndVersion(1L, 99)).thenReturn(null)

        // when & then: BusinessException 含"不存在"（→404）
        val ex = assertThrows(BusinessException::class.java) {
            service.rollback(1L, 99)
        }
        assertTrue(ex.message!!.contains("不存在"), "目标版本不存在 → 消息含「不存在」")
        Mockito.verify(configRepository, Mockito.never()).save(Mockito.any(StrategyConfig::class.java))
    }

    @Test
    fun `testRollback strategyNotFoundThrowsBusinessException404`() {
        // given: 策略 id 不存在
        Mockito.`when`(configRepository.findById(999L)).thenReturn(Optional.empty())

        // when & then: BusinessException 含"不存在"（→404）
        val ex = assertThrows(BusinessException::class.java) {
            service.rollback(999L, 1)
        }
        assertTrue(ex.message!!.contains("不存在"), "策略不存在 → 消息含「不存在」")
    }

    // ==================== 正常流程：history / yaml 导出 ====================

    @Test
    fun `testHistory successReturnsVersionChainAscending`() {
        // given: 历史链 v1→v2→v3（升序，findByConfigIdOrderByVersionAsc）
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(config(version = 3)))
        Mockito.`when`(historyRepository.findByConfigIdOrderByVersionAsc(1L)).thenReturn(
            listOf(historyRow(version = 1), historyRow(yaml = "yaml-v2", version = 2), historyRow(yaml = "yaml-v3", version = 3)),
        )

        // when
        val items = service.history(1L)

        // then: 版本列表升序（页面 diff 可回退，G3）
        assertEquals(listOf(1, 2, 3), items.map { it.version }, "history 版本列表升序")
        Mockito.verify(configRepository).findById(1L)
        Mockito.verify(historyRepository).findByConfigIdOrderByVersionAsc(1L)
    }

    @Test
    fun `testGetYaml successReturnsCurrentYaml`() {
        // given: 当前配置 v3
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(config(yaml = "yaml-v3", version = 3)))

        // when
        val yamlDto = service.getYaml(1L)

        // then: 当前 strategy_config.yaml 全文导出
        assertEquals("yaml-v3", yamlDto.yaml, "yaml 导出当前版本全文")
    }

    // ==================== 下游契约测试（DTO 传播完整性，回归防护） ====================

    @Test
    fun `testRollback downstreamContractDetailFieldsNonNull`() {
        // given: 全字段源数据（回滚 v3 → v4）
        val current = config(yaml = "yaml-v3", version = 3)
        val target = historyRow(configId = 1L, yaml = "yaml-v1", version = 1)
        Mockito.`when`(configRepository.findById(1L)).thenReturn(Optional.of(current))
        Mockito.`when`(historyRepository.findByConfigIdAndVersion(1L, 1)).thenReturn(target)
        Mockito.`when`(configRepository.save(Mockito.any(StrategyConfig::class.java)))
            .thenAnswer { it.getArgument(0) }
        Mockito.`when`(historyRepository.save(Mockito.any(StrategyConfigHistory::class.java)))
            .thenAnswer { it.getArgument(0) }

        // when
        val result = service.rollback(1L, 1)

        // then: 下游消费者（StrategyController → 页面）必需的字段全部非 null——
        // 断言集合=下游必需字段集合（id/name/yaml/version/status 必填；note 语义可空）
        assertNotNull(result.id, "id 为 null 会导致策略页无法定位")
        assertNotNull(result.name, "name 为 null 会导致列表/标题空白")
        assertNotNull(result.yaml, "yaml 为 null 会导致只读预览空白")
        assertNotNull(result.version, "version 为 null 会导致版本号缺失")
        assertNotNull(result.status, "status 为 null 会导致状态徽标缺失")
    }
}
