package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 节点生成参数：让每张成品卡「自描述、可复现」。
 *
 * 竞品的核心能力是「改一个节点只重跑这一步」——前提是节点本身完整留存
 * 提示词 / 模型 / 参考 / 参数。本类序列化后写入 [com.tapcreator.app.data.db.CardEntity.paramsJson]，
 * 使画布节点脱离 runId 也能被单独重跑、复制为变体或让 Agent 局部重做。
 */
@Serializable
data class NodeParams(
    val prompt: String = "",
    val kind: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val ratio: String? = null,
    val quality: String? = null,
    val resolution: String? = null,
    val seconds: Int? = null,
    val referenceCardIds: List<String> = emptyList(),
    /** 由哪张卡复制/派生而来（变体链） */
    val variantOf: String? = null,
    /** 角色节点：绑定的素材文件夹 id（固定人物/产品形象） */
    val characterFolderId: String? = null,
) {
    fun toJson(): String = runCatching { codec.encodeToString(serializer(), this) }.getOrDefault("")

    /** 人类可读的参数摘要（画布节点角标用） */
    fun summary(): String {
        val bits = mutableListOf<String>()
        if (modelName.isNotBlank()) bits += modelName
        ratio?.takeIf { it.isNotBlank() }?.let { bits += it }
        resolution?.takeIf { it.isNotBlank() }?.let { bits += it }
        seconds?.let { bits += "${it}s" }
        quality?.takeIf { it.isNotBlank() }?.let { bits += it }
        return bits.joinToString(" · ")
    }

    companion object {
        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(raw: String?): NodeParams? {
            if (raw.isNullOrBlank()) return null
            return runCatching { codec.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

/** 画布快照中的单个节点（仅持久化可恢复的最小集） */
@Serializable
data class SnapshotNode(
    val id: String,
    val x: Float,
    val y: Float,
    val paramsJson: String = "",
    val variantOf: String? = null,
    val version: Int = 1,
    val deleted: Boolean = false,
)

/** 画布快照中的一条关系边 */
@Serializable
data class SnapshotLink(
    val fromCardId: String,
    val toCardId: String,
    val role: String = "reference",
)

/** 画布快照载荷：记录某时刻的节点坐标 + 关系边，用于回滚/版本对比 */
@Serializable
data class CanvasSnapshotPayload(
    val nodes: List<SnapshotNode> = emptyList(),
    val links: List<SnapshotLink> = emptyList(),
    val note: String = "",
) {
    fun toJson(): String = runCatching { codec.encodeToString(serializer(), this) }.getOrDefault("{}")

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(raw: String?): CanvasSnapshotPayload? {
            if (raw.isNullOrBlank()) return null
            return runCatching { codec.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}
