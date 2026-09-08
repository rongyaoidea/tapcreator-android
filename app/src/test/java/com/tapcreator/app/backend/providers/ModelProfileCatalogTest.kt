package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ModelProfileCatalog 纯函数单测：档案匹配与默认值正确。 */
class ModelProfileCatalogTest {

    @Test
    fun `agnes base url maps to extra body image mode`() {
        assertEquals(
            ImageRefMode.AGNES_EXTRA_BODY_IMAGE,
            ModelProfileCatalog.imageRefMode("https://apihub.agnes-ai.com/v1", "agnes-image-2.1-flash"),
        )
    }

    @Test
    fun `sensenova model name maps to sensenova json edits`() {
        assertEquals(
            ImageRefMode.SENSENOVA_EDITS_JSON,
            ModelProfileCatalog.imageRefMode("https://example.com", "u1.5-lite"),
        )
    }

    @Test
    fun `generic openai compat defaults to multipart edits`() {
        assertEquals(
            ImageRefMode.EDITS_MULTIPART,
            ModelProfileCatalog.imageRefMode("https://api.example.com/v1", "gpt-image-1"),
        )
    }

    @Test
    fun `h3 video profile matches by model name`() {
        val vp = ModelProfileCatalog.videoProfile("https://api.minimaxi.com", "MiniMax-H3")
        assertNotNull(vp)
        assertEquals(15, vp!!.maxSeconds)
        assertEquals(9, vp.maxRefImages)
        assertEquals(3, vp.maxRefVideos)
        assertEquals(15, vp.refVideoMaxSeconds)
        assertEquals(false, vp.hasMotionCfg)
    }

    @Test
    fun `unknown video model has no profile`() {
        assertNull(ModelProfileCatalog.videoProfile("https://api.example.com", "some-video-model"))
    }

    @Test
    fun `catalog summary lists known image and video rows`() {
        val s = ModelProfileCatalog.catalogSummary()
        assertTrue(s.any { it.kind == MediaKind.IMAGE && it.id.contains("agnes") })
        assertTrue(s.any { it.kind == MediaKind.VIDEO && it.id.contains("minimax") })
        assertTrue(s.all { it.summary.isNotBlank() })
    }
}
