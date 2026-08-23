package com.tapcreator.app.backend.agent

import com.tapcreator.app.backend.model.ModelRouter
import com.tapcreator.app.backend.providers.ProviderGateway
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.backend.service.ContentService
import com.tapcreator.app.backend.service.RunService
import com.tapcreator.app.data.db.AppDatabase
import androidx.room.withTransaction
import com.tapcreator.app.data.db.AssetFolderEntity
import com.tapcreator.app.data.db.AgentSkillEntity
import com.tapcreator.app.data.db.AgentRunSkillEntity
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.CardLinkEntity
import com.tapcreator.app.data.db.MemoryEntity
import com.tapcreator.app.data.db.MessageEntity
import com.tapcreator.app.data.db.TraceEntity
import com.tapcreator.app.data.model.AgentAction
import com.tapcreator.app.data.model.AgentTool
import com.tapcreator.app.data.model.Channel
import com.tapcreator.app.data.model.ChannelSecrets
import com.tapcreator.app.data.model.ChatMessage
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.ModelOption
import com.tapcreator.app.data.model.RunRequest
import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.data.model.ChatResponse
import com.tapcreator.app.data.model.ThinkingLevel
import com.tapcreator.app.data.model.ToolCall
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 真正的自主 Agent（ReAct Loop）：
 * 以文本模型为大脑，反复「思考→行动→观察」，直至达成用户诉求或显式 finish。
 *  - 工具调用：generate（文本/图片/视频/音频，经 RunService 落为卡片）、卡片读写/改删、
 *    素材库整理（移动/删除素材、建/删文件夹 + ContentService 引用感知删除）、memorize、recall
 *  - 卡片编排：每步生成一张卡并写入 reference 边，关系图形成完整链路
 *  - 记忆系统：会话级持久化记忆记忆 + recall 检索，注入上下文供后续回合使用
 *  - 视频生成：generate(GENERATE_VIDEO)，可用 reference 引用上文图片/视频续写
 * Loop 由 loop-turn 上限与协程取消保护，避免死循环与卡死。
 */
@Singleton
class AgentBrain @Inject constructor(
    private val router: ModelRouter,
    private val channels: ChannelRepository,
    private val gateway: ProviderGateway,
    private val runService: RunService,
    private val contentService: ContentService,
    private val http: OkHttpClient,
    private val db: AppDatabase,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun systemPrompt(cinematic: Boolean, style: String = ""): String {
        val styleBlock = if (cinematic) """
        3. 视频/图片提示词优化（MadStory 电影级分镜）：描述"谁在动、怎么动、镜头怎么跟、第几秒发生什么、声音是什么"。按 核心创意→时间轴节奏(按秒分段)→视觉构图(景别/机位)→动态运镜(一种运镜)→光影细节→声音与合成 组织；避免文本字幕/水印/变脸/过度抖动/物理穿帮。
        """.trimIndent() else ""
        val personalStyle = if (style.isNotBlank()) """
【个人风格偏好】在所有创作中始终贯彻我的偏好：
$style
""".trimIndent() else ""
        return """
你是 tapcreator 的全自动创作 Agent。你以"思考→行动→观察"循环反复推进，直到完成用户诉求。
每次只输出一个 JSON 动作对象，不要任何其它文字、不要 markdown 代码围栏。

可选动作：
${AgentToolRegistry.toPromptTable()}
生成 tip：完成"图片→视频续写"用时序参考 reference 或 reference_card；固定人物/产品形象用 reference_folder；引用前先明细用 read_card / list_cards / list_assets 确认完整 id 后原样复制。
$personalStyle
$styleBlock
规则：
- 用户只是提问、无需生成时，直接用 finish。
- P2-6：复杂/多步诉求，先在第一条输出中给出 1~3 步简明规划（纳入该条 assistant 输出内容），再逐步执行，避免遗漏步骤。
- 需要多步时一步步来：先执行第一步并观察结果，再决定下一步（如先图后视频，用 reference 延续同画面）。
- 一次只做一件事，输出必须是一个合法 JSON 对象，action 的值必须取自上方工具名。
    """.trimIndent()
    }

    /**
     * 运行一次 Agent Loop。
     * @return 本轮所有产出卡片的 id（供上层统计/高亮）。
     */
    suspend fun run(
        token: String,
        conversationId: String,
        userPrompt: String,
        modelId: String? = null,
        referenceCardIds: List<String> = emptyList(),
        referenceAssetPaths: List<String> = emptyList(),
        cinematic: Boolean = true,
        memoryEnabled: Boolean = true,
        style: String = "",
        maxTurns: Int = 12,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onEvent: (role: String, text: String) -> Unit = { _, _ -> },
        onThinking: (String) -> Unit = {},
        // 推理过程流：reasoning_content 单独回调，UI 显示为「推理」流，与思考(content token)流区分
        onReasoning: (String) -> Unit = {},
        // 推理深度级别（用户面板手动选择）：透传给 gateway.agentChatStream
        thinkingLevel: ThinkingLevel = ThinkingLevel.NONE,
    ): List<String> {
        val modelEntity = modelId?.let { id -> router.models(MediaKind.TEXT).firstOrNull { it.id == id } }
            ?: router.defaultModel(MediaKind.TEXT)
            ?: throw TapcreatorException("未配置文本模型，Agent 不可用，请先到设置添加", "NO_MODEL")
        val channelEntity = channels.byId(modelEntity.channelId)
            ?: throw TapcreatorException("Agent 文本渠道不存在", "CHANNEL_MISSING")
        val channel = channels.toDomain(channelEntity)
        val secrets = channels.secrets(channelEntity.id)
        channels.assertReady(channel, secrets)
        val model = router.toDomain(modelEntity)

        // 自进化：本回合注入的技能（供复盘时统计赢率：被用→成功与否）
        val injectedSkillIds = db.skillDao().active(8).filter { it.state == "active" }.map { it.id }

        // 本轮产出编号(1起) -> 卡片 id，用于 reference 解析
        val produced = HashMap<Int, String>()
        val outputCards = mutableListOf<String>()
        // 本次执行批次 id：区分同会话的多次 Agent 运行（agent_trace 按 runId 分组）
        val runId = "run_${UUID.randomUUID()}"
        // 记录本批次注入了哪些技能：用户随后给出满意/重做反馈时，据此精确归因赢率（而非模型自评拍板）。
        // 仅清理超过有效期（30 分钟）的悬空批次，保留近期尚未被反馈消费的批次——
        // 避免同会话并发运行时误清掉另一批次的注入记录（前置条件：Agent 任务同会话串行，见 ChatViewModel.agentBusy 串行化）。
        val staleBefore = System.currentTimeMillis() - 30 * 60 * 1000L
        db.runSkillDao().clearForConversation(conversationId, staleBefore)
        if (injectedSkillIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.runSkillDao().insertAll(
                injectedSkillIds.map { sid ->
                    AgentRunSkillEntity(
                        conversationId = conversationId,
                        runId = runId,
                        skillId = sid,
                        createdAt = now,
                    )
                }
            )
        }
        // 工作记忆：user/assistant 交替，观察结果以 user 角色回灌，保证 OpenAI 兼容
        val trace = mutableListOf<ChatMessage>()
        trace += ChatMessage("user", "用户诉求：$userPrompt")

        var finished = false
        // 已因预算/超时等非「模型主动 finish」原因强制终止：此后的 finished 不应再触发 reflexion
        var forcedStop = false
        // finish 收尾总结只写一次，避免 reflexion 往返时堆叠重复 assistant 消息
        var summaryWritten = false
        var turn = 0
        // P0：动作恢复预算——解析/未知动作连续失败达上限即停止，避免吃光 turn 预算空转
        var recoveries = 0
        val ACTION_RECOVERY_BUDGET = 3
        // P0：reflexion——模型 finish 后可自检数轮，复盘产出是否达成，可再修正再收尾
        var reflexions = 0
        val REFLEXION_TURNS = 2
        // P0：结构化工具调用 schema（供文本大脑）；上游不支持时由调用处回退纯文本
        val toolsJson = AgentToolRegistry.toFunctionSchemas()
        // P1：整体时间预算 + 单工具超时（毫秒）
        val startedAt = System.currentTimeMillis()
        val MAX_RUN_MS = 180_000L
        val TOOL_TIMEOUT_MS = 20_000L
        // 识图校验：多模态模型回读产物图/视频关键帧的自检超时与结论截断长度
        val VISION_TIMEOUT_MS = 30_000L
        val VISION_RESULT_LIMIT = 300
        val visionCapable = model.capabilities.contains("vision")
        // 流式输出游标：把每轮新增的「思考/动作/观察」实时推给 UI（自增去重）；同时落盘 agent_trace 便于复盘
        var emittedTrace = 0
        val emitTrace: suspend () -> Unit = {
            while (emittedTrace < trace.size) {
                val msg = trace[emittedTrace]
                onEvent(msg.role, msg.content)
                runCatching {
                    db.traceDao().insert(
                        TraceEntity(
                            // 全 UUID，避免跨次运行时撞主键；runId 隔离不同批次
                            id = "tr_${UUID.randomUUID()}",
                            conversationId = conversationId,
                            runId = runId,
                            turn = turn,
                            role = msg.role,
                            content = msg.content,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                emittedTrace++
            }
        }
        while (turn < maxTurns && !finished) {
            turn++
            // P2 上下文压缩：trace 超过阈值时压缩旧消息，保持上下文有界
            if (trace.size > MEMORY_WINDOW * 2) {
                compactMessages(conversationId, trace)
            }
            if (System.currentTimeMillis() - startedAt > MAX_RUN_MS) {
                emitTrace()
                writeAssistantSummary(conversationId, "Agent 执行超过时间预算，已终止本轮（本轮产出 ${outputCards.size} 张卡片）。可继续再发指令。")
                summaryWritten = true
                break
            }
            onProgress(turn, maxTurns)
            emitTrace()

            try {
            val messages = buildList {
                add(ChatMessage("system", systemPrompt(cinematic, style)))
                buildContext(conversationId, memoryEnabled)?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
                addAll(trace.takeLast(MEMORY_WINDOW))
            }
            val response = try {
                // P2-7：SSE 流式读取大脑输出，逐 token 推到 UI；上游不支持 tools/流式时回退
                gateway.agentChatStream(channel, secrets, model, messages, toolsJson, thinkingLevel, onThinking, onReasoning)
            } catch (e: TapcreatorException) {
                // 429 限流：不立即回退非流式（会双倍限流），退避后重试流式；连续限流则终止
                if (e.message?.contains("429") == true || e.message?.contains("rpm", ignoreCase = true) == true ||
                    e.message?.contains("exhausted", ignoreCase = true) == true
                ) {
                    if (recoveries >= ACTION_RECOVERY_BUDGET) {
                        emitTrace()
                        writeAssistantSummary(conversationId, "上游限流（RPM 耗尽），已终止本轮。请稍后重试或更换模型。")
                        summaryWritten = true
                        break
                    }
                    recoveries++
                    trace += ChatMessage("user", "[限流] 上游返回 429（${e.message}）。等待 5 秒后重试。")
                    emitTrace()
                    delay(5000L)
                    continue
                }
                // 非 429 的 4xx：回退到非流式纯文本模式再试一次；仍失败则回灌
                try {
                    val fallbackText = gateway.agentChat(channel, secrets, model, messages, null)
                    ChatResponse(text = fallbackText)
                } catch (e2: TapcreatorException) {
                    trace += ChatMessage("user", "[工具异常] 大脑调用失败：${e2.message}。请重新输出一个动作。")
                    continue
                }
            }
            // 解析 action：优先 tool_calls 结构化输出，其次文本 JSON 解析兜底
            var action: AgentAction? = null
            val actionText: String
            if (response.toolCalls.isNotEmpty()) {
                val tc = response.toolCalls.first()
                actionText = buildString {
                    append("{\"action\":\"${tc.name}\"")
                    tc.args.forEach { (k, v) ->
                        val vStr = when {
                            v is kotlinx.serialization.json.JsonPrimitive -> when {
                                v.isString -> "\"${v.content}\""
                                else -> v.content
                            }
                            v is kotlinx.serialization.json.JsonNull -> "null"
                            else -> v.toString()
                        }
                        append(",\"$k\":$vStr")
                    }
                    append("}")
                }
                action = parseAction(actionText)
                if (action == null) {
                    trace += ChatMessage("user", "[工具错误] 工具调用「${tc.name}」参数无法解析，跳过。")
                    continue
                }
                // 模型输出文本（如有）作 assistant 消息记录
                if (response.text.isNotBlank()) {
                    trace += ChatMessage("assistant", response.text)
                }
            } else {
                val raw = response.text
                actionText = extractJsonObject(stripFence(raw)) ?: raw.trim()
                action = parseAction(actionText)
            }

            if (action == null) {
                recoveries++
                if (recoveries >= ACTION_RECOVERY_BUDGET) {
                    emitTrace()
                    writeAssistantSummary(conversationId, "Agent 连续多次无法产出可执行动作（解析失败），已终止本轮。请重试或切换模型。")
                    summaryWritten = true
                    break
                }
                trace += ChatMessage("user", "[解析失败] 你的输出无法解析为 JSON 动作，请只输出一个动作对象，例如 {\"action\":\"finish\",\"summary\":\"...\"}。")
                continue
            }
            recoveries = 0
            trace += ChatMessage("assistant", actionText)

            when (action.action) {
                "generate" -> {
                    val kind = kindOfTool(action.tool)
                    if (kind == null) {
                        trace += ChatMessage("user", "[工具错误] generate 动作缺少 tool 字段或值无法识别（收到：${action.tool ?: "（空）"}）。请补全 tool 字段，取值必须为 GENERATE_TEXT / GENERATE_IMAGE / GENERATE_VIDEO / GENERATE_AUDIO 之一。")
                        continue
                    }
                    if (action.prompt.isNullOrBlank()) {
                        trace += ChatMessage("user", "[工具错误] generate 必须提供 prompt。")
                        continue
                    }
                    // 参考矩阵校验：对 本轮产出引用(reference) / 本会话卡片引用(reference_card) 统一按矩阵过滤，并回灌不合规项
                    val usedRefIds = mutableListOf<String>()
                    var invalidRef: String? = null
                    action.reference?.let { idx ->
                        val refId = produced[idx]
                        when {
                            refId == null ->
                                invalidRef = "reference=$idx 对应的本轮产出不存在，请先产出该编号或在有效范围内引用"
                            else -> {
                                val card = db.cardDao().byId(refId)
                                when {
                                    card == null || card.conversationId != conversationId ->
                                        invalidRef = "reference=$idx 对应产出卡不存在"
                                    !canUseReference(card.kind, kind) ->
                                        invalidRef = "生成$kind 不能引用本轮产出$idx（${card.kind} 卡，参考矩阵：图可不引视频；视频可引图像/视频）"
                                    else -> usedRefIds += refId
                                }
                            }
                        }
                    }
                    action.reference_card?.let { id ->
                        val card = db.cardDao().byId(id)
                        when {
                            card == null || card.conversationId != conversationId ->
                                invalidRef = "reference_card 不存在或不属于本会话"
                            !canUseReference(card.kind, kind) ->
                                invalidRef = "生成$kind 不能引用${card.kind} 卡（参考矩阵：图可不引视频；视频可引图像/视频）。"
                            else -> usedRefIds += id
                        }
                    }
                    // 调用方（UI）选中的参考卡一律再做一次参考矩阵过滤，保证与是否 agent 执行无关、规则统一
                    val callerUsable = referenceCardIds.filter { id ->
                        runCatching {
                            val c = db.cardDao().byId(id)
                            c != null && c.conversationId == conversationId && canUseReference(c.kind, kind)
                        }.getOrDefault(false)
                    }
                    val droppedCaller = referenceCardIds.size - callerUsable.size
                    if (invalidRef != null) {
                        trace += ChatMessage("user", "[工具错误] $invalidRef 已忽略，请重新输出动作。")
                        continue
                    }
                    if (droppedCaller > 0) {
                        trace += ChatMessage("user", "[观察] 已忽略 $droppedCaller 个不合参考矩阵的调用方参考卡（生成$kind 时）。")
                    }
                    // 解析「文件夹」身份参考：按文件夹名取其中的可参考素材路径
                    val folderAssetPaths = resolveFolderReference(action.reference_folder)
                    val run = runService.launch(
                        token,
                        RunRequest(
                            conversationId = conversationId,
                            prompt = action.prompt,
                            kind = kind,
                            count = 1,
                            ratio = action.ratio?.takeIf { it.isNotBlank() },
                            quality = action.quality?.takeIf { it.isNotBlank() },
                            resolution = action.resolution?.takeIf { it.isNotBlank() },
                            seconds = if (kind == MediaKind.VIDEO) action.seconds else null,
                            referencedAssetIds = usedRefIds + callerUsable,
                            referencedAssetPaths = referenceAssetPaths + folderAssetPaths,
                            motion = action.motion,
                            cfgScale = action.cfgScale,
                        )
                    )
                    val card = db.cardDao().byRun(run.id).lastOrNull()
                    if (card != null) {
                        // 仅成功产出时递增序号，保证 produced 编号单调、reference 指向稳定
                        val n = produced.size + 1
                        produced[n] = card.id
                        outputCards += card.id
                        trace += ChatMessage("user", "[观察] 已生成第${n}张${kind.name}卡「${card.title}」。")
                        // P-识图校验：文本模型具备视觉能力时，把产出的图/视频关键帧回喂给多模态模型自检；
                        // 无视觉能力（visionCapable=false）的模型直接跳过观察，仅记录上面的文字产物描述。
                        if (visionCapable && kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
                            val dataUri = producedMediaDataUri(card, kind)
                            if (dataUri != null) {
                                val verdict = runCatching {
                                    withTimeout(VISION_TIMEOUT_MS) {
                                        gateway.visionChat(
                                            channel, secrets, model, dataUri,
                                            "这是本轮生成的第${n}张${kind.name}卡，原始要求：${action.prompt}。" +
                                                "请核对产物是否达标，指出与要求的明显偏差，并用一行给出「达标/不达标」结论。",
                                        )
                                    }
                                }.getOrNull()
                                if (!verdict.isNullOrBlank()) {
                                    trace += ChatMessage("user", "[观察] 识图校验：${verdict.trim().take(VISION_RESULT_LIMIT)}")
                                } else {
                                    trace += ChatMessage("user", "[观察] 识图校验超时/失败，跳过本次自检。")
                                }
                            }
                        }
                        // 记忆关键产出，便于后续回合直接引用
                        if (memoryEnabled) memorize(conversationId, "产出了第${n}张${kind.name}卡，标题：${card.title}", turn)
                    } else {
                        trace += ChatMessage("user", "[观察] 生成完成但未得到卡片，请尝试下一步或 finish。")
                    }
                }
                "memorize" -> {
                    val content = action.memory?.takeIf { it.isNotBlank() }
                    if (content == null) {
                        trace += ChatMessage("user", "[工具错误] memorize 需提供 memory 内容。")
                    } else if (!memoryEnabled) {
                        trace += ChatMessage("user", "[观察] 记忆系统已关闭，本次忽略记忆请求。")
                    } else {
                        memorize(conversationId, content, turn)
                        trace += ChatMessage("user", "[观察] 已记忆。")
                    }
                }
                "recall" -> {
                    if (!memoryEnabled) {
                        trace += ChatMessage("user", "[观察] 记忆系统已关闭，无记忆可召回。")
                    } else {
                    val keyword = action.memory?.takeIf { it.isNotBlank() } ?: ""
                    // P2-9：按关键词命中打分排序，而非仅一次 LIKE
                    val pool = db.memoryDao().recentByConversation(conversationId, 80)
                    val hits = scoreMemories(keyword, pool).take(10)
                    if (hits.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 未回忆起相关内容。")
                    } else {
                        trace += ChatMessage("user", "[观察] 回忆到：\n" + hits.joinToString("\n") { "- ${it.content}" })
                    }
                    }
                }
                "list_cards" -> {
                    val cards = db.cardDao().listByConversation(conversationId)
                    if (cards.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 本会话还没有卡片，可先 generate 创建。")
                    } else {
                        // P1-5：限制返回条数，避免海量卡片撑爆上下文
                        val cap = 30
                        val shown = cards.take(cap)
                        val summary = shown.joinToString("\n") { c ->
                            val body = c.content.take(40).let { if (it.length == 40) "$it…" else it }
                            val noContent = if (c.content.isBlank()) "（无文本，媒体卡）" else body
                            "  [${c.kind}] id=${c.id}「${c.title}」$noContent"
                        }
                        val truncated = if (cards.size > cap) "\n…（共 ${cards.size} 张，仅示前 $cap 张）" else ""
                        trace += ChatMessage("user", "[观察] 本会话 ${cards.size} 张卡片（请原样复制完整 id 用于 read/update/delete）：\n$summary$truncated")
                    }
                }
                "read_card" -> {
                    val card = action.card_id?.let { db.cardDao().byId(it) }
                        ?.takeIf { it.conversationId == conversationId }
                    if (card == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该卡片，请先 list_cards 获取 card_id。")
                    } else {
                        val body = if (card.content.isBlank()) "（媒体卡，无文本内容）" else card.content
                        trace += ChatMessage("user", "[观察] 卡片「${card.title}」（${card.kind}）：\n$body")
                    }
                }
                "update_card" -> {
                    val card = action.card_id?.let { db.cardDao().byId(it) }
                        ?.takeIf { it.conversationId == conversationId }
                    if (card == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该卡片，无法修改。")
                        continue
                    }
                    val newTitle = action.title?.takeIf { it.isNotBlank() }
                    val newContent = action.content
                    if (newTitle == null && newContent == null) {
                        trace += ChatMessage("user", "[工具错误] update_card 需提供 title 或 content。")
                        continue
                    }
                    db.cardDao().update(
                        card.copy(
                            title = newTitle ?: card.title,
                            content = newContent?.trim() ?: card.content,
                        )
                    )
                    trace += ChatMessage("user", "[观察] 已更新卡片「${newTitle ?: card.title}」。")
                }
                "update_asset" -> {
                    val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                    val newTitle = action.title?.takeIf { it.isNotBlank() }
                    if (asset == null || newTitle == null) {
                        trace += ChatMessage("user", "[工具错误] update_asset 需提供有效的 asset_id 与非空 title。可先 list_assets 获取 id。")
                        continue
                    }
                    db.assetDao().update(asset.copy(title = newTitle))
                    trace += ChatMessage("user", "[观察] 已将素材标题改为「$newTitle」。")
                }
                "delete_card" -> {
                    val card = action.card_id?.let { db.cardDao().byId(it) }
                        ?.takeIf { it.conversationId == conversationId }
                    if (card == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该卡片，无法删除。")
                        continue
                    }
                    when (contentService.deleteCard(card.id)) {
                        com.tapcreator.app.backend.service.DeleteResult.DELETED ->
                            trace += ChatMessage("user", "[观察] 已删除卡片「${card.title}」（连同媒体文件）。")
                        com.tapcreator.app.backend.service.DeleteResult.HIDDEN_KEPT ->
                            trace += ChatMessage("user", "[观察] 卡片「${card.title}」被其它卡引用，已隐藏并保留数据/文件。")
                        else -> trace += ChatMessage("user", "[观察] 卡片已不存在，无需删除。")
                    }
                }
                "delete_asset" -> {
                    val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                    if (asset == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该素材。可先 list_assets 获取 id。")
                        continue
                    }
                    // 安全守卫：正被卡片使用则拒绝，避免悬空引用
                    val paths = listOfNotNull(asset.mediaPath, asset.previewPath)
                    val inUse = paths.any { db.cardDao().byMediaPath(it) != null }
                    if (inUse) {
                        trace += ChatMessage("user", "[工具错误] 该素材正被卡片使用，无法删除。请先删除相关卡片。")
                        continue
                    }
                    db.assetDao().deleteById(asset.id)
                    // 去重后删除二进制文件（mediaPath 与 previewPath 可能指向同一文件，删除幂等）
                    paths.distinct().forEach { runCatching { File(it).delete() } }
                    trace += ChatMessage("user", "[观察] 已删除素材「${asset.title.ifBlank { asset.id.take(8) }}」（连同二进制文件）。")
                }
                "move_asset" -> {
                    val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                    if (asset == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该素材。可先 list_assets 获取 id。")
                        continue
                    }
                    var target = action.folder_id?.let { id -> db.assetFolderDao().byId(id) }
                    if (target == null && !action.folder_name.isNullOrBlank()) {
                        target = db.assetFolderDao().all().firstOrNull {
                            it.name.equals(action.folder_name, ignoreCase = true) ||
                                it.name.contains(action.folder_name!!.trim(), ignoreCase = true)
                        }
                    }
                    // folder_name 给予了但无匹配 → 自动创建文件夹归类
                    if (target == null && !action.folder_name.isNullOrBlank()) {
                        val created = AssetFolderEntity(
                            id = UUID.randomUUID().toString(),
                            name = action.folder_name!!.trim(),
                            kind = "folder",
                            createdAt = System.currentTimeMillis(),
                        )
                        db.assetFolderDao().insert(created)
                        target = created
                    }
                    db.assetDao().setFolder(asset.id, target?.id)
                    trace += ChatMessage("user", "[观察] 素材已移至${target?.let { "「${it.name}」" } ?: "未归档"}。")
                }
                "create_folder" -> {
                    val name = action.folder_name?.trim().takeIf { it.isNullOrBlank()?.not() == true }
                    val kind = action.folder_kind?.trim() ?: "folder"
                    if (name == null || kind !in setOf("folder", "role", "product")) {
                        trace += ChatMessage("user", "[工具错误] create_folder 需提供非空 folder_name，且 folder_kind 只能是 folder/role/product。")
                        continue
                    }
                    if (db.assetFolderDao().byName(name) != null) {
                        trace += ChatMessage("user", "[观察] 文件夹「$name」已存在。")
                    } else {
                        db.assetFolderDao().insert(
                            AssetFolderEntity(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                kind = kind,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                        trace += ChatMessage("user", "[观察] 已建文件夹「$name」（$kind）。")
                    }
                }
                "delete_folder" -> {
                    val folder = action.folder_id?.let { id -> db.assetFolderDao().byId(id) }
                        ?: action.folder_name?.let { n ->
                            db.assetFolderDao().all().firstOrNull {
                                it.name.equals(n.trim(), ignoreCase = true) || it.name.contains(n.trim(), ignoreCase = true)
                            }
                        }
                    if (folder == null) {
                        trace += ChatMessage("user", "[工具错误] 未找到该文件夹。可先 list_assets 获取 folder_id。")
                        continue
                    }
                    db.assetDao().byFolder(folder.id).forEach { db.assetDao().setFolder(it.id, null) }
                    db.assetFolderDao().deleteById(folder.id)
                    trace += ChatMessage("user", "[观察] 已删除文件夹「${folder.name}」（其内素材移回未归档）。")
                }
                "list_assets" -> {
                    val folders = db.assetFolderDao().all()
                    if (folders.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 素材库暂无文件夹。可 create_folder 新建。")
                    } else {
                        // P1-5：限制列出条目数，避免海量素材撑爆上下文
                        val MAX_ASSET_ROWS = 80
                        var rows = 0
                        var omitted = 0
                        val lines = buildList {
                            folders.forEach { f ->
                                val assets = db.assetDao().byFolder(f.id)
                                if (assets.isEmpty()) {
                                    if (rows < MAX_ASSET_ROWS) { add("f.「${f.name}」folder_id=${f.id}(kind=${f.kind})：空"); rows++ } else omitted++
                                } else {
                                    add("f.「${f.name}」folder_id=${f.id}(kind=${f.kind})：")
                                    assets.forEach { a ->
                                        if (rows < MAX_ASSET_ROWS) {
                                            add("   - [asset_id=${a.id}] ${a.kind} ${a.title.ifBlank { "(无标题)" }}")
                                            rows++
                                        } else omitted++
                                    }
                                }
                            }
                            val unassigned = db.assetDao().all().filter { it.folderId == null }
                            if (unassigned.isNotEmpty()) {
                                add("未归档：")
                                unassigned.forEach { a ->
                                    if (rows < MAX_ASSET_ROWS) {
                                        add("   - [asset_id=${a.id}] ${a.kind} ${a.title.ifBlank { "(无标题)" }}")
                                        rows++
                                    } else omitted++
                                }
                            }
                        }
                        val tail = if (omitted > 0) "\n…（另有 $omitted 项被省略，可追问 Agent 关注特定文件夹/素材）" else ""
                        trace += ChatMessage("user", "[观察] 素材库（请原样复制完整 id 用于 move/delete/update）：\n" + lines.joinToString("\n") + tail)
                    }
                }
                "web_search" -> {
                    val q = action.query?.trim()?.takeIf { it.isNotBlank() }
                    if (q == null) {
                        trace += ChatMessage("user", "[工具错误] web_search 需提供 query。")
                        continue
                    }
                    // P1-6：单工具超时，超时按可恢复错误回灌，不中断整轮
                    val outcome = try {
                        withTimeout(TOOL_TIMEOUT_MS) { webSearch(q) }
                    } catch (e: TimeoutCancellationException) {
                        trace += ChatMessage("user", "[工具错误] 联网搜索「$q」超时（${TOOL_TIMEOUT_MS / 1000}s）。可换关键词或改用 fetch_url。")
                        continue
                    }
                    when {
                        outcome.error != null ->
                            trace += ChatMessage("user", "[工具错误] 联网搜索「$q」失败：${outcome.error}。可换关键词重试或改用 fetch_url。")
                        outcome.items.isEmpty() ->
                            trace += ChatMessage("user", "[观察] 未搜索到「$q」的相关结果。")
                        else -> trace += ChatMessage("user", "[观察] 搜索「$q」：\n" + outcome.items.joinToString("\n") {
                            "- ${it.first}\n  ${it.second}"
                        })
                    }
                }
                "fetch_url" -> {
                    val u = action.url?.trim() ?: ""
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        trace += ChatMessage("user", "[工具错误] fetch_url 仅支持 http/https 链接。")
                        continue
                    }
                    // P1-6：单工具超时，超时按可恢复错误回灌
                    val text = try {
                        withTimeout(TOOL_TIMEOUT_MS) { fetchWebText(u) }
                    } catch (e: TimeoutCancellationException) {
                        trace += ChatMessage("user", "[工具错误] 读取网页 $u 超时（${TOOL_TIMEOUT_MS / 1000}s）。")
                        continue
                    }
                    if (text.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 未能读取 $u（无正文或访问失败）。")
                    } else {
                        trace += ChatMessage("user", "[观察] $u 内容：\n" + text.take(1200) + if (text.length > 1200) "\n…（已截断）" else "")
                    }
                }
                "finish" -> {
                    finished = true
                    if (!summaryWritten) {
                        writeAssistantSummary(conversationId, action.summary?.takeIf { it.isNotBlank() } ?: "已完成。本轮共产出 ${outputCards.size} 张卡片。")
                        summaryWritten = true
                    }
                }
                "list_runs" -> {
                    val traces = db.traceDao().byConversation(conversationId, 300).filter { it.runId.isNotBlank() }
                    if (traces.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 本会话还没有可回溯的 Agent 执行批次（执行完成后即有）。")
                    } else {
                        val lines = traces.groupBy { it.runId }
                            .toList()
                            .sortedByDescending { (_, tl) -> tl.minOfOrNull { it.createdAt } ?: 0L }
                            .take(10)
                            .mapIndexed { idx, (rid, tl) ->
                                val turns = tl.maxOfOrNull { it.turn } ?: 0
                                val created = tl.minOfOrNull { it.createdAt } ?: 0L
                                "  [run_id=$rid] 轮数=$turns（${timeHhMm(created)}）"
                            }
                        trace += ChatMessage("user", "[观察] 本会话此前执行批次（请原样复制 run_id 用于 read_trace）：\n" + lines.joinToString("\n"))
                    }
                }
                "read_trace" -> {
                    val rid = action.run_id?.takeIf { it.isNotBlank() }
                    if (rid == null) {
                        trace += ChatMessage("user", "[工具错误] read_trace 需提供 run_id（先用 list_runs 获取）。")
                        continue
                    }
                    val tl = db.traceDao().byRun(rid, 200)
                    if (tl.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 未找到 run_id=$rid 的轨迹。")
                    } else {
                        val text = tl.joinToString("\n") { "(${it.turn})${if (it.role == "user") "观察" else it.role}: ${it.content.take(300)}" }
                        val capped = text.take(2500)
                        trace += ChatMessage("user", "[观察] run_id=$rid 轨迹：\n$capped" + if (text.length > 2500) "\n…（已截断）" else "")
                    }
                }
                "write_skill" -> {
                    val content = action.content?.trim()?.takeIf { it.isNotBlank() }
                    if (content == null) {
                        trace += ChatMessage("user", "[工具错误] write_skill 需提供 content 技能内容。")
                        continue
                    }
                    if (content.length > 200) {
                        trace += ChatMessage("user", "[工具错误] 技能内容过长（≤200 字），已忽略。可精简后再 write_skill。")
                        continue
                    }
                    val risk = action.risk?.trim()?.lowercase()?.takeIf { it in setOf("low", "high") } ?: "low"
                    val dup = db.skillDao().findByContent(content)
                    if (dup != null) {
                        trace += ChatMessage("user", "[观察] 该技能已存在（id=${dup.id}），无需重复沉淀。")
                    } else {
                        val now = System.currentTimeMillis()
                        db.skillDao().insert(
                            AgentSkillEntity(
                                id = "sk_${UUID.randomUUID()}",
                                category = action.category?.trim()?.takeIf { it.isNotBlank() } ?: "general",
                                content = content,
                                risk = risk,
                                state = if (risk == "high") "pending" else "active",
                                conversationId = null,
                                sourceRunId = runId,
                                confidence = if (risk == "high") 0.3f else 0.6f,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        trace += ChatMessage(
                            "user",
                            if (risk == "high")
                                "[观察] 已沉淀为待审批技能（id=${content.take(12)}…），用户审批后生效。"
                            else
                                "[观察] 已沉淀为生效技能「${content.take(40)}」，后续回合自动遵循。",
                        )
                    }
                }
                "read_skills" -> {
                    val active = db.skillDao().active(20)
                    val pending = db.skillDao().pendingList()
                    if (active.isEmpty() && pending.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 技能库为空，尚无已沉淀经验。可在复盘后 write_skill 沉淀。")
                    } else {
                        val sb = StringBuilder()
                        if (active.isNotEmpty()) {
                            sb.appendLine("已生效技能：")
                            active.take(15).forEach { s ->
                                sb.appendLine("  - [${s.category}/${riskOf(s.risk)}] id=${s.id} ${s.content}")
                            }
                        }
                        if (pending.isNotEmpty()) {
                            sb.appendLine("待审批（${pending.size} 条，high 风险）：")
                            pending.take(10).forEach { s -> sb.appendLine("  - id=${s.id} ${s.content}") }
                        }
                        trace += ChatMessage("user", "[观察] 技能库：\n" + sb.toString().trimEnd())
                    }
                }
                "retire_skill" -> {
                    val id = action.skill_id?.takeIf { it.isNotBlank() }
                    if (id == null) {
                        trace += ChatMessage("user", "[工具错误] retire_skill 需提供 skill_id（read_skills 获取）。")
                        continue
                    }
                    val s = db.skillDao().byId(id)
                    if (s == null) {
                        trace += ChatMessage("user", "[观察] 未找到技能 id=$id。")
                    } else {
                        db.skillDao().setState(id, "retired", System.currentTimeMillis())
                        trace += ChatMessage("user", "[观察] 已撤销技能「${s.content.take(40)}」。")
                    }
                }
                "link_cards" -> {
                    val from = action.from_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == conversationId }
                    val to = action.to_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == conversationId }
                    val role = action.role?.trim()?.takeIf { it == "parent" } ?: "reference"
                    if (from == null || to == null || from.id == to.id) {
                        trace += ChatMessage("user", "[工具错误] link_cards 需提供本会话内且不同的 from_card_id/to_card_id（可先 list_cards 获取）。")
                        continue
                    }
                    if (db.cardLinkDao().exists(from.id, to.id, role)) {
                        trace += ChatMessage("user", "[观察] 「${from.title}」→「${to.title}」引用关系已存在。")
                    } else {
                        db.cardLinkDao().insert(
                            CardLinkEntity(
                                id = UUID.randomUUID().toString(),
                                fromCardId = from.id,
                                toCardId = to.id,
                                role = role,
                            )
                        )
                        trace += ChatMessage("user", "[观察] 已建立「${from.title}」→「${to.title}」引用关系。")
                    }
                }
                "unlink_cards" -> {
                    val from = action.from_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == conversationId }
                    val to = action.to_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == conversationId }
                    val role = action.role?.trim()?.takeIf { it == "parent" } ?: "reference"
                    if (from == null || to == null) {
                        trace += ChatMessage("user", "[工具错误] unlink_cards 需提供本会话内有效的 from_card_id/to_card_id。")
                        continue
                    }
                    db.cardLinkDao().delete(from.id, to.id, role)
                    trace += ChatMessage("user", "[观察] 已解除「${from.title}」→「${to.title}」的引用关系（若存在）。")
                }
                "layout_canvas" -> {
                    val cards = db.cardDao().listByConversation(conversationId)
                    if (cards.isEmpty()) {
                        trace += ChatMessage("user", "[观察] 本会话还没有卡片可整理。")
                    } else {
                        cards.forEachIndexed { i, c ->
                            val x = (i % 4) * 190f + 24f
                            val y = (i / 4) * 200f + 24f
                            db.cardDao().updatePosition(c.id, x, y)
                        }
                        trace += ChatMessage("user", "[观察] 已把 ${cards.size} 个节点整理为网格布局。")
                    }
                }
                "configure_resolution" -> {
                    val rawName = action.model_name?.trim()?.takeIf { it.isNotBlank() }
                    if (rawName == null) {
                        trace += ChatMessage("user", "[工具错误] configure_resolution 需提供 model_name。")
                        continue
                    }
                    val modelName = rawName // 经非空守卫，供下面的 lambda 引用（Kotlin 智能转换失效时的别名）
                    val wantSet = action.resolutions?.trim()?.takeIf { it.isNotBlank() }
                    val matched = db.modelOptionDao().all().filter {
                        it.name.equals(modelName, ignoreCase = true) || it.name.lowercase().contains(modelName.lowercase())
                    }
                    val current = matched.filter { it.resolutions.isNotBlank() }
                        .flatMap { it.resolutions.split(",", "，").map { r -> r.trim() }.filter { r -> r.isNotEmpty() } }
                        .distinct()
                    val declared = if (current.isNotEmpty()) current.joinToString(",") else channels.resolveResolutions(modelName)
                    if (wantSet == null) {
                        // 仅查询
                        val hit = if (current.isNotEmpty()) "该模型已配置" else "内置知识库"
                        trace += ChatMessage(
                            "user",
                            if (declared.isEmpty())
                                "[观察] 模型「$modelName」未匹配到已知分辨率，可先用 web_search/fetch_url 调研官方文档，再通过 configure_resolution 携带 resolutions 写入。"
                            else
                                "[观察] 模型「$modelName」（$hit）可选分辨率：$declared"
                        )
                    } else {
                        // 设置：写入所有匹配到的模型条目
                        val parsed = wantSet.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        if (parsed.isEmpty()) {
                            trace += ChatMessage("user", "[工具错误] resolutions 需提供合法的分辨率列表。")
                            continue
                        }
                        if (matched.isEmpty()) {
                            trace += ChatMessage("user", "[工具错误] 未找到名为「$modelName」的模型配置，无法写入（可在渠道模型目录中确认该模型名后重试）。")
                        } else {
                            var updated = 0
                            matched.forEach { m ->
                                db.modelOptionDao().insert(m.copy(resolutions = parsed.joinToString(",")))
                                updated++
                            }
                            trace += ChatMessage("user", "[观察] 已为模型「$modelName」配置可选分辨率：${parsed.joinToString(",")}（写入 $updated 个匹配条目）。")
                        }
                    }
                }
                else -> {
                    recoveries++
                    if (recoveries >= ACTION_RECOVERY_BUDGET) {
                        emitTrace()
                        writeAssistantSummary(conversationId, "Agent 连续输出未知动作，已终止本轮。请重试或切换模型。")
                        finished = true
                        forcedStop = true
                        summaryWritten = true
                    } else {
                        trace += ChatMessage("user", "[动作错误] 未知动作：${action.action}。请仅使用系统提示「可用工具」表中的动作名。")
                    }
                }
            }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 单轮发生未预期异常：回灌给模型换个方案，避免整轮 Agent 直接失败
                trace += ChatMessage("user", "[循环异常] ${e.javaClass.simpleName}：${e.message ?: "未知错误"}。请改正后用一个动作继续，或 finish。")
            }
            // P0：reflexion——模型主动 finish 且尚未自检够时，补一轮「自检」让其复盘；预算/超时等强制终止不复活
            if (finished && !forcedStop && reflexions < REFLEXION_TURNS && turn < maxTurns) {
                trace += ChatMessage("user", "[自检] 请对照用户诉求「$userPrompt」与已有产出复盘：若已充分达成，请输出 finish（附最终小结）；若存在明确缺陷且你已有清晰下一步，请输出那个修正动作（勿重复做同一件事）。")
                finished = false
                reflexions++
            }
        }
        emitTrace()
        if (!finished && !summaryWritten) {
            // 达到 turn 上限仍未 finish：强制收尾，避免无限循环
            writeAssistantSummary(conversationId, "已执行到本轮上限（$maxTurns 轮），共产出 ${outputCards.size} 张卡片。如需继续请再发指令。")
        }
        // P3：自进化——finish 后把本轮经验提炼为技能落库（仅沉淀候选；成败赢率待用户反馈驱动）
        runCatching {
            withTimeout(40_000L) {
                reflectAndLearn(conversationId, userPrompt, outputCards, runId, channel, secrets, model)
            }
        }
        return outputCards
    }

    /** 写入一条会话记忆 */
    private suspend fun memorize(conversationId: String, content: String, turn: Int) {
        db.memoryDao().insert(
            MemoryEntity(
                id = "mem_${conversationId}_${turn}_${UUID.randomUUID()}",
                conversationId = conversationId,
                content = content,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** P2-9：记忆检索打分——按查询关键词命中次数排序；无关键词时回退最近记忆 */
    private fun scoreMemories(keyword: String, pool: List<MemoryEntity>): List<MemoryEntity> {
        val kws = keyword
            .split(Regex("\\s+|[\u3000,，。；;、:：]"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (kws.isEmpty()) return pool
        val ranked = pool.map { m ->
            m to kws.count { m.content.lowercase().contains(it) }
        }.filter { (_, hits) -> hits > 0 }
            .sortedByDescending { (_, hits) -> hits }
            .map { (m, _) -> m }
        return if (ranked.isEmpty()) pool.take(3) else ranked
    }

    /** 写入一条 assistant 收尾消息，让用户在对话流看到汇报 */
    private suspend fun writeAssistantSummary(conversationId: String, summary: String) {
        db.withTransaction {
            // sequence 取号 + 插入放同一事务，避免与手动生成并发时取到相同序号导致时间线乱序
            val seq = db.messageDao().maxSequence(conversationId) + 1
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sequence = seq,
                    role = "assistant",
                    content = summary,
                    kind = MediaKind.TEXT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 动态上下文：注入本会话最近的卡片索引与记忆（记忆可按设置关闭），供大脑参考 */
    private suspend fun buildContext(conversationId: String, memoryEnabled: Boolean = true): String? {
        val cards = db.cardDao().listByConversation(conversationId).takeLast(10)
        val mems = if (memoryEnabled) db.memoryDao().recentByConversation(conversationId, 20) else emptyList()
        val skills = db.skillDao().active(8).filter { it.state == "active" }
        // 素材库文件夹（角色/产品/普通），供 generate 用 reference_folder 引用固定身份/画面
        val folders = db.assetFolderDao().all()
            .map { f ->
                val assets = db.assetDao().byFolder(f.id)
                val kinds = assets.map { it.kind.name.lowercase() }.distinct().ifEmpty { listOf("空") }
                "「${f.name}」（${f.kind}：${kinds.joinToString("/")} 素材）"
            }
        if (cards.isEmpty() && mems.isEmpty() && folders.isEmpty() && skills.isEmpty()) return null
        return buildString {
            if (folders.isNotEmpty()) {
                appendLine("素材库文件夹（可在 generate 用 reference_folder 传这些文件夹名来固定形象/画面）：")
                folders.forEach { appendLine("  - $it") }
            }
            if (cards.isNotEmpty()) {
                appendLine("本会话已有卡片索引（仅示意内容；如需引用请用 read_card 取完整 id，再以 reference_card 引用）：")
                cards.forEachIndexed { i, c -> appendLine("  第${i + 1}卡 [${c.kind}] ${c.title}") }
            }
            if (skills.isNotEmpty()) {
                appendLine("已习得的全局创作技能（自进化沉淀，应主动遵循）：")
                skills.forEach { s -> appendLine("  - [${s.category}] ${s.content}") }
            }
            if (mems.isNotEmpty()) {
                appendLine("已记忆的关键信息：")
                mems.forEach { appendLine("  - ${it.content}") }
            }
        }
    }

    /** 按「角色/产品」文件夹名解析图片/视频素材的 mediaPath，作为身份一致性参考 */
    private suspend fun resolveFolderReference(folderName: String?): List<String> {
        if (folderName.isNullOrBlank()) return emptyList()
        val folder = db.assetFolderDao().all().firstOrNull {
            it.name.equals(folderName.trim(), ignoreCase = true) ||
                it.name.contains(folderName.trim(), ignoreCase = true)
        } ?: return emptyList()
        return db.assetDao().byFolder(folder.id)
            .filter { it.kind == MediaKind.IMAGE || it.kind == MediaKind.VIDEO }
            .mapNotNull { it.mediaPath }
    }

    private fun kindOfTool(raw: String?): MediaKind? =
        when (AgentTool.from(raw ?: "")) {
            AgentTool.GENERATE_TEXT -> MediaKind.TEXT
            AgentTool.GENERATE_IMAGE -> MediaKind.IMAGE
            AgentTool.GENERATE_VIDEO -> MediaKind.VIDEO
            AgentTool.GENERATE_AUDIO -> MediaKind.AUDIO
            null -> null
        }

    /** 参考矩阵：目标为图像时仅接受图像来源；目标为视频等其他类型时可接受图像或视频来源 */
    private fun canUseReference(sourceKind: MediaKind, targetKind: MediaKind): Boolean =
        when (targetKind) {
            MediaKind.IMAGE -> sourceKind == MediaKind.IMAGE
            else -> sourceKind == MediaKind.IMAGE || sourceKind == MediaKind.VIDEO
        }

    /** 联网搜索的结果：error 非空表示失败原因（用于回灌给 Agent，而非吞成无结果）；items 为标题+链接 */
    private class SearchOutcome(val error: String?, val items: List<Pair<String, String>>)

    /** 联网搜索（国内可访问的必应无 key 端点，轻量解析标题与链接；循环读取截止到上限，不依赖单次 socket read） */
    private suspend fun webSearch(query: String): SearchOutcome = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            var code = 0
            var html = ""
            http.newCall(webRequest(endpoint)).execute().use { resp ->
                code = resp.code
                html = readCapped(resp, 1024 * 1024)
            }
            if (code !in 200..299) SearchOutcome("搜索服务返回 HTTP $code", emptyList())
            else if (html.isBlank()) SearchOutcome("搜索服务返回空页，可能被反爬拦截", emptyList())
            else {
                val results = mutableListOf<Pair<String, String>>()
                for (block in html.split("class=\"b_algo\"").drop(1)) {
                    if (results.size >= 6) break
                    val title = Regex("<h2[^>]*>\\s*<a[^>]*>(.*?)</a>\\s*</h2>").find(block)
                        ?.groupValues?.getOrNull(1)?.let { decodeTitle(it) }
                    val href = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"").find(block)
                        ?.groupValues?.getOrNull(1)
                    if (title != null && href?.startsWith("http") == true) results += title to href
                }
                SearchOutcome(null, results)
            }
        } catch (t: Throwable) {
            SearchOutcome(t.message ?: "未知网络错误", emptyList())
        }
    }

    /** 读取响应体到上限字节，循环读完避免 socket 分块导致截断；整块解码规避多字节字符被切断 */
    private fun readCapped(resp: okhttp3.Response, cap: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        resp.body?.byteStream()?.use { ins ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            var n = ins.read(buf)
            while (n > 0 && total < cap) {
                bytes.write(buf, 0, n)
                total += n
                n = ins.read(buf)
            }
        }
        return bytes.toString("UTF-8")
    }

    /** 去除标题内残留的 <strong>/<b> 等标签并解码 HTML 实体 */
    private fun decodeTitle(raw: String): String =
        decodeEntities(Regex("<[^>]+>").replace(raw, " ").replace(Regex("\\s+"), " ").trim())

    /** 抓取网页正文并转纯文本（循环读取上限 512KB，防止超大页 OOM；供 Agent 参考链接内容） */
    private suspend fun fetchWebText(url: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(webRequest(url)).execute().use { resp ->
                if (resp.code !in 200..299) "" else htmlToText(readCapped(resp, 512 * 1024))
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun webRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36")
        .build()

    private fun htmlToText(html: String): String = decodeEntities(
        html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|<li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[\\s\\u00a0]+"), " ")
            .trim()
    )

    private fun decodeEntities(s: String) = s
        .replace("&amp;", "&").replace("&nbsp;", " ")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&apos;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&ndash;", "–")
        .replace("&mdash;", "—").replace("&hellip;", "…").replace("&ensp;", " ")
        .replace("&emsp;", " ")

    private fun parseAction(raw: String): AgentAction? = runCatching {
        json.decodeFromString<AgentAction>(raw)
    }.getOrNull()

    private fun stripFence(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim().removePrefix("json").trim()
        }
        return cleaned
    }

    /** 从混合文本中提取「第一个合法 JSON 对象」子串；找不到则返回 null。用于容忍模型输出的多余前后缀/注释。 */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** P2 上下文压缩：当 trace 过长时，将最早的消息合并为一条摘要，减少 token 消耗 */
    private fun compactMessages(conversationId: String, trace: MutableList<ChatMessage>) {
        val head = trace.take(MEMORY_WINDOW)
        val tail = trace.drop(MEMORY_WINDOW)
        if (head.size < 2) return
        // 把最早的 user/assistant 交替对压缩为一条摘要
        val summary = head.joinToString(" | ") { m ->
            when (m.role) {
                "user" -> "用户：${m.content.take(80)}"
                "assistant" -> "助手：${m.content.take(80)}"
                else -> ""
            }
        }
        trace.clear()
        trace.add(ChatMessage("system", "[上下文压缩] 以下为早期对话摘要：$summary"))
        trace.addAll(tail)
    }
    /** P3 自进化：本轮执行完毕后，让文本大脑做一次「诉求 vs 产出 vs 轨迹」复盘，
     *  把可复用的启发式沉淀为技能落库（仅提炼候选；成败赢率不由模型自评拍板，改由用户反馈驱动）。
     *  全程容错，复盘失败不影响主流程返回。 */
    private suspend fun reflectAndLearn(
        conversationId: String,
        userPrompt: String,
        outputCards: List<String>,
        runId: String,
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
    ) {
        val traceHead = db.traceDao().byConversation(conversationId, 80)
        val messages = buildList {
            add(
                ChatMessage(
                    "system",
                    "你是创作 Agent 的复盘员。阅读【诉求】【产出】【轨迹片段】三段，判断本轮创作是否成功、有哪些可复用经验。只输出一个 JSON 对象，不要任何其它文字。\n" +
                        "JSON 结构：{\"success\": true/false, \"assessment\": \"一句话成败定性\", " +
                        "\"skills\":[{\"content\":\"一句可执行启发式≤200字\",\"category\":\"generate/flow/reference/folder/toolfix/layout\",\"risk\":\"low或high\"}]}\n" +
                        "规则：1) skills 最多 3 条，只沉淀确实通用、可复用、防止重复踩坑的启发式；2) 涉及删除/改写用户预期数据的风险标 high，普通偏好/流程标 low；3) 无有价值经验则 skills 留空数组。",
                )
            )
            add(ChatMessage("user", buildString {
                appendLine("诉求：$userPrompt")
                appendLine("产出卡片数：${outputCards.size}")
                val cardNames = outputCards.mapNotNull { db.cardDao().byId(it) }
                    .joinToString("；") { "${it.title}[${it.kind}]" }
                appendLine("产出：${if (cardNames.isEmpty()) "（无）" else cardNames}")
                if (traceHead.isNotEmpty()) {
                    appendLine("轨迹片段（末尾 ${traceHead.size} 条）：")
                    traceHead.takeLast(40).forEach { t ->
                        appendLine("  (${t.turn})${if (t.role == "user") "观察" else t.role}: ${t.content.take(120)}")
                    }
                }
            }))
        }
        // 1) 提炼技能落库
        val respBody: String = try {
            gateway.agentChat(channel, secrets, model, messages, null)
        } catch (e: Exception) {
            null
        } ?: ""
        runCatching {
            val root = json.parseToJsonElement(extractJsonObject(stripFence(respBody)) ?: "").jsonObject
            val candidates = root["skills"]?.jsonArray.orEmpty().take(3)
            for (el in candidates) {
                val obj = el.jsonObject
                val content = obj["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                    .takeIf { it.isNotBlank() && it.length <= 200 } ?: continue
                // 已存在相同内容则跳过，避免重复沉淀刷库
                if (db.skillDao().findByContent(content) != null) continue
                val risk = obj["risk"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it in setOf("low", "high") } ?: "low"
                val now = System.currentTimeMillis()
                db.skillDao().insert(
                    AgentSkillEntity(
                        id = "sk_${UUID.randomUUID()}",
                        category = obj["category"]?.jsonPrimitive?.content?.trim()
                            ?.takeIf { it.isNotBlank() && it.length <= 32 } ?: "flow",
                        content = content,
                        risk = risk,
                        state = if (risk == "high") "pending" else "active",
                        conversationId = null,
                        sourceRunId = runId,
                        confidence = if (risk == "high") 0.3f else 0.6f,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }

        // 2) 赢率统计：不由模型自评，交给 recordUserFeedback——用户对产出反馈满意/重做时精确归因
    }

    /**
     * P3 用户反馈驱动赢率：用户在 Agent 回合后的下一条消息里表达「满意/不行」时调用。
     * 仅当存在尚未消费的注入技能批次时生效，把情感精确归因到那一批技能：
     *  - 正向反馈：技能 usedCount/successCount 均 +1；
     *  - 负向反馈：仅 usedCount +1（不记成功）；
     *  - 随后按下述阈值淘汰：用满 3 次仍不足一半成功率 → 退休；
     *  - 消费完删除该批次的注入记录，避免重复统计；中立消息（无明确评价词）不消费、不改统计。
     * @return 是否消费了一次有效反馈
     */
    suspend fun recordUserFeedback(conversationId: String, userMessage: String): Boolean {
        val runId = db.runSkillDao().latestPendingRunId(conversationId) ?: return false
        val skillIds = db.runSkillDao().skillsForRun(conversationId, runId)
        if (skillIds.isEmpty()) return false
        val positive = feedbackOf(userMessage)
        if (positive == 0) return false // 中立消息/新任务：不消费，留着等真正的评价
        val now = System.currentTimeMillis()
        skillIds.forEach { db.skillDao().bumpUsed(it, now) }
        if (positive == 1) skillIds.forEach { db.skillDao().bumpSuccess(it, now) }
        retireWeak(skillIds)
        db.runSkillDao().deleteForRun(conversationId, runId)
        return true
    }

    /**
     * 从用户消息判断是否是对"上一条 Agent 产出"的评价（1=满意，-1=重做/不满，0=中立/新任务）。
     * 反误判策略：长文本或含创作指令动词的输入一律视为新任务而非评价；
     * 只认短语级评价词，刻意不收单字"对/别/不要/差/废"——它们频繁出现在普通指令里（对齐/别太亮/色差）。
     */
    private fun feedbackOf(text: String): Int {
        val s = text.trim().lowercase()
        if (s.isEmpty()) return 0
        if (s.length > 24) return 0 // 长文本是任务描述，不是评价
        // 含创作指令意图 → 新任务，即便带了评价词也当作任务，避免污染赢率
        val taskLike = listOf(
            "画", "做", "生成", "制作", "换", "加", "改", "调整", "修", "参考", "补充",
            "希望", "想要", "给我", "顺便", "继续", "背景", "配色", "色调", "颜色", "风格",
            "比例", "时长", "分辨率", "字幕", "配乐", "镜头", "台词", "语气", "人物", "位置", "方向",
        )
        if (taskLike.any { s.contains(it) }) return 0

        val negative = listOf(
            "不行", "重做", "重来", "翻车", "难看", "糟糕", "太差", "很差", "不好", "垃圾",
            "丑", "烂", "失败", "不像", "不对", "错了",
            "fail", "bad", "awful", "terrible", "ugly", "not good", "no good",
        )
        val positive = listOf(
            "满意", "不错", "很好", "太好", "很好看", "好看", "漂亮", "喜欢", "完美",
            "很棒", "棒", "赞", "给力", "就这样", "很满意",
            "good", "great", "nice", "perfect", "love", "awesome", "excellent",
        )
        val hasNeg = negative.any { s.contains(it) }
        if (hasNeg) return -1
        return if (positive.any { s.contains(it) }) 1 else 0
    }

    /** 赢率淘汰：技能用满 3 次仍不足一半成功率 → 退休，防止沉淀的无效启发式持续占用上下文 */
    private suspend fun retireWeak(skillIds: List<String>) {
        for (id in skillIds) {
            val skill = db.skillDao().byId(id) ?: continue
            if (skill.usedCount >= 3 && skill.successCount * 2 < skill.usedCount) {
                db.skillDao().setState(id, "retired", System.currentTimeMillis())
            }
        }
    }

    /** 风险等级的中文描述（read_skills 展示用） */
    private fun riskOf(r: String): String = when (r) {
        "high" -> "高"
        "low" -> "低"
        else -> "低"
    }

    /** 毫秒时间戳 → "HH:mm"（list_runs 展示用） */
    private fun timeHhMm(ts: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        return "%02d:%02d".format(h, m)
    }

    /** 读取产出卡，转成 data URI 供识图校验：
     *  图片直接读卡文件；视频提取首帧关键帧（JPEG）。失败返回 null（跳过识图）。 */
    private suspend fun producedMediaDataUri(card: CardEntity, kind: MediaKind): String? =
        withContext(Dispatchers.IO) {
            val path = card.mediaPath?.takeIf { it.isNotBlank() }
                ?: card.previewPath?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val bytes = try {
                if (kind == MediaKind.VIDEO) extractVideoFrame(path) else File(path).takeIf { it.exists() }?.readBytes()
            } catch (t: Throwable) {
                null
            } ?: return@withContext null
            val mime = if (kind == MediaKind.VIDEO) "image/jpeg" else imageMime(path)
            "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

    private fun imageMime(path: String): String = when (path.lowercase().substringAfterLast('.', "")) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /** 用 MediaMetadataRetriever 提取视频首帧并编码为 JPEG 字节（识图校验的关键帧）；失败返回 null。 */
    private fun extractVideoFrame(path: String): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bitmap = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        /** 每轮发送给模型的工作记忆条数上限（保持上下文有界） */
        private const val MEMORY_WINDOW = 20
    }
}