package com.tapcreator.app.backend.agent

import com.tapcreator.app.backend.model.ModelRouter
import com.tapcreator.app.backend.providers.ProviderGateway
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.backend.service.ContentService
import com.tapcreator.app.backend.service.RunService
import com.tapcreator.app.data.db.AppDatabase
import androidx.room.withTransaction
import com.tapcreator.app.data.db.AssetFolderEntity
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
    private val sandboxSearch: com.tapcreator.app.backend.sandbox.SandboxSearchTool,
    private val sandbox: com.tapcreator.app.backend.sandbox.PRootSandbox,
    private val skillRegistry: com.tapcreator.app.backend.skill.SkillRegistry,
    private val mcpManager: com.tapcreator.app.backend.mcp.MCPManager,
    private val rateLimiter: com.tapcreator.app.backend.RateLimiter,
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
- finish 的 summary 字段若包含引号/换行，必须用反斜杠转义，如 `{"action":"finish","summary":"已完成。\\n总共生成 2 张卡片。"}`
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
        thinkingLevel: ThinkingLevel = ThinkingLevel.AUTO,
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

        // 本轮产出编号(1起) -> 卡片 id，用于 reference 解析
        val produced = HashMap<Int, String>()
        val outputCards = mutableListOf<String>()
        // 本次执行批次 id：区分同会话的多次 Agent 运行（agent_trace 按 runId 分组）
        val runId = "run_${UUID.randomUUID()}"
        // 工作记忆：user/assistant 交替，观察结果以 user 角色回灌，保证 OpenAI 兼容
        val trace = mutableListOf<ChatMessage>()
        trace += ChatMessage("user", "用户诉求：$userPrompt")


        var turn = 0
        // P0：结构化工具调用 schema（供文本大脑）；上游不支持时由调用处回退纯文本
        val toolsJson = AgentToolRegistry.toFunctionSchemas()
        // 上游是否支持结构化 function calling：首次默认 true，收到 400 后标记 false 切纯文本 JSON 动作模式
        var toolsSupported = true
        // 防重复动作检测队列：记录最近 3 轮动作特征，连续相同则强制 finish
        val lastActions = mutableListOf<String>()
        // P1：整体时间预算 + 单工具超时（毫秒）
        val startedAt = System.currentTimeMillis()
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

        // 可变循环状态：抽出为 AgentLoopState，与下方局部容器持有同一引用，供 executeAction 共享
        val st = AgentLoopState(
            finished = false,
            forcedStop = false,
            summaryWritten = false,
            recoveries = 0,
            produced = produced,
            outputCards = outputCards,
            trace = trace,
            lastActions = lastActions,
        )
        while (turn < maxTurns && !st.finished) {
            turn++
            // 每轮重建上下文：携带当前 turn 快照与共享的 st 容器引用，供 executeAction 使用
            val ctx = AgentLoopContext(
                conversationId = conversationId,
                runId = runId,
                token = token,
                referenceCardIds = referenceCardIds,
                referenceAssetPaths = referenceAssetPaths,
                memoryEnabled = memoryEnabled,
                visionCapable = visionCapable,
                channel = channel,
                secrets = secrets,
                model = model,
                userPrompt = userPrompt,
                turn = turn,
                runService = runService,
                contentService = contentService,
                db = db,
                gateway = gateway,
                channels = channels,
                emitTrace = emitTrace,
            )
            // P2 上下文压缩：st.trace 超过阈值时压缩旧消息，保持上下文有界
            if (st.trace.size > MEMORY_WINDOW * 2) {
                compactMessages(ctx.conversationId, st.trace)
            }
            if (System.currentTimeMillis() - startedAt > MAX_RUN_MS) {
                ctx.emitTrace()
                writeAssistantSummary(ctx.conversationId, "Agent 执行超过时间预算，已终止本轮（本轮产出 ${st.outputCards.size} 张卡片）。可继续再发指令。")
                st.summaryWritten = true
                break
            }
            onProgress(turn, maxTurns)
            ctx.emitTrace()

            try {
            val messages = buildList {
                add(ChatMessage("system", systemPrompt(cinematic, style)))
                buildContext(ctx.conversationId, ctx.memoryEnabled)?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
                addAll(st.trace.takeLast(MEMORY_WINDOW))
            }
            val response = try {
                // 每分钟请求数限制：等待配额
                rateLimiter.acquire()
                // 优先发结构化 tools（function calling）；若上游不支持（400）则标记后切纯文本 JSON 动作模式
                gateway.agentChatStream(ctx.channel, ctx.secrets, ctx.model, messages, if (toolsSupported) toolsJson else null, thinkingLevel, maxTokens = 4096, onToken = onThinking, onReasoning = onReasoning)
            } catch (e: TapcreatorException) {
                when (val res = handleBrainCallFailure(e, ctx.channel, ctx.secrets, ctx.model, messages, st.recoveries, st.trace)) {
                    is BrainRetry -> {
                        if (res.notify) { st.trace += ChatMessage("user", res.message); ctx.emitTrace() }
                        st.recoveries++
                        delay(res.backoffMs)
                        continue
                    }
                    is BrainStop -> {
                        ctx.emitTrace()
                        writeAssistantSummary(ctx.conversationId, res.message)
                        st.summaryWritten = true
                        break
                    }
                    is BrainFallback -> {
                        toolsSupported = false
                        res.response
                    }
                    BrainGiveUp -> {
                        st.trace += ChatMessage("user", "[工具异常] 大脑调用失败。请重新输出一个动作。")
                        continue
                    }
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
                    st.trace += ChatMessage("user", "[工具错误] 工具调用「${tc.name}」参数无法解析，跳过。")
                    continue
                }
                // 模型输出文本（如有）作 assistant 消息记录
                if (response.text.isNotBlank()) {
                    st.trace += ChatMessage("assistant", response.text)
                }
            } else {
                val raw = response.text
                actionText = extractJsonObject(stripFence(raw)) ?: raw.trim()
                action = parseAction(actionText)
            }

            // 容错：模型可能输出含特殊字符的 finish（summary 里有引号/换行导致 JSON 解析失败）
            if (action == null) {
                val text = response.text
                if (Regex(""""action"\s*:\s*"finish"""").containsMatchIn(text)) {
                    // 用更健壮的方式提取：先找到 finish 动作块，再提取 summary
                    val finishBlock = extractJsonObject(text) ?: text
                    val summary = runCatching {
                        json.decodeFromString<AgentAction>(finishBlock).summary
                    }.getOrNull() ?: runCatching {
                        // 容错：用正则提取 summary（支持转义引号）
                        Regex(""""summary"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)
                            ?.groupValues?.getOrNull(1)
                            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()
                    }.getOrNull() ?: "已完成"
                    action = AgentAction(action = "finish", summary = summary)
                }
            }

            if (action == null) {
                st.recoveries++
                if (st.recoveries >= ACTION_RECOVERY_BUDGET) {
                    ctx.emitTrace()
                    writeAssistantSummary(ctx.conversationId, "Agent 连续多次无法产出可执行动作（解析失败），已终止本轮。请重试或切换模型。")
                    st.summaryWritten = true
                    break
                }
                st.trace += ChatMessage("user", "[解析失败] 你的输出无法解析为 JSON 动作，请只输出一个动作对象，例如 {\"action\":\"finish\",\"summary\":\"...\"}。")
                continue
            }
            st.recoveries = 0
            st.trace += ChatMessage("assistant", actionText)

            // 防重复动作检测：最近 3 轮动作特征完全相同时强制 finish，避免 Agent 反复执行同一 failed 动作
            if (detectRepeatedAction(action, st.lastActions)) {
                ctx.emitTrace()
                writeAssistantSummary(ctx.conversationId, "检测到连续重复动作（${action.action}），已终止本轮避免空转。请重试或简化指令。")
                st.summaryWritten = true
                st.finished = true
                st.forcedStop = true
                break
            }

            when (executeAction(action, st, ctx)) {
                ActionOutcome.CONTINUE -> continue
                ActionOutcome.BREAK -> break
                ActionOutcome.PROCEED -> {}
            }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 单轮发生未预期异常：回灌给模型换个方案，避免整轮 Agent 直接失败
                // 网络异常（SocketException/连接中断等）给用户友好提示而非裸异常名
                val friendlyMsg = when {
                    e is java.net.SocketException || e is java.io.IOException ->
                        "网络连接中断（${e.message ?: "连接被对端关闭"}），可能是上游超时或网络不稳定。请重试或检查网络。"
                    e.message?.contains("Software caused connection abort", ignoreCase = true) == true ->
                        "网络连接被系统中断（Software caused connection abort）。可能是网络切换或防火墙拦截，请重试。"
                    else -> "${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
                }
                st.trace += ChatMessage("user", "[循环异常] $friendlyMsg 请改正后用一个动作继续，或 finish。")
            }
        }
        emitTrace()
        if (!st.finished && !st.summaryWritten) {
            // 达到 turn 上限仍未 finish：强制收尾，避免无限循环
            writeAssistantSummary(conversationId, "已执行到本轮上限（$maxTurns 轮），共产出 ${st.outputCards.size} 张卡片。如需继续请再发指令。")
        }
        return st.outputCards
    }

    /** executeAction 的控制流结果：对原 when 块内 continue/break 的显式编码 */
    private enum class ActionOutcome { CONTINUE, BREAK, PROCEED }

    /** run() 循环的可变状态：抽出为独立类，使 executeAction 与 run() 共享同一组可变字段 */
    private data class AgentLoopState(
        var finished: Boolean = false,
        var forcedStop: Boolean = false,
        var summaryWritten: Boolean = false,
        var recoveries: Int = 0,
        var produced: HashMap<Int, String> = HashMap(),
        var outputCards: MutableList<String> = mutableListOf(),
        var trace: MutableList<ChatMessage> = mutableListOf(),
        var lastActions: MutableList<String> = mutableListOf(),
        /** 当前激活的设计 Skill prompt（apply_skill 设置，generate 时注入到 prompt 前） */
        var activeSkillPrompt: String? = null,
    )

    /** run() 内供 executeAction 使用的只读上下文：把 run() 入参与构造依赖打包传入 */
    private data class AgentLoopContext(
        val conversationId: String,
        val runId: String,
        val token: String,
        val referenceCardIds: List<String>,
        val referenceAssetPaths: List<String>,
        val memoryEnabled: Boolean,
        val visionCapable: Boolean,
        val channel: Channel,
        val secrets: ChannelSecrets,
        val model: ModelOption,
        val userPrompt: String,
        val turn: Int,
        val runService: RunService,
        val contentService: ContentService,
        val db: AppDatabase,
        val gateway: ProviderGateway,
        val channels: ChannelRepository,
        val emitTrace: suspend () -> Unit,
    )


    /**
     * 执行单个动作（原 run() 内 when(action.action) 块，抽出以规避 JVM 单方法 64KB 限制）。
     * - CONTINUE：等同原 continue，跳过本轮后续（reflexion 等），进入下一轮。
     * - BREAK：等同原 break，终止整轮循环。
     * - PROCEED：动作正常走完，继续后续流程（reflexion 自检等）。
     */
    private suspend fun executeAction(action: AgentAction, st: AgentLoopState, ctx: AgentLoopContext): ActionOutcome {
        when (action.action) {
            "generate" -> {
                val kind = kindOfTool(action.tool)
                if (kind == null) {
                    st.trace += ChatMessage("user", "[工具错误] generate 动作缺少 tool 字段或值无法识别（收到：${action.tool ?: "（空）"}）。请补全 tool 字段，取值必须为 GENERATE_TEXT / GENERATE_IMAGE / GENERATE_VIDEO / GENERATE_AUDIO 之一。")
                    return ActionOutcome.CONTINUE
                }
                if (action.prompt.isNullOrBlank()) {
                    st.trace += ChatMessage("user", "[工具错误] generate 必须提供 prompt。")
                    return ActionOutcome.CONTINUE
                }
                // 参考矩阵校验：对 本轮产出引用(reference) / 本会话卡片引用(reference_card) 统一按矩阵过滤，并回灌不合规项
                val usedRefIds = mutableListOf<String>()
                var invalidRef: String? = null
                action.reference?.let { idx ->
                    val refId = st.produced[idx]
                    when {
                        refId == null ->
                            invalidRef = "reference=$idx 对应的本轮产出不存在，请先产出该编号或在有效范围内引用"
                        else -> {
                            val card = db.cardDao().byId(refId)
                            when {
                                card == null || card.conversationId != ctx.conversationId ->
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
                        card == null || card.conversationId != ctx.conversationId ->
                            invalidRef = "reference_card 不存在或不属于本会话"
                        !canUseReference(card.kind, kind) ->
                            invalidRef = "生成$kind 不能引用${card.kind} 卡（参考矩阵：图可不引视频；视频可引图像/视频）。"
                        else -> usedRefIds += id
                    }
                }
                // 调用方（UI）选中的参考卡一律再做一次参考矩阵过滤，保证与是否 agent 执行无关、规则统一
                val callerUsable = ctx.referenceCardIds.filter { id ->
                    runCatching {
                        val c = db.cardDao().byId(id)
                        c != null && c.conversationId == ctx.conversationId && canUseReference(c.kind, kind)
                    }.getOrDefault(false)
                }
                val droppedCaller = ctx.referenceCardIds.size - callerUsable.size
                if (invalidRef != null) {
                    st.trace += ChatMessage("user", "[工具错误] $invalidRef 已忽略，请重新输出动作。")
                    return ActionOutcome.CONTINUE
                }
                if (droppedCaller > 0) {
                    st.trace += ChatMessage("user", "[观察] 已忽略 $droppedCaller 个不合参考矩阵的调用方参考卡（生成$kind 时）。")
                }
                // 解析「文件夹」身份参考：按文件夹名取其中的可参考素材路径
                val folderAssetPaths = resolveFolderReference(action.reference_folder)
                val run = try {
                    runService.launch(
                        ctx.token,
                        RunRequest(
                            conversationId = ctx.conversationId,
                            // 注入已激活的 Skill 风格指导到 prompt 前（apply_skill 设置）
                            prompt = st.activeSkillPrompt?.let { "$it\n\n${action.prompt}" } ?: action.prompt,
                            kind = kind,
                            count = 1,
                            ratio = action.ratio?.takeIf { it.isNotBlank() },
                            quality = action.quality?.takeIf { it.isNotBlank() },
                            resolution = action.resolution?.takeIf { it.isNotBlank() },
                            seconds = if (kind == MediaKind.VIDEO) action.seconds else null,
                            referencedAssetIds = usedRefIds + callerUsable,
                            referencedAssetPaths = ctx.referenceAssetPaths + folderAssetPaths,
                            motion = action.motion,
                            cfgScale = action.cfgScale,
                        )
                    )
                } catch (e: TapcreatorException) {
                    // generate 失败：回灌带错误码的可读消息，让 Agent 知道真实原因并决定下一步
                    // （换模型/换工具/finish），而非盲目重试同类失败
                    val hint = when (e.code) {
                        "NO_MODEL" -> "没有可用的${kind}模型，请在设置配置渠道后重试，或换一种生成类型"
                        "CHANNEL_NO_URL", "CHANNEL_NO_KEY" -> "渠道未就绪（${e.message}），请先在设置配置，或换一个已就绪的生成类型"
                        "AUTH_FAILED" -> "登录态失效，请重新登录"
                        "UPSTREAM_HTTP" -> "上游返回错误：${e.message}。可换模型/渠道，或精简提示词后重试"
                        else -> "生成失败（${e.code}）：${e.message}"
                    }
                    st.trace += ChatMessage("user", "[工具失败] generate($kind) 未成功：$hint。请据此调整下一步动作。")
                    return ActionOutcome.CONTINUE
                } catch (e: Exception) {
                    st.trace += ChatMessage("user", "[工具失败] generate($kind) 异常：${e.javaClass.simpleName}：${e.message ?: "未知"}。可换一种生成类型或 finish。")
                    return ActionOutcome.CONTINUE
                }
                val card = db.cardDao().byRun(run.id).lastOrNull()
                if (card != null) {
                    // 仅成功产出时递增序号，保证 st.produced 编号单调、reference 指向稳定
                    val n = st.produced.size + 1
                    st.produced[n] = card.id
                    st.outputCards += card.id
                    st.trace += ChatMessage("user", "[观察] 已生成第${n}张${kind.name}卡「${card.title}」。")
                    // P-识图校验：文本模型具备视觉能力时，把产出的图/视频关键帧回喂给多模态模型自检；
                    // 无视觉能力（visionCapable=false）的模型直接跳过观察，仅记录上面的文字产物描述。
                    if (ctx.visionCapable && kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
                        val dataUri = producedMediaDataUri(card, kind)
                        if (dataUri != null) {
                            val verdict = runCatching {
                                withTimeout(VISION_TIMEOUT_MS) {
                                    rateLimiter.acquire()
                                    gateway.visionChat(
                                        ctx.channel, ctx.secrets, ctx.model, dataUri,
                                        "这是本轮生成的第${n}张${kind.name}卡，原始要求：${action.prompt}。" +
                                            "请核对产物是否达标，指出与要求的明显偏差，并用一行给出「达标/不达标」结论。",
                                    )
                                }
                            }.getOrNull()
                            if (!verdict.isNullOrBlank()) {
                                st.trace += ChatMessage("user", "[观察] 识图校验：${verdict.trim().take(VISION_RESULT_LIMIT)}")
                            } else {
                                st.trace += ChatMessage("user", "[观察] 识图校验超时/失败，跳过本次自检。")
                            }
                        }
                    }
                    // 记忆关键产出，便于后续回合直接引用
                    if (ctx.memoryEnabled) memorize(ctx.conversationId, "产出了第${n}张${kind.name}卡，标题：${card.title}", ctx.turn)
                } else {
                    st.trace += ChatMessage("user", "[观察] 生成完成但未得到卡片，请尝试下一步或 finish。")
                }
            }
            "memorize" -> {
                val content = action.memory?.takeIf { it.isNotBlank() }
                if (content == null) {
                    st.trace += ChatMessage("user", "[工具错误] memorize 需提供 memory 内容。")
                } else if (!ctx.memoryEnabled) {
                    st.trace += ChatMessage("user", "[观察] 记忆系统已关闭，本次忽略记忆请求。")
                } else {
                    memorize(ctx.conversationId, content, ctx.turn)
                    st.trace += ChatMessage("user", "[观察] 已记忆。")
                }
            }
            "recall" -> {
                if (!ctx.memoryEnabled) {
                    st.trace += ChatMessage("user", "[观察] 记忆系统已关闭，无记忆可召回。")
                } else {
                val keyword = action.memory?.takeIf { it.isNotBlank() } ?: ""
                // P2-9：按关键词命中打分排序，而非仅一次 LIKE
                val pool = db.memoryDao().recentByConversation(ctx.conversationId, 80)
                val hits = scoreMemories(keyword, pool).take(10)
                if (hits.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 未回忆起相关内容。")
                } else {
                    st.trace += ChatMessage("user", "[观察] 回忆到：\n" + hits.joinToString("\n") { "- ${it.content}" })
                }
                }
            }
            "list_cards" -> {
                val cards = db.cardDao().listByConversation(ctx.conversationId)
                if (cards.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 本会话还没有卡片，可先 generate 创建。")
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
                    st.trace += ChatMessage("user", "[观察] 本会话 ${cards.size} 张卡片（请原样复制完整 id 用于 read/update/delete）：\n$summary$truncated")
                }
            }
            "read_card" -> {
                val card = action.card_id?.let { db.cardDao().byId(it) }
                    ?.takeIf { it.conversationId == ctx.conversationId }
                if (card == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到该卡片，请先 list_cards 获取 card_id。")
                } else {
                    val body = if (card.content.isBlank()) "（媒体卡，无文本内容）" else card.content
                    st.trace += ChatMessage("user", "[观察] 卡片「${card.title}」（${card.kind}）：\n$body")
                }
            }
            "update_card" -> {
                val card = action.card_id?.let { db.cardDao().byId(it) }
                    ?.takeIf { it.conversationId == ctx.conversationId }
                if (card == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到该卡片，无法修改。")
                    return ActionOutcome.CONTINUE
                }
                val newTitle = action.title?.takeIf { it.isNotBlank() }
                val newContent = action.content
                if (newTitle == null && newContent == null) {
                    st.trace += ChatMessage("user", "[工具错误] update_card 需提供 title 或 content。")
                    return ActionOutcome.CONTINUE
                }
                db.cardDao().update(
                    card.copy(
                        title = newTitle ?: card.title,
                        content = newContent?.trim() ?: card.content,
                    )
                )
                st.trace += ChatMessage("user", "[观察] 已更新卡片「${newTitle ?: card.title}」。")
            }
            "update_asset" -> {
                val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                val newTitle = action.title?.takeIf { it.isNotBlank() }
                if (asset == null || newTitle == null) {
                    st.trace += ChatMessage("user", "[工具错误] update_asset 需提供有效的 asset_id 与非空 title。可先 list_assets 获取 id。")
                    return ActionOutcome.CONTINUE
                }
                db.assetDao().update(asset.copy(title = newTitle))
                st.trace += ChatMessage("user", "[观察] 已将素材标题改为「$newTitle」。")
            }
            "delete_card" -> {
                val card = action.card_id?.let { db.cardDao().byId(it) }
                    ?.takeIf { it.conversationId == ctx.conversationId }
                if (card == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到该卡片，无法删除。")
                    return ActionOutcome.CONTINUE
                }
                when (contentService.deleteCard(card.id)) {
                    com.tapcreator.app.backend.service.DeleteResult.DELETED ->
                        st.trace += ChatMessage("user", "[观察] 已删除卡片「${card.title}」（连同媒体文件）。")
                    com.tapcreator.app.backend.service.DeleteResult.HIDDEN_KEPT ->
                        st.trace += ChatMessage("user", "[观察] 卡片「${card.title}」被其它卡引用，已隐藏并保留数据/文件。")
                    else -> st.trace += ChatMessage("user", "[观察] 卡片已不存在，无需删除。")
                }
            }
            "delete_asset" -> {
                val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                if (asset == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到该素材。可先 list_assets 获取 id。")
                    return ActionOutcome.CONTINUE
                }
                // 安全守卫：正被卡片使用则拒绝，避免悬空引用
                val paths = listOfNotNull(asset.mediaPath, asset.previewPath)
                val inUse = paths.any { db.cardDao().byMediaPath(it) != null }
                if (inUse) {
                    st.trace += ChatMessage("user", "[工具错误] 该素材正被卡片使用，无法删除。请先删除相关卡片。")
                    return ActionOutcome.CONTINUE
                }
                db.assetDao().deleteById(asset.id)
                // 去重后删除二进制文件（mediaPath 与 previewPath 可能指向同一文件，删除幂等）
                paths.distinct().forEach { runCatching { File(it).delete() } }
                st.trace += ChatMessage("user", "[观察] 已删除素材「${asset.title.ifBlank { asset.id.take(8) }}」（连同二进制文件）。")
            }
            "move_asset" -> {
                val asset = action.asset_id?.let { id -> db.assetDao().byId(id) }
                if (asset == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到该素材。可先 list_assets 获取 id。")
                    return ActionOutcome.CONTINUE
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
                st.trace += ChatMessage("user", "[观察] 素材已移至${target?.let { "「${it.name}」" } ?: "未归档"}。")
            }
            "create_folder" -> {
                val name = action.folder_name?.trim().takeIf { it.isNullOrBlank()?.not() == true }
                val kind = action.folder_kind?.trim() ?: "folder"
                if (name == null || kind !in setOf("folder", "role", "product")) {
                    st.trace += ChatMessage("user", "[工具错误] create_folder 需提供非空 folder_name，且 folder_kind 只能是 folder/role/product。")
                    return ActionOutcome.CONTINUE
                }
                if (db.assetFolderDao().byName(name) != null) {
                    st.trace += ChatMessage("user", "[观察] 文件夹「$name」已存在。")
                } else {
                    db.assetFolderDao().insert(
                        AssetFolderEntity(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            kind = kind,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    st.trace += ChatMessage("user", "[观察] 已建文件夹「$name」（$kind）。")
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
                    st.trace += ChatMessage("user", "[工具错误] 未找到该文件夹。可先 list_assets 获取 folder_id。")
                    return ActionOutcome.CONTINUE
                }
                db.assetDao().byFolder(folder.id).forEach { db.assetDao().setFolder(it.id, null) }
                db.assetFolderDao().deleteById(folder.id)
                st.trace += ChatMessage("user", "[观察] 已删除文件夹「${folder.name}」（其内素材移回未归档）。")
            }
            "list_assets" -> {
                val folders = db.assetFolderDao().all()
                if (folders.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 素材库暂无文件夹。可 create_folder 新建。")
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
                    st.trace += ChatMessage("user", "[观察] 素材库（请原样复制完整 id 用于 move/delete/update）：\n" + lines.joinToString("\n") + tail)
                }
            }
            "web_search" -> {
                val q = action.query?.trim()?.takeIf { it.isNotBlank() }
                if (q == null) {
                    st.trace += ChatMessage("user", "[工具错误] web_search 需提供 query。")
                    return ActionOutcome.CONTINUE
                }
                // 优先用沙箱搜索（curl+Python DuckDuckGo+BeautifulSoup，更健壮）；失败回退 OkHttp+Bing
                val sandboxResults = runCatching {
                    withTimeout(TOOL_TIMEOUT_MS) { sandboxSearch.search(q) }
                }.getOrNull()
                if (sandboxResults != null && sandboxResults.isNotEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 搜索「$q」：\n" + sandboxResults.joinToString("\n") {
                        "- ${it.title}\n  ${it.url}"
                    })
                } else {
                    // 回退：OkHttp + Bing HTML 正则（沙箱不可用或无结果时）
                    val outcome = try {
                        withTimeout(TOOL_TIMEOUT_MS) { webSearch(q) }
                    } catch (e: TimeoutCancellationException) {
                        st.trace += ChatMessage("user", "[工具错误] 联网搜索「$q」超时（${TOOL_TIMEOUT_MS / 1000}s）。可换关键词或改用 fetch_url。")
                        return ActionOutcome.CONTINUE
                    }
                    when {
                        outcome.error != null ->
                            st.trace += ChatMessage("user", "[工具错误] 联网搜索「$q」失败：${outcome.error}。可换关键词重试或改用 fetch_url。")
                        outcome.items.isEmpty() ->
                            st.trace += ChatMessage("user", "[观察] 未搜索到「$q」的相关结果。")
                        else -> st.trace += ChatMessage("user", "[观察] 搜索「$q」：\n" + outcome.items.joinToString("\n") {
                            "- ${it.first}\n  ${it.second}"
                        })
                    }
                }
            }
            "fetch_url" -> {
                val u = action.url?.trim() ?: ""
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    st.trace += ChatMessage("user", "[工具错误] fetch_url 仅支持 http/https 链接。")
                    return ActionOutcome.CONTINUE
                }
                // 优先用沙箱抓取（curl+Python 正文提取）；失败回退 OkHttp+手写 HTML 清理
                val sandboxText = runCatching {
                    withTimeout(TOOL_TIMEOUT_MS) { sandboxSearch.fetchText(u) }
                }.getOrNull()
                val text = if (!sandboxText.isNullOrBlank()) sandboxText else try {
                    withTimeout(TOOL_TIMEOUT_MS) { fetchWebText(u) }
                } catch (e: TimeoutCancellationException) {
                    st.trace += ChatMessage("user", "[工具错误] 读取网页 $u 超时（${TOOL_TIMEOUT_MS / 1000}s）。")
                    return ActionOutcome.CONTINUE
                }
                if (text.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 未能读取 $u（无正文或访问失败）。")
                } else {
                    st.trace += ChatMessage("user", "[观察] $u 内容：\n" + text.take(1200) + if (text.length > 1200) "\n…（已截断）" else "")
                }
            }
            "finish" -> {
                st.finished = true
                if (!st.summaryWritten) {
                    writeAssistantSummary(ctx.conversationId, action.summary?.takeIf { it.isNotBlank() } ?: "已完成。本轮共产出 ${st.outputCards.size} 张卡片。")
                    st.summaryWritten = true
                }
            }
            "list_runs" -> {
                val traces = db.traceDao().byConversation(ctx.conversationId, 300).filter { it.runId.isNotBlank() }
                if (traces.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 本会话还没有可回溯的 Agent 执行批次（执行完成后即有）。")
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
                    st.trace += ChatMessage("user", "[观察] 本会话此前执行批次（请原样复制 run_id 用于 read_trace）：\n" + lines.joinToString("\n"))
                }
            }
            "read_trace" -> {
                val rid = action.run_id?.takeIf { it.isNotBlank() }
                if (rid == null) {
                    st.trace += ChatMessage("user", "[工具错误] read_trace 需提供 run_id（先用 list_runs 获取）。")
                    return ActionOutcome.CONTINUE
                }
                val tl = db.traceDao().byRun(rid, 200)
                if (tl.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 未找到 run_id=$rid 的轨迹。")
                } else {
                    val text = tl.joinToString("\n") { "(${it.turn})${if (it.role == "user") "观察" else it.role}: ${it.content.take(300)}" }
                    val capped = text.take(2500)
                    st.trace += ChatMessage("user", "[观察] run_id=$rid 轨迹：\n$capped" + if (text.length > 2500) "\n…（已截断）" else "")
                }
            }
            "link_cards" -> {
                val from = action.from_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == ctx.conversationId }
                val to = action.to_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == ctx.conversationId }
                val role = action.role?.trim()?.takeIf { it == "parent" } ?: "reference"
                if (from == null || to == null || from.id == to.id) {
                    st.trace += ChatMessage("user", "[工具错误] link_cards 需提供本会话内且不同的 from_card_id/to_card_id（可先 list_cards 获取）。")
                    return ActionOutcome.CONTINUE
                }
                if (db.cardLinkDao().exists(from.id, to.id, role)) {
                    st.trace += ChatMessage("user", "[观察] 「${from.title}」→「${to.title}」引用关系已存在。")
                } else {
                    db.cardLinkDao().insert(
                        CardLinkEntity(
                            id = UUID.randomUUID().toString(),
                            fromCardId = from.id,
                            toCardId = to.id,
                            role = role,
                        )
                    )
                    st.trace += ChatMessage("user", "[观察] 已建立「${from.title}」→「${to.title}」引用关系。")
                }
            }
            "unlink_cards" -> {
                val from = action.from_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == ctx.conversationId }
                val to = action.to_card_id?.let { db.cardDao().byId(it) }?.takeIf { it.conversationId == ctx.conversationId }
                val role = action.role?.trim()?.takeIf { it == "parent" } ?: "reference"
                if (from == null || to == null) {
                    st.trace += ChatMessage("user", "[工具错误] unlink_cards 需提供本会话内有效的 from_card_id/to_card_id。")
                    return ActionOutcome.CONTINUE
                }
                db.cardLinkDao().delete(from.id, to.id, role)
                st.trace += ChatMessage("user", "[观察] 已解除「${from.title}」→「${to.title}」的引用关系（若存在）。")
            }
            "list_skills" -> {
                val skills = skillRegistry.list()
                if (skills.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 暂无可用设计 Skill。")
                } else {
                    val lines = skills.joinToString("\n") { s ->
                        val tag = if (s.builtIn) "内置" else "已安装"
                        val imgTag = if (s.requiresImage) "（需原图）" else ""
                        "  - [${s.id}] ${s.name}（${s.category}$imgTag, $tag）${s.description}"
                    }
                    st.trace += ChatMessage("user", "[观察] 可用设计 Skill（apply_skill 引用 id 使用，photo 类需在 generate 时引用原图）：\n$lines")
                }
            }
            "apply_skill" -> {
                val skill = action.skill_id?.let { skillRegistry.byId(it) }
                if (skill == null) {
                    st.trace += ChatMessage("user", "[工具错误] 未找到 Skill「${action.skill_id ?: "（空）"}」。可先 list_skills 获取。")
                    return ActionOutcome.CONTINUE
                }
                // 记录当前激活的 skill prompt，generate 时注入到 prompt 前
                st.activeSkillPrompt = skill.promptGuide
                val imgHint = if (skill.requiresImage) "；此 Skill 需提供原图，请在 generate 时用 reference/reference_card 引用" else ""
                val ratioHint = if (skill.suggestedRatios.isNotBlank()) "；建议比例：${skill.suggestedRatios}" else ""
                st.trace += ChatMessage("user", "[观察] 已应用 Skill「${skill.name}」${imgHint}${ratioHint}。后续 generate 将注入该风格指导。")
            }
            "skill_creator" -> {
                val name = action.name?.trim()?.takeIf { it.isNotBlank() }
                val cat = action.category?.trim()?.takeIf { it in setOf("photo", "poster") }
                val guide = action.prompt_guide?.trim()?.takeIf { it.isNotBlank() }
                if (name == null || cat == null || guide == null) {
                    st.trace += ChatMessage("user", "[工具错误] skill_creator 需提供 name（非空）、category（photo/poster）、prompt_guide（风格指导，非空）。")
                    return ActionOutcome.CONTINUE
                }
                skillRegistry.install(
                    com.tapcreator.app.data.model.DesignSkill(
                        id = name.lowercase().replace(" ", "-"),
                        name = name,
                        category = cat,
                        promptGuide = guide,
                        suggestedRatios = action.suggested_ratios?.trim() ?: "",
                        requiresImage = cat == "photo",
                        description = action.description?.trim() ?: "",
                        builtIn = false,
                    )
                )
                st.trace += ChatMessage("user", "[观察] 已安装 Skill「$name」（$cat）。可通过 apply_skill 应用到后续 generate。")
            }
            "uninstall_skill" -> {
                val id = action.skill_id?.trim()?.takeIf { it.isNotBlank() }
                if (id == null) {
                    st.trace += ChatMessage("user", "[工具错误] uninstall_skill 需提供 skill_id。")
                    return ActionOutcome.CONTINUE
                }
                val removed = skillRegistry.uninstall(id)
                if (removed) {
                    if (st.activeSkillPrompt != null) st.activeSkillPrompt = null // 清除可能激活的同名 skill
                    st.trace += ChatMessage("user", "[观察] 已删除 Skill「$id」。")
                } else {
                    st.trace += ChatMessage("user", "[观察] 未找到可删除的第三方 Skill「$id」（内置预设不可删）。")
                }
            }
            "shell_execute" -> {
                val cmd = action.command?.trim()?.takeIf { it.isNotBlank() }
                if (cmd == null) {
                    st.trace += ChatMessage("user", "[工具错误] shell_execute 需提供 command。")
                    return ActionOutcome.CONTINUE
                }
                val timeoutMs = (action.timeout ?: 30) * 1000L
                // 确保沙箱就绪，并尝试安装基础包（网络可达时自动安装 curl/python3/ffmpeg）
                runCatching { sandbox.ensureReady() }
                runCatching { sandbox.ensureBasePackages() }
                val result = try {
                    sandbox.exec(cmd, timeoutMs = timeoutMs)
                } catch (e: Exception) {
                    st.trace += ChatMessage("user", "[工具错误] 沙箱执行失败：${e.message}。可重试或换一种方式。")
                    return ActionOutcome.CONTINUE
                }
                if (result.isSuccess) {
                    val out = result.output.take(2000)
                    st.trace += ChatMessage("user", "[观察] 命令执行成功（exit=0）：\n$out${if (result.output.length > 2000) "\n…（已截断）" else ""}")
                } else {
                    val out = result.output.take(2000)
                    st.trace += ChatMessage("user", "[观察] 命令执行失败（exit=${result.exitCode}）：\n$out${if (result.output.length > 2000) "\n…（已截断）" else ""}")
                }
            }
            "run_script" -> {
                val content = action.script_content?.trim()?.takeIf { it.isNotBlank() }
                if (content == null) {
                    st.trace += ChatMessage("user", "[工具错误] run_script 需提供 content（脚本内容）。")
                    return ActionOutcome.CONTINUE
                }
                val lang = action.language?.trim()?.lowercase()?.takeIf { it in setOf("python", "sh") } ?: "sh"
                val ext = if (lang == "python") "py" else "sh"
                val scriptName = "agent_script_${System.nanoTime()}.$ext"
                runCatching { sandbox.ensureReady() }
                runCatching { sandbox.ensureBasePackages() }
                // 写入脚本到沙箱 media 目录
                val writeCmd = "cat > /work/media/$scriptName << 'EOF'\n$content\nEOF"
                val writeResult = sandbox.exec(writeCmd, timeoutMs = 10_000L)
                if (!writeResult.isSuccess) {
                    st.trace += ChatMessage("user", "[工具错误] 无法写入脚本文件。")
                    return ActionOutcome.CONTINUE
                }
                sandbox.exec("chmod +x /work/media/$scriptName", timeoutMs = 5_000L)
                val interpreter = if (lang == "python") "python3" else "sh"
                val result = sandbox.exec("$interpreter /work/media/$scriptName", timeoutMs = 60_000L)
                val out = result.output.take(2000)
                if (result.isSuccess) {
                    st.trace += ChatMessage("user", "[观察] 脚本执行成功（exit=0）：\n$out${if (result.output.length > 2000) "\n…（已截断）" else ""}")
                } else {
                    st.trace += ChatMessage("user", "[观察] 脚本执行失败（exit=${result.exitCode}）：\n$out${if (result.output.length > 2000) "\n…（已截断）" else ""}")
                }
            }
            "install_package" -> {
                val pkg = action.`package`?.trim()?.takeIf { it.isNotBlank() }
                if (pkg == null) {
                    st.trace += ChatMessage("user", "[工具错误] install_package 需提供 package（包名）。")
                    return ActionOutcome.CONTINUE
                }
                runCatching { sandbox.ensureReady() }
                runCatching { sandbox.ensureBasePackages() }
                val result = sandbox.installPackage(pkg)
                if (result.isSuccess) {
                    st.trace += ChatMessage("user", "[观察] 已安装包「$pkg」。可在 shell_execute 中使用。")
                } else {
                    st.trace += ChatMessage("user", "[观察] 安装包「$pkg」失败（exit=${result.exitCode}）：${result.output.take(300)}")
                }
            }
            "layout_canvas" -> {
                val cards = db.cardDao().listByConversation(ctx.conversationId)
                if (cards.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] 本会话还没有卡片可整理。")
                } else {
                    cards.forEachIndexed { i, c ->
                        val x = (i % 4) * 190f + 24f
                        val y = (i / 4) * 200f + 24f
                        db.cardDao().updatePosition(c.id, x, y)
                    }
                    st.trace += ChatMessage("user", "[观察] 已把 ${cards.size} 个节点整理为网格布局。")
                }
            }
            "configure_resolution" -> {
                val rawName = action.model_name?.trim()?.takeIf { it.isNotBlank() }
                if (rawName == null) {
                    st.trace += ChatMessage("user", "[工具错误] configure_resolution 需提供 model_name。")
                    return ActionOutcome.CONTINUE
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
                    st.trace += ChatMessage(
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
                        st.trace += ChatMessage("user", "[工具错误] resolutions 需提供合法的分辨率列表。")
                        return ActionOutcome.CONTINUE
                    }
                    if (matched.isEmpty()) {
                        st.trace += ChatMessage("user", "[工具错误] 未找到名为「$modelName」的模型配置，无法写入（可在渠道模型目录中确认该模型名后重试）。")
                    } else {
                        var updated = 0
                        matched.forEach { m ->
                            db.modelOptionDao().insert(m.copy(resolutions = parsed.joinToString(",")))
                            updated++
                        }
                        st.trace += ChatMessage("user", "[观察] 已为模型「$modelName」配置可选分辨率：${parsed.joinToString(",")}（写入 $updated 个匹配条目）。")
                    }
                }
            }
            "mcp_add_server" -> {
                val name = action.name?.trim()?.takeIf { it.isNotBlank() }
                val type = action.type?.trim()?.takeIf { it in setOf("stdio", "http") }
                if (name == null || type == null) {
                    st.trace += ChatMessage("user", "[工具错误] mcp_add_server 需提供 name（非空）和 type（stdio/http）。")
                    return ActionOutcome.CONTINUE
                }
                val server = com.tapcreator.app.backend.mcp.MCPServer(
                    name = name,
                    type = type,
                    command = if (type == "stdio") (action.command?.trim() ?: "") else "",
                    args = if (type == "stdio" && !action.args.isNullOrBlank())
                        action.args.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    else emptyList(),
                    url = if (type == "http") (action.command?.trim() ?: "") else "",
                    env = if (!action.env.isNullOrBlank())
                        runCatching { json.decodeFromString<Map<String, String>>(action.env) }.getOrDefault(emptyMap())
                    else emptyMap(),
                )
                mcpManager.addServer(server)
                st.trace += ChatMessage("user", "[观察] 已注册 MCP 服务器「$name」（$type）。可用 mcp_list_tools 查看可用工具。")
            }
            "mcp_remove_server" -> {
                val name = action.server?.trim()?.takeIf { it.isNotBlank() }
                if (name == null) {
                    st.trace += ChatMessage("user", "[工具错误] mcp_remove_server 需提供 server（服务器名称）。")
                    return ActionOutcome.CONTINUE
                }
                val removed = mcpManager.removeServer(name)
                if (removed) {
                    st.trace += ChatMessage("user", "[观察] 已删除 MCP 服务器「$name」。")
                } else {
                    st.trace += ChatMessage("user", "[观察] 未找到 MCP 服务器「$name」。")
                }
            }
            "mcp_list_tools" -> {
                val serverName = action.server?.trim()?.takeIf { it.isNotBlank() }
                if (serverName == null) {
                    st.trace += ChatMessage("user", "[工具错误] mcp_list_tools 需提供 server（服务器名称）。")
                    return ActionOutcome.CONTINUE
                }
                val tools = try {
                    mcpManager.listTools(serverName)
                } catch (e: com.tapcreator.app.data.model.TapcreatorException) {
                    st.trace += ChatMessage("user", "[工具错误] 列出 MCP 工具失败：${e.message}")
                    return ActionOutcome.CONTINUE
                }
                if (tools.isEmpty()) {
                    st.trace += ChatMessage("user", "[观察] MCP 服务器「$serverName」没有可用工具。")
                } else {
                    val lines = tools.joinToString("\n") { t ->
                        "  - [${t.name}] ${t.description.take(100)}"
                    }
                    st.trace += ChatMessage("user", "[观察] MCP 服务器「$serverName」可用工具（共 ${tools.size} 个）：\n$lines")
                }
            }
            "mcp_call_tool" -> {
                val serverName = action.server?.trim()?.takeIf { it.isNotBlank() }
                val toolName = action.tool?.trim()?.takeIf { it.isNotBlank() }
                if (serverName == null || toolName == null) {
                    st.trace += ChatMessage("user", "[工具错误] mcp_call_tool 需提供 server（服务器名称）和 tool（工具名称）。")
                    return ActionOutcome.CONTINUE
                }
                val arguments = if (!action.arguments.isNullOrBlank())
                    runCatching { json.decodeFromString<kotlinx.serialization.json.JsonObject>(action.arguments) }
                        .getOrDefault(kotlinx.serialization.json.buildJsonObject { })
                else kotlinx.serialization.json.buildJsonObject { }
                val result = try {
                    mcpManager.callTool(serverName, toolName, arguments)
                } catch (e: com.tapcreator.app.data.model.TapcreatorException) {
                    st.trace += ChatMessage("user", "[工具错误] MCP 调用失败：${e.message}")
                    return ActionOutcome.CONTINUE
                }
                val capped = result.take(2000)
                st.trace += ChatMessage("user", "[观察] MCP「$serverName/$toolName」返回：\n$capped${if (result.length > 2000) "\n…（已截断）" else ""}")
            }
            else -> {
                st.recoveries++
                if (st.recoveries >= ACTION_RECOVERY_BUDGET) {
                    ctx.emitTrace()
                    writeAssistantSummary(ctx.conversationId, "Agent 连续输出未知动作，已终止本轮。请重试或切换模型。")
                    st.finished = true
                    st.forcedStop = true
                    st.summaryWritten = true
                } else {
                    st.trace += ChatMessage("user", "[动作错误] 未知动作：${action.action}。请仅使用系统提示「可用工具」表中的动作名。")
                }
            }
        }
        return ActionOutcome.PROCEED
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
        // 清理 summary 中的转义符号和多余空白，使其可读
        val clean = summary
            .replace("\\n", "\n")      // 转义换行 → 实际换行
            .replace("\\\"", "\"")     // 转义引号 → 实际引号
            .replace("\\t", "\t")      // 转义制表符 → 实际制表符
            .replace("\\\\", "\\")     // 双反斜杠 → 单反斜杠
            .trim()
        db.withTransaction {
            // sequence 取号 + 插入放同一事务，避免与手动生成并发时取到相同序号导致时间线乱序
            val seq = db.messageDao().maxSequence(conversationId) + 1
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sequence = seq,
                    role = "assistant",
                    content = clean,
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
        // 素材库文件夹（角色/产品/普通），供 generate 用 reference_folder 引用固定身份/画面
        val folders = db.assetFolderDao().all()
            .map { f ->
                val assets = db.assetDao().byFolder(f.id)
                val kinds = assets.map { it.kind.name.lowercase() }.distinct().ifEmpty { listOf("空") }
                "「${f.name}」（${f.kind}：${kinds.joinToString("/")} 素材）"
            }
        if (cards.isEmpty() && mems.isEmpty() && folders.isEmpty()) return null
        return buildString {
            if (folders.isNotEmpty()) {
                appendLine("素材库文件夹（可在 generate 用 reference_folder 传这些文件夹名来固定形象/画面）：")
                folders.forEach { appendLine("  - $it") }
            }
            if (cards.isNotEmpty()) {
                appendLine("本会话已有卡片索引（仅示意内容；如需引用请用 read_card 取完整 id，再以 reference_card 引用）：")
                cards.forEachIndexed { i, c -> appendLine("  第${i + 1}卡 [${c.kind}] ${c.title}") }
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

    internal fun kindOfTool(raw: String?): MediaKind? =
        when (AgentTool.from(raw ?: "")) {
            AgentTool.GENERATE_TEXT -> MediaKind.TEXT
            AgentTool.GENERATE_IMAGE -> MediaKind.IMAGE
            AgentTool.GENERATE_VIDEO -> MediaKind.VIDEO
            AgentTool.GENERATE_AUDIO -> MediaKind.AUDIO
            null -> null
        }

    /** 参考矩阵：目标为图像时仅接受图像来源；目标为视频等其他类型时可接受图像或视频来源 */
    internal fun canUseReference(sourceKind: MediaKind, targetKind: MediaKind): Boolean =
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

    internal fun parseAction(raw: String): AgentAction? = runCatching {
        json.decodeFromString<AgentAction>(raw)
    }.getOrNull()

    internal fun stripFence(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim().removePrefix("json").trim()
        }
        return cleaned
    }

    /** 从混合文本中提取「第一个合法 JSON 对象」子串；找不到则返回 null。用于容忍模型输出的多余前后缀/注释。 */
    internal fun extractJsonObject(text: String): String? {
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

    /** 大脑调用失败恢复结果：限流重试 / 终止 / 降级成功 / 放弃本轮 */
private sealed interface BrainCallOutcome
/** 限流：指数退避后重试 */
private data class BrainRetry(val backoffMs: Long, val notify: Boolean, val message: String) : BrainCallOutcome
/** 限流预算耗尽：终止整轮 */
private data class BrainStop(val message: String) : BrainCallOutcome
/** 非 429 失败：降级为纯文本模式成功 */
private data class BrainFallback(val response: ChatResponse) : BrainCallOutcome
/** 降级也失败：放弃本轮，让模型重新输出 */
private object BrainGiveUp : BrainCallOutcome

/**
 * 处理大脑调用失败：
 *  - 429 限流 → 指数退避（5s→10s→20s），仅首次推消息给 UI（notify=true）
 *  - 限流预算耗尽 → 终止整轮
 *  - 非 429 的 4xx → 降级为非流式纯文本模式（不带 tools）
 *  - 降级仍失败 → 放弃本轮
 */
private suspend fun handleBrainCallFailure(
    e: TapcreatorException,
    channel: Channel,
    secrets: ChannelSecrets,
    model: ModelOption,
    messages: List<ChatMessage>,
    recoveries: Int,
    trace: MutableList<ChatMessage>,
): BrainCallOutcome {
    val rateLimited = e.message?.contains("429") == true ||
        e.message?.contains("rpm", ignoreCase = true) == true ||
        e.message?.contains("exhausted", ignoreCase = true) == true
    if (rateLimited) {
        if (recoveries >= ACTION_RECOVERY_BUDGET) {
            return BrainStop("上游限流（RPM 耗尽），已终止本轮。请稍后重试或更换模型。")
        }
        // 指数退避：5s→10s→20s，避免固定间隔在严格 RPM 上游反复撞限流
        val backoffMs = 5000L * (1L shl recoveries.coerceAtMost(2))
        // 限流消息只在首次推 UI；后续重试只更新 trace 供复盘，不刷屏
        val notify = recoveries == 0
        val message = "[限流] 上游返回 429（${e.message}）。将指数退避重试（${backoffMs / 1000}s）。"
        delay(backoffMs)
        return BrainRetry(backoffMs, notify, message)
    }
    // 非 429 的 4xx：标记上游不支持 function calling，回退到非流式纯文本模式再试一次
    return try {
        rateLimiter.acquire()
        val fallbackText = gateway.agentChat(channel, secrets, model, messages, null)
        BrainFallback(ChatResponse(text = fallbackText))
    } catch (e2: TapcreatorException) {
        BrainGiveUp
    }
}

/** 防重复动作检测：维护最近 3 轮动作特征队列，动作特征为 "action:tool:prompt前60字符"。
     *  连续 3 轮完全相同时返回 true，触发强制 finish 避免空转。 */
    internal fun detectRepeatedAction(action: AgentAction, lastActions: MutableList<String>): Boolean {
        val hash = buildString {
            append(action.action)
            if (action.tool != null) append(":").append(action.tool)
            if (action.prompt != null) append(":").append(action.prompt.take(60))
        }
        lastActions.add(hash)
        if (lastActions.size > 3) lastActions.removeAt(0)
        return lastActions.size == 3 && lastActions.toSet().size == 1
    }

    companion object {
        /** 每轮发送给模型的工作记忆条数上限（保持上下文有界） */
        private const val MEMORY_WINDOW = 20
        /** 动作恢复预算：解析/未知动作连续失败达上限即停止，避免吃光 turn 预算空转 */
        private const val ACTION_RECOVERY_BUDGET = 3
        /** 整体时间预算（毫秒） */
        private const val MAX_RUN_MS = 180_000L
        /** 单工具超时（毫秒） */
        private const val TOOL_TIMEOUT_MS = 20_000L
        /** 识图校验超时（毫秒） */
        private const val VISION_TIMEOUT_MS = 30_000L
        /** 识图校验结论截断长度 */
        private const val VISION_RESULT_LIMIT = 300
    }
}