package com.tapcreator.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.tapcreator.app.backend.agent.AgentBrain
import com.tapcreator.app.backend.media.MediaStore
import com.tapcreator.app.backend.model.ModelRouter
import com.tapcreator.app.backend.service.RunService
import com.tapcreator.app.data.db.AgentRunEntity
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.CardLinkEntity
import com.tapcreator.app.data.db.ConversationStateEntity
import com.tapcreator.app.data.db.MessageEntity
import com.tapcreator.app.data.db.ModelOptionEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.GenerationPreferences
import com.tapcreator.app.data.model.RunRequest
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.data.model.ThinkingLevel
import com.tapcreator.app.data.prefs.SettingsStore
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 自主 Agent、手动创作合并为同一工作界面；Agent 以浮动按钮呼出参与工作 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val router: ModelRouter,
    private val runService: RunService,
    private val brain: AgentBrain,
    private val media: MediaStore,
    @ApplicationContext private val appContext: Context,
    savedState: SavedStateHandle,
) : ViewModel() {

    val conversationId: String = checkNotNull(savedState["conversationId"])

    private var token: String? = null

    init {
        viewModelScope.launch {
            conversationTitle = runCatching {
                db.conversationDao().byId(conversationId)?.title?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: conversationId
            token = settings.token()
            val models = try {
                router.models(MediaKind.IMAGE)
            } catch (e: Exception) {
                emptyList()
            }
            modelOptions = models
            selectedModelId = firstAvailableModel(models)
            selectedKind = MediaKind.IMAGE
            // 同时加载 Agent 可用的文本模型（规划大脑 & 对话模型）
            val textModels = try {
                router.models(MediaKind.TEXT)
            } catch (e: Exception) {
                emptyList()
            }
            agentTextModels = textModels
            selectedAgentModelId = firstAvailableModel(textModels)
            // 空白创作卡（runId='draft'）随 cards 流持久显示在画布上，返回页面不丢失；
            // 点卡才打开提交面板，故无需在 init 里恢复 draftKind。仅恢复会话级编辑现场。
            restoreState()
        }
    }

    var modelOptions by mutableStateOf<List<ModelOptionEntity>>(emptyList())
        private set

    var selectedKind by mutableStateOf(MediaKind.IMAGE)
        private set

    var selectedModelId by mutableStateOf<String?>(null)
        private set

    /** Agent 可选的文本模型（规划大脑 & 对话模型） */
    var agentTextModels by mutableStateOf<List<ModelOptionEntity>>(emptyList())
        private set

    /** Agent 当前选中的文本模型 id */
    var selectedAgentModelId by mutableStateOf<String?>(null)
        private set

    /** Agent 可选的已启用文本模型 */
    val visibleAgentTextModels: List<ModelOptionEntity>
        get() = agentTextModels.filter { it.enabled }

    var count by mutableStateOf(1)
        private set
    var ratio by mutableStateOf("1:1")
        private set
    var quality by mutableStateOf("medium")
        private set
    var videoSeconds by mutableStateOf(5)
        private set

    /** 当前模型可选的分辨率：视频 "768P/2K/4K"；图片 "WxH"。
     *  仅当模型明确声明了 resolutions 时才返回声明列表；
     *  未声明时返回空（不预设任何档位，避免把不支持的分辨率发给上游）。 */
    val selectedModelResolutions: List<String>
        get() {
            selectedModel()?.let { m ->
                if (m.resolutions.isNotBlank()) {
                    val list = m.resolutions.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isNotEmpty()) return list
                }
            }
            return emptyList()
        }

    var resolution by mutableStateOf<String?>(null)
        private set

    /** 是否生成音频（音画同步）——仅当模型具备 audio 能力时 UI 展示 */
    var audioEnabled by mutableStateOf(true)
        private set

    var input by mutableStateOf("")
        private set

    /** 是否在提交创作前用默认文本模型优化提示词（默认关闭） */
    var promptOptimize by mutableStateOf(false)
        private set

    fun togglePromptOptimize() {
        promptOptimize = !promptOptimize
        markStateChanged()
    }

    /** 会话昵称（可手动编辑）。初始取库中标题，未命名时回退会话 id */
    var conversationTitle by mutableStateOf(conversationId)
        private set

    /** 重命名当前会话 */
    fun renameConversation(title: String) {
        val clean = title.trim()
        viewModelScope.launch {
            db.conversationDao().rename(conversationId, clean.ifBlank { conversationTitle }, System.currentTimeMillis())
            conversationTitle = clean.ifBlank { conversationTitle }
        }
    }

    /** Agent 对话输入：与手动输入隔离，避免互相覆盖/误清空 */
    var agentInput by mutableStateOf("")
        private set

    /** Agent 是否在规划/执行中 */
    var agentBusy by mutableStateOf(false)
        private set

    /** Agent 执行进度 (已执行步数, 总步数)；null 表示未在执行 */
    var agentProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** Agent 执行对话（按时间排序的单一流）：think=大脑实时输出，assistant=Agent 动作/思考，user=Agent 观察/反馈 */
    var agentStream by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** 当前正在编辑（在提交面板中填写内容）的那张空白卡的类型 */
    var draftKind by mutableStateOf<MediaKind?>(null)
        private set

    /** 当前正在编辑的空白卡 id；每次打开提交面板时指向对应那一张卡 */
    var editingDraftId by mutableStateOf<String?>(null)
        private set

    /**
     * 点「生图/生视频」：每次新建一张独立的空白媒体卡（runId='draft' 落在画布上）。
     * 多张空白卡互不覆盖（id 唯一、坐标错位），可同时添加任意数量的图卡/视频卡。
     */
    fun startDraft(kind: MediaKind) {
        val id = "draft_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        viewModelScope.launch {
            val count = runCatching { db.cardDao().listByConversation(conversationId) }
                .getOrNull().orEmpty().size
            // 把新空白卡错位铺开，避免与已有卡重叠
            val col = 6
            val x = 24f + (count % col) * 170f
            val y = 24f + (count / col) * 170f
            db.cardDao().saveDraft(
                CardEntity(
                    id = id,
                    runId = "draft",
                    conversationId = conversationId,
                    sequence = 0,
                    kind = kind,
                    title = "",
                    x = x,
                    y = y,
                    status = com.tapcreator.app.data.model.RunStatus.DRAFT,
                )
            )
        }
    }

    /** 点某张空白卡：打开提交面板开始编辑它（写入该卡的 prompt/参数并提交） */
    fun openDraftEditor(cardId: String, kind: MediaKind) {
        draftKind = kind
        editingDraftId = cardId
        setKind(kind)
    }

    /** 关闭提交面板=放弃这次创作，只删当前这一张空白卡（不误删其它空白卡） */
    fun clearDraft() {
        val id = editingDraftId
        draftKind = null
        editingDraftId = null
        if (id == null) return
        viewModelScope.launch {
            runCatching {
                db.cardLinkDao().deleteForCard(id)
                db.cardDao().hardDelete(id)
            }
        }
    }

    // ---------- 会话级 UI 状态持久化（创作编辑现场 + Agent 现场 + 模型偏好） ----------
    /** 要随会话保存的编辑现场；返回会话时恢复，避免「填了一半就丢」 */
    @Serializable
    private data class ConversationEditState(
        val prompt: String = "",
        val count: Int = 1,
        val ratio: String = "1:1",
        val quality: String = "medium",
        val videoSeconds: Int = 5,
        val audioEnabled: Boolean = true,
        val resolution: String? = null,
        val referenceCardIds: List<String> = emptyList(),
        val referenceAssetPaths: List<String> = emptyList(),
        val agentInput: String = "",
        val agentStream: List<AgentLine> = emptyList(),
        val modelId: String? = null,
        val agentModelId: String? = null,
        val cinematic: Boolean = true,
        val promptOptimize: Boolean = false,
        val reasoning: ThinkingLevel = ThinkingLevel.AUTO,
    )

    @Serializable
    private data class AgentLine(val role: String, val text: String)

    private val stateJson = Json { ignoreUnknownKeys = true }
    private var stateFlush: Job? = null
    // 串行化会话状态写入，避免 debounce 与 onCleared 并发出乱序
    private val stateMutex = Mutex()
    // 独立于 viewModelScope 的清理协程作用域：onCleared 里 viewModelScope 已被取消，用它在后台落库而不阻塞主线程
    private val cleanupOwner = SupervisorJob()
    private val cleanupScope = CoroutineScope(cleanupOwner + Dispatchers.IO)

    /** 任意一次编辑变更后居中调用：去重写入会话状态行（debounce，避免连打字符频繁写库） */
    private fun markStateChanged() {
        stateFlush?.cancel()
        stateFlush = viewModelScope.launch {
            delay(400)
            val s = snapshotState()
            stateMutex.withLock { writeState(s) }
        }
    }

    /** 在主线程取快照（Compose 状态只能在 UI 上下文安全读取），返回一个纯数据对象 */
    private fun snapshotState(): ConversationEditState = ConversationEditState(
        prompt = input,
        count = count,
        ratio = ratio,
        quality = quality,
        videoSeconds = videoSeconds,
        audioEnabled = audioEnabled,
        resolution = resolution,
        referenceCardIds = selectedReferenceCards.map { it.id },
        referenceAssetPaths = selectedReferenceAssets.mapNotNull { it.mediaPath },
        agentInput = agentInput,
        agentStream = agentStream.map { AgentLine(it.first, it.second) },
        modelId = selectedModelId,
        agentModelId = selectedAgentModelId,
        cinematic = cinematicEnabled,
        promptOptimize = promptOptimize,
        reasoning = thinkingLevel,
    )

    private suspend fun writeState(s: ConversationEditState) {
        runCatching {
            db.conversationStateDao().upsert(
                ConversationStateEntity(
                    id = conversationId,
                    payloadJson = stateJson.encodeToString(ConversationEditState.serializer(), s),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun restoreState() {
        val s = runCatching {
            db.conversationStateDao().byId(conversationId)?.let { row ->
                stateJson.decodeFromString<ConversationEditState>(row.payloadJson)
            }
        }.getOrNull() ?: return
        input = s.prompt
        count = s.count.coerceIn(1, 6)
        ratio = s.ratio
        quality = s.quality
        videoSeconds = s.videoSeconds.coerceIn(4, 30)
        audioEnabled = s.audioEnabled
        resolution = s.resolution
        agentInput = s.agentInput
        agentStream = s.agentStream.map { it.role to it.text }
        s.referenceCardIds.forEach { id -> runCatching { db.cardDao().byId(id) }.getOrNull()?.let { selectedReferenceCards += it } }
        s.referenceAssetPaths.forEach { p -> runCatching { db.assetDao().byMediaPath(p) }.getOrNull()?.firstOrNull()?.let { selectedReferenceAssets += it } }
        // 模型偏好：仅当该 id 仍属于当前 kind 的可选模型时才恢复，否则保持默认
        if (s.modelId != null && visibleModels.any { it.id == s.modelId }) selectedModelId = s.modelId
        // Agent 文本模型偏好
        if (s.agentModelId != null && agentTextModels.any { it.id == s.agentModelId }) selectedAgentModelId = s.agentModelId
        // 镜头分镜优化开关
        cinematicEnabled = s.cinematic
        // 创作提示词优化开关
        promptOptimize = s.promptOptimize
        // 推理深度级别
        thinkingLevel = s.reasoning
    }

    override fun onCleared() {
        // ViewModel 销毁（返回退出会话）前确保最后一次编辑现场落库。
        // 在主线程即时取快照，再由独立后台 scope + Mutex 串行写入，避免阻塞主线程(ANR)与乱序。
        stateFlush?.cancel()
        val s = snapshotState()
        cleanupScope.launch {
            withTimeout(1500) { stateMutex.withLock { writeState(s) } }
            cleanupOwner.cancel() // 落库完成后收掉后台作用域，避免随 ViewModel 生命周期潜隐不灭
        }
        super.onCleared()
    }

    /** MadStory 镜头分镜提示词优化开关 */
    var cinematicEnabled by mutableStateOf(true)

    /** 推理深度级别：默认 AUTO（自动，不发 reasoning_effort），可选 LOW/MEDIUM/HIGH */
    var thinkingLevel by mutableStateOf(ThinkingLevel.AUTO)

    private var agentJob: Job? = null

    val visibleModels: List<ModelOptionEntity>
        get() = modelOptions.filter { it.kind == selectedKind && it.enabled }

    /** 当前模型是否具备 reference 能力标签（决定参考素材能否真实生效） */
    val supportsReference: Boolean
        get() = selectedModel()?.capabilities?.split(",")?.any { it.trim() == "reference" } == true

    /** 当前模型是否具备 audio 能力标签（决定能否生成带音轨的视频） */
    val supportsAudio: Boolean
        get() = selectedModel()?.capabilities?.split(",")?.any { it.trim() == "audio" } == true

    private fun selectedModel(): ModelOptionEntity? =
        modelOptions.firstOrNull { it.id == selectedModelId }

    val messages: StateFlow<List<MessageEntity>> =
        db.messageDao().observeByConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CardEntity>> =
        db.cardDao().observeByConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cardLinks: StateFlow<List<CardLinkEntity>> =
        db.cardLinkDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRun: StateFlow<AgentRunEntity?> =
        db.agentRunDao().observeActive(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val runs: StateFlow<List<AgentRunEntity>> =
        db.agentRunDao().observeByConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedReferenceCards by mutableStateOf<List<CardEntity>>(emptyList())
        private set

    /** 跨会话素材参考：从全局素材库选中的素材（图/音频→上送；视频暂不参与） */
    var selectedReferenceAssets by mutableStateOf<List<AssetEntity>>(emptyList())
        private set

    /** 全局素材库列表，供"从素材库引用"选择器使用 */
    val libraryAssets: StateFlow<List<AssetEntity>> =
        db.assetDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var lastRequestBase: RunRequest? = null

    fun setKind(kind: MediaKind) {
        selectedKind = kind
        // 按类型重载模型：不同 kind（文本/图像/视频/音频）对应不同模型目录
        viewModelScope.launch {
            val models = try {
                router.models(kind)
            } catch (e: Exception) {
                emptyList()
            }
            modelOptions = models
            selectedModelId = firstAvailableModel(models)
            syncResolutionDefault()
            markStateChanged()
        }
    }

    fun setModel(id: String) {
        selectedModelId = id
        syncResolutionDefault()
        markStateChanged()
    }

    /** ② 分辨率 UI 联动：模型/类型切换后，仅当该模型明确声明了分辨率时才重置到合法默认；
     *  未声明则保留用户手动填写的自定义值（不抹掉，也不硬塞默认档）。 */
    private fun syncResolutionDefault() {
        val declared = selectedModelResolutions
        if (declared.isNotEmpty()) {
            resolution = declared.firstOrNull()?.takeUnless { it.isBlank() } ?: resolution
        }
    }

    fun setAgentModel(id: String) {
        selectedAgentModelId = id
        markStateChanged()
    }

    fun setCinematic(enabled: Boolean) {
        cinematicEnabled = enabled
        markStateChanged()
    }

    fun updateThinkingLevel(level: ThinkingLevel) {
        thinkingLevel = level
        markStateChanged()
    }

    /** 从系统文件选择器导入本地图片/视频：登记为素材库资源，并自动加入参考素材 */
    fun importLocalMedia(uri: Uri) {
        val scheme = uri.scheme
        if (scheme != "content" && scheme != "file") return
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()?.lowercase() ?: ""
        val kind = when {
            mime.startsWith("video") -> MediaKind.VIDEO
            mime.startsWith("audio") -> MediaKind.AUDIO
            else -> MediaKind.IMAGE
        }
        viewModelScope.launch {
            try {
                val asset = media.importFromUri(conversationId, uri, kind, "上传 ${kind.name.lowercase()}")
                selectedReferenceAssets = selectedReferenceAssets.filterNot { it.mediaPath == asset.mediaPath } + asset
                markStateChanged()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "导入本地文件失败"
            }
        }
    }

    fun toggleAudio(enabled: Boolean) {
        audioEnabled = enabled
        markStateChanged()
    }

    fun onCountChange(value: Int) {
        count = value.coerceIn(1, 6)
        markStateChanged()
    }

    fun onInputChange(v: String) {
        input = v
        markStateChanged()
    }

    /** Agent 对话输入变更（与手动输入隔离） */
    fun onAgentInputChange(v: String) {
        agentInput = v
        markStateChanged()
    }

    fun onVideoSecondsChange(seconds: Int) {
        // 与创作卡片的滑动范围（MIN_SECONDS..MAX_SECONDS=4..30）保持一致
        videoSeconds = seconds.coerceIn(4, 30)
        markStateChanged()
    }

    fun onRatioChange(r: String) {
        ratio = r
        markStateChanged()
    }

    fun onResolutionChange(res: String) {
        resolution = res
        markStateChanged()
    }

    fun toggleReference(card: CardEntity) {
        val has = selectedReferenceCards.any { it.id == card.id }
        selectedReferenceCards = if (has) {
            selectedReferenceCards.filterNot { it.id == card.id }
        } else {
            selectedReferenceCards + card
        }
        // 在卡片里选/反选参考卡：同步画布连线（参考卡 → 正在编辑的空白卡），线自动出现/消失
        val draftId = editingDraftId
        if (draftId != null && draftId != card.id) {
            if (has) unlinkReference(card.id, draftId)
            else linkReference(card.id, draftId)
        }
        markStateChanged()
    }

    /** 选中/取消一个跨会话素材作为参考（按 mediaPath 判等） */
    fun toggleReferenceAsset(asset: AssetEntity) {
        selectedReferenceAssets = if (selectedReferenceAssets.any { it.mediaPath != null && it.mediaPath == asset.mediaPath }) {
            selectedReferenceAssets.filterNot { it.id == asset.id }
        } else {
            selectedReferenceAssets + asset
        }
        markStateChanged()
    }

    // ---------- 画布：节点多选 / 位置持久化 / 自动整理 ----------
    /** 画布上被框选/点选中的节点 id（用于批量移动、删除、连线、缩放聚焦） */
    var selectedCanvasIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleCanvasNode(id: String, multi: Boolean) {
        selectedCanvasIds = if (!multi) {
            setOf(id)
        } else if (id in selectedCanvasIds) {
            selectedCanvasIds - id
        } else {
            selectedCanvasIds + id
        }
    }

    fun setCanvasSelection(ids: Set<String>) {
        selectedCanvasIds = ids
    }

    fun selectCanvasNode(id: String) {
        selectedCanvasIds = setOf(id)
    }

    fun clearCanvasSelection() {
        selectedCanvasIds = emptySet()
    }

    /** 拖动结束/自动整理后批量落库节点坐标（x,y 为画布坐标系 dp） */
    fun commitNodePositions(updates: List<Pair<String, Pair<Float, Float>>>) {
        if (updates.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                updates.forEach { (id, p) -> db.cardDao().updatePosition(id, p.first, p.second) }
            }.onFailure { e -> lastError = e.message ?: "保存节点位置失败" }
        }
    }

    /** 批量删除画布上选中的节点（含其关系边） */
    fun deleteSelectedNodes() {
        val ids = selectedCanvasIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                ids.forEach { id ->
                    db.cardLinkDao().deleteForCard(id)
                    db.cardDao().softDelete(id)
                }
            }.onFailure { e -> lastError = e.message ?: "批量删除失败" }
        }
        selectedCanvasIds = emptySet()
        markStateChanged()
    }

    /** 自动整理：把会话内全部节点按类型网格重排（P2 力导向的轻量替代：稳定、无碰撞的网格铺排） */
    fun autoLayoutCanvas() {
        viewModelScope.launch {
            val nodeIds = runCatching { db.cardDao().listByConversation(conversationId) }.getOrNull().orEmpty()
                .map { it.id to it.kind }
            if (nodeIds.isEmpty()) return@launch
            val updates = mutableListOf<Pair<String, Pair<Float, Float>>>()
            val col = 4
            nodeIds.forEachIndexed { i, (id, _) ->
                val x = (i % col) * 190f + 24f
                val y = (i / col) * 200f + 24f
                updates += id to (x to y)
            }
            commitNodePositions(updates)
        }
    }

    fun send() {
        val prompt = input.trim()
        if (prompt.isEmpty()) return
        val t = token ?: return
        // 提示词优化开关快照：若开启，提交前先用默认文本模型润色
        val opt = promptOptimize
        // 参考类型过滤：图像生成不接受视频/音频参考；视频生成才接受图像+视频参考。
        // 不适用当前生成类型的参考卡/素材直接剔除并提示，避免「选了却无效」的误导。
        val target = selectedKind
        val usableCards = selectedReferenceCards.filter { canServeAsReference(it.kind, target) }
        val usableAssets = selectedReferenceAssets.filter { canServeAsReference(it.kind, target) }
        val dropped = (selectedReferenceCards.size - usableCards.size) + (selectedReferenceAssets.size - usableAssets.size)
        if (dropped > 0) {
            toast("已忽略 $dropped 个不适用于${if (target == MediaKind.IMAGE) "图像" else "视频"}生成的参考")
        }
        lastRequestBase = RunRequest(
            conversationId = conversationId,
            prompt = prompt,
            kind = selectedKind,
            modelIds = listOfNotNull(selectedModelId),
            count = count,
            ratio = ratio.ifEmpty { null },
            quality = quality.ifEmpty { null },
            resolution = resolution?.takeIf { it.isNotBlank() },
            seconds = if (selectedKind == MediaKind.VIDEO) videoSeconds else null,
            referencedAssetIds = usableCards.map { it.id },
            referencedAssetPaths = usableAssets.mapNotNull { it.mediaPath },
        )
        val req = lastRequestBase!!
        // 提交瞬间：确保有一张「生成中」占位卡在画布上显示进度，而非静默消失。
        // 若有 draft 卡则升级为 RUNNING；若没有（直接从输入框提交），创建一张 RUNNING 占位卡。
        // 同步快照 editingDraftId，然后立即清空 draft 状态（关闭底部浮层），
        // 避免 onSent 的 clearDraft() 与 send() 协程产生竞态。
        val currentDraftId = editingDraftId
        val draftIdForRunning = currentDraftId ?: "draft_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val hasExistingDraft = currentDraftId != null
        draftKind = null
        editingDraftId = null
        viewModelScope.launch {
            if (hasExistingDraft) {
                runCatching {
                    db.cardDao().byId(draftIdForRunning)?.let { c ->
                        db.cardDao().update(c.copy(status = RunStatus.RUNNING, content = req.prompt))
                    }
                }
            } else {
                // 无 draft 卡：创建一张 RUNNING 占位卡，使其在画布上显示生成中进度
                runCatching {
                    val count = runCatching { db.cardDao().listByConversation(conversationId) }
                        .getOrNull().orEmpty().size
                    val col = 6
                    val x = 24f + (count % col) * 170f
                    val y = 24f + (count / col) * 170f
                    db.cardDao().saveDraft(
                        com.tapcreator.app.data.db.CardEntity(
                            id = draftIdForRunning,
                            runId = "draft",
                            conversationId = conversationId,
                            sequence = 0,
                            kind = selectedKind,
                            title = "",
                            content = req.prompt,
                            x = x,
                            y = y,
                            status = RunStatus.RUNNING,
                        )
                    )
                }
            }
            // Agent 仍执行时不消费，避免把「进行中批次」当作已完成产出误判。
            // 提示词优化：开启时先用默认文本模型润色，失败则回退原文继续生成
            if (opt) {
                runCatching { runService.optimizePrompt(t, prompt) }
                    .onSuccess { optPrompt ->
                        if (optPrompt.isNotBlank() && optPrompt != prompt) {
                            // 优化成功且内容变化：标记增强，落卡时写 promptEnhanced 供预览标识
                            lastRequestBase = lastRequestBase!!.copy(prompt = optPrompt, promptEnhanced = true)
                        }
                    }
                    .onFailure { e -> toast("提示词优化失败，已使用原文：${e.message}") }
            }
            val r2 = lastRequestBase!!
            var genFailed = false
            try {
                runService.launch(t, r2)
            } catch (ce: CancellationException) {
                // 取消时仍须清理草稿卡，避免残留 RUNNING 卡在画布上
                cleanupDraftCard(currentDraftId, genFailed = false)
                throw ce // 取消必须透传，不能当普通失败吞掉
            } catch (e: Exception) {
                lastError = e.message ?: "生成失败"
                genFailed = true
            }
            input = ""
            cleanupDraftCard(currentDraftId, genFailed)
            if (r2.referencedAssetIds.isNotEmpty()) selectedReferenceCards = emptyList()
            if (r2.referencedAssetPaths.isNotEmpty()) selectedReferenceAssets = emptyList()
            markStateChanged()
        }
    }

    /** 清理草稿卡：生成成功时删除，失败时标 FAILED，取消时删除。 */
    private suspend fun cleanupDraftCard(draftId: String?, genFailed: Boolean) {
        if (draftId == null) return
        runCatching {
            if (genFailed) {
                db.cardDao().byId(draftId)?.let { c ->
                    db.cardDao().update(c.copy(status = RunStatus.FAILED))
                }
            } else {
                db.cardLinkDao().deleteForCard(draftId)
                db.cardDao().hardDelete(draftId)
            }
        }
    }

    /** 自主 Agent：以 ReAct Loop 推进（思考→工具→观察→记忆→收尾），直至完成 */
    fun sendAgent() {
        val prompt = agentInput.trim()
        if (prompt.isEmpty()) return
        val t = token ?: return
        if (agentBusy) { toast("Agent 正在执行，请稍候"); return } // 防重入，避免双 Loop 串流/重复产出
        agentBusy = true
        agentProgress = null
        agentStream = emptyList()
        agentInput = ""
        // 先行快照，避免协程尚未读取时就被下方清空
        val refCards = selectedReferenceCards.map { it.id }
        val refAssets = selectedReferenceAssets.mapNotNull { it.mediaPath }
        selectedReferenceCards = emptyList()
        selectedReferenceAssets = emptyList()
        markStateChanged()
        agentJob = viewModelScope.launch {
            try {
                // 读取用户偏好：记忆系统开关 + 个人风格偏好（注入 Agent 系统提示）
                val memEnabled = settings.agentMemoryEnabledValue()
                val style = settings.agentStyleValue().trim()
                brain.run(
                    token = t,
                    conversationId = conversationId,
                    userPrompt = prompt,
                    modelId = selectedAgentModelId,
                    referenceCardIds = refCards,
                    referenceAssetPaths = refAssets,
                    cinematic = cinematicEnabled,
                    memoryEnabled = memEnabled,
                    style = style,
                    thinkingLevel = thinkingLevel,
                    onProgress = { done, total -> agentProgress = done to total },
                    onEvent = { role, text -> agentStream = agentStream + (role to text); markStateChanged() },
                    onThinking = { token ->
                        // 大脑正文 token：若尾部仍是 thinking 块则续写，否则开一段新 thinking
                        val last = agentStream.lastOrNull()
                        agentStream = if (last != null && last.first == "think") {
                            agentStream.dropLast(1) + (last.first to (last.second + token))
                        } else {
                            agentStream + ("think" to token)
                        }
                        markStateChanged()
                    },
                    onReasoning = { token ->
                        // 推理过程(reasoning_content)单独成流：标签「推理」，与思考流区分，实时滚动
                        val last = agentStream.lastOrNull()
                        agentStream = if (last != null && last.first == "reasoning") {
                            agentStream.dropLast(1) + (last.first to (last.second + token))
                        } else {
                            agentStream + ("reasoning" to token)
                        }
                        markStateChanged()
                    },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "${e.javaClass.simpleName}: Agent 执行失败"
            } finally {
                agentBusy = false
                agentProgress = null
            }
        }
    }

    /** 取消整个 Agent 任务（未执行步骤不再执行，已产生的 Run 各自退款/置取消） */
    fun cancelAgent() {
        agentJob?.cancel(CancellationException("取消 Agent 任务"))
        agentBusy = false
        agentProgress = null
    }

    fun cancel() {
        val runId = activeRun.value?.id ?: return
        viewModelScope.launch { runService.cancel(runId) }
    }

    fun retry() {
        val base = lastRequestBase ?: return
        val t = token ?: return
        viewModelScope.launch {
            try {
                runService.retry(
                    token = t,
                    conversationId = conversationId,
                    prompt = base.prompt,
                    kind = base.kind,
                    modelIds = base.modelIds,
                    count = base.count,
                    ratio = base.ratio,
                    quality = base.quality,
                    resolution = base.resolution,
                    seconds = base.seconds,
                    referencedCardIds = base.referencedAssetIds,
                    referencedAssetPaths = base.referencedAssetPaths,
                    promptEnhanced = base.promptEnhanced,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "重试失败"
            }
        }
    }

    var lastError by mutableStateOf<String?>(null)
        private set

    /** 恢复中断的视频 run（断点续传）：从 checkpoint 复用已生成段，续生成剩余段 */
    fun resumeVideoRun(runId: String) {
        val t = token ?: return
        viewModelScope.launch {
            val run = db.agentRunDao().byId(runId) ?: return@launch
            if (run.status != RunStatus.PAUSED) return@launch
            try {
                // 重置为 RUNNING 后重新走 launchVideo，会从 checkpoint 恢复段文件
                db.agentRunDao().update(run.copy(status = RunStatus.RUNNING, error = null, updatedAt = System.currentTimeMillis()))
                val modelEntity = router.resolve(run.modelIds.split(",").filter { it.isNotBlank() }, run.kind).firstOrNull() ?: return@launch
                val pref = GenerationPreferences(kind = run.kind, prompt = run.prompt, modelIds = listOf(modelEntity.id))
                // 重新解析渠道+密钥走 launchVideo 的续传路径
                runService.launch(t, RunRequest(
                    conversationId = run.conversationId,
                    prompt = run.prompt,
                    kind = run.kind,
                    modelIds = listOf(modelEntity.id),
                    seconds = null,
                ))
            } catch (e: Exception) {
                lastError = e.message ?: "恢复失败"
            }
        }
    }

    fun clearError() {
        lastError = null
    }

    /** 长按删除一张结果卡片：清除关系边并从时间线隐藏（磁盘文件保留，防误删引用） */
    fun deleteCard(card: CardEntity) {
        if (card.id in selectedReferenceCards.map { it.id }) {
            selectedReferenceCards = selectedReferenceCards.filterNot { it.id == card.id }
            markStateChanged()
        }
        viewModelScope.launch {
            runCatching {
                db.cardLinkDao().deleteForCard(card.id)
                db.cardDao().softDelete(card.id)
            }.onFailure { e -> lastError = e.message ?: "删除卡片失败" }
        }
    }

    /** 在工作界面建立卡片间参考关系（from → to，role=reference；缺省避免重复） */
    fun linkReference(fromCardId: String, toCardId: String) {
        viewModelScope.launch {
            runCatching {
                val from = db.cardDao().byId(fromCardId) ?: return@launch
                val to = db.cardDao().byId(toCardId) ?: return@launch
                // 类型合法性防线：不符合「可参考类型矩阵」的连接直接拒绝
                if (!canServeAsReference(from.kind, to.kind)) {
                    toast("不可连接：${labelOf(from.kind)}不能作为${labelOf(to.kind)}的输入参考")
                    return@launch
                }
                if (!db.cardLinkDao().exists(fromCardId, toCardId, "reference")) {
                    db.cardLinkDao().insert(
                        CardLinkEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            fromCardId = fromCardId,
                            toCardId = toCardId,
                            role = "reference",
                        )
                    )
                }
            }.onFailure { e -> lastError = e.message ?: "建立关系失败" }
        }
    }

    /** 在工作界面移除卡片间参考关系 */
    fun unlinkReference(fromCardId: String, toCardId: String) {
        viewModelScope.launch {
            runCatching {
                db.cardLinkDao().delete(fromCardId, toCardId, "reference")
            }.onFailure { e -> lastError = e.message ?: "移除关系失败" }
        }
    }

    /** 长按删除一条对话消息（用户输入气泡） */
    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            runCatching { db.messageDao().deleteById(message.id) }
                .onFailure { e -> lastError = e.message ?: "删除消息失败" }
        }
    }

    fun activeRunProgress(): Pair<Int, Int> {
        val run = activeRun.value ?: return 0 to 0
        val done = cards.value.count { it.runId == run.id }
        return done to run.requestCount
    }

    private fun firstAvailableModel(models: List<ModelOptionEntity>): String? =
        models.firstOrNull { it.isDefault }?.id ?: models.firstOrNull()?.id

    /** 可参考类型矩阵：from(参考) 能否作为 to(生成目标) 的输入参考。
     *  - 图像生成：只接受图像参考（图不能接收视频/音频参考）
     *  - 视频生成：接受图像 + 视频 + 音频参考（音频在 H3 等模型里作为 reference_audio 音色参考，实现音画衔接）
     *  - 音频生成：接受音频参考（音色） */
    fun canServeAsReference(from: MediaKind, to: MediaKind): Boolean =
        when (to) {
            MediaKind.IMAGE -> from == MediaKind.IMAGE
            MediaKind.VIDEO -> from == MediaKind.IMAGE || from == MediaKind.VIDEO || from == MediaKind.AUDIO
            MediaKind.AUDIO -> from == MediaKind.AUDIO
            else -> from == MediaKind.IMAGE
        }

    private fun labelOf(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "图像"
        MediaKind.VIDEO -> "视频"
        MediaKind.TEXT -> "文本"
        MediaKind.AUDIO -> "音频"
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 把卡片产物导出到系统相册（图片→Pictures/Tapcreator，视频→Movies/Tapcreator），返回是否成功 */
    fun saveCardToGallery(card: CardEntity, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val source = card.mediaPath?.let(::File)?.takeIf { it.exists() } ?: return@withContext false
                runCatching { exportToGallery(source, card.kind) }.getOrDefault(false)
            }
            toast(if (ok) "已保存到相册" else "保存失败")
            onResult(ok)
        }
    }

    private fun exportToGallery(source: File, kind: MediaKind): Boolean {
        val mime = when (kind) {
            MediaKind.IMAGE -> "image/webp"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
            else -> return false
        }
        val collection = when (kind) {
            MediaKind.IMAGE -> android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.VIDEO -> android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.AUDIO -> android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return false
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    when (kind) {
                        MediaKind.IMAGE -> "Pictures/Tapcreator"
                        MediaKind.VIDEO -> "Movies/Tapcreator"
                        MediaKind.AUDIO -> "Music/Tapcreator"
                        else -> return false
                    },
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}