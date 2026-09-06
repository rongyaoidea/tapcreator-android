package com.tapcreator.app.backend.service

import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChannelRepository 纯函数单测：模型分辨率知识库、模型分类、协议检测。
 * 这些函数不依赖实例状态，直接复制实现逻辑测试。
 */
class ChannelRepositoryPureFunctionsTest {

    // ============ resolveResolutions（核心匹配逻辑） ============

    /** 直接测生产表驱动目录（ResolutionCatalog），不再维护容易脱节的私有镜像 */
    private fun resolveResolutions(modelName: String): String = ResolutionCatalog.lookup(modelName)

    private fun classifyModel(modelName: String): Pair<MediaKind, String> {
        val n = modelName.lowercase()
        return when {
            containsAny(n, "video", "veo", "sora", "kling", "hailuo", "runway", "pika") ->
                MediaKind.VIDEO to "video"
            containsAny(n, "image", "dall", "img", "sdxl", "flux", "stable", "midjourney", "cogview") ->
                MediaKind.IMAGE to "image"
            containsAny(n, "tts", "audio", "voice", "whisper", "sing") ->
                MediaKind.AUDIO to "audio"
            containsAny(n, "gpt", "llm", "chat", "claude", "deepseek", "qwen", "glm", "moonshot", "kimi", "text") ->
                MediaKind.TEXT to "text,reference"
            else -> MediaKind.TEXT to "text,reference"
        }
    }

    private fun detectProtocol(baseUrl: String, fallback: Protocol): Protocol =
        if (fallback == Protocol.OPENAI_COMPAT && baseUrl.lowercase().contains("minimax")) Protocol.MINIMAX_H3 else fallback

    private fun kindCaps(kind: MediaKind): String = when (kind) {
        MediaKind.TEXT -> "text,reference"
        MediaKind.IMAGE -> "image"
        MediaKind.VIDEO -> "video"
        MediaKind.AUDIO -> "audio"
        else -> "text"
    }

    private fun containsAny(value: String, vararg keys: String): Boolean =
        keys.any { value.contains(it) }

    // ============ resolveResolutions 测试 ============

    @Test
    fun `resolveResolutions 上游图模型 returns known sizes`() {
        val result = resolveResolutions("上游图模型-2")
        assertTrue(result.isNotBlank())
        assertTrue(result.contains("1024x1024"))
        assertTrue(result.contains("1536x1024"))
    }

    @Test
    fun `resolveResolutions wan2-7 returns 4K sizes`() {
        val result = resolveResolutions("wan2.7-image-pro")
        assertTrue(result.contains("1024x1024"))
        assertTrue(result.contains("4096x4096"))
    }

    @Test
    fun `resolveResolutions qwen-image returns fixed presets`() {
        val result = resolveResolutions("qwen-image-max")
        assertTrue(result.contains("1664x928"))
    }

    @Test
    fun `resolveResolutions minimax h3 returns video sizes`() {
        val result = resolveResolutions("minimax-h3")
        assertTrue(result.contains("768P"))
        assertTrue(result.contains("2K"))
    }

    @Test
    fun `resolveResolutions veo returns video sizes`() {
        val result = resolveResolutions("veo-2")
        assertTrue(result.contains("720P"))
        assertTrue(result.contains("1080P"))
    }

    @Test
    fun `resolveResolutions kling returns video sizes`() {
        val result = resolveResolutions("kling-v2")
        assertTrue(result.contains("720P") || result.contains("2K"))
    }

    @Test
    fun `resolveResolutions seedream returns image sizes`() {
        val result = resolveResolutions("seedream-4")
        assertTrue(result.contains("1K") || result.contains("2K"))
    }

    @Test
    fun `resolveResolutions agnes video returns video sizes`() {
        val result = resolveResolutions("agnes-video-v2")
        assertTrue(result.contains("480P"))
        assertTrue(result.contains("1080P"))
    }

    @Test
    fun `resolveResolutions unknown model returns empty`() {
        assertEquals("", resolveResolutions("totally-unknown-model-xyz"))
    }

    @Test
    fun `resolveResolutions case insensitive`() {
        assertEquals(resolveResolutions("wan2.7-image-pro"), resolveResolutions("WAN2.7-IMAGE-PRO"))
    }

    @Test
    fun `resolveResolutions overrides take precedence`() {
        try {
            ResolutionCatalog.overrides = mapOf("自定义模型-x" to "512x512,768x768")
            assertEquals("512x512,768x768", resolveResolutions("自定义模型-x"))
            // 覆盖不应影响未覆盖模型的目录匹配
            assertTrue(resolveResolutions("veo-2").contains("1080P"))
        } finally {
            ResolutionCatalog.overrides = emptyMap()
        }
    }

    // ============ classifyModel 测试 ============

    @Test
    fun `classifyModel video keyword returns VIDEO`() {
        assertEquals(MediaKind.VIDEO, classifyModel("some-video-model").first)
    }

    @Test
    fun `classifyModel image keyword returns IMAGE`() {
        assertEquals(MediaKind.IMAGE, classifyModel("dall-e-3").first)
    }

    @Test
    fun `classifyModel audio keyword returns AUDIO`() {
        assertEquals(MediaKind.AUDIO, classifyModel("tts-1").first)
    }

    @Test
    fun `classifyModel llm keyword returns TEXT`() {
        assertEquals(MediaKind.TEXT, classifyModel("gpt-4o").first)
    }

    @Test
    fun `classifyModel unknown defaults to TEXT`() {
        assertEquals(MediaKind.TEXT, classifyModel("random-model-xyz").first)
    }

    // ============ detectProtocol 测试 ============

    @Test
    fun `detectProtocol minimax url returns MINIMAX_H3`() {
        assertEquals(Protocol.MINIMAX_H3, detectProtocol("https://api.minimaxi.com", Protocol.OPENAI_COMPAT))
    }

    @Test
    fun `detectProtocol metaso minimax url returns MINIMAX_H3`() {
        assertEquals(Protocol.MINIMAX_H3, detectProtocol("https://metaso.cn/api/minimax", Protocol.OPENAI_COMPAT))
    }

    @Test
    fun `detectProtocol openai url keeps OPENAI_COMPAT`() {
        assertEquals(Protocol.OPENAI_COMPAT, detectProtocol("https://api.openai.com/v1", Protocol.OPENAI_COMPAT))
    }

    @Test
    fun `detectProtocol non-minax with openai fallback keeps fallback`() {
        assertEquals(Protocol.OPENAI_COMPAT, detectProtocol("https://api.example.com", Protocol.OPENAI_COMPAT))
    }

    @Test
    fun `detectProtocol non-openai fallback keeps fallback`() {
        assertEquals(Protocol.SEEDANCE, detectProtocol("https://api.example.com", Protocol.SEEDANCE))
    }

    // ============ kindCaps 测试 ============

    @Test
    fun `kindCaps TEXT returns text reference`() {
        val caps = kindCaps(MediaKind.TEXT)
        assertTrue(caps.contains("text"))
        assertTrue(caps.contains("reference"))
    }

    @Test
    fun `kindCaps IMAGE returns image`() {
        assertEquals("image", kindCaps(MediaKind.IMAGE))
    }

    @Test
    fun `kindCaps VIDEO returns video`() {
        assertEquals("video", kindCaps(MediaKind.VIDEO))
    }

    @Test
    fun `kindCaps AUDIO returns audio`() {
        assertEquals("audio", kindCaps(MediaKind.AUDIO))
    }
}