package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.Channel
import com.tapcreator.app.data.model.ChannelSecrets
import com.tapcreator.app.data.model.ChatMessage
import com.tapcreator.app.data.model.GenerationPreferences
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.ModelOption
import com.tapcreator.app.data.model.Protocol
import com.tapcreator.app.data.model.UpstreamResult
import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.data.model.ThinkingLevel
import com.tapcreator.app.data.model.ToolCall
import com.tapcreator.app.data.model.ChatResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 上游网关：在设备内直连 AI 上游。本版落地 OpenAI 兼容协议（文本/图像）。
 * 「一次只调用一次上游创建接口」语义由 TaskExecutor 保证；OpenAI 兼容为同步结果，无需轮询。
 */
/**
 * 一段可上送的参考媒体：把素材库本地文件以 base64 data URI 形式作为多模态参考。
 * 用于「角色/产品一致」——把同一人物/产品的多视角图片/视频作为身份锚点注入生成。
 */
data class IdentityFile(val name: String, val mime: String, val base64: String) {
    val dataUri: String get() = "data:$mime;base64,$base64"
}

/** 首段视频生成的身份参考集合（角色/产品的多视角图+视频） */
data class IdentityRefs(
    val videos: List<IdentityFile> = emptyList(),
    val images: List<IdentityFile> = emptyList(),
)

@Singleton
class ProviderGateway @Inject constructor(
    private val sharedClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 复用 Hilt 注入的全局单例 client（共享连接池/超时），不再各处自建。
    private val client: OkHttpClient get() = sharedClient

    suspend fun create(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        prefs: GenerationPreferences,
    ): UpstreamResult = when (channel.protocol) {
            Protocol.OPENAI_COMPAT -> openAiCreate(channel, secrets, model, prefs)
            Protocol.MINIMAX_H3 ->
                throw TapcreatorException("上游视频模型 H3 渠道专用于视频生成，请通过视频任务使用", "KIND_UNSUPPORTED")
            Protocol.SEEDANCE, Protocol.STABLE_DIFFUSION, Protocol.GEMINI_VIDEO ->
                throw TapcreatorException("协议 ${channel.protocol} 尚未接入", "PROTOCOL_UNSUPPORTED")
        }

    /** 供视频续生器调用：带上一段尾帧续生成下一段 */
    suspend fun createVideoSegment(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        prefs: GenerationPreferences,
        continueFrame: ByteArray?,
    ): UpstreamResult =
        openAiVideo(channel, secrets, model, prefs, continueFrame)

    /**
     * 上游视频模型 H3 视频生成（异步：create → 轮询 query → 返回 CDN URL）。
     * 续写：把上一段产出的视频 CDN URL 作为 reference_video 传入，使画面与音频衔接；
     * reference_audio 可选（音色参考）。单段时长强制 [4,15]。
     * 身份参考：firstIdentityRefs 仅在首段传入——把角色/产品的多视角图/视频 base64 一起送，
     * 让首段即锁定人物/产品身份，后续段再随 reference_video 自然延续。
     */
    suspend fun 上游视频模型H3Video(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        prefs: GenerationPreferences,
        referenceVideoUrl: String?,
        firstIdentityRefs: IdentityRefs?,
        referenceAudioUrl: String?,
    ): UpstreamResult {
        val content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", prefs.prompt)
                }
            )
            firstIdentityRefs?.images?.forEach { f ->
                add(identityFilePart(f, "image_file"))
            }
            if (referenceVideoUrl != null) {
                add(
                    buildJsonObject {
                        put("type", "video_url")
                        put("video_url", buildJsonObject { put("url", referenceVideoUrl) })
                        put("role", "reference_video")
                    }
                )
            }
            firstIdentityRefs?.videos?.forEach { f ->
                add(identityFilePart(f, "video_file"))
            }
            if (referenceAudioUrl != null) {
                add(
                    buildJsonObject {
                        put("type", "audio_url")
                        put("audio_url", buildJsonObject { put("url", referenceAudioUrl) })
                        put("role", "reference_audio")
                    }
                )
            }
        }
        val body = buildJsonObject {
            put("model", model.name)
            put("content", content)
            put("resolution", normalizeResolution(model, MediaKind.VIDEO, prefs.resolution, prefs.ratio, prefs.quality) ?: if (prefs.quality == "high") "2K" else "768P")
            put("duration", (prefs.seconds ?: 5).coerceIn(4, 15))
            put("ratio", h3Ratio(prefs.ratio))
            // 分镜优化 参数透传（由 Agent 规划提供；上游若不识别会走统一错误处理）
            prefs.cfgScale?.let { put("cfg_scale", it) }
            prefs.motion?.let { put("motion", buildJsonObject { put("type", "up"); put("scale", it) }) }
        }
        val createResp = execute(channel, secrets, "/v2/video_generation", body)
        val taskId = createResp["task_id"]?.jsonPrimitive?.content
            ?: throw TapcreatorException("上游视频模型 H3 未返回 task_id", "UPSTREAM_EMPTY")
        val url = pollH3Result(channel, secrets, taskId)
        return UpstreamResult(kind = MediaKind.VIDEO, mediaUrl = url, mime = "video/mp4", upstreamId = taskId)
    }

    /** 轮询 H3 视频任务直到成功/失败/超时，返回视频下载 URL。
     *  挂起实现：每轮先 `ensureActive()` 检查协程取消，配合外层 `delay` 的取消响应，
     *  即可在用户取消时立即中断轮询，不再无谓阻塞满整个超时窗口。 */
    private suspend fun pollH3Result(channel: Channel, secrets: ChannelSecrets, taskId: String): String {
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
        while (true) {
            // 协程（用户取消时被 cancel）在此及时中断
            currentCoroutineContext().ensureActive()
            val resp = executeGet(channel, secrets, "/v2/query/video_generation/$taskId")
            val task = resp["task"]?.jsonObject ?: throw TapcreatorException("上游视频模型 H3 查询响应异常", "UPSTREAM_BAD_BODY")
            val status = task["status"]?.jsonPrimitive?.content
            when (status) {
                "succeeded", "success" -> {
                    val url = task["content"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    if (!url.isNullOrBlank()) return url
                    throw TapcreatorException("上游视频模型 H3 未返回视频地址", "UPSTREAM_EMPTY")
                }
                "failed", "fail", "error" -> {
                    val reason = task["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    throw TapcreatorException("上游视频模型 H3 生成失败${reason?.let { "：$it" } ?: ""}", "UPSTREAM")
                }
            }
            if (System.currentTimeMillis() > deadline) throw TapcreatorException("上游视频模型 H3 生成超时", "UPSTREAM_TIMEOUT")
            // delay 是协程可取消的挂起点，取消信号会在此立即触发
            delay(10_000L)
        }
    }

    private fun h3Ratio(ratio: String?): String =
        ratio?.takeIf { it in setOf("21:9", "16:9", "4:3", "1:1", "3:4", "9:16") } ?: "16:9"

    /** 把本地参考文件（base64 data URI）构造成 H3 多模态 content 片段（图片/视频用同名 structure） */
    private fun identityFilePart(f: IdentityFile, type: String): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("type", type)
            put("file_name", f.name)
            put("url", f.dataUri)
            put("file_type", f.mime)
            put("role", if (type == "video_file") "reference_video" else "reference_image")
        }

    private fun openAiCreate(channel: Channel, secrets: ChannelSecrets, model: ModelOption, prefs: GenerationPreferences): UpstreamResult {
        return when (prefs.kind) {
            MediaKind.TEXT -> openAiChat(channel, secrets, model, prefs)
            MediaKind.IMAGE -> openAiImage(channel, secrets, model, prefs)
            MediaKind.VIDEO -> openAiVideo(channel, secrets, model, prefs, continueFrame = null)
            MediaKind.AUDIO -> openAiAudio(channel, secrets, model, prefs)
            else -> throw TapcreatorException("OpenAI 兼容协议无法处理 ${prefs.kind}，请切换专用渠道", "KIND_UNSUPPORTED")
        }
    }

    /**
     * 单段视频生成。突破时长：由 VideoSequencer 传上一段尾帧(continueFrame)进来续帧，
     * 生成与上一段视觉连续的下一段，再于设备端拼接成超时长视频。
     * 端点映射 OpenAI 兼容聚合商通用的 /videos/generations；不同渠道可在 baseUrl 处接入。
     */
    private fun openAiVideo(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        prefs: GenerationPreferences,
        continueFrame: ByteArray?,
    ): UpstreamResult {
        val body = buildJsonObject {
            put("model", model.name)
            put("prompt", prefs.prompt)
            put("n", 1)
            put("duration_seconds", (prefs.seconds ?: 5).coerceIn(1, 15).toString())
            val res = normalizeResolution(model, MediaKind.VIDEO, prefs.resolution, prefs.ratio, prefs.quality)
                ?: prefs.resolution?.takeIf { !it.contains('x') }
                ?: prefs.ratio?.let(::ratioToSize)
            res?.let { put("resolution", it) }
            if (continueFrame != null) put("image", java.util.Base64.getEncoder().encodeToString(continueFrame))
        }
        val resp = execute(channel, secrets, "/videos/generations", body)
        val data = resp.jsonObject["data"]?.jsonArray ?: throw TapcreatorException("上游未返回视频", "UPSTREAM_EMPTY")
        val first = data.firstOrNull()?.jsonObject ?: throw TapcreatorException("上游未返回视频", "UPSTREAM_EMPTY")
        val url = first["url"]?.jsonPrimitive?.content
        val b64 = first["b64_json"]?.jsonPrimitive?.content
        return when {
            url != null && url.startsWith("http") -> UpstreamResult(kind = MediaKind.VIDEO, mediaUrl = url, mime = "video/mp4")
            url != null -> UpstreamResult(kind = MediaKind.VIDEO, mediaBytes = decodeB64(url), mime = "video/mp4")
            b64 != null -> UpstreamResult(kind = MediaKind.VIDEO, mediaBytes = decodeB64(b64), mime = "video/mp4")
            else -> throw TapcreatorException("上游未返回可下载视频", "UPSTREAM_EMPTY")
        }
    }

    /** 供自主 Agent 规划调用：以给定 system prompt 调文本模型，返回纯文本（方案 A：JSON 计划） */
    suspend fun plannerChat(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
            add(buildJsonObject { put("role", "user"); put("content", json.encodeToJsonElement(userPrompt)) })
        }
        val body = buildJsonObject {
            put("model", model.name)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 2048)
        }
        val resp = execute(channel, secrets, "/chat/completions", body)
        return resp.jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw TapcreatorException("规划模型未返回文本", "UPSTREAM_EMPTY")
    }

    /** 供 Agent Loop 调用：携带多轮消息(system/user/assistant 交替)调文本模型。
     * 传入 toolsJson（OpenAI function schema 数组）时走结构化工具调用，把 tool_calls 组合成
     * {action, ...args} 的 JSON 文本返回；上游不支持 tools 时由调用方回退到纯文本（传 null）。
     */
    suspend fun agentChat(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        messages: List<ChatMessage>,
        toolsJson: kotlinx.serialization.json.JsonArray? = null,
        maxTokens: Int = 4096,
    ): String {
        val arr = buildJsonArray {
            messages.forEach { m ->
                add(buildJsonObject { put("role", m.role); put("content", m.content) })
            }
        }
        val body = buildJsonObject {
            put("model", model.name)
            put("messages", arr)
            put("temperature", 0.5)
            put("max_tokens", maxTokens)
            if (toolsJson != null) {
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }
        }
        val resp = withContext(Dispatchers.IO) {
            execute(channel, secrets, "/chat/completions", body)
        }
        val message = resp.jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val toolCalls = message?.get("tool_calls")?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        if (toolCalls is kotlinx.serialization.json.JsonElement && toolCalls.jsonArray.isNotEmpty()) {
            val fn = toolCalls.jsonArray.firstOrNull()?.jsonObject?.get("function")?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
            if (name != null) {
                val argsObj = runCatching {
                    json.parseToJsonElement(fn.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject
                }.getOrNull() ?: emptyJsonObject()
                val combined = buildJsonObject {
                    put("action", name)
                    // 跳过保留键，避免上游 arguments 里的 "action"/"tool" 覆盖真实动作名
                    argsObj.forEach { (k, v) -> if (k != "action" && k != "tool") put(k, v) }
                }
                return combined.toString()
            }
        }
        return message?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw TapcreatorException("Agent 大脑未返回输出", "UPSTREAM_EMPTY")
    }

    /** P2-7：SSE 流式版 agentChat——逐 token 回调 onToken，结束时返回与 agentChat 相同的组合 JSON / 纯文本。
     * 流式可让 UI 实时看到大脑在“想什么”；末尾解析出完整动作后交给 Agent 校验执行。
     */
    suspend fun agentChatStream(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        messages: List<ChatMessage>,
        toolsJson: kotlinx.serialization.json.JsonArray? = null,
        // 推理深度级别：AUTO/NONE 不发 reasoning_effort；LOW/MEDIUM/HIGH 透传
        thinkingLevel: ThinkingLevel = ThinkingLevel.AUTO,
        maxTokens: Int = 4096,
        onToken: (String) -> Unit = {},
        // 推理过程单独流：DeepSeek-R1 / o1 等模型在 SSE delta.reasoning_content 里返回推理，
        // 与正文 content 分开，单独回调供 UI 显示「推理」流（不混入动作解析）
        onReasoning: (String) -> Unit = {},
    ): ChatResponse {
        val base = normalizeBase(channel.baseUrl)
        val body = buildJsonObject {
            put("model", model.name)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
                },
            )
            put("temperature", 0.5)
            put("max_tokens", maxTokens)
            put("stream", true)
            // 用户手动选择推理深度：NONE 不发送，LOW/MEDIUM/HIGH 透传
            // AUTO/NONE 不发 reasoning_effort；且 reasoning_effort 与 tools 不同时发（避免聚合商 400）
            if (thinkingLevel != ThinkingLevel.AUTO && thinkingLevel != ThinkingLevel.NONE && toolsJson == null) {
                put("reasoning_effort", thinkingLevel.effort)
            }
            if (toolsJson != null) {
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }
        }
        val payload = json.encodeToJsonElement(body).toString().toRequestBody("application/json".toMediaType())
        var lastHttp: Pair<Int, String>? = null
        for (url in endpointUrls(base, "/chat/completions")) {
            val builder = Request.Builder().url(url).post(payload)
            if (secrets.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${secrets.apiKey}")
            try {
                val output = withContext(Dispatchers.IO) {
                    client.newCall(builder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val text = resp.body?.string().orEmpty()
                            lastHttp = resp.code to text
                            if (resp.code != 404) throw TapcreatorException(upstreamError(resp, text, url), "UPSTREAM_HTTP")
                            null
                        } else {
                            // 上游可能不支持 SSE（国内聚合商常直接返回普通 JSON）：
                            // 按 Content-Type 分流。非 text/event-stream 时整体读 JSON 解析，
                            // 并把 content 通过 onToken 回调，让 UI 思考流实时可见。
                            val ct = resp.header("Content-Type").orEmpty()
                            val out = if (!ct.contains("text/event-stream", ignoreCase = true)) {
                                parseJsonStreamResponse(resp, onToken, onReasoning)
                            } else {
                                parseSse(resp, onToken, onReasoning)
                            }
                            // 成功但切流无任何内容/工具调用：属空结果，不应回落下一个端点重发
                            if (out == null || out.isEmpty) throw TapcreatorException("Agent 大脑未返回任何输出", "UPSTREAM_EMPTY")
                            out
                        }
                    }
                }
                if (output != null) return output
            } catch (se: SerializationException) {
                throw TapcreatorException("上游流式响应无法解析：$url", "UPSTREAM_BAD_BODY")
            }
        }
        val (code, text) = lastHttp ?: (0 to "")
        throw TapcreatorException(upstreamUrlError(code, text), "UPSTREAM_HTTP")
    }

    /** 识图校验：把一张产物图片（data URI）连同要求回馈给多模态模型，返回其自检结论（纯文本）。 */
    suspend fun visionChat(
        channel: Channel,
        secrets: ChannelSecrets,
        model: ModelOption,
        imageDataUri: String,
        instruction: String,
    ): String {
        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "你是严格的产物质检员。请依据要求客观判断图片是否达标，指出与要求的明显偏差，最后单独一行给「达标/不达标」结论。只描述图片实际可见内容，不臆测。"
                    )
                }
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", instruction) })
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", imageDataUri) })
                                }
                            )
                        }
                    )
                }
            )
        }
        val body = buildJsonObject {
            put("model", model.name)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 800)
        }
        val resp = withContext(Dispatchers.IO) {
            execute(channel, secrets, "/chat/completions", body)
        }
        return resp.jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw TapcreatorException("识图校验无返回", "UPSTREAM_EMPTY")
    }

    /**
     * 非 SSE 降级解析：上游不支持 stream 时直接返回普通 chat completion JSON。
     * 把 content 通过 onToken 回调（让 UI 思考流可见），解析 tool_calls，
     * 返回与 parseSse 相同结构的 ChatResponse。
     */
    private fun parseJsonStreamResponse(resp: Response, onToken: (String) -> Unit, onReasoning: (String) -> Unit): ChatResponse? {
        val text = resp.body?.string().orEmpty()
        if (text.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return null
        val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isNotEmpty()) onToken(content)
        // 非流式响应里推理过程可能在 message.reasoning_content（部分兼容实现）
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (reasoning.isNotEmpty()) onReasoning(reasoning)
        val toolCalls = message["tool_calls"]?.takeIf { it !is JsonNull }
        if (toolCalls is JsonElement && toolCalls.jsonArray.isNotEmpty()) {
            val fn = toolCalls.jsonArray.firstOrNull()?.jsonObject?.get("function")?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
            if (name != null) {
                val argsObj = runCatching {
                    json.parseToJsonElement(fn.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject
                }.getOrNull() ?: emptyJsonObject()
                val id = toolCalls.jsonArray.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                return ChatResponse(
                    text = content,
                    toolCalls = listOf(ToolCall(id = id ?: "tc_${System.nanoTime()}", name = name, args = argsObj)),
                    reasoning = reasoning,
                )
            }
        }
        if (content.isBlank() && reasoning.isBlank()) return null
        return ChatResponse(text = content, reasoning = reasoning)
    }

    private fun parseSse(resp: Response, onToken: (String) -> Unit, onReasoning: (String) -> Unit = {}): ChatResponse? {
        val source = resp.body?.source() ?: throw TapcreatorException("流式响应无正文", "UPSTREAM_EMPTY")
        val content = StringBuilder()
        val reasoningText = StringBuilder()
        var toolName: String? = null
        val toolArgs = StringBuilder()
        var toolId: String? = null
        // SSE 事件帧缓冲：单条事件可被拆成多行 "data:"，以空行作为事件结束标志后合并解析
        val frame = StringBuilder()
        var done = false
        while (!done && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                // 一帧结束：解析缓冲内容
                if (frame.isEmpty()) continue
                done = parseFrame(frame.toString(), content, onToken, onReasoning, toolNameRef = { toolName = it }, toolArgs, toolIdRef = { toolId = it })
                frame.setLength(0)
                continue
            }
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:")
                if (data.trim() == "[DONE]") { done = true; break }
                if (frame.isNotEmpty()) frame.append('\n')
                frame.append(data)
            }
            // 忽略 "event:"/"id:"/":" 等其它 SSE 字段行
        }
        // 处理最后一帧（可能没有尾随空行）
        if (!done && frame.isNotEmpty()) {
            parseFrame(frame.toString(), content, onToken, onReasoning, { toolName = it }, toolArgs, { toolId = it })
            frame.setLength(0)
        }
        // 返回结构化 ChatResponse：工具调用直接以 ToolCall 对象返回，文本内容分离
        if (toolName != null) {
            val argsObj = runCatching { json.parseToJsonElement(toolArgs.toString()).jsonObject }.getOrNull() ?: emptyJsonObject()
            val call = ToolCall(
                id = toolId ?: "tc_${System.nanoTime()}",
                name = toolName!!,
                args = argsObj,
            )
            return ChatResponse(
                text = content.toString(),
                toolCalls = listOf(call),
                reasoning = reasoningText.toString(),
            )
        }
        val full = content.toString()
        val reasoningFull = reasoningText.toString()
        if (full.isBlank() && reasoningFull.isBlank()) return null
        return ChatResponse(text = full, reasoning = reasoningFull)
    }

    /** 解析单个 SSE 帧（可能含多行 data 需整体 JSON 解析）。返回 true 表示收到 [DONE] 应结束。 */
    private fun parseFrame(
        frame: String,
        content: StringBuilder,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        toolNameRef: (String) -> Unit,
        toolArgs: StringBuilder,
        toolIdRef: (String) -> Unit = {},
    ): Boolean {
        val data = frame.trim()
        if (data.isEmpty()) return false
        val chunk = runCatching { json.parseToJsonElement(data) }.getOrNull()?.takeIf { it is JsonObject }?.let { it as JsonObject }
            ?: return false
        val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        val textTok = delta?.get("content")?.jsonPrimitive?.contentOrNull
        if (!textTok.isNullOrEmpty()) {
            content.append(textTok)
            onToken(textTok)
        }
        // 推理过程流：DeepSeek-R1 / o1 等在 delta.reasoning_content 返回推理文本，与正文分开回调
        val reasonTok = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
        if (!reasonTok.isNullOrEmpty()) {
            onReasoning(reasonTok)
        }
        delta?.get("tool_calls")?.jsonArray?.forEach { tc ->
            val fn = tc.jsonObject.get("function")?.jsonObject
            val nm = fn?.get("name")?.jsonPrimitive?.contentOrNull
            val arg = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
            val id = tc.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (!id.isNullOrEmpty()) toolIdRef(id)
            if (!nm.isNullOrEmpty()) toolNameRef(nm)
            if (!arg.isNullOrEmpty()) {
                toolArgs.append(arg)
                onToken(arg)
            }
        }
        return false
    }

    private fun emptyJsonObject(): kotlinx.serialization.json.JsonObject = buildJsonObject { }

    private fun openAiChat(channel: Channel, secrets: ChannelSecrets, model: ModelOption, prefs: GenerationPreferences): UpstreamResult {
        val refSnippet = prefs.referenceTexts.joinToString("\n", prefix = "参考素材：", postfix = "\n\n")
        val userContent = if (prefs.referenceImages.isEmpty()) {
            json.encodeToJsonElement(refSnippet + prefs.prompt)
        } else {
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", refSnippet + prefs.prompt) })
                prefs.referenceImages.forEach { dataUri ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", dataUri) })
                        }
                    )
                }
            }
        }
        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", "你是 tapcreator 的创作助手。请严格按用户要求生成内容。")
                }
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            )
        }
        val body = buildJsonObject {
            put("model", model.name)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }
        val resp = execute(channel, secrets, "/chat/completions", body)
        val content = resp.jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw TapcreatorException("上游未返回文本结果", "UPSTREAM_EMPTY")
        return UpstreamResult(kind = MediaKind.TEXT, text = content, mime = "text/plain")
    }

    private fun openAiImage(channel: Channel, secrets: ChannelSecrets, model: ModelOption, prefs: GenerationPreferences): UpstreamResult {
        val refSnippet = prefs.referenceTexts.joinToString("\n", prefix = "参考素材：", postfix = "\n\n")
        // 每次只请求一张：数量(count)由 TaskExecutor 在设备内循环调用保证，便于逐卡落库与进度汇报
        val body = buildJsonObject {
            put("model", model.name)
            put("prompt", refSnippet + prefs.prompt)
            put("n", 1)
            put("size", normalizeResolution(model, MediaKind.IMAGE, prefs.resolution, prefs.ratio, prefs.quality)
                ?: prefs.resolution?.takeIf { it.contains('x') }
                ?: prefs.ratio?.let(::ratioToSize)
                ?: "1024x1024")
            if (prefs.quality == "high") put("quality", "hd")
        }
        val resp = execute(channel, secrets, "/images/generations", body)
        val data = resp.jsonObject["data"]?.jsonArray ?: throw TapcreatorException("上游未返回图片", "UPSTREAM_EMPTY")
        val first = data.firstOrNull()?.jsonObject ?: throw TapcreatorException("上游未返回图片", "UPSTREAM_EMPTY")
        val url = first["url"]?.jsonPrimitive?.content
        val b64 = first["b64_json"]?.jsonPrimitive?.content
        return when {
            url != null && url.startsWith("http") -> UpstreamResult(kind = MediaKind.IMAGE, mediaUrl = url, mime = "image/*")
            url != null -> UpstreamResult(kind = MediaKind.IMAGE, mediaBytes = decodeB64(url), mime = "image/*")
            b64 != null -> UpstreamResult(kind = MediaKind.IMAGE, mediaBytes = decodeB64(b64), mime = "image/*")
            else -> throw TapcreatorException("上游未返回可下载图片", "UPSTREAM_EMPTY")
        }
    }

    private fun openAiAudio(channel: Channel, secrets: ChannelSecrets, model: ModelOption, prefs: GenerationPreferences): UpstreamResult {
        val refSnippet = prefs.referenceTexts.joinToString("\n", prefix = "参考素材：", postfix = "\n\n")
        // OpenAI 兼容 TTS：/audio/speech 返回二进制音频而非 JSON，需用 raw 字节通道
        val body = buildJsonObject {
            put("model", model.name)
            put("input", refSnippet + prefs.prompt)
            put("voice", "alloy")
            put("response_format", "mp3")
        }
        val bytes = executeBytes(channel, secrets, "/audio/speech", body)
        return UpstreamResult(kind = MediaKind.AUDIO, mediaBytes = bytes, mime = "audio/mpeg")
    }

    private fun execute(channel: Channel, secrets: ChannelSecrets, endpoint: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val base = normalizeBase(channel.baseUrl)
        val payload = json.encodeToJsonElement(body).toString().toRequestBody("application/json".toMediaType())
        var lastHttp: Pair<Int, String>? = null
        for (url in endpointUrls(base, endpoint)) {
            val builder = Request.Builder().url(url).post(payload)
            if (secrets.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${secrets.apiKey}")
            try {
                val parsed = client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastHttp = resp.code to text
                        if (resp.code != 404) throw TapcreatorException(upstreamError(resp, text, url), "UPSTREAM_HTTP")
                        null
                    } else if (text.isBlank()) {
                        throw TapcreatorException("上游返回空响应：$url", "UPSTREAM_EMPTY")
                    } else {
                        json.parseToJsonElement(text).jsonObject
                    }
                }
                if (parsed != null) return parsed
            } catch (se: SerializationException) {
                throw TapcreatorException("上游响应无法解析：$url", "UPSTREAM_BAD_BODY")
            }
        }
        val (code, text) = lastHttp ?: (0 to "")
        throw TapcreatorException(upstreamUrlError(code, text), "UPSTREAM_HTTP")
    }

    /** GET 查询（上游视频模型 H3 轮询任务状态） */
    private fun executeGet(channel: Channel, secrets: ChannelSecrets, endpoint: String): kotlinx.serialization.json.JsonObject {
        val base = normalizeBase(channel.baseUrl)
        val builder = Request.Builder().url(base + endpoint).get()
        if (secrets.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${secrets.apiKey}")
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw TapcreatorException(upstreamError(resp, text, base + endpoint), "UPSTREAM_HTTP")
                if (text.isBlank()) throw TapcreatorException("上游返回空响应", "UPSTREAM_EMPTY")
                return json.parseToJsonElement(text).jsonObject
            }
        } catch (se: SerializationException) {
            throw TapcreatorException("上游响应无法解析", "UPSTREAM_BAD_BODY")
        }
    }

    /** POST 二进制端点（TTS /audio/speech 等返回原始媒体字节而非 JSON） */
    private fun executeBytes(channel: Channel, secrets: ChannelSecrets, endpoint: String, body: kotlinx.serialization.json.JsonObject): ByteArray {
        val base = normalizeBase(channel.baseUrl)
        val payload = json.encodeToJsonElement(body).toString().toRequestBody("application/json".toMediaType())
        var lastHttp: Pair<Int, String>? = null
        for (url in endpointUrls(base, endpoint)) {
            val builder = Request.Builder().url(url).post(payload)
            if (secrets.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${secrets.apiKey}")
            val result = client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    lastHttp = resp.code to text
                    if (resp.code != 404) throw TapcreatorException(upstreamError(resp, text, url), "UPSTREAM_HTTP")
                    null
                } else {
                    // 二进制音频端点：直接读字节，避免 string() 的 UTF-8 往返解码损坏 mp3 数据
                    resp.body?.bytes() ?: throw TapcreatorException("上游未返回音频内容：$url", "UPSTREAM_EMPTY")
                }
            }
            if (result != null) return result
        }
        val (code, text) = lastHttp ?: (0 to "")
        throw TapcreatorException(upstreamUrlError(code, text), "UPSTREAM_HTTP")
    }

    /** 连接测试：对渠道 baseUrl GET /models（自动补 /v1 变体），校验地址 + API Key 连通性。成功返回耗时信息，失败抛出可读异常。 */
    suspend fun testConnection(channel: Channel, secrets: ChannelSecrets): String =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(channel.baseUrl)
            if (base.isBlank()) throw TapcreatorException("未配置接口地址(Base URL)", "CHANNEL_NO_URL")
            if (secrets.apiKey.isBlank()) throw TapcreatorException("未配置 API Key", "CHANNEL_NO_KEY")
            val candidates = listOf("$base/models", "$base/v1/models").distinct()
            val start = System.currentTimeMillis()
            var lastErr: TapcreatorException? = null
            for (url in candidates) {
                val builder = Request.Builder().url(url).get()
                if (secrets.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${secrets.apiKey}")
                try {
                    client.newCall(builder.build()).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val count = runCatching {
                                json.parseToJsonElement(text).jsonObject["data"]?.jsonArray?.size
                            }.getOrNull()
                            val ms = System.currentTimeMillis() - start
                            val note = if (channel.modelCatalog.isBlank()) "" else "，模型目录：${channel.modelCatalog}"
                            return@withContext "连接成功 ${ms}ms，可识别模型 ${count ?: 0} 个$note"
                        } else {
                            lastErr = TapcreatorException(upstreamError(resp, text, url), "UPSTREAM_HTTP")
                        }
                    }
                } catch (e: Exception) {
                    lastErr = TapcreatorException("连接异常：${e.message}", "NETWORK")
                }
            }
            throw lastErr ?: TapcreatorException("连接失败", "CHANNEL_TEST")
        }

    /** 拉取供应商真实模型列表（GET /models + 自动 /v1 变体），返回模型 id 列表；失败抛出可读异常。 */
    suspend fun listModelsRaw(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            if (base.isBlank()) throw TapcreatorException("未配置接口地址(Base URL)", "CHANNEL_NO_URL")
            if (apiKey.isBlank()) throw TapcreatorException("未配置 API Key", "CHANNEL_NO_KEY")
            val candidates = listOf("$base/models", "$base/v1/models").distinct()
            var lastErr: TapcreatorException? = null
            for (url in candidates) {
                val builder = Request.Builder().url(url).get()
                builder.header("Authorization", "Bearer $apiKey")
                try {
                    client.newCall(builder.build()).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val ids = runCatching {
                                json.parseToJsonElement(text).jsonObject["data"]?.jsonArray?.mapNotNull {
                                    it.jsonObject["id"]?.jsonPrimitive?.content ?: it.jsonObject["model"]?.jsonPrimitive?.content
                                }.orEmpty()
                            }.getOrDefault(emptyList())
                            return@withContext ids
                        } else {
                            lastErr = TapcreatorException(upstreamError(resp, text, url), "UPSTREAM_HTTP")
                        }
                    }
                } catch (e: Exception) {
                    lastErr = TapcreatorException("连接异常：${e.message}", "NETWORK")
                }
            }
            throw lastErr ?: TapcreatorException("拉取模型失败", "CHANNEL_TEST")
        }

    /** 归一化 Base URL：用户可能误把完整端点（/chat/completions、/models 等）也填进 Base URL，
     * 这里裁剪回基础地址（保留品牌域名与 /v1），再交给后续拼端点。 */
    internal fun normalizeBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        val endpoints = listOf(
            "/chat/completions", "/images/generations", "/videos/generations",
            "/audio/speech", "/models", "/completions",
        )
        var changed = true
        while (changed) {
            changed = false
            for (e in endpoints) {
                if (b.endsWith(e) && b.length > e.length) {
                    b = b.removeSuffix(e)
                    changed = true
                }
            }
        }
        return b.trimEnd('/')
    }

    /** 生成候选请求 URL：OpenAI 风格端点若 Base URL 没带 /v1（最常见 404 原因），补一次 /v1 变体重试 */
    internal fun endpointUrls(base: String, endpoint: String): List<String> {
        val first = base + endpoint
        val openAiStyle = endpoint in setOf(
            "/chat/completions", "/images/generations", "/videos/generations", "/audio/speech",
        )
        val needsV1 = openAiStyle && !base.endsWith("/v1") &&
            !base.contains("completions") && !base.contains("generations") && !base.contains("/speech")
        return if (needsV1) listOf(first, base + "/v1" + endpoint) else listOf(first)
    }

    private fun upstreamUrlError(code: Int, body: String): String =
        "上游错误 HTTP ${code}${body.take(200).let { if (it.isBlank()) "" else "：$it" }}"

    private fun upstreamError(resp: Response, body: String, url: String): String {
        val reason = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
        val snippet = body.take(200)
        return "上游错误 HTTP ${resp.code}（$url）${reason?.let { "：$it" } ?: if (snippet.isBlank()) "" else "：$snippet"}"
    }

    internal fun ratioToSize(ratio: String): String = when (ratio) {
        "1:1" -> "1024x1024"
        "4:3", "3:4" -> "1024x1024"
        "16:9", "21:9" -> "1792x1024"
        "9:16" -> "1024x1792"
        else -> "1024x1024"
    }

    /**
     * ① 出参归一化：把请求的 (resolution, ratio, quality) 归一到该模型实际支持的合法分辨率。
     * 以模型声明的 resolutions 为唯一真源，保证发上游的永远是「对得上」的值：
     *  - 显式请求且在该模型支持集内 → 原样用；
     *  - 图片按目标比例在支持集里挑「最接近比例」的一项（优先同朝向、再取更大面积）；
     *  - 视频按 quality 挑支持集内的档位，否则取模型第一档；
     *  - 模型未声明支持集时返回 null，交由调用处回退原有逻辑。
     */
    internal fun normalizeResolution(
        model: ModelOption,
        kind: MediaKind,
        resolution: String?,
        ratio: String?,
        quality: String?,
    ): String? {
        val supported = model.resolutions.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
        if (supported.isEmpty()) return null
        if (!resolution.isNullOrBlank() && supported.contains(resolution)) return resolution
        return when (kind) {
            MediaKind.IMAGE -> pickImageResolution(supported, ratioAspect(ratio))
            MediaKind.VIDEO -> {
                // 视频档位序： "2K"/"4K" 这类 K 档按 n*1000，否则按像素数值（720P/1080P/768P…）
                fun tier(s: String): Int {
                    val n = s.filter { it.isDigit() }.toIntOrNull() ?: 0
                    return if (s.lowercase().contains('k')) n * 1000 else n
                }
                if (quality == "high") supported.maxBy { tier(it) } else supported.minBy { tier(it) }
            }
            else -> supported.first()
        }
    }

    /** 比例串("16:9"/"1:1") -> 宽:高 数值；无法解析回退 1.0 */
    internal fun ratioAspect(ratio: String?): Double {
        if (ratio != null) {
            val parts = ratio.split(":").map { it.trim().toDoubleOrNull() }
            if (parts.size == 2 && parts[0] != null && parts[1] != null && parts[1] != 0.0) {
                return parts[0]!! / parts[1]!!
            }
        }
        return 1.0
    }

    /** "WxH"（兼容全角乘号/小写 x）-> 宽:高 数值；无法解析回退 1.0 */
    internal fun sizeAspect(size: String): Double {
        val parts = size.lowercase().replace("×", "x").split("x").map { it.trim().toDoubleOrNull() }
        return if (parts.size == 2 && parts[0] != null && parts[1] != null && parts[1] != 0.0) parts[0]!! / parts[1]!! else 1.0
    }

    /** "WxH" -> 像素数；无法解析回退 1 */
    internal fun sizeProduct(size: String): Long {
        val parts = size.lowercase().replace("×", "x").split("x").map { it.trim().toLongOrNull() }
        return if (parts.size == 2 && parts[0] != null && parts[1] != null) parts[0]!! * parts[1]!! else 1L
    }

    /** 在支持集里挑分辨率：优先命中目标比例的同朝向（横对横/竖对竖），组内取面积最大的一档；
     *  仅当该朝向无任何候选时才退化为全量比例最近。 */
    internal fun pickImageResolution(supported: List<String>, target: Double): String {
        val wantsWide = target >= 1.0
        val sameOrientation = supported.filter { s -> (sizeAspect(s) >= 1.0) == wantsWide }
        val pool = sameOrientation.ifEmpty { supported }
        return pool.maxByOrNull { sizeProduct(it) } ?: supported.first()
    }

    private fun decodeB64(value: String): ByteArray =
        java.util.Base64.getDecoder().decode(value)
}