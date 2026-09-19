package com.tapcreator.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeParamsTest {

    @Test
    fun `round trips all fields through json`() {
        val src = NodeParams(
            prompt = "一只猫",
            kind = "VIDEO",
            modelId = "m1",
            modelName = "Veo",
            ratio = "16:9",
            quality = "high",
            resolution = "1080P",
            seconds = 8,
            referenceCardIds = listOf("c1", "c2"),
            variantOf = "c0",
            characterFolderId = "f1",
        )
        val decoded = NodeParams.fromJson(src.toJson())
        assertEquals(src, decoded)
    }

    @Test
    fun `fromJson tolerates blank and malformed input`() {
        assertNull(NodeParams.fromJson(null))
        assertNull(NodeParams.fromJson(""))
        assertNull(NodeParams.fromJson("{not json"))
    }

    @Test
    fun `summary joins present fields only`() {
        val s = NodeParams(modelName = "Veo", ratio = "16:9", seconds = 5).summary()
        assertTrue(s.contains("Veo"))
        assertTrue(s.contains("16:9"))
        assertTrue(s.contains("5s"))
    }

    @Test
    fun `snapshot payload round trips`() {
        val payload = CanvasSnapshotPayload(
            nodes = listOf(SnapshotNode("a", 1f, 2f, "{}", "p", 2)),
            links = listOf(SnapshotLink("a", "b", "reference")),
            note = "checkpoint",
        )
        val decoded = CanvasSnapshotPayload.fromJson(payload.toJson())
        assertEquals(payload, decoded)
    }
}
