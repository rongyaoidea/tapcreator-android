package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable

/** 生成媒体类型（对齐原作 text/image/video/audio） */
enum class MediaKind { TEXT, IMAGE, VIDEO, AUDIO }

/** Agent Run 生命周期 */
enum class RunStatus { DRAFT, PLANNING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** 单个子任务状态 */
enum class TaskStatus { READY, RUNNING, COMPLETED, FAILED, CANCELLED }

/** 会话来源，仅 chat（本复刻只做统一创作 Agent） */
enum class ConversationSurface { CHAT }

/** 上游协议类型 */
enum class Protocol {
    OPENAI_COMPAT,      // OpenAI 兼容（文本 + 图像生成本次先落地）
    MINIMAX_H3,         // MiniMax H3（官方 api.minimaxi.com 与 metaso.cn/api/minimax 代理共用 schema）
    SEEDANCE,           // 预留：图片/视频
    STABLE_DIFFUSION,   // 预留
    GEMINI_VIDEO,       // 预留
}

/** 逻辑模型 —— 路由单元 */
@Serializable
data class ModelOption(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val channelId: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val capabilities: Set<String> = emptySet(), // 能力标签，如 "text","image","reference"
    val resolutions: String = "", // 模型可选分辨率（图片 "WxH"；视频 "768P/2K/4K"），逗号分隔
)

/** 模型渠道（含敏感字段，API Key 单独走 Keystore） */
@Serializable
data class Channel(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val baseUrl: String,
    val modelCatalog: String = "", // 逗号分隔的该渠道可用模型 id
    val active: Boolean = true,
    val priority: Int = 100,
)

/** 渠道密钥载荷（持久化用？不，只存在于内存/Keystore，不落 Room 明文） */
data class ChannelSecrets(val apiKey: String)

/** 生成偏好（对齐原作 creative-generation-preferences 的核心字段） */
@Serializable
data class GenerationPreferences(
    val kind: MediaKind = MediaKind.IMAGE,
    val modelIds: List<String> = emptyList(),
    val ratio: String? = null,     // 1:1 / 16:9 ...
    val quality: String? = null,   // high / medium / low
    val resolution: String? = null, // 模型可选分辨率：视频 "768P/2K/4K"；图片 "WxH"
    val seconds: Int? = null,      // 视频
    val count: Int = 1,            // 生成数量
    val referencedAssetIds: List<String> = emptyList(),
    val referenceTexts: List<String> = emptyList(),   // 参考素材解析出的文本内容
    val referenceImages: List<String> = emptyList(),  // 参考图片的 data URI（多模态输入）
    val motion: Float? = null,        // MadStory：运动强度 scale（H3 透传 motion.scale）
    val cfgScale: Float? = null,      // MadStory：CFG scale（H3 透传 cfg_scale）
    val prompt: String = "",
    /** 提示词是否经 LLM 增强；persistOneCard 据此写 CardEntity.promptEnhanced */
    val promptEnhanced: Boolean = false,
)

/** 创建 Run 的请求 */
@Serializable
data class RunRequest(
    val conversationId: String,
    val prompt: String,
    val kind: MediaKind,
    val modelIds: List<String> = emptyList(),
    val count: Int = 1,
    val ratio: String? = null,
    val quality: String? = null,
    val resolution: String? = null, // 模型可选分辨率（见 GenerationPreferences）
    val seconds: Int? = null,   // 视频目标总时长（秒），超单段上限时自动分段续生成
    val referencedAssetIds: List<String> = emptyList(),
    val referencedAssetPaths: List<String> = emptyList(), // 跨会话素材参考：全局素材库 mediaPath
    val motion: Float? = null,   // MadStory：运动强度（透传 H3）
    val cfgScale: Float? = null, // MadStory：CFG scale（透传 H3）
    /** 提示词是否经 LLM 增强（promptOptimize 开关且优化成功）；落卡时写入 CardEntity.promptEnhanced */
    val promptEnhanced: Boolean = false,
)

/** 上游生成结果（provider 归一化后） */
data class UpstreamResult(
    val kind: MediaKind,
    val text: String? = null,
    val mediaUrl: String? = null,
    val mediaBytes: ByteArray? = null,
    val mime: String? = null,
    val upstreamId: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

/** Agent 对话消息（OpenAI 兼容 chats：system/user/assistant） */
data class ChatMessage(val role: String, val content: String)

/** Agent 工具调用（结构化输出，替代文本 JSON 解析） */
data class ToolCall(
    val id: String,
    val name: String,
    val args: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject { },
)

/** Agent 大脑响应（agentChatStream 返回：正文 + 工具调用分离，不再依赖文本解析） */
data class ChatResponse(
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: String = "",
    val usage: Pair<Int, Int>? = null, // (input tokens, output tokens)
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
    val isEmpty: Boolean get() = text.isBlank() && toolCalls.isEmpty() && reasoning.isBlank()
}

/** 推理深度级别（透传 reasoning_effort） */
enum class ThinkingLevel(val effort: String) {
    NONE("none"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** 结果卡片（替代画布的节点） */
@Serializable
data class ResultCard(
    val id: String,
    val runId: String,
    val conversationId: String,
    val sequence: Int,
    val kind: MediaKind,
    val title: String,
    val content: String = "",      // 文本内容或媒体描述
    val previewPath: String? = null,
    val mediaPath: String? = null,
    val status: RunStatus,
    val inputCardIds: List<String> = emptyList(), // 进入链路关系
)

open class TapcreatorException(message: String, val code: String = "BUSINESS") :
    Exception(message)

class AuthFailedException(message: String) : TapcreatorException(message, "AUTH_FAILED")
class UpstreamException(message: String) : TapcreatorException(message, "UPSTREAM")