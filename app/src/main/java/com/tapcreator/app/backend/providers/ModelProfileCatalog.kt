package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.MediaKind

/**
 * 图像参考的发送形态。不同网关接口差异巨大且互不通用：
 *  - agnes：无 /images/edits，参考图放 /images/generations 的 extra_body.image（URL/data URI 数组）
 *  - 商汤 SenseNova：/images/edits，顶层 image=纯 base64（JSON）
 *  - 其余 OpenAI 兼容：/images/edits multipart 优先，失败链式兜底多模态 chat / generations 顶层 image
 */
enum class ImageRefMode(val label: String) {
    EDITS_MULTIPART("OpenAI edits(multipart)→多模态→generations"),
    SENSENOVA_EDITS_JSON("SenseNova edits(JSON base64)"),
    AGNES_EXTRA_BODY_IMAGE("Agnes generations extra_body.image"),
}

/** 视频生成参数档案：按各模型官方限制预置（H3 Ref2VA ≤9 图/≤3 视频·每段≤15s·≤50MB/≤3 音频·≤15s·≤15MB，单次 4–15s）。 */
data class VideoProfile(
    val maxSeconds: Int = 15,
    val maxRefImages: Int = 9,
    val maxRefVideos: Int = 3,
    val refVideoMaxSeconds: Int? = 15,
    val refVideoMaxBytes: Long = 50L * 1024 * 1024,
    val maxRefAudios: Int = 3,
    val refAudioMaxSeconds: Int? = 15,
    val refAudioMaxBytes: Long = 15L * 1024 * 1024,
    /** H3 V2 无独立 motion/cfg 参数（镜头控制写在 prompt 里）；为 true 的模型才有该字段透传 */
    val hasMotionCfg: Boolean = false,
) {
    companion object {
        /** MiniMax H3（官方：单次 4–15s；Ref2VA 参考图 ≤9、视频 ≤3·每段&合计≤15s·≤50MB、音频 ≤3·≤15s·≤15MB） */
        val MINIMAX_H3 = VideoProfile(
            maxSeconds = 15,
            maxRefImages = 9,
            maxRefVideos = 3,
            refVideoMaxSeconds = 15,
            refVideoMaxBytes = 50L * 1024 * 1024,
            maxRefAudios = 3,
            refAudioMaxSeconds = 15,
            refAudioMaxBytes = 15L * 1024 * 1024,
            hasMotionCfg = false,
        )
    }
}

/**
 * 模型档案注册表（静态知识，参照 ResolutionCatalog）：把「图生图参考形态 / 视频参数」按
 * baseUrl/模型名关键字匹配成档案行。加新网关/新模型 = 加一行数据，不改 dispatcher 代码。
 * 命中顺序 = 列表顺序（具体在前，未命中走默认）。未知模型：
 *  - 图像参考默认 ImageRefMode.EDITS_MULTIPART（链式兜底，保持现状行为）
 *  - 视频无档案 → 调用方沿用通用路径
 */
object ModelProfileCatalog {

    private data class ImageRow(
        val id: String,
        val baseUrlHits: List<String> = emptyList(),
        val modelHits: List<String> = emptyList(),
        val mode: ImageRefMode,
    )

    private data class VideoRow(
        val id: String,
        val baseUrlHits: List<String> = emptyList(),
        val modelHits: List<String> = emptyList(),
        val video: VideoProfile,
    )

    private val imageRows = listOf(
        ImageRow("agnes-image", baseUrlHits = listOf("agnes"), modelHits = listOf("agnes"), mode = ImageRefMode.AGNES_EXTRA_BODY_IMAGE),
        ImageRow("sensenova-image", baseUrlHits = listOf("sensenova", "sensetime"), modelHits = listOf("u1", "日日新", "sensenova"), mode = ImageRefMode.SENSENOVA_EDITS_JSON),
    )

    private val videoRows = listOf(
        VideoRow("minimax-h3", baseUrlHits = listOf("minimaxi", "metaso"), modelHits = listOf("h3"), video = VideoProfile.MINIMAX_H3),
    )

    /**
     * 按 modelId 的参考形态覆盖（进程内，由 DataStore 启动加载 / 设置页或 Agent 写入）。
     * 覆盖优先于档案表：用于「档案未收录但实测需特定形态」或「用户/Agent 纠正」。
     */
    private val imageOverrides = java.util.concurrent.ConcurrentHashMap<String, ImageRefMode>()

    fun setImageRefOverride(modelId: String, mode: ImageRefMode) { imageOverrides[modelId] = mode }
    fun clearImageRefOverride(modelId: String) { imageOverrides.remove(modelId) }
    fun imageRefOverride(modelId: String?): ImageRefMode? = modelId?.let { imageOverrides[it] }
    fun imageRefOverrides(): Map<String, ImageRefMode> = imageOverrides.toMap()

    /**
     * 图像参考形态：modelId 覆盖 > 档案表 > EDITS_MULTIPART（默认 OpenAI 兼容链式兜底）。
     * 不传 modelId（如纯函数单测）时跳过覆盖，直接用档案表。
     */
    fun imageRefMode(baseUrl: String?, modelName: String?, modelId: String? = null): ImageRefMode {
        modelId?.let { imageOverrides[it]?.let { return it } }
        return imageRows.firstOrNull { match(it, baseUrl, modelName) }?.mode ?: ImageRefMode.EDITS_MULTIPART
    }

    /** 视频参数档案：未命中 → null（调用方沿用通用路径与通用常量）。 */
    fun videoProfile(baseUrl: String?, modelName: String?): VideoProfile? =
        videoRows.firstOrNull { match(it, baseUrl, modelName) }?.video

    /** 命中的视频档案 id（UI/日志展示用）；未命中返回 null。 */
    fun videoProfileId(baseUrl: String?, modelName: String?): String? =
        videoRows.firstOrNull { match(it, baseUrl, modelName) }?.id

    /** 供 UI/Agent 展示的已知档案清单（size/名称/形态摘要）。 */
    data class CatalogEntry(val id: String, val kind: MediaKind, val summary: String)

    fun catalogSummary(): List<CatalogEntry> =
        imageRows.map { CatalogEntry(it.id, MediaKind.IMAGE, it.mode.label) } +
            videoRows.map { CatalogEntry(it.id, MediaKind.VIDEO, videoSummary(it.video)) }

    private fun videoSummary(v: VideoProfile): String =
        "单次 ${v.maxSeconds}s 上限；参考图 ≤${v.maxRefImages}、视频 ≤${v.maxRefVideos}(≤${v.refVideoMaxSeconds}s/段)、音频 ≤${v.maxRefAudios}(≤${v.refAudioMaxSeconds}s)；motion 参数=${v.hasMotionCfg}"

    private fun match(row: ImageRow, baseUrl: String?, modelName: String?): Boolean {
        val b = baseUrl?.lowercase() ?: ""
        val m = modelName?.lowercase() ?: ""
        return (row.baseUrlHits.any { b.contains(it) } || row.modelHits.any { m.contains(it) })
    }

    private fun match(row: VideoRow, baseUrl: String?, modelName: String?): Boolean {
        val b = baseUrl?.lowercase() ?: ""
        val m = modelName?.lowercase() ?: ""
        return (row.baseUrlHits.any { b.contains(it) } || row.modelHits.any { m.contains(it) })
    }
}
