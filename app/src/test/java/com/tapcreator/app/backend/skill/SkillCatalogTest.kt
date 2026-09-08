package com.tapcreator.app.backend.skill

import com.tapcreator.app.data.model.DesignSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SkillCatalog.render 纯函数单测：紧凑、可发现、空/超限边界正确。 */
class SkillCatalogTest {

    private fun skill(
        id: String,
        category: String = "poster",
        requiresImage: Boolean = false,
        name: String = id,
        description: String = "desc-$id",
    ) = DesignSkill(
        id = id,
        name = name,
        category = category,
        promptGuide = "guide",
        description = description,
        requiresImage = requiresImage,
        builtIn = true,
    )

    @Test
    fun `empty skills render to empty string`() {
        assertEquals("", SkillCatalog.render(emptyList()))
    }

    @Test
    fun `renders id name and description and mentions apply_skill`() {
        val out = SkillCatalog.render(listOf(skill("gc-minimal")))
        assertTrue(out.contains("gc-minimal"))
        assertTrue(out.contains("desc-gc-minimal"))
        assertTrue("应提示用 apply_skill", out.contains("apply_skill"))
    }

    @Test
    fun `photo skill shows needs-image tag`() {
        val out = SkillCatalog.render(listOf(skill("photo-revival", category = "photo", requiresImage = true)))
        assertTrue("photo 类应标『需原图』", out.contains("需原图"))
    }

    @Test
    fun `non-photo skill row shows category not needs-image`() {
        val out = SkillCatalog.render(listOf(skill("muted", category = "poster")))
        // 只看该 skill 所在的行，说明性 header 文本本身含「需原图」
        val row = out.lines().first { it.contains("muted｜") }
        assertTrue("行内应显示分类", row.contains("poster"))
        assertFalse("非 photo 行不应标『需原图』", row.contains("需原图"))
    }

    @Test
    fun `blank description falls back to name`() {
        val out = SkillCatalog.render(listOf(skill("x", description = "")))
        assertTrue(out.contains("x｜x"))
    }

    @Test
    fun `caps rows and notes remainder`() {
        val many = (1..(SkillCatalog.MAX_ROWS + 5)).map { skill("s$it") }
        val out = SkillCatalog.render(many)
        assertTrue("应有省略提示", out.contains("未列"))
        assertTrue("应显示剩余数量 5", out.contains("另有 5 个"))
        // 渲染的行数不超过上限 + 说明行
        val rowCount = out.lines().count { it.trimStart().startsWith("- s") }
        assertEquals(SkillCatalog.MAX_ROWS, rowCount)
    }
}