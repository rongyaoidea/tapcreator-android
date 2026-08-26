package com.tapcreator.app.backend.service

import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.ChannelEntity
import com.tapcreator.app.data.db.ModelOptionEntity
import com.tapcreator.app.data.model.Channel
import com.tapcreator.app.data.model.ChannelSecrets
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.Protocol
import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.data.prefs.SettingsStore
import com.tapcreator.app.data.security.KeyStoreManager
import com.tapcreator.app.backend.providers.ProviderGateway
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 渠道仓库 —— 管理模型渠道与密钥：
 *  - 渠道概要（地址/协议/模型目录）落 Room；
 *  - API Key 用 Keystore 加密后落 DataStore，解密只在内存用于直连。
 *  - 首次启动种子化一个 OpenAI 兼容占位渠道，密钥由用户在设置页填写。
 */
@Singleton
class ChannelRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val keyStore: KeyStoreManager,
    private val gateway: ProviderGateway,
) {

    /** 种子化默认渠道：预置 OpenAI 兼容、MiniMax 官方 H3、秘塔 MiniMax H3 三个禁用占位渠道等待配置 */
    suspend fun seedDefaults() {
        // 仅首次启动种子化一次；若每次启动都跑，用户删除的固定 id 种子模型会因“存在性检查为空”再次被插入，
        // 表现为“删了又自己恢复”。用持久化标记保证只种一次。
        if (settings.seeded()) return
        seedChannel("ch-openai", "OpenAI 兼容", Protocol.OPENAI_COMPAT, "https://api.openai.com/v1")
        seedChannel("ch-minimax", "MiniMax 官方 H3", Protocol.MINIMAX_H3, "https://api.minimaxi.com")
        seedChannel("ch-metaso-minimax", "秘塔 MiniMax H3", Protocol.MINIMAX_H3, "https://metaso.cn/api/MiniMax")
        seedModels()
        settings.markSeeded()
    }

    private suspend fun seedChannel(id: String, name: String, protocol: Protocol, baseUrl: String) {
        if (db.channelDao().byId(id) != null) return
        db.channelDao().insert(
            ChannelEntity(
                id = id,
                name = name,
                protocol = protocol,
                baseUrl = baseUrl,
                modelCatalog = "",
                active = false,
                priority = 100,
            )
        )
    }

    private suspend fun seedModels() {
        seedModel("m-gpt-4o-mini", "gpt-4o-mini", MediaKind.TEXT, "ch-openai", "text,reference", true)
        seedModel("m-上游图模型-3", "上游图模型-3", MediaKind.IMAGE, "ch-openai", "image", true, "1024x1024,1792x1024,1024x1792")
        seedModel("m-veo-2", "veo-2", MediaKind.VIDEO, "ch-openai", "video", true, "720P,1080P")
        seedModel("m-tts-1", "tts-1", MediaKind.AUDIO, "ch-openai", "audio", true)
        seedModel("m-minimax-h3", "MiniMax-H3", MediaKind.VIDEO, "ch-minimax", "video,reference,audio", false, "768P,2K")
        seedModel("m-metaso-h3", "MiniMax-H3", MediaKind.VIDEO, "ch-metaso-minimax", "video,reference,audio", false, "768P,2K")
        // 兜底：无论常数据残留，保证文本/图像/视频/音频四类里每类都至少有一个已启用模型，
        // 避免"只能看到视频模型、看不到文本/生图模型"。
        ensureKindHasDefault(MediaKind.TEXT, "gpt-4o-mini", "ch-openai", "text,reference")
        ensureKindHasDefault(MediaKind.IMAGE, "上游图模型-3", "ch-openai", "image")
    }

    private suspend fun ensureKindHasDefault(kind: MediaKind, modelName: String, channelId: String, capabilities: String) {
        val existing = db.modelOptionDao().byKind(kind.name)
        if (existing.isNotEmpty()) return
        val id = "m-${kind.name.lowercase()}-seed-${modelName}"
        seedModel(id, modelName, kind, channelId, capabilities, true)
    }

    private suspend fun seedModel(
        id: String,
        name: String,
        kind: MediaKind,
        channelId: String,
        capabilities: String,
        isDefault: Boolean,
        resolutions: String = "",
    ) {
        if (db.modelOptionDao().byId(id) != null) return
        db.modelOptionDao().insert(
            ModelOptionEntity(
                id = id,
                name = name,
                kind = kind,
                channelId = channelId,
                enabled = true,
                isDefault = isDefault,
                capabilities = capabilities,
                resolutions = resolutions,
            )
        )
    }

    suspend fun channels(): List<ChannelEntity> = db.channelDao().all()

    suspend fun byId(id: String): ChannelEntity? = db.channelDao().byId(id)

    suspend fun toDomain(entity: ChannelEntity): Channel =
        Channel(
            id = entity.id,
            name = entity.name,
            protocol = entity.protocol,
            baseUrl = entity.baseUrl,
            modelCatalog = entity.modelCatalog,
            active = entity.active,
            priority = entity.priority,
        )

    /** 读取某渠道明文 API Key（仅在内存中，用于直连） */
    suspend fun secrets(channelId: String): ChannelSecrets {
        val cipher = settings.savedSecretCipher(channelId) ?: return ChannelSecrets("")
        return ChannelSecrets(
            runCatching { keyStore.decrypt(cipher) }.getOrDefault("")
        )
    }

    suspend fun saveSecret(channelId: String, apiKey: String) {
        if (apiKey.isBlank()) return
        settings.saveSecretCipher(channelId, keyStore.encrypt(apiKey.trim()))
    }

    suspend fun removeSecret(channelId: String) {
        settings.removeSecret(channelId)
    }

    /** 新增渠道 + 可选密钥（key 落在 Keystore）。kind 非空时把模型目录强制归入该媒体类型（设置页按文本/图像/视频分类新增）。 */
    suspend fun add(
        name: String,
        baseUrl: String,
        modelCatalog: String,
        apiKey: String,
        protocol: Protocol = Protocol.OPENAI_COMPAT,
        priority: Int = 100,
        active: Boolean,
        kind: MediaKind? = null,
    ): ChannelEntity {
        val id = "ch-${UUID.randomUUID().toString().substring(0, 8)}"
        val entity = ChannelEntity(
            id = id,
            name = name.trim().ifEmpty { "未命名渠道" },
            protocol = detectProtocol(baseUrl, protocol),
            baseUrl = baseUrl.trim().trimEnd('/'),
            modelCatalog = modelCatalog.trim(),
            active = active,
            priority = priority,
        )
        db.channelDao().insert(entity)
        // 让"模型目录"真正生效：据此创建模型条目，聊天页按类型可选
        // （文本/生图/视频/音频分开配置，不再只有视频能用）
        syncCatalogModels(entity, kind)
        if (apiKey.isNotBlank()) {
            settings.saveSecretCipher(entity.id, keyStore.encrypt(apiKey.trim()))
        }
        return entity
    }

    /** 把渠道的 modelCatalog（逗号分隔模型名）解析为 model_option 条目。kind 非空时强制归类到该类型，否则按名字关键词推断 */
    private suspend fun syncCatalogModels(channel: ChannelEntity, kind: MediaKind? = null) {
        val names = channel.modelCatalog.split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        // 当 kind 未显式指定时，查询该渠道已有模型的 kind：若全部一致则用该 kind 作为默认值，
        // 避免编辑渠道时新增的模型名被保底归为 TEXT（如文本模型名混入图像渠道时）。
        val existingModels = if (kind == null) db.modelOptionDao().all().filter { it.channelId == channel.id } else emptyList()
        val existingKinds = existingModels.map { it.kind }.distinct()
        val effectiveKind = kind ?: if (existingKinds.size == 1) existingKinds.first() else null
        names.forEach { modelName ->
            if (effectiveKind != null) {
                val existing = existingModels.firstOrNull { it.name == modelName }
                if (existing != null) return@forEach
                db.modelOptionDao().insert(
                    ModelOptionEntity(
                        id = UUID.randomUUID().toString(),
                        name = modelName,
                        kind = effectiveKind,
                        channelId = channel.id,
                        enabled = true,
                        isDefault = false,
                        capabilities = kindCaps(effectiveKind),
                        resolutions = resolveResolutions(modelName),
                    )
                )
            } else {
                val (k, capabilities) = classifyModel(modelName)
                val existing = db.modelOptionDao().all().firstOrNull {
                    it.channelId == channel.id && it.name == modelName
                }
                if (existing != null) return@forEach
                db.modelOptionDao().insert(
                    ModelOptionEntity(
                        id = UUID.randomUUID().toString(),
                        name = modelName,
                        kind = k,
                        channelId = channel.id,
                        enabled = true,
                        isDefault = false,
                        capabilities = capabilities,
                        // 自动按模型名匹配已知分辨率；未命中留空，由用户在生成页手动填写
                        resolutions = resolveResolutions(modelName),
                    )
                )
            }
        }
        // 自动发现：将该渠道下「分辨率仍为空、但知识库能匹配」的旧模型补全（用户之前保存过的模型也能拿到调研档位）
        autoFillChannelResolutions(channel.id)
    }

    /** 已修正的旧档位 → 当前正确档位。曾自动预填过的错误取值，只要仍存着就纠正，
     *  但绝不覆盖用户手动填写的其他值（手动值不会恰好等于这些长串）。 */
    private val staleResolutionFixes = mapOf(
        // 上游模型 U1 Fast 旧值（错误）：1024/1536/2560 等非官方档位
        "1024x1024,2048x2048,1536x1024,1024x1536,2560x1024,1024x2560" to
            "2048x2048,2496x1664,1664x2496,2368x1760,1760x2368,2272x1824,1824x2272,2752x1536,1536x2752,2752x1184,1184x2752",
        // wan2.6/wan2.5 旧预填值：修正为官方推荐预设
        "1024x1024,1024x1536,1536x1024,1280x1024,1024x1280" to
            "1280x1280,1696x960,960x1696,1472x1104,1104x1472",
        // qwen-image 旧预填值：修正为官方固定预设
        "1024x1024,2048x2048,1024x1536,1536x1024" to
            "1664x928,1328x1328,1472x1104,1104x1472,928x1664",
        // cogview 旧预填值（含 2048x2048 超出 2^21 像素上限）：修正为合法档
        "1024x1024,1440x720,720x1440,2048x2048,1280x1280" to
            "1024x1024,1440x720,720x1440,1536x1024,1024x1536,1280x1280",
        // seedream/doubao 旧预填值：修正为官方 1K/2K/4K + 多比例预设
        "1024x1024,2048x2048,1024x1536,1536x1024,1280x720,720x1280" to
            "1K,2K,4K,2048x2048,2560x1440,1440x2560,2304x1728,1728x2304",
    )

    /** 为该渠道内已有的模型条目自动补全分辨率：仅当条目当前无分辨率且知识库命中时才写入，保留用户的手动设置 */
    internal suspend fun autoFillChannelResolutions(channelId: String) {
        db.modelOptionDao().all()
            .filter { it.channelId == channelId }
            .forEach { m ->
                val corrected = staleResolutionFixes[m.resolutions] ?: run {
                    // 无旧值需纠正：仅填空缺
                    if (m.resolutions.isNotBlank()) return@forEach
                    val known = resolveResolutions(m.name)
                    if (known.isBlank()) return@forEach
                    known
                }
                if (corrected != m.resolutions) {
                    db.modelOptionDao().insert(m.copy(resolutions = corrected))
                }
            }
    }

    /** 对所有渠道做一次自动补全/纠正（App 启动或设置页刷新时调用，幂等） */
    internal suspend fun autoFillAllResolutions() {
        db.modelOptionDao().all().forEach { m ->
            val corrected = staleResolutionFixes[m.resolutions] ?: run {
                // 无旧值需纠正：仅填空缺
                if (m.resolutions.isNotBlank()) return@forEach
                val known = resolveResolutions(m.name)
                if (known.isBlank()) return@forEach
                known
            }
            if (corrected != m.resolutions) {
                db.modelOptionDao().insert(m.copy(resolutions = corrected))
            }
        }
    }

    /** 媒体类型对应的（参考/生成）能力标签 */
    internal fun kindCaps(kind: MediaKind): String = when (kind) {
        MediaKind.TEXT -> "text,reference"
        MediaKind.IMAGE -> "image,reference"
        MediaKind.VIDEO -> "video,reference,audio"
        MediaKind.AUDIO -> "audio"
        else -> "text"
    }

    /** 内置分辨率知识库：按模型名关键词自动匹配该模型已知支持的合法分辨率。
     *  返回逗号分隔字符串；未命中返回空（让用户在生成页手动填写，不硬塞未知档位）。
     *  数据来源：各家官方 API 文档（阿里云百炼、腾讯混元、智谱 BIGModel、火山/豆包 Seedream 等）。
     *  说明：标准 OpenAI 兼容 /models 不会返回分辨率，只有已知模型能可靠预填。 */
    internal fun resolveResolutions(modelName: String): String {
        val n = modelName.lowercase()
        // —— 图片模型 ——
        // OpenAI 系
        if (containsAny(n, "上游图模型", "上游图模型-1", "上游图模型-2")) return "1024x1024,1536x1024,1024x1536"
        if (containsAny(n, "上游图模型-2", "上游图模型2", "上游图模型 2")) return "1024x1024,512x512,256x256"
        if (containsAny(n, "上游图模型-3", "上游图模型3", "上游图模型 3")) return "1024x1024,1792x1024,1024x1792"
        // 阿里云通义万相 / 千问 Qwen-Image（多源核实：阿里云百炼「文本生成图像」官方文档 + QwenCloud API 参考 + DashScope SDK）
        // wan2.7-image-pro：官方支持 1K(1024x1024)/2K(2048x2048)/4K(4096x4096)，默认 2K，宽高比 1:8–8:1
        if (containsAny(n, "wan2.7", "wanx2.7")) return "1024x1024,2048x2048,4096x4096"
        // wan2.6-t2i / wan2.5-t2i-preview：总像素在 [1280x1280, 1440x1440]，宽高比 1:4–4:1；官方推荐就这套预设
        if (containsAny(n, "wan2.6", "wan2.5", "wanx2.6", "wanx2.5", "wan2.6-t2i", "wan2.5-t2i")) return "1280x1280,1696x960,960x1696,1472x1104,1104x1472"
        // wan2.2/wan2.1/wan2.0 等旧版：宽高 ∈ [512,1440]（单边不超 1440，总面积≤1440²）
        if (containsAny(n, "wan2.2", "wan2.1", "wanx2.2", "wanx2.1", "wan2.0", "wanx2.0", "wan-image", "wanx")) return "1024x1024,1280x720,720x1280,1280x1280,1440x1440"
        // qwen-image / qwen-image-plus / qwen-image-max：官方固定预设，默认 1664x928(16:9)
        if (containsAny(n, "qwen-image", "qwen-img", "qwen-2-5")) return "1664x928,1328x1328,1472x1104,1104x1472,928x1664"
        // 腾讯混元 Hy-Image（hy-image-v3 / hunyuan-image）：宽高[512,2048] 且面积≤1024²，官方 37 组预设，取常用档
        if (containsAny(n, "hy-image", "hunyuan-image", "hunyuanimg", "混元")) return "1024x1024,1280x720,720x1280,1152x896,896x1152"
        // 智谱 CogView-4 / GLM-Image：宽高 [512,2048] 且能被 32 整除，最大像素 ≤ 2^21（2K 上限）；官方示例用 1440x720
        if (containsAny(n, "cogview", "glm-image", "glm-4v")) return "1024x1024,1440x720,720x1440,1536x1024,1024x1536,1280x1280"
        // 字节豆包 / 即梦 Seedream 4.0+：size 支持 1K/2K/4K 或具体像素，默认 2048x2048(1:1)，官方预设多比例
        if (containsAny(n, "seedream", "doubao", "jimeng", "即梦", "豆包", "born-in-speech")) return "1K,2K,4K,2048x2048,2560x1440,1440x2560,2304x1728,1728x2304"
        // 上游模型 上游模型 U1.5 Lite：支持 4K 真实视觉创作（U1 升级版），在 U1 的 2K 基准上加 4K 档位
        if (containsAny(n, "u1.5", "u1-5", "u15", "上游模型-u1.5")) return "4K,2K,2048x2048,2496x1664,1664x2496,2368x1760,1760x2368,2272x1824,1824x2272,2752x1536,1536x2752,2752x1184,1184x2752,3840x2160,2160x3840,4096x4096"
        // 上游模型 上游模型 U1 / U1 Fast：信息图专用，经 /v1/images/generations 调用，
        // 2K 基准输出，官方支持 11 种宽高比（比例从 9:21 到 21:9）。取自官方 MCP 仓库尺寸表 + 官方 PR。#115。
        if (containsAny(n, "上游模型", "sense-nova", "上游模型", "u1-fast", "上游模型-u1")) return "2048x2048,2496x1664,1664x2496,2368x1760,1760x2368,2272x1824,1824x2272,2752x1536,1536x2752,2752x1184,1184x2752"
        // Agnes（agnes-image / agnes-video，OpenAI 兼容网关）
        if (containsAny(n, "agnes-image", "agnes-image-2", "agnesvideo")) {
            return "1024x1024,1536x1024,1024x1536"
        }
        if (containsAny(n, "agnes") && containsAny(n, "video", "v2")) return "480P,720P,1080P"
        if (containsAny(n, "agnes")) return "1024x1024,1536x1024,1024x1536"
        // —— 视频模型 ——
        if (containsAny(n, "veo")) return "720P,1080P"
        if (containsAny(n, "minimax", "minimax", "hailuo")) return "768P,2K"
        if (containsAny(n, "wan2.5-t2v", "wan2.2-t2v", "wan-t2v", "wan-i2v", "wan2.5-i2v")) return "720P,1080P"
        if (containsAny(n, "kling", "可灵")) return "720P,2K"
        if (containsAny(n, "sora", "runway", "pika")) return "720P,1080P"
        // 其余未知 → 留空，手动输入
        return ""
    }

    /** 根据模型名推断其媒体类型与能力标签（仅用于把"新增渠道"的模型目录落成可选模型） */
    internal fun classifyModel(modelName: String): Pair<MediaKind, String> {
        val n = modelName.lowercase()
        return when {
            containsAny(n, "video", "veo", "sora", "minimax", "minimax", "runway", "pika", "text-to-video") ->
                MediaKind.VIDEO to "video"
            containsAny(n, "image", "dall", "img", "上游图模型", "上游图模型", "stable", "行业工具", "cogview") ->
                MediaKind.IMAGE to "image,reference"
            containsAny(n, "tts", "audio", "voice", "whisper", "sing") ->
                MediaKind.AUDIO to "audio"
            containsAny(n, "gpt", "llm", "chat", "chat-model", "deepseek", "qwen", "glm", "moonshot", "kimi", "text") ->
                MediaKind.TEXT to "text,reference"
            // 保底归入文本（最新的语言模型能力区间）
            else -> MediaKind.TEXT to "text,reference"
        }
    }

    internal fun containsAny(value: String, vararg keys: String): Boolean =
        keys.any { value.contains(it) }

    suspend fun update(channel: ChannelEntity) {
        db.channelDao().insert(channel)
        // 编辑模型目录后，把新增的模型名同步成可用模型条目（已有的按名字去重，不重复建）
        if (channel.modelCatalog.isNotBlank()) syncCatalogModels(channel)
    }

    suspend fun delete(id: String) {
        db.channelDao().deleteById(id)
        db.modelOptionDao().deleteByChannel(id)
        settings.removeSecret(id)
    }

    suspend fun setModelEnabled(modelId: String, enabled: Boolean) {
        db.modelOptionDao().setEnabled(modelId, enabled)
    }

    suspend fun setModelKind(modelId: String, kind: MediaKind) {
        db.modelOptionDao().setKind(modelId, kind.name)
    }

    suspend fun deleteModel(modelId: String) {
        db.modelOptionDao().deleteById(modelId)
    }

    /** 测试某渠道的连通性（校验 Base URL + API Key），返回 (是否成功, 可读信息) */
    suspend fun testConnection(channelId: String): Pair<Boolean, String> {
        val entity = db.channelDao().byId(channelId) ?: return false to "渠道不存在"
        return try {
            val domain = toDomain(entity)
            val msg = gateway.testConnection(domain, secrets(channelId))
            true to msg
        } catch (e: Exception) {
            false to (e.message ?: "连接失败")
        }
    }

    /** 拉取某供应商真实模型 id 列表（用当前填写的 Base URL + API Key，无需先保存） */
    suspend fun fetchModelList(baseUrl: String, apiKey: String): List<String> =
        gateway.listModelsRaw(baseUrl, apiKey)

    /** 用已保存渠道的 Base URL + 已保存 Key 拉取真实模型 id 列表 */
    suspend fun fetchModelsForChannel(channelId: String): List<String> {
        val entity = db.channelDao().byId(channelId) ?: return emptyList()
        return gateway.listModelsRaw(entity.baseUrl, secrets(channelId).apiKey)
    }

    /** 校验渠道是否可直连：地址与密钥齐备 */
    fun assertReady(channel: Channel, secrets: ChannelSecrets) {
        if (channel.baseUrl.isBlank()) throw TapcreatorException("渠道 ${channel.name} 未配置接口地址", "CHANNEL_NO_URL")
        if (secrets.apiKey.isBlank()) throw TapcreatorException("渠道 ${channel.name} 未配置 API Key", "CHANNEL_NO_KEY")
    }

    /** 地址含 MiniMax 时自动识别为 MiniMax H3 协议（官方 api.minimaxi.com / api.minimax.io 与 metaso.cn/api/MiniMax 共用 schema） */
    internal fun detectProtocol(baseUrl: String, fallback: Protocol): Protocol =
        if (fallback == Protocol.OPENAI_COMPAT && baseUrl.lowercase().contains("minimax")) Protocol.MINIMAX_H3 else fallback
}