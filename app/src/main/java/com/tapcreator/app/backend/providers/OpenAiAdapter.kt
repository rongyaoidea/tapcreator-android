package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.TapcreatorException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenAI 兼容格式的 LLM 适配器。
 *
 * 内部格式 ↔ OpenAI /chat/completions 格式的双向翻译。
 * 流式（SSE）和非流式响应均支持。
 * 工具参数全程保留原始 JSON 字符串，不做格式化/截断。
 */
@Singleton
class OpenAiAdapter @Inject constructor(
    private val client: OkHttpClient,
) : LlmAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun stream(
        request: InternalRequest,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): InternalResponse = withContext(Dispatchers.IO) {
        // 此方法由 ProviderGateway 在确定 URL 后调用
        throw UnsupportedOperationException("OpenAiAdapter 通过 ProviderGateway 路由，不直接调用 stream()")
    }

    /**
     * 构建 HTTP 请求体。
     */
    fun buildRequestBody(request: InternalRequest): okhttp3.RequestBody {
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                        m.toolCallId?.let { put("tool_call_id", it) }
                        m.toolCalls?.let { calls ->
                            put("tool_calls", buildJsonArray {
                                calls.forEach { tc ->
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("type", tc.type)
                                        put("function", buildJsonObject {
                                            put("name", tc.function.name)
                                            // arguments 是原始 JSON 字符串，不做格式化
                                            put("arguments", tc.function.arguments)
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
            })
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", request.stream)
            request.tools?.let { toolsStr ->
                val toolsJson = json.parseToJsonElement(toolsStr).jsonArray
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }
        }
        return body.toString()
            .toRequestBody("application/json".toMediaType())
    }

    /**
     * 解析 HTTP 响应为内部统一格式。
     * 支持 SSE 流式和非流式 JSON。
     */
    suspend fun parseHttpResponse(
        resp: Response,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): InternalResponse = withContext(Dispatchers.IO) {
        val ct = resp.header("Content-Type").orEmpty()
        val (content, toolCalls, failure) = if (!ct.contains("text/event-stream", ignoreCase = true)) {
            parseJsonResponse(resp)
        } else {
            parseSseResponse(resp, onToken, onReasoning)
        }
        InternalResponse(
            message = InternalMessage(
                role = "assistant",
                content = content,
                toolCalls = toolCalls?.ifEmpty { null }?.map { tc ->
                    InternalToolCall(
                        id = tc.id,
                        type = "function",
                        function = InternalFunction(
                            name = tc.name,
                            // 工具参数：原始 JSON 字符串，不可改写
                            arguments = tc.arguments,
                        ),
                    )
                },
            ),
            failure = failure,
        )
    }

    private data class ToolCallResult(val id: String, val name: String, val arguments: String)

    private fun parseJsonResponse(resp: Response): Triple<String, List<ToolCallResult>?, LlmFailure?> {
        val text = resp.body?.string().orEmpty()
        if (text.isBlank()) return Triple("", null, null)
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.content.orEmpty()
            val toolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
                val fn = tc.jsonObject.get("function")?.jsonObject ?: return@mapNotNull null
                ToolCallResult(
                    id = tc.jsonObject["id"]?.jsonPrimitive?.content.orEmpty(),
                    name = fn["name"]?.jsonPrimitive?.content.orEmpty(),
                    arguments = fn["arguments"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            val failure = detectFailure(root)
            Triple(content, toolCalls, failure)
        } catch (e: Exception) {
            Triple("", null, LlmFailure.Fatal("PARSE_ERROR", "响应解析失败：${e.message}"))
        }
    }

    private fun parseSseResponse(
        resp: Response,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): Triple<String, List<ToolCallResult>?, LlmFailure?> {
        val content = StringBuilder()
        var toolName = ""
        val toolArgs = StringBuilder()
        var toolId = ""
        val toolCalls = mutableListOf<ToolCallResult>()
        var lastFailure: LlmFailure? = null

        try {
            val reader = resp.body?.charStream()?.buffered() ?: return Triple("", null, null)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                val frame = try {
                    json.parseToJsonElement(data).jsonObject
                } catch (e: Exception) {
                    continue
                }

                val err = detectFailure(frame)
                if (err != null) {
                    lastFailure = err
                    break
                }

                val delta = frame["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta")?.jsonObject ?: continue
                val textTok = delta["content"]?.jsonPrimitive?.content.orEmpty()
                val reasonTok = delta["reasoning_content"]?.jsonPrimitive?.content.orEmpty()
                val fn = delta["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("function")?.jsonObject

                if (textTok.isNotEmpty()) {
                    content.append(textTok)
                    onToken(textTok)
                }
                if (reasonTok.isNotEmpty()) {
                    onReasoning(reasonTok)
                }
                if (fn != null) {
                    val nm = fn["name"]?.jsonPrimitive?.content.orEmpty()
                    val arg = fn["arguments"]?.jsonPrimitive?.content.orEmpty()
                    if (nm.isNotEmpty()) toolName = nm
                    if (arg.isNotEmpty()) toolArgs.append(arg)
                    val id = delta["tool_calls"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()
                    if (id.isNotEmpty()) toolId = id
                }
                val finishReason = frame["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content.orEmpty()
                if (finishReason == "tool_calls" && toolName.isNotEmpty()) {
                    toolCalls.add(ToolCallResult(toolId, toolName, toolArgs.toString()))
                    toolName = ""
                    toolArgs.clear()
                    toolId = ""
                }
            }
        } catch (e: Exception) {
            lastFailure = LlmFailure.Retryable("SSE 读取中断：${e.message}")
        }

        if (toolName.isNotEmpty()) {
            toolCalls.add(ToolCallResult(toolId, toolName, toolArgs.toString()))
        }

        return Triple(content.toString(), toolCalls.ifEmpty { null }, lastFailure)
    }

    /** 检测上游错误，映射到规范 LlmFailure */
    private fun detectFailure(root: JsonObject): LlmFailure? {
        val error = root["error"]?.jsonObject ?: return null
        val msg = error["message"]?.jsonPrimitive?.content.orEmpty()
        val code = error["code"]?.jsonPrimitive?.content.orEmpty()

        if (msg.contains("context_length", ignoreCase = true) ||
            msg.contains("max_tokens", ignoreCase = true) ||
            msg.contains("too many tokens", ignoreCase = true) ||
            code == "context_length_exceeded"
        ) {
            return LlmFailure.Fatal(CONTEXT_OVERFLOW, msg)
        }

        if (code == "invalid_api_key" || code == "authentication_error" ||
            msg.contains("401", ignoreCase = true) ||
            msg.contains("unauthorized", ignoreCase = true)
        ) {
            return LlmFailure.Fatal("AUTH_FAILED", msg)
        }

        if (code == "model_not_found" || msg.contains("model not found", ignoreCase = true)) {
            return LlmFailure.Fatal("MODEL_NOT_FOUND", msg)
        }

        if (code == "rate_limit_exceeded" || msg.contains("429", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true)
        ) {
            return LlmFailure.Retryable(msg)
        }

        return LlmFailure.Fatal("UPSTREAM_ERROR", msg)
    }
}

/**
 * 把内部工具调用格式转为 AgentAction 兼容的 tool_calls 列表。
 */
fun InternalResponse.toToolCallsJson(): List<JsonObject> =
    message.toolCalls?.map { tc ->
        buildJsonObject {
            put("id", tc.id)
            put("type", tc.type)
            put("function", buildJsonObject {
                put("name", tc.function.name)
                put("arguments", tc.function.arguments)
            })
        }
    } ?: emptyList()