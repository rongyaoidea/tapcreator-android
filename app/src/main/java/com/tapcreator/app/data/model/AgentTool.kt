package com.tapcreator.app.data.model

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
