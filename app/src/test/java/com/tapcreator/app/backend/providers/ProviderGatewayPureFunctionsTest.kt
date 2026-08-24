package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.ModelOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderGateway 纯函数单测：URL 归一化、端点候选、比例转换、分辨率选择。
 * 这些函数决定发给上游的请求格式，错误会导致生成失败。
 */
class ProviderGatewayPureFunctionsTest {

    // ProviderGateway 构造只依赖 OkHttpClient，可直接 new
    private val gateway = ProviderGateway(okhttp3.OkHttpClient())

    // ============ normalizeBase ============

    @Test
    fun `normalizeBase strips trailing slash`() {
        assertEquals("https://api.example.com/v1", gateway.normalizeBase("https://api.example.com/v1/"))
    }

    @Test
    fun `normalizeBase strips chat completions endpoint`() {
        assertEquals("https://api.example.com/v1", gateway.normalizeBase("https://api.example.com/v1/chat/completions"))
    }

    @Test
    fun `normalizeBase strips images generations endpoint`() {
        assertEquals("https://api.example.com", gateway.normalizeBase("https://api.example.com/images/generations"))
    }

    @Test
    fun `normalizeBase strips multiple endpoints in sequence`() {
        val result = gateway.normalizeBase("https://api.example.com/v1/chat/completions")
        assertEquals("https://api.example.com/v1", result)
    }

    @Test
    fun `normalizeBase handles plain base url`() {
        assertEquals("https://api.example.com", gateway.normalizeBase("https://api.example.com"))
    }

    // ============ endpointUrls ============

    @Test
    fun `endpointUrls returns single url for non-openai endpoints`() {
        val urls = gateway.endpointUrls("https://api.example.com", "/v2/video_generation")
        assertEquals(1, urls.size)
        assertEquals("https://api.example.com/v2/video_generation", urls[0])
    }

    @Test
    fun `endpointUrls adds v1 variant when base lacks v1`() {
        val urls = gateway.endpointUrls("https://api.example.com", "/chat/completions")
        assertEquals(2, urls.size)
        assertEquals("https://api.example.com/chat/completions", urls[0])
        assertEquals("https://api.example.com/v1/chat/completions", urls[1])
    }

    @Test
    fun `endpointUrls does not add v1 when base already has v1`() {
        val urls = gateway.endpointUrls("https://api.example.com/v1", "/chat/completions")
        assertEquals(1, urls.size)
    }

    // ============ ratioToSize ============

    @Test
    fun `ratioToSize 1 colon 1 returns 1024x1024`() {
        assertEquals("1024x1024", gateway.ratioToSize("1:1"))
    }

    @Test
    fun `ratioToSize 16 colon 9 returns 1792x1024`() {
        assertEquals("1792x1024", gateway.ratioToSize("16:9"))
    }

    @Test
    fun `ratioToSize 9 colon 16 returns 1024x1792`() {
        assertEquals("1024x1792", gateway.ratioToSize("9:16"))
    }

    @Test
    fun `ratioToSize unknown ratio defaults to 1024x1024`() {
        assertEquals("1024x1024", gateway.ratioToSize("unknown"))
    }

    // ============ ratioAspect ============

    @Test
    fun `ratioAspect 16 colon 9 returns 1 dot 778`() {
        val result = gateway.ratioAspect("16:9")
        assertTrue("16:9 应约 1.778", kotlin.math.abs(result - 16.0 / 9.0) < 0.001)
    }

    @Test
    fun `ratioAspect 1 colon 1 returns 1 dot 0`() {
        assertEquals(1.0, gateway.ratioAspect("1:1"), 0.001)
    }

    @Test
    fun `ratioAspect null returns 1 dot 0`() {
        assertEquals(1.0, gateway.ratioAspect(null), 0.001)
    }

    @Test
    fun `ratioAspect invalid returns 1 dot 0`() {
        assertEquals(1.0, gateway.ratioAspect("invalid"), 0.001)
    }

    // ============ sizeAspect ============

    @Test
    fun `sizeAspect 1024x1024 returns 1 dot 0`() {
        assertEquals(1.0, gateway.sizeAspect("1024x1024"), 0.001)
    }

    @Test
    fun `sizeAspect 1792x1024 returns 1 dot 75`() {
        assertTrue(kotlin.math.abs(gateway.sizeAspect("1792x1024") - 1.75) < 0.01)
    }

    @Test
    fun `sizeAspect handles fullwidth multiplication sign`() {
        assertEquals(1.0, gateway.sizeAspect("1024×1024"), 0.001)
    }

    @Test
    fun `sizeAspect invalid returns 1 dot 0`() {
        assertEquals(1.0, gateway.sizeAspect("invalid"), 0.001)
    }

    // ============ sizeProduct ============

    @Test
    fun `sizeProduct 1024x1024 returns 1048576`() {
        assertEquals(1048576L, gateway.sizeProduct("1024x1024"))
    }

    @Test
    fun `sizeProduct 1792x1024 returns 1835008`() {
        assertEquals(1835008L, gateway.sizeProduct("1792x1024"))
    }

    @Test
    fun `sizeProduct invalid returns 1`() {
        assertEquals(1L, gateway.sizeProduct("invalid"))
    }

    // ============ pickImageResolution ============

    @Test
    fun `pickImageResolution prefers same orientation wide`() {
        val supported = listOf("1024x1024", "1792x1024", "1024x1792")
        val result = gateway.pickImageResolution(supported, 1.78) // 16:9 横向
        assertEquals("1792x1024", result)
    }

    @Test
    fun `pickImageResolution prefers same orientation tall`() {
        val supported = listOf("1024x1024", "1792x1024", "1024x1792")
        val result = gateway.pickImageResolution(supported, 0.56) // 9:16 竖向
        assertEquals("1024x1792", result)
    }

    @Test
    fun `pickImageResolution falls back to all when no same orientation`() {
        val supported = listOf("1792x1024") // 只有横向
        val result = gateway.pickImageResolution(supported, 0.56) // 要竖向
        assertEquals("1792x1024", result) // 回退到全量
    }

    // ============ normalizeResolution ============

    @Test
    fun `normalizeResolution returns null when model has no resolutions`() {
        val model = ModelOption(id = "m1", name = "test", kind = MediaKind.IMAGE, channelId = "ch1", resolutions = "")
        assertNull(gateway.normalizeResolution(model, MediaKind.IMAGE, null, "1:1", "high"))
    }

    @Test
    fun `normalizeResolution returns explicit when in supported set`() {
        val model = ModelOption(id = "m1", name = "test", kind = MediaKind.IMAGE, channelId = "ch1", resolutions = "1024x1024,1792x1024")
        assertEquals("1792x1024", gateway.normalizeResolution(model, MediaKind.IMAGE, "1792x1024", null, null))
    }

    @Test
    fun `normalizeResolution image picks by ratio when no explicit`() {
        val model = ModelOption(id = "m1", name = "test", kind = MediaKind.IMAGE, channelId = "ch1", resolutions = "1024x1024,1792x1024,1024x1792")
        val result = gateway.normalizeResolution(model, MediaKind.IMAGE, null, "16:9", null)
        assertEquals("1792x1024", result)
    }

    @Test
    fun `normalizeResolution video picks high quality`() {
        val model = ModelOption(id = "m1", name = "test", kind = MediaKind.VIDEO, channelId = "ch1", resolutions = "720P,1080P,2K")
        val result = gateway.normalizeResolution(model, MediaKind.VIDEO, null, null, "high")
        assertEquals("2K", result)
    }

    @Test
    fun `normalizeResolution video picks low quality`() {
        val model = ModelOption(id = "m1", name = "test", kind = MediaKind.VIDEO, channelId = "ch1", resolutions = "720P,1080P,2K")
        val result = gateway.normalizeResolution(model, MediaKind.VIDEO, null, null, "low")
        assertEquals("720P", result)
    }
}