package com.tapcreator.app.ui.chat

import com.tapcreator.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLayoutTest {

    private fun n(id: String, kind: MediaKind = MediaKind.IMAGE) = CanvasLayout.Node(id, kind)

    @Test
    fun `layered places upstream left of downstream`() {
        val placements = CanvasLayout.layered(
            nodes = listOf(n("a"), n("b"), n("c")),
            edges = listOf("a" to "b", "b" to "c"),
        ).associateBy { it.id }
        assertEquals(3, placements.size)
        assertTrue("a 应在 b 左侧", placements.getValue("a").x < placements.getValue("b").x)
        assertTrue("b 应在 c 左侧", placements.getValue("b").x < placements.getValue("c").x)
    }

    @Test
    fun `layered handles empty and isolated nodes`() {
        assertTrue(CanvasLayout.layered(emptyList(), emptyList()).isEmpty())
        val p = CanvasLayout.layered(listOf(n("x"), n("y")), emptyList())
        assertEquals(2, p.size)
        assertTrue("无边的节点同层（x 相同）", p[0].x == p[1].x)
    }

    @Test
    fun `layered survives cycles`() {
        val p = CanvasLayout.layered(
            nodes = listOf(n("a"), n("b")),
            edges = listOf("a" to "b", "b" to "a"),
        )
        assertEquals(2, p.size)
    }

    @Test
    fun `snap aligns to nearby node and reports guide`() {
        val res = CanvasLayout.snap(
            x = 103f, y = 50f, selfId = "me",
            others = listOf(Triple("other", 100f, 200f)),
            threshold = 8f,
        )
        assertEquals(100f, res.x, 0.001f)
        assertEquals(100f, res.guideX)
        assertEquals(50f, res.y, 0.001f)
    }

    @Test
    fun `hitTest returns intersecting nodes only`() {
        val sel = CanvasLayout.Rect(0f, 0f, 50f, 50f)
        val hits = CanvasLayout.hitTest(
            sel,
            listOf(
                "inside" to CanvasLayout.Rect(10f, 10f, 20f, 20f),
                "outside" to CanvasLayout.Rect(100f, 100f, 120f, 120f),
            ),
        )
        assertEquals(listOf("inside"), hits)
        assertNotNull(hits)
    }
}
