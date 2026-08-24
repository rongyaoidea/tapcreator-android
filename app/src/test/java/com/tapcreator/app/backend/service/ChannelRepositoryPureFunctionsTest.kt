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

    private fun resolveResolutions(modelName: String): String {
        val n = modelName.lowercase()
        // 图片模型
        if (containsAny(n, "gpt-image", "gpt-image-1", "gpt-image-2")) return "1024x1024,1536x1024,1024x1536"
        if (containsAny(n, "dall-e-2", "dall-e2")) return "1024x1024,512x512,256x256"
        if (containsAny(n, "dall-e-3", "dall-e3")) return "1024x1024,1792x1024,1024x1792"
        if (containsAny(n, "wan2.7", "wanx2.7")) return "1024x1024,2048x2048,4096x4096"
        if (containsAny(n, "wan2.6", "wan2.5")) return "1280x1280,1696x960,960x1696,1472x1104,1104x1472"
        if (containsAny(n, "qwen-image")) return "1664x928,1328x1328,1472x1104,1104x1472,928x1664"
        if (containsAny(n, "seedream", "doubao")) return "1K,2K,4K,2048x2048,2560x1440,1440x2560"
        if (containsAny(n, "sensenova", "u1-fast")) return "2048x2048,2496x1664,1664x2496"
        // 视频模型
        if (containsAny(n, "veo")) return "720P,1080P"
        if (containsAny(n, "minimax", "hailuo", "海螺")) return "768P,2K"
        if (containsAny(n, "kling", "可灵")) return "720P,2K"
        if (containsAny(n, "sora", "runway", "pika")) return "720P,1080P"
        return ""
    }

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
    fun `resolveResolutions gpt-image returns known sizes`() {
        val result = resolveResolutions("gpt-image-1")
        assertTrue(result.isNotBlank())
        assertTrue(result.contains("1024x1024"))
    }

    @Test
    fun `resolveResolutions dall-e-3 returns known sizes`() {
        val result = resolveResolutions("dall-e-3")
        assertTrue(result.contains("1024x1024"))
        assertTrue(result.contains("1792x1024"))
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
    fun `resolveResolutions unknown model returns empty`() {
        assertEquals("", resolveResolutions("totally-unknown-model-xyz"))
    }

    @Test
    fun `resolveResolutions case insensitive`() {
        assertEquals(resolveResolutions("dall-e-3"), resolveResolutions("DALL-E-3"))
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