package com.tapcreator.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tapcreator.app.data.model.ConversationSurface
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.Protocol
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.data.model.TaskStatus

@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val email: String?,
    val passwordHash: String,
    val salt: String,
    val createdAt: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val token: String,
    val userId: String,
    val createdAt: Long,
)

@Entity(tableName = "conversations", indices = [Index(value = ["userId"]), Index(value = ["updatedAt"])])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String = "",
    val title: String,
    val surface: ConversationSurface,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 会话内单条消息（用户指令 / Agent 汇总回复） */
@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "sequence"])])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequence: Int,
    val role: String,           // "user" | "assistant" | "system"
    val content: String,
    val kind: MediaKind,
    val referencedAssetIds: String = "", // 逗号分隔
    val runId: String? = null,
    val createdAt: Long,
)

/** Agent Run（一次生成任务的编排载体） */
@Entity(tableName = "agent_runs", indices = [Index(value = ["conversationId"])])
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessageId: String,
    val prompt: String,
    val kind: MediaKind,
    val status: RunStatus,
    val modelIds: String = "",
    val requestCount: Int,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 子任务：对应需要轮询的上游任务 */
@Entity(tableName = "tasks", indices = [Index(value = ["runId"])])
data class TaskEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val title: String,
    val type: MediaKind,
    val modelId: String,
    val channelId: String,
    val upstreamTaskId: String? = null,
    val prompt: String,
    val status: TaskStatus,
    val error: String? = null,
    val resultAssetId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 素材（媒体登记） */
@Entity(tableName = "assets", indices = [Index(value = ["conversationId"]), Index(value = ["folderId"])])
data class AssetEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val runId: String? = null,
    val kind: MediaKind,
    val storageKey: String,
    val mediaPath: String? = null,
    val previewPath: String? = null,
    val mime: String? = null,
    val title: String = "",
    val createdAt: Long,
    /** 所属文件夹；null = 未归档。角色/产品文件夹内的多视角素材用于固定人物/产品身份 */
    val folderId: String? = null,
)

/** 素材文件夹：普通素材夹 / 角色 / 产品。角色与产品文件夹绑定多视角素材，用作视频/图片的一致性参考 */
@Entity(tableName = "asset_folders")
data class AssetFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String = "folder", // "folder" | "role" | "product"
    val note: String = "",
    val createdAt: Long,
)

/** 模型渠道（不含明文 API Key） */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: Protocol,
    val baseUrl: String,
    val modelCatalog: String = "",
    val active: Boolean = true,
    val priority: Int = 100,
)

/** 逻辑模型 */
@Entity(tableName = "model_options", indices = [Index(value = ["channelId"])])
data class ModelOptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: MediaKind,
    val channelId: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val capabilities: String = "",
    /** 该模型允许的分辨率（逗号分隔）：视频如 "768P,2K"；图片如 "1024x1024,1536x1024"。空 = 按类型使用默认项 */
    val resolutions: String = "",
)

/** 结果卡片——关系图的节点 */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val conversationId: String,
    val sequence: Int,
    val kind: MediaKind,
    val title: String,
    val content: String = "",
    val previewPath: String? = null,
    val mediaPath: String? = null,
    val status: RunStatus,
    val x: Float = 0f,
    val y: Float = 0f,
    /** 仍被其他卡片引用时删除只会置 1 隐藏于面板，数据与文件保留；彻底无引用时才物理删除 */
    val deleted: Boolean = false,
)

/** 卡片自由连接——关系图的边 */
@Entity(tableName = "card_links", indices = [Index(value = ["fromCardId"])])
data class CardLinkEntity(
    @PrimaryKey val id: String,
    val fromCardId: String,
    val toCardId: String,
    val role: String = "reference", // "reference" | "parent"
)

/** Agent 记忆系统：会话级持久化记忆，供 Agent Loop 回顾使用 */
@Entity(tableName = "agent_memories", indices = [Index(value = ["conversationId"])])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val content: String,
    val createdAt: Long,
)

/** Agent run 可观测轨迹：记录每轮思考/动作/观察，runId 区分同会话多次执行，便于排查与复盘 */
@Entity(tableName = "agent_trace", indices = [Index(value = ["conversationId"]), Index(value = ["runId"])])
data class TraceEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val runId: String,
    val turn: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
)

/**
 * Agent 自进化技能库（全局默认生效）：自省阶段把失败教训/成功手法提炼为可复用启发式并落库，
 * 后续回合注入 system prompt 供遵循。state 流转：low 风险直接 active；high 风险先 pending 等人工审批。
 */
@Entity(tableName = "agent_skills", indices = [Index(value = ["state"])])
data class AgentSkillEntity(
    @PrimaryKey val id: String,
    /** 分类：generate / flow / reference / folder / toolfix / layout … */
    val category: String,
    /** 可执行启发式（≤200 字），注入时原样呈现 */
    val content: String,
    /** low 自动生效；high 需人工审批 */
    val risk: String = "low",
    /** draft / active / pending / rejected / retired */
    val state: String = "active",
    /** 仅作来源追溯；生效范围默认全局，不限定在此会话 */
    val conversationId: String? = null,
    val sourceRunId: String? = null,
    val confidence: Float = 0.5f,
    val usedCount: Int = 0,
    val successCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 会话级 UI 状态：保存「创作编辑现场 + Agent 现场 + 模型偏好」，返回会话时恢复 */
@Entity(tableName = "conversation_states")
data class ConversationStateEntity(
    @PrimaryKey val id: String, // conversationId
    val payloadJson: String = "{}",
    val updatedAt: Long = 0L,
)

/**
 * Agent 运行 → 注入技能的关联：记录「某个 Agent 执行批次里注入了哪些已生效技能」。
 * 用途：用户对上一轮产出反馈（满意/重做）时，把赢率精确归因到这些技能，而不是由模型自评拍板。
 * 反馈被消费后即删除，避免重复统计。
 */
@Entity(tableName = "agent_run_skills", indices = [Index(value = ["conversationId", "runId"])])
data class AgentRunSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: String,
    val runId: String,
    val skillId: String,
    val createdAt: Long,
)