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
import com.tapcreator.app.backend.auth.AuthService
import com.tapcreator.app.backend.media.MediaStore
import com.tapcreator.app.backend.model.ModelRouter
import com.tapcreator.app.backend.service.RunService
import com.tapcreator.app.backend.video.Mp4Concatenator
import com.tapcreator.app.data.db.AgentRunEntity
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.db.AssetFolderEntity
import com.tapcreator.app.data.db.CanvasSnapshotEntity
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.CardLinkEntity
import com.tapcreator.app.data.db.ConversationStateEntity
import com.tapcreator.app.data.db.MessageEntity
import com.tapcreator.app.data.db.ModelOptionEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.CanvasSnapshotPayload
import com.tapcreator.app.data.model.GenerationPreferences
import com.tapcreator.app.data.model.NodeParams
import com.tapcreator.app.data.model.RunRequest
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.data.model.SnapshotLink
import com.tapcreator.app.data.model.SnapshotNode
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
    private val skillRegistry: com.tapcreator.app.backend.skill.SkillRegistry,
    private val media: MediaStore,
    private val auth: AuthService,
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
            // 无需登录：自动建立/复用本机匿名会话（首次启动自动创建，用户无感）
            token = auth.ensureAnonymousSession()
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

    /**
     * 关闭提交面板=放弃这次编辑。只清空编辑现场，【不删除】空白卡——
     * 卡片保留在画布上（runId='draft'），除非用户手动删除。
     */
    fun clearDraft() {
        draftKind = null
        editingDraftId = null
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

    // 分镜优化已内置到 Agent skill（仅视频生成任务自动应用），不再提供用户开关
    // 推理深度级别已移除——始终使用 AUTO
    // fun updateThinkingLevel(level: ThinkingLevel) { ... }

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

    /** 分辨率双输入框：只接受数字，宽/高任一变化时实时合成 "WxH"；两框皆空则置 null（用模型默认） */
    fun onResolutionPartChange(isWidth: Boolean, raw: String) {
        val digits = raw.filter { it.isDigit() }.take(4)
        val parts = resolution.orEmpty().split("x", "X", "×")
        val curW = parts.getOrNull(0)?.trim()?.filter { it.isDigit() } ?: ""
        val curH = parts.getOrNull(1)?.trim()?.filter { it.isDigit() } ?: ""
        val w = if (isWidth) digits else curW
        val h = if (isWidth) curH else digits
        resolution = when {
            w.isEmpty() && h.isEmpty() -> null
            h.isEmpty() -> w
            else -> "${w}x${h}"
        }
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

    /** 一键取消所有参考引用（卡片+素材），同步清理画布连线 */
    fun clearAllReferences() {
        // 同步删除画布上 draft 卡的 reference 连线（与 toggleReference 一致）
        val draftId = editingDraftId
        if (draftId != null) {
            selectedReferenceCards.forEach { card ->
                unlinkReference(card.id, draftId)
            }
        }
        selectedReferenceCards = emptyList()
        selectedReferenceAssets = emptyList()
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

    // ---------- 素材库文件夹级一键选取（picker 模式，点击文件夹 chip） ----------
    /** 该素材能否作为「当前生成类型」的参考（与 send() 的过滤规则一致） */
    fun isUsableReferenceAsset(asset: AssetEntity): Boolean =
        canServeAsReference(asset.kind, selectedKind)

    /** 返回文件夹内可用于当前生成类型的素材（folderId 需为具体文件夹 id） */
    fun folderUsableAssets(folderId: String): List<AssetEntity> =
        libraryAssets.value.filter { it.folderId == folderId && canServeAsReference(it.kind, selectedKind) }

    /** 文件夹内可用素材是否已全部选中（chip 显示 ✓ 全选态） */
    fun isFolderAllPicked(folderId: String): Boolean {
        val usable = folderUsableAssets(folderId)
        if (usable.isEmpty()) return false
        val picked = usable.all { a ->
            a.mediaPath != null && selectedReferenceAssets.any { it.mediaPath == a.mediaPath }
        }
        return picked
    }

    /** 点击文件夹 chip：未全选→补全该夹可用素材；已全选→一键全部取消 */
    fun toggleFolderAssets(folderId: String) {
        val usable = folderUsableAssets(folderId)
        if (usable.isEmpty()) return
        val pickedPaths = selectedReferenceAssets.mapNotNull { it.mediaPath }.toSet()
        val missing = usable.filter { it.mediaPath != null && it.mediaPath !in pickedPaths }
        selectedReferenceAssets = if (missing.isEmpty()) {
            // 已全选：移除该夹所有素材
            val removePaths = usable.mapNotNull { it.mediaPath }.toSet()
            selectedReferenceAssets.filterNot { it.mediaPath in removePaths }
        } else {
            (selectedReferenceAssets + missing).distinctBy { it.mediaPath }
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
            val before = captureCanvasState()
            runCatching {
                updates.forEach { (id, p) -> db.cardDao().updatePosition(id, p.first, p.second) }
            }.onFailure { e -> lastError = e.message ?: "保存节点位置失败" }
            pushUndo(before, captureCanvasState())
        }
    }

    /** 批量删除画布上选中的节点（含其关系边） */
    fun deleteSelectedNodes() {
        val ids = selectedCanvasIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val before = captureCanvasState()
            runCatching {
                ids.forEach { id ->
                    db.cardLinkDao().deleteForCard(id)
                    db.cardDao().softDelete(id)
                }
            }.onFailure { e -> lastError = e.message ?: "批量删除失败" }
            pushUndo(before, captureCanvasState())
        }
        selectedCanvasIds = emptySet()
        markStateChanged()
    }

    /** 自动整理：按引用边做 DAG 分层布局（上游在左、下游在右），让工作流一眼可读 */
    fun autoLayoutCanvas() {
        viewModelScope.launch {
            val cs = runCatching { db.cardDao().listByConversation(conversationId) }.getOrNull().orEmpty()
            if (cs.isEmpty()) return@launch
            val before = captureCanvasState()
            val idSet = cs.map { it.id }.toSet()
            val edges = cs.flatMap { db.cardLinkDao().outgoing(it.id) }
                .map { it.fromCardId to it.toCardId }
                .filter { it.first in idSet && it.second in idSet }
                .distinct()
            val placements = CanvasLayout.layered(
                nodes = cs.map { CanvasLayout.Node(it.id, it.kind) },
                edges = edges,
            )
            placements.forEach { p -> db.cardDao().updatePosition(p.id, p.x, p.y) }
            pushUndo(before, captureCanvasState())
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
        // 角色节点不作为普通图片卡上送（它没有单张媒体），而是展开为其绑定文件夹的多视角素材
        val characterIds = selectedReferenceCards.filter { isCharacterNode(it) }.map { it.id }.toSet()
        val usableCards = selectedReferenceCards
            .filterNot { it.id in characterIds }
            .filter { canServeAsReference(it.kind, target) }
        val usableAssets = selectedReferenceAssets.filter { canServeAsReference(it.kind, target) }
        val dropped = (selectedReferenceCards.count { it.id !in characterIds } - usableCards.size) +
            (selectedReferenceAssets.size - usableAssets.size)
        if (dropped > 0) {
            toast("已忽略 $dropped 个不适用于${if (target == MediaKind.IMAGE) "图像" else "视频"}生成的参考")
        }
        // 参考图文件缺失校验：勾选了图片参考但磁盘文件不存在 → 上送时会被静默丢弃（结果与参考无关）。
        // 这里明确剔除并提示，避免「选了参考却没生效」的困惑。
        fun refFileOk(mediaPath: String?, previewPath: String?): Boolean =
            (mediaPath?.let { File(it).exists() } == true) || (previewPath?.let { File(it).exists() } == true)
        val missingCards = usableCards.filter { it.kind == MediaKind.IMAGE && !refFileOk(it.mediaPath, it.previewPath) }
        val missingAssets = usableAssets.filter { it.kind == MediaKind.IMAGE && !refFileOk(it.mediaPath, it.previewPath) }
        if (missingCards.isNotEmpty() || missingAssets.isNotEmpty()) {
            toast("${missingCards.size + missingAssets.size} 张参考图文件缺失，已忽略（磁盘文件可能已被清理）")
        }
        val usableCardsValid = usableCards.filterNot { it in missingCards }
        val usableAssetsValid = usableAssets.filterNot { it in missingAssets }
        // 参考图数量上限：与 RunService 上送截断一致（保留前 8 张，卡片优先）。超限时从尾部删图，保留前面的参考。
        val over = (usableCardsValid.count { it.kind == MediaKind.IMAGE } + usableAssetsValid.count { it.kind == MediaKind.IMAGE }) - MAX_REF_ASSETS
        val (limitedCards, limitedAssets) = if (over > 0) {
            var dropRemain = over
            // 先删素材尾部图片（RunService 消费顺序：卡片优先、素材在后），仍超再删卡片尾部
            val assets = usableAssetsValid.toMutableList()
            while (dropRemain > 0 && assets.isNotEmpty()) {
                val idx = assets.indexOfLast { it.kind == MediaKind.IMAGE }
                if (idx < 0) break
                assets.removeAt(idx)
                dropRemain--
            }
            val cards = usableCardsValid.toMutableList()
            while (dropRemain > 0 && cards.isNotEmpty()) {
                val idx = cards.indexOfLast { it.kind == MediaKind.IMAGE }
                if (idx < 0) break
                cards.removeAt(idx)
                dropRemain--
            }
            toast("参考图超过 $MAX_REF_ASSETS 张上限，仅保留前 $MAX_REF_ASSETS 张")
            cards.toList() to assets.toList()
        } else {
            usableCardsValid to usableAssetsValid
        }
                // 手动分辨率有效性校验：图片强制 WxH 数字（64..8192 正整数）；视频放行模型声明档位（768P/2K/4K），
        // 两者皆非则回落模型默认并提示。
        val resValid = run {
            val r = resolution?.trim()
            if (r.isNullOrBlank()) {
                true
            } else {
                val parts = r.split("x", "X", "×").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2 && parts.all { it in 64..8192 }) {
                    true
                } else if (selectedKind == MediaKind.VIDEO && r in selectedModelResolutions) {
                    true
                } else {
                    toast("分辨率「$r」无效，已使用模型默认")
                    false
                }
            }
        }
        lastRequestBase = RunRequest(
            conversationId = conversationId,
            prompt = prompt,
            kind = selectedKind,
            modelIds = listOfNotNull(selectedModelId),
            count = count,
            ratio = ratio.ifEmpty { null },
            quality = quality.ifEmpty { null },
            resolution = if (resValid) resolution?.takeIf { it.isNotBlank() } else null,
            seconds = if (selectedKind == MediaKind.VIDEO) videoSeconds else null,
            referencedAssetIds = limitedCards.map { it.id },
            referencedAssetPaths = limitedAssets.mapNotNull { it.mediaPath },
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
            // 提示词优化：开启时按生成类型分发不同优化器
            if (opt) {
                val optimizeFn = if (selectedKind == MediaKind.VIDEO) {
                    runCatching { runService.optimizeVideoPrompt(prompt) }
                } else {
                    runCatching { runService.optimizePrompt(prompt) }
                }
                optimizeFn
                    .onSuccess { optPrompt ->
                        if (optPrompt.isNotBlank() && optPrompt != prompt) {
                            lastRequestBase = lastRequestBase!!.copy(prompt = optPrompt, promptEnhanced = true)
                        } else if (optPrompt.isNotBlank()) {
                            toast("提示词优化未通过原意校验，已使用原文")
                        }
                    }
                    .onFailure { e -> toast("提示词优化失败，已使用原文：${e.message}") }
            }
            // 「连线即参考」：把画布上连到本草稿卡的参考边（来源卡）并入生成输入。
            // 此前生成只读提交面板勾选的 selectedReferenceCards，从不回读连线边——
            // 于是「把卡片 A 连到下一张卡 B 作参考」对生成不生效。这里回读入边补齐。
            if (hasExistingDraft) {
                val linkedSrc = runCatching {
                    db.cardLinkDao().incoming(draftIdForRunning)
                        .filter { it.role == "reference" }
                        .mapNotNull { l -> runCatching { db.cardDao().byId(l.fromCardId) }.getOrNull() }
                }.getOrDefault(emptyList())
                val have = lastRequestBase!!.referencedAssetIds.toSet()
                // 与同步路径一致：连线卡也须过 类型矩阵 + 文件存在 校验（避免加入文件已缺失的参考导致后端报错）
                val extra = linkedSrc
                    .filter { it.id !in have && canServeAsReference(it.kind, selectedKind) && refFileOk(it.mediaPath, it.previewPath) }
                    .map { it.id }.distinct()
                if (extra.isNotEmpty()) {
                    val merged = (lastRequestBase!!.referencedAssetIds + extra).distinct()
                    // 预取 id→kind，避免把 suspend 查询塞进 count/filter 的普通 lambda
                    val kindOf = merged.associateWith { id ->
                        runCatching { db.cardDao().byId(id)?.kind }.getOrNull()
                    }
                    // 图片参考总数不超 MAX_REF_ASSETS：从尾部（后加的连线卡优先砍）削到上限
                    var over = merged.count { kindOf[it] == MediaKind.IMAGE } - MAX_REF_ASSETS
                    val capped = if (over > 0) {
                        merged.filter { id ->
                            if (kindOf[id] == MediaKind.IMAGE && over > 0) { over--; false } else true
                        }
                    } else merged
                    lastRequestBase = lastRequestBase!!.copy(referencedAssetIds = capped)
                }
            }
            // 角色节点：展开绑定文件夹的多视角素材，作为身份锚点参考并入上送路径
            if (characterIds.isNotEmpty()) {
                val charPaths = runCatching { characterPathsFor(characterIds.toList()) }.getOrDefault(emptyList())
                if (charPaths.isNotEmpty()) {
                    val paths = (lastRequestBase!!.referencedAssetPaths + charPaths).distinct()
                    lastRequestBase = lastRequestBase!!.copy(referencedAssetPaths = paths)
                }
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

    // ---------- 提示词撰写：在输入框处唤出 Agent 调用设计 Skill 扩写提示词 ----------

    /** 可用的设计 Skill（内置预设 + 已安装），供输入框的「提示词 Skill」选择。 */
    val promptSkills: StateFlow<List<com.tapcreator.app.data.model.DesignSkill>> =
        skillRegistry.designSkillsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            skillRegistry.all(),
        )

    /** 提示词撰写中（按钮转圈/禁用） */
    var promptComposeBusy by mutableStateOf(false)
        private set

    /** 正在使用的 Skill id（高亮 chip；null = 不限风格直接扩写） */
    var promptComposeSkillId by mutableStateOf<String?>(null)
        private set

    /**
     * 唤出 Agent（提示词撰写模式）把当前输入扩写成生成提示词：
     * 只允许只读/风格 Skill 动作，不产卡；完成后把提示词回填输入框供用户编辑再发送。
     * @param skillId 设计 Skill id；null = 不限风格直接扩写
     */
    fun composePromptWithSkill(skillId: String?) {
        val base = input.trim()
        if (base.isEmpty()) {
            toast("先输入一句话创作诉求，再唤出 Agent 写提示词")
            return
        }
        if (promptComposeBusy) {
            toast("Agent 正在撰写提示词，请稍候")
            return
        }
        val t = token ?: return
        val skill = skillId?.let { skillRegistry.byId(it) }
        promptComposeBusy = true
        promptComposeSkillId = skill?.id
        viewModelScope.launch {
            var produced: String? = null
            try {
                val instruction = buildString {
                    append("请把下面这句话扩写成一段可直接用于生成的提示词")
                    if (skill != null) {
                        append("，并先 apply_skill 应用设计 Skill「${skill.id}」（${skill.name}）的风格指导")
                    }
                    append("。\n用户原话：$base")
                }
                brain.run(
                    token = t,
                    conversationId = conversationId,
                    userPrompt = instruction,
                    modelId = selectedAgentModelId,
                    cinematic = selectedKind == MediaKind.VIDEO, // 视频诉求按分镜结构扩写
                    memoryEnabled = false,
                    style = settings.agentStyleValue().trim(),
                    maxTurns = 4,
                    promptOnly = true,
                    onPromptReady = { produced = it },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                toast(e.message ?: "撰写提示词失败")
            } finally {
                promptComposeBusy = false
                promptComposeSkillId = null
            }
            val out = produced?.trim()
            if (out.isNullOrBlank()) {
                toast("Agent 未产出提示词，请重试")
            } else {
                input = out
                markStateChanged()
                toast("提示词已写入输入框，可编辑后发送")
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
        // 参考图数量上限：与 RunService 上送截断一致（保留前 8 张）。超限时从尾部删图片素材，保留前面的参考。
        val overImgs = selectedReferenceCards.count { it.kind == MediaKind.IMAGE } +
            selectedReferenceAssets.count { it.kind == MediaKind.IMAGE } - MAX_REF_ASSETS
        var dropImgs = overImgs.coerceAtLeast(0)
        val refAssets = if (dropImgs > 0) {
            val paths = selectedReferenceAssets.mapNotNull { a -> a.mediaPath }
            val kindByPath = selectedReferenceAssets.associateBy { it.mediaPath }
            val out = paths.toMutableList()
            while (dropImgs > 0 && out.isNotEmpty()) {
                val idx = out.indexOfLast { kindByPath[it]?.kind == MediaKind.IMAGE }
                if (idx < 0) break
                out.removeAt(idx)
                dropImgs--
            }
            out
        } else {
            selectedReferenceAssets.mapNotNull { it.mediaPath }
        }
        if (overImgs > 0) toast("参考图超过 $MAX_REF_ASSETS 张上限，仅保留前 $MAX_REF_ASSETS 张")
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
                    cinematic = true, // 分镜优化内置到 Agent skill，视频生成任务自动应用
                    memoryEnabled = memEnabled,
                    style = style,
                    thinkingLevel = thinkingLevel,
                    onProgress = { done, total -> agentProgress = done to total },
                    onEvent = { role, text -> agentStream = agentStream + (role to text); markStateChanged() },
                    // 不传 onThinking/onReasoning，不在 UI 显示思考/推理过程
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

    // ================= 节点工程化：可复现 / 变体 / 快照 / 成片 =================

    /** 节点操作菜单当前目标卡（null=关闭） */
    var nodeMenuCard by mutableStateOf<CardEntity?>(null)
        private set

    /** 节点检查器：编辑参数/换模型后原地重跑（null=关闭） */
    var nodeInspectorCard by mutableStateOf<CardEntity?>(null)
        private set

    /** 检查器里该节点类型的可选模型 */
    var nodeModels by mutableStateOf<List<ModelOptionEntity>>(emptyList())
        private set

    /** 画布快照列表（最近优先） */
    val snapshots: StateFlow<List<CanvasSnapshotEntity>> =
        db.canvasSnapshotDao().observeByConversation(conversationId).stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
        )

    /** 可选的角色/产品素材文件夹（角色节点来源） */
    val assetFolders: StateFlow<List<AssetFolderEntity>> =
        db.assetFolderDao().observeAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
        )

    /** 成片合成进行中 */
    var filmBusy by mutableStateOf(false)
        private set

    fun openNodeMenu(card: CardEntity) {
        nodeMenuCard = card
    }

    fun dismissNodeMenu() {
        nodeMenuCard = null
    }

    fun openNodeInspector(card: CardEntity) {
        nodeInspectorCard = card
        nodeMenuCard = null
        viewModelScope.launch {
            nodeModels = runCatching { router.models(card.kind) }.getOrDefault(emptyList())
        }
    }

    fun dismissNodeInspector() {
        nodeInspectorCard = null
    }

    /** 仅保存节点参数（不重跑） */
    fun saveNodeParams(card: CardEntity, edited: NodeParams) {
        viewModelScope.launch {
            runCatching {
                db.cardDao().updateParams(
                    card.id,
                    edited.copy(variantOf = card.variantOf).toJson(),
                    card.version,
                    card.variantOf,
                )
                nodeInspectorCard = null
                toast("参数已保存")
            }.onFailure { e -> lastError = e.message ?: "保存参数失败" }
            markStateChanged()
        }
    }

    /** 保存参数并立即重跑该节点（产出为变体） */
    fun rerunNodeWithParams(card: CardEntity, edited: NodeParams) {
        val t = token ?: return
        viewModelScope.launch {
            val before = captureCanvasState()
            try {
                db.cardDao().updateParams(
                    card.id,
                    edited.copy(variantOf = card.variantOf).toJson(),
                    card.version,
                    card.variantOf,
                )
                nodeInspectorCard = null
                val ok = regenerateOnce(card, t, asVariant = true, override = edited)
                if (!ok) toast("没有可复现的参数或未产出结果")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "重跑失败"
            }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 批量重跑选中的节点（仅对有留存参数的节点生效） */
    fun rerunCards(targets: List<CardEntity>) {
        val t = token ?: return
        val runnable = targets.filter {
            it.runId != "film" && !isCharacterNode(it) &&
                NodeParams.fromJson(it.paramsJson)?.prompt?.isNotBlank() == true
        }
        if (runnable.isEmpty()) {
            toast("所选节点没有可复现参数")
            return
        }
        viewModelScope.launch {
            val before = captureCanvasState()
            try {
                runnable.forEach { c -> regenerateOnce(c, t, asVariant = true) }
                toast("已重跑 ${runnable.size} 个节点")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "批量重跑失败"
            }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 批量重跑当前画布选中的节点 */
    fun rerunSelectedNodes() {
        rerunCards(cards.value.filter { it.id in selectedCanvasIds })
    }

    // ---------- 画布撤销/重做：快照式（节点行 + 关系边），覆盖移动/增删/连线/整理 ----------

    private data class CanvasState(val cards: List<CardEntity>, val links: List<CardLinkEntity>)

    private val undoStack = java.util.ArrayDeque<CanvasState>()
    private val redoStack = java.util.ArrayDeque<CanvasState>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private suspend fun captureCanvasState(): CanvasState = CanvasState(
        cards = runCatching { db.cardDao().listAllByConversation(conversationId) }.getOrDefault(emptyList()),
        links = runCatching { db.cardLinkDao().listByConversation(conversationId) }.getOrDefault(emptyList()),
    )

    /** 恢复到某画布状态：删除多出的行、REPLACE 恢复目标行、重建关系边 */
    private suspend fun applyCanvasState(state: CanvasState) {
        val currentIds = db.cardDao().listAllByConversation(conversationId).map { it.id }.toSet()
        val targetIds = state.cards.map { it.id }.toSet()
        (currentIds - targetIds).forEach { db.cardDao().hardDelete(it) }
        state.cards.forEach { db.cardDao().insert(it) }
        db.cardLinkDao().deleteByConversation(conversationId)
        state.links.forEach { db.cardLinkDao().insert(it) }
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun pushUndo(before: CanvasState, after: CanvasState) {
        if (before == after) return
        undoStack.addLast(before)
        while (undoStack.size > 20) undoStack.removeFirst()
        redoStack.clear()
        updateUndoFlags()
    }

    fun undoCanvas() {
        viewModelScope.launch {
            val target = undoStack.pollLast() ?: return@launch
            redoStack.addLast(captureCanvasState())
            applyCanvasState(target)
            clearCanvasSelection()
            updateUndoFlags()
            markStateChanged()
        }
    }

    fun redoCanvas() {
        viewModelScope.launch {
            val target = redoStack.pollLast() ?: return@launch
            undoStack.addLast(captureCanvasState())
            applyCanvasState(target)
            clearCanvasSelection()
            updateUndoFlags()
            markStateChanged()
        }
    }

    /** 判断一张卡是否为「角色节点」（绑定素材文件夹，作身份锚点参考） */
    fun isCharacterNode(card: CardEntity): Boolean =
        NodeParams.fromJson(card.paramsJson)?.characterFolderId != null

    private suspend fun nextCanvasPosition(): Pair<Float, Float> {
        val count = runCatching { db.cardDao().listByConversation(conversationId) }
            .getOrNull().orEmpty().size
        val col = 6
        return (24f + (count % col) * 170f) to (24f + (count / col) * 170f)
    }

    /** 内部：用节点留存参数重跑一次，产出标记为变体并建 parent 边。返回是否有产出。 */
    private suspend fun regenerateOnce(
        card: CardEntity,
        token: String,
        asVariant: Boolean,
        override: NodeParams? = null,
    ): Boolean {
        // 角色节点/成片节点不是「生成」节点，不能按参数重跑
        if (isCharacterNode(card) || card.runId == "film") return false
        val p = override ?: NodeParams.fromJson(card.paramsJson)
            ?: return false
        if (p.prompt.isBlank() && p.characterFolderId == null) return false
        // 上游若连了角色节点，重跑时保持身份锚点参考
        val characterRefs = db.cardLinkDao().incoming(card.id)
            .mapNotNull { db.cardDao().byId(it.fromCardId) }
            .filter { isCharacterNode(it) }
            .map { it.id }
        val characterPaths = if (characterRefs.isEmpty()) emptyList() else characterPathsFor(characterRefs)
        val req = RunRequest(
            conversationId = conversationId,
            prompt = p.prompt,
            kind = card.kind,
            modelIds = listOfNotNull(p.modelId.ifBlank { selectedModelId ?: return false }),
            count = 1,
            ratio = p.ratio,
            quality = p.quality,
            resolution = p.resolution,
            seconds = p.seconds,
            referencedAssetIds = p.referenceCardIds,
            referencedAssetPaths = characterPaths,
        )
        val run = runService.launch(token, req)
        val produced = db.cardDao().byRun(run.id)
        val newParams = p.copy(variantOf = card.id).toJson()
        produced.forEach { c ->
            db.cardDao().updateParams(c.id, newParams, (card.version + 1).coerceAtLeast(1), card.id)
            if (asVariant) {
                db.cardLinkDao().insert(
                    CardLinkEntity(id = java.util.UUID.randomUUID().toString(), fromCardId = card.id, toCardId = c.id, role = "parent"),
                )
            }
        }
        return produced.isNotEmpty()
    }

    /** 重新生成一个节点（可复现）：默认产出为变体，保留原节点可对比 */
    fun regenerateNode(card: CardEntity, asVariant: Boolean = true) {
        val t = token ?: return
        if (isCharacterNode(card) || card.runId == "film") {
            toast("该节点不是生成节点，不能按参数重跑")
            return
        }
        if (NodeParams.fromJson(card.paramsJson) == null) {
            toast("该节点没有留存生成参数（旧数据），请用底部入口重新创作")
            return
        }
        nodeMenuCard = null
        viewModelScope.launch {
            val before = captureCanvasState()
            try {
                val ok = regenerateOnce(card, t, asVariant)
                if (!ok) toast("没有可复现的参数或未产出结果")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "重新生成失败"
            }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 复制节点为变体（不重新生成，仅复制参数/媒体引用，坐标错开），供改参后单独重跑 */
    fun duplicateNodeAsVariant(card: CardEntity) {
        nodeMenuCard = null
        viewModelScope.launch {
            val before = captureCanvasState()
            runCatching {
                val (x, y) = nextCanvasPosition()
                val seq = db.cardDao().maxSequence(conversationId) + 1
                val newId = java.util.UUID.randomUUID().toString()
                val srcParams = NodeParams.fromJson(card.paramsJson) ?: NodeParams()
                db.cardDao().insert(
                    card.copy(
                        id = newId,
                        runId = "variant",
                        sequence = seq,
                        title = "${card.title} · 变体",
                        x = x,
                        y = y,
                        status = RunStatus.COMPLETED,
                        variantOf = card.id,
                        version = card.version + 1,
                        paramsJson = srcParams.copy(variantOf = card.id).toJson(),
                    ),
                )
                db.cardLinkDao().insert(
                    CardLinkEntity(id = java.util.UUID.randomUUID().toString(), fromCardId = card.id, toCardId = newId, role = "parent"),
                )
                toast("已复制为变体，可改参数后重跑")
            }.onFailure { e -> lastError = e.message ?: "复制变体失败" }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 重跑该节点及其所有下游（引用链），用于「改了上游，只重算受影响的部分」 */
    fun rerunDownstream(card: CardEntity) {
        val t = token ?: return
        nodeMenuCard = null
        viewModelScope.launch {
            val before = captureCanvasState()
            try {
                val visited = linkedSetOf<String>()
                val queue = ArrayDeque<String>()
                db.cardLinkDao().outgoing(card.id).forEach { queue.addLast(it.toCardId) }
                val order = mutableListOf<CardEntity>()
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    if (!visited.add(id)) continue
                    val c = db.cardDao().byId(id) ?: continue
                    if (c.runId != "film" && !isCharacterNode(c) &&
                        NodeParams.fromJson(c.paramsJson)?.prompt?.isNotBlank() == true
                    ) order += c
                    db.cardLinkDao().outgoing(id).forEach { queue.addLast(it.toCardId) }
                }
                if (order.isEmpty()) {
                    toast("没有可重跑的下游节点")
                    return@launch
                }
                order.forEach { c -> regenerateOnce(c, t, asVariant = true) }
                toast("已重跑 ${order.size} 个下游节点")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "重跑下游失败"
            }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 让 Agent 重做某个节点：把该节点上下文作为指令交给 Agent 重新规划执行 */
    fun redoNodeWithAgent(card: CardEntity) {
        nodeMenuCard = null
        val p = NodeParams.fromJson(card.paramsJson)
        agentInput = buildString {
            append("重做这张${labelOf(card.kind)}节点，保持整体风格与引用关系，可优化提示词后重新生成。")
            if (p?.prompt?.isNotBlank() == true) append("\n原提示词：${p.prompt}")
            append("\n节点类型：${card.kind}")
        }
        sendAgent()
    }

    /** 把某素材文件夹放为画布「角色节点」，生成时可作为身份锚点参考 */
    fun createCharacterNode(folder: AssetFolderEntity) {
        viewModelScope.launch {
            val before = captureCanvasState()
            runCatching {
                val (x, y) = nextCanvasPosition()
                val seq = db.cardDao().maxSequence(conversationId) + 1
                val params = NodeParams(
                    prompt = folder.name,
                    kind = MediaKind.IMAGE.name,
                    characterFolderId = folder.id,
                ).toJson()
                db.cardDao().insert(
                    CardEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        runId = "character",
                        conversationId = conversationId,
                        sequence = seq,
                        kind = MediaKind.IMAGE,
                        title = folder.name,
                        content = "角色/产品：${folder.name}",
                        status = RunStatus.COMPLETED,
                        x = x,
                        y = y,
                        paramsJson = params,
                    ),
                )
                toast("已放置角色节点「${folder.name}」")
            }.onFailure { e -> lastError = e.message ?: "创建角色节点失败" }
            pushUndo(before, captureCanvasState())
            markStateChanged()
        }
    }

    /** 展开角色节点绑定的素材路径（供生成作为身份参考） */
    suspend fun characterPathsFor(cardIds: List<String>): List<String> {
        val out = mutableListOf<String>()
        cardIds.forEach { id ->
            val c = db.cardDao().byId(id) ?: return@forEach
            val folderId = NodeParams.fromJson(c.paramsJson)?.characterFolderId ?: return@forEach
            db.assetDao().byFolder(folderId).mapNotNull { it.mediaPath }.forEach { out += it }
        }
        return out.distinct()
    }

    /** 保存画布快照（节点坐标 + 关系边），用于回滚/版本对比 */
    fun snapshotCanvas(label: String = "") {
        viewModelScope.launch {
            runCatching {
                val cs = db.cardDao().listByConversation(conversationId)
                val links = cs.flatMap { db.cardLinkDao().outgoing(it.id) }
                    .map { SnapshotLink(it.fromCardId, it.toCardId, it.role) }
                    .distinct()
                val payload = CanvasSnapshotPayload(
                    nodes = cs.map { SnapshotNode(it.id, it.x, it.y, it.paramsJson, it.variantOf, it.version) },
                    links = links,
                    note = label,
                )
                db.canvasSnapshotDao().insert(
                    CanvasSnapshotEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        label = label.ifBlank { "快照 ${System.currentTimeMillis() % 100000}" },
                        payloadJson = payload.toJson(),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                toast("已保存画布快照")
            }.onFailure { e -> lastError = e.message ?: "保存快照失败" }
        }
    }

    /** 回滚到某快照：恢复节点坐标/参数，并按快照重建关系边 */
    fun restoreSnapshot(snapshot: CanvasSnapshotEntity) {
        viewModelScope.launch {
            val undoBefore = captureCanvasState()
            try {
                val payload = CanvasSnapshotPayload.fromJson(snapshot.payloadJson) ?: return@launch
                val existing = db.cardDao().listByConversation(conversationId).associateBy { it.id }
                payload.nodes.forEach { n ->
                    if (existing.containsKey(n.id)) {
                        db.cardDao().restorePosition(n.id, n.x, n.y)
                        db.cardDao().restoreNodeMeta(n.id, n.paramsJson, n.variantOf, n.version)
                    }
                }
                db.cardLinkDao().deleteByConversation(conversationId)
                payload.links.forEach { l ->
                    if (existing.containsKey(l.fromCardId) && existing.containsKey(l.toCardId)) {
                        db.cardLinkDao().insert(
                            CardLinkEntity(id = java.util.UUID.randomUUID().toString(), fromCardId = l.fromCardId, toCardId = l.toCardId, role = l.role),
                        )
                    }
                }
                clearCanvasSelection()
                toast("已回滚到快照")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "回滚快照失败"
            }
            pushUndo(undoBefore, captureCanvasState())
            markStateChanged()
        }
    }

    fun deleteSnapshot(snapshot: CanvasSnapshotEntity) {
        viewModelScope.launch { runCatching { db.canvasSnapshotDao().deleteById(snapshot.id) } }
    }

    /** 画布 → 成片：把选中的视频节点按 x 坐标从左到右拼接为一条视频并落卡 */
    fun composeFilm(videoCards: List<CardEntity>) {
        if (filmBusy) return
        val ordered = videoCards.filter { it.kind == MediaKind.VIDEO }.sortedBy { it.x }
        val inputs = ordered.mapNotNull { it.mediaPath }.map { File(it) }.filter { it.exists() }
        if (inputs.size < 2) {
            toast("至少需要 2 段可用的视频节点才能合成成片")
            return
        }
        filmBusy = true
        viewModelScope.launch {
            val before = captureCanvasState()
            try {
                val out = File(media.mediaRoot(), "film_${System.currentTimeMillis()}.mp4")
                val ok = withContext(Dispatchers.IO) { Mp4Concatenator.concat(inputs, out) }
                if (!ok || !out.exists()) {
                    toast("成片合成失败（分段编码参数可能不一致）")
                    return@launch
                }
                val title = "成片 ${System.currentTimeMillis() % 100000}"
                val asset = media.persistFromLocal(conversationId, "film", out, title, MediaKind.VIDEO)
                out.delete()
                val seq = db.cardDao().maxSequence(conversationId) + 1
                val (x, y) = nextCanvasPosition()
                val cardId = java.util.UUID.randomUUID().toString()
                val params = NodeParams(
                    prompt = "成片（${inputs.size} 段）",
                    kind = MediaKind.VIDEO.name,
                    referenceCardIds = ordered.map { it.id },
                ).toJson()
                db.cardDao().insert(
                    CardEntity(
                        id = cardId,
                        runId = "film",
                        conversationId = conversationId,
                        sequence = seq,
                        kind = MediaKind.VIDEO,
                        title = title,
                        content = "由 ${inputs.size} 段视频合成",
                        mediaPath = asset.mediaPath,
                        previewPath = asset.previewPath,
                        status = RunStatus.COMPLETED,
                        x = x,
                        y = y,
                        paramsJson = params,
                    ),
                )
                ordered.forEach { vc ->
                    db.cardLinkDao().insert(
                        CardLinkEntity(id = java.util.UUID.randomUUID().toString(), fromCardId = vc.id, toCardId = cardId, role = "reference"),
                    )
                }
                toast("成片已生成（${inputs.size} 段）")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.message ?: "成片合成失败"
            } finally {
                filmBusy = false
            }
            pushUndo(before, captureCanvasState())
            markStateChanged()
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
            val before = captureCanvasState()
            runCatching {
                db.cardLinkDao().deleteForCard(card.id)
                db.cardDao().softDelete(card.id)
            }.onFailure { e -> lastError = e.message ?: "删除卡片失败" }
            pushUndo(before, captureCanvasState())
        }
    }

    /** 在工作界面建立卡片间参考关系（from → to，role=reference；缺省避免重复） */
    fun linkReference(fromCardId: String, toCardId: String) {
        viewModelScope.launch {
            val before = captureCanvasState()
            runCatching {
                val from = db.cardDao().byId(fromCardId) ?: return@launch
                val to = db.cardDao().byId(toCardId) ?: return@launch
                // 类型合法性防线：不符合「可参考类型矩阵」的连接直接拒绝；角色节点可作任意生成的身份参考
                if (!isCharacterNode(from) && !canServeAsReference(from.kind, to.kind)) {
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
            pushUndo(before, captureCanvasState())
        }
    }

    /** 在工作界面移除卡片间参考关系 */
    fun unlinkReference(fromCardId: String, toCardId: String) {
        viewModelScope.launch {
            val before = captureCanvasState()
            runCatching {
                db.cardLinkDao().delete(fromCardId, toCardId, "reference")
            }.onFailure { e -> lastError = e.message ?: "移除关系失败" }
            pushUndo(before, captureCanvasState())
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

    companion object {
        /** 单次生成可携带的参考图数量上限（与 RunService 上送截断一致） */
        const val MAX_REF_ASSETS = 8
    }
}