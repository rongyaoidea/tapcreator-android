package com.tapcreator.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.data.db.AgentSkillEntity
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.ChannelEntity
import com.tapcreator.app.data.db.ModelOptionEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页里的模型条目（附带其所属渠道名） */
data class ModelInfo(
    val model: ModelOptionEntity,
    val channelName: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val db: AppDatabase,
    private val channels: ChannelRepository,
    private val settings: SettingsStore,
    private val mcpManager: com.tapcreator.app.backend.mcp.MCPManager,
    private val sandbox: com.tapcreator.app.backend.sandbox.PRootSandbox,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** 已注册的 MCP 服务器列表 */
    val mcpServers = mutableStateOf<List<com.tapcreator.app.backend.mcp.MCPServer>>(emptyList())

    /** 沙箱状态 */
    var sandboxReady by mutableStateOf(false)
        private set
    var sandboxBusy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            agentMemoryEnabled = settings.agentMemoryEnabledValue()
            agentStyle = settings.agentStyleValue()
            mcpManager.init()
            mcpServers.value = mcpManager.listServers()
            sandboxReady = sandbox.isReady()
        }
    }

    /** 手动初始化沙箱（解压+启动） */
    fun initSandbox() {
        if (sandboxBusy) return
        sandboxBusy = true
        viewModelScope.launch {
            try {
                sandbox.ensureReady()
                sandboxReady = sandbox.isReady()
                toast(if (sandboxReady) "沙箱已就绪" else "沙箱启动失败")
            } catch (e: Exception) {
                toast("沙箱启动失败：${e.message}")
            } finally {
                sandboxBusy = false
            }
        }
    }

    /** 重置沙箱（删除 rootfs + 重新解压启动） */
    fun resetSandbox() {
        if (sandboxBusy) return
        sandboxBusy = true
        viewModelScope.launch {
            try {
                sandbox.reset()
                sandbox.ensureReady()
                sandboxReady = sandbox.isReady()
                toast(if (sandboxReady) "沙箱已重置并就绪" else "沙箱重置后启动失败")
            } catch (e: Exception) {
                toast("沙箱重置失败：${e.message}")
            } finally {
                sandboxBusy = false
            }
        }
    }

    fun removeMcpServer(name: String) {
        viewModelScope.launch {
            mcpManager.removeServer(name)
            mcpServers.value = mcpManager.listServers()
        }
    }

    fun toggleMcpEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpManager.setEnabled(name, enabled)
            mcpServers.value = mcpManager.listServers()
        }
    }

    fun installMcp(entry: com.tapcreator.app.backend.mcp.McpMarketEntry, apiKey: String = "", onDone: () -> Unit) {
        viewModelScope.launch {
            val env = if (entry.requiresApiKey && apiKey.isNotBlank()) {
                mapOf(entry.apiKeyEnvKey to apiKey)
            } else emptyMap()
            val server = com.tapcreator.app.backend.mcp.MCPServer(
                name = entry.name,
                type = entry.type,
                command = entry.command,
                args = entry.args,
                url = entry.url,
                env = env,
                enabled = true,
            )
            mcpManager.addServer(server)
            mcpServers.value = mcpManager.listServers()
            onDone()
        }
    }

    /** Agent 记忆系统开关（读写内部状态；UI 通过 toggleAgentMemory 调用，避免与属性 setter 的 JVM 签名冲突） */
    var agentMemoryEnabled by mutableStateOf(true)
        private set

    /** Agent 个人风格偏好（自然语言描述） */
    var agentStyle by mutableStateOf("")
        private set

    /** 深色模式偏好（自原「我的」页面迁移到设置页） */
    val darkTheme: StateFlow<Boolean> = settings.darkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(enabled) }
    }

    fun toggleAgentMemory(enabled: Boolean) {
        agentMemoryEnabled = enabled
        viewModelScope.launch { settings.setAgentMemoryEnabled(enabled) }
    }

    fun saveAgentStyle(value: String) {
        val clean = value.trim()
        agentStyle = clean
        viewModelScope.launch { settings.setAgentStyle(clean) }
    }

    val channelList: StateFlow<List<ChannelEntity>> =
        db.channelDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 按媒体类型分组的模型列表（文本/图像/视频/音频分开展示） */
    val modelsByKind: StateFlow<Map<MediaKind, List<ModelInfo>>> =
        combine(db.modelOptionDao().observeAllRaw(), channelList) { models, chs ->
            val nameById = chs.associate { it.id to it.name }
            models.groupBy(
                { it.kind },
                { ModelInfo(it, nameById[it.channelId] ?: "(渠道已删除)") }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    var busy by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    /** 上一条 feedback 是成功(true)还是失败(false)，用于 UI 配色 */
    var feedbackOk by mutableStateOf(true)
        private set

    private fun toast(msg: String) {
        Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun addChannel(
        kind: MediaKind,
        name: String,
        baseUrl: String,
        modelCatalog: String,
        apiKey: String,
        onDone: () -> Unit,
    ) {
        busy = true
        viewModelScope.launch {
            try {
                channels.add(
                    name = name,
                    baseUrl = baseUrl,
                    modelCatalog = modelCatalog,
                    apiKey = apiKey,
                    active = apiKey.isNotBlank(),
                    kind = kind,
                )
                feedback = "已保存供应商与模型"
                feedbackOk = true
                toast("已保存${labelOf(kind)}供应商与模型")
                onDone()
            } catch (e: Exception) {
                feedback = e.message ?: "保存失败"
                feedbackOk = false
                toast("保存失败：${e.message}")
            }
            busy = false
        }
    }

    fun saveSecret(channelId: String, apiKey: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                channels.saveSecret(channelId, apiKey)
                channels.byId(channelId)?.let { c ->
                    if (!c.active && apiKey.isNotBlank()) channels.update(c.copy(active = true))
                }
                feedback = "密钥已保存"
                feedbackOk = true
                toast(if (apiKey.isBlank()) "已清空 API Key" else "API Key 保存成功")
                onDone()
            } catch (e: Exception) {
                feedback = e.message ?: "保存密钥失败"
                feedbackOk = false
                toast("保存密钥失败：${e.message}")
            }
        }
    }

    /** 测试指定渠道的模型 API 是否连得通（校验 Base URL + Key） */
    fun testConnection(channelId: String) {
        if (testing) return
        testing = true
        viewModelScope.launch {
            try {
                val (ok, msg) = channels.testConnection(channelId)
                toast(if (ok) "$msg" else "测试失败：$msg")
                feedback = msg
                feedbackOk = ok
            } finally {
                testing = false
            }
        }
    }

    /** 拉取供应商真实模型列表：成功回调 onResult，失败弹 Toast */
    fun fetchModels(baseUrl: String, apiKey: String, onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val ids = channels.fetchModelList(baseUrl, apiKey)
                if (ids.isEmpty()) toast("拉取成功，但没有可用模型列表（该接口未开放 /models）")
                else toast("拉取成功，共 ${ids.size} 个模型")
                onResult(ids)
            } catch (e: Exception) {
                toast("拉取模型失败：${e.message}")
            }
        }
    }

    /** 查询知识库能为该模型自动匹配到的分辨率（用于配置页预览；未命中返回空字符串） */
    fun knownResolutions(modelId: String): String = channels.resolveResolutions(modelId)

    /** 自动发现并补全所有已配置模型的调研分辨率（幂等，启动/进入设置页时调用） */
    fun autoFillResolutions() {
        viewModelScope.launch {
            try {
                channels.autoFillAllResolutions()
            } catch (_: Exception) {
            }
        }
    }

    /** 用已保存渠道的 Base URL + Key 拉取真实模型列表 */
    fun listModelsForChannel(channelId: String, onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val ids = channels.fetchModelsForChannel(channelId)
                if (ids.isEmpty()) toast("拉取成功，但没有可用模型列表（该接口未开放 /models）")
                else toast("拉取成功，共 ${ids.size} 个模型")
                onResult(ids)
            } catch (e: Exception) {
                toast("拉取模型失败：${e.message}")
            }
        }
    }

    private fun labelOf(kind: MediaKind): String = when (kind) {
        MediaKind.TEXT -> "文本"
        MediaKind.IMAGE -> "生图"
        MediaKind.VIDEO -> "视频"
        MediaKind.AUDIO -> "音频"
        else -> "模型"
    }

    /** 编辑供应商配置：改名 / 改 Base URL / 改模型目录，保存后同步新模型 */
    fun updateChannel(
        channel: ChannelEntity,
        name: String,
        baseUrl: String,
        modelCatalog: String,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                channels.update(
                    channel.copy(
                        name = name.trim().ifBlank { channel.name },
                        baseUrl = baseUrl.trim().trimEnd('/'),
                        modelCatalog = modelCatalog.trim(),
                    )
                )
                toast("已保存修改")
                onDone()
            } catch (e: Exception) {
                toast("保存修改失败：${e.message}")
            }
        }
    }

    fun toggleActive(channel: ChannelEntity) {
        viewModelScope.launch { channels.update(channel.copy(active = !channel.active)) }
    }

    fun toggleModelEnabled(info: ModelInfo) {
        viewModelScope.launch { channels.setModelEnabled(info.model.id, !info.model.enabled) }
    }

    fun changeModelKind(info: ModelInfo, kind: MediaKind) {
        viewModelScope.launch {
            channels.setModelKind(info.model.id, kind)
            toast("已更新类型")
        }
    }

    fun deleteModel(info: ModelInfo) {
        viewModelScope.launch { channels.deleteModel(info.model.id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { channels.delete(id) }
    }

    fun consumeFeedback() {
        feedback = null
    }

    // ---------- Agent 自进化技能库（P3 学习区） ----------

    /** 待人工审批的技能（high 风险沉淀产生） */
    val pendingSkills: StateFlow<List<AgentSkillEntity>> =
        db.skillDao().observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前已生效的技能（全局，自动注入 Agent 上下文） */
    val activeSkills: StateFlow<List<AgentSkillEntity>> =
        db.skillDao().observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 高危技能的展示备注 */
    fun riskLabel(risk: String): String = when (risk) {
        "high" -> "高危"
        "low" -> "低危"
        else -> "低危"
    }

    /** 审批通过一条待审技能 → 生效 */
    fun approveSkill(id: String) {
        viewModelScope.launch {
            db.skillDao().setState(id, "active", System.currentTimeMillis())
            toast("技能已生效")
        }
    }

    /** 拒绝一条待审技能 → 直接删除 */
    fun rejectSkill(id: String) {
        viewModelScope.launch {
            db.skillDao().deleteById(id)
            toast("已拒绝该技能")
        }
    }

    /** 撤销一条已生效技能 → 退休 */
    fun revokeSkill(id: String) {
        viewModelScope.launch {
            db.skillDao().setState(id, "retired", System.currentTimeMillis())
            toast("技能已撤销")
        }
    }

    /** 一键清空整个学习库（撤销全部已学技能） */
    fun clearSkills() {
        viewModelScope.launch {
            db.skillDao().clearAll()
            toast("学习库已清空")
        }
    }

    /** 保存搜索 API 配置 */
    fun saveSearchApi(url: String, key: String) {
        viewModelScope.launch {
            settings.saveSearchApi(key, url)
            toast("搜索 API 已保存")
        }
    }
}