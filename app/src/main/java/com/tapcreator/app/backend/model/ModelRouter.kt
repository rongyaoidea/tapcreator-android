package com.tapcreator.app.backend.model

import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.ChannelEntity
import com.tapcreator.app.data.db.ModelOptionEntity
import com.tapcreator.app.data.model.Channel
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.ModelOption
import com.tapcreator.app.data.model.TapcreatorException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 逻辑模型路由 —— 对齐 logical-model-router：
 * 由能力(kind)+通道优先级+默认模型推导实际使用的逻辑模型，供任务执行。
 */
@Singleton
class ModelRouter @Inject constructor(
    private val db: AppDatabase,
) {

    suspend fun channels(): List<ChannelEntity> = db.channelDao().all().filter { it.active }

    /** 已激活（配了 API Key/已开启）的渠道 id 集合 —— 只有激活渠道的模型才可被选用 */
    private suspend fun activeChannelIds(): Set<String> =
        db.channelDao().all().filter { it.active }.map { it.id }.toSet()

    /** 某媒体类型的可用模型：仅返回其渠道已激活的模型（未配置渠道时不默认给出任何模型） */
    suspend fun models(kind: MediaKind): List<ModelOptionEntity> {
        val active = activeChannelIds()
        return db.modelOptionDao().byKind(kind.name).filter { it.channelId in active }
    }

    /** 渠道 Base URL 是否存在（缺失即为"未就绪"，用于就绪回退，避免引用到空地址） */
    private suspend fun readyChannelIds(): Set<String> =
        db.channelDao().all().filter { it.baseUrl.isNotBlank() }.map { it.id }.toSet()

    suspend fun defaultModel(kind: MediaKind): ModelOptionEntity? {
        // 兜底：优先选「渠道已就绪（Base URL 已配）」的默认/首个模型，
        // 若默认模型渠道未配地址，则回退到同类型其它已就绪模型，杜绝引用空 Base URL
        val models = models(kind)
        val ready = readyChannelIds()
        return models.firstOrNull { it.isDefault && it.channelId in ready }
            ?: models.firstOrNull { it.channelId in ready }
            ?: models.firstOrNull { it.isDefault }
            ?: models.firstOrNull()
    }

    suspend fun resolve(requestedIds: List<String>, kind: MediaKind): List<ModelOptionEntity> {
        val active = activeChannelIds()
        val ready = readyChannelIds()
        val requested = requestedIds.mapNotNull { db.modelOptionDao().byId(it) }
            .filter { it.enabled && it.channelId in active && it.kind == kind }
        // 优先返回所有已就绪的请求模型；若所选模型渠道未配 Base URL，则回退到已就绪的默认模型
        val readyRequested = requested.filter { it.channelId in ready }
        if (readyRequested.isNotEmpty()) return readyRequested
        if (requested.isNotEmpty()) return requested
        val def = defaultModel(kind)
        if (def == null) throw TapcreatorException("未配置$kind 类型的可用模型（需先在设置配置渠道、API Key 与模型目录）", "NO_MODEL")
        return listOf(def)
    }

    suspend fun toDomain(entity: ModelOptionEntity): ModelOption =
        ModelOption(
            id = entity.id,
            name = entity.name,
            kind = entity.kind,
            channelId = entity.channelId,
            enabled = entity.enabled,
            isDefault = entity.isDefault,
            capabilities = entity.capabilities.split(",").filter { it.isNotBlank() }.toSet(),
            resolutions = entity.resolutions,
        )
}