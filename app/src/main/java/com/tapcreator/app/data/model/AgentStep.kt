package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable

/** Agent 可用工具：对应 tapcreator 各生成管线 */
enum class AgentTool {
    GENERATE_TEXT,
    GENERATE_IMAGE,
    GENERATE_VIDEO,
    GENERATE_AUDIO;

    companion object {
        /** 把模型返回的工具字符串（大小写/下划线不敏感）归一到枚举 */
        fun from(raw: String): AgentTool? {
            val norm = raw.lowercase().replace("_", "")
            return entries.firstOrNull { it.name.lowercase().replace("_", "") == norm }
        }
    }
}

/** Agent 计划的单个步骤 */
@Serializable
data class AgentStep(
    val tool: String,
    val prompt: String,
    val ratio: String? = null,
    val quality: String? = null,
    val seconds: Int? = null,
    /** 引用第 n 步（从 1 计）的产物卡 id，用于视频续写/音频音色/风格参考 */
    val reference: Int? = null,
    /** 分镜优化 参数：运动强度（motion intensity scale，建议 0.5~1.5） */
    val motion: Float? = null,
    /** 分镜优化 参数：CFG scale（相关性尺度，如 2.0） */
    val cfgScale: Float? = null,
) {
    /** 该步骤对应的媒体类型 */
    val kind: MediaKind
        get() = when (AgentTool.from(tool)) {
            AgentTool.GENERATE_IMAGE -> MediaKind.IMAGE
            AgentTool.GENERATE_VIDEO -> MediaKind.VIDEO
            AgentTool.GENERATE_AUDIO -> MediaKind.AUDIO
            else -> MediaKind.TEXT
        }
}