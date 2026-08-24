package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.TapcreatorException

/**
 * 规范化 LLM 错误。
 * 适配器负责把各家的报错文本映射到规范类型，消费方按类型路由。
 */
sealed class LlmFailure {
    /** 可重试：限流、临时网络错误、上游 overload */
    data class Retryable(val message: String, val retryAfterMs: Long = 0) : LlmFailure()
    /** 致命：认证失败、上下文溢出、模型不存在、参数非法 */
    data class Fatal(val code: String, val message: String) : LlmFailure()
}

/** 上下文溢出规范 code */
const val CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"

/**
 * 内部统一消息格式（OpenAI 兼容风格）。
 * 所有 provider 的请求/响应都收敛到此格式。
 */
data class InternalMessage(
    val role: String, // "system" | "user" | "assistant" | "tool"
    val content: String,
    /** 工具调用（assistant 消息中） */
    val toolCalls: List<InternalToolCall>? = null,
    /** 工具结果（tool 角色消息中）：tool_call_id + 结果内容 */
    val toolCallId: String? = null,
)

/**
 * 内部统一工具调用格式。
 * 工具参数全程保留原始 JSON 字符串，不做格式化/截断。
 * 一旦进入日志即视为不可改写的审计证据。
 */
data class InternalToolCall(
    val id: String,
    val type: String = "function",
    val function: InternalFunction,
)

data class InternalFunction(
    val name: String,
    /** 原始 JSON 字符串，不解析、不格式化、不截断 */
    val arguments: String,
)

/**
 * 内部统一请求。
 */
data class InternalRequest(
    val model: String,
    val messages: List<InternalMessage>,
    /** 工具定义 JSON 数组字符串（原始 JSON，不做格式化） */
    val tools: String? = null,
    val temperature: Double = 0.5,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
)

/**
 * 内部统一响应。
 */
data class InternalResponse(
    val message: InternalMessage,
    val failure: LlmFailure? = null,
)

/**
 * Provider 适配器接口。
 * 只强制实现 stream()，入参出参都是内部统一格式。
 * 适配器负责双向翻译：内部格式 → 各家格式 → 内部格式。
 */
interface LlmAdapter {
    /**
     * 流式调用 LLM。
     * @param request 内部统一格式请求
     * @param onToken 每收到一个 content token 时回调（用于 UI 实时显示）
     * @param onReasoning 每收到一个 reasoning_content token 时回调（DeepSeek-R1/o1 等）
     * @return 完整的内部统一格式响应
     * @throws LlmException 当发生可重试或致命错误时
     */
    suspend fun stream(
        request: InternalRequest,
        onToken: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
    ): InternalResponse
}

/**
 * LLM 适配器异常。
 * 包含规范化错误信息，消费方按 failure 类型路由。
 */
class LlmException(
    val failure: LlmFailure,
    override val message: String,
) : Exception(message)