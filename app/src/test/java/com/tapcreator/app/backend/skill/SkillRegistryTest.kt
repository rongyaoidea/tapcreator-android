package com.tapcreator.app.backend.skill

import com.tapcreator.app.data.model.BuiltinSkills
import com.tapcreator.app.data.model.DesignSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DesignSkill / BuiltinSkills / SkillRegistry 的纯逻辑单测。
 * 不依赖 Android 环境，纯 JVM 可测。
 */
class SkillRegistryTest {

    @Test
    fun `BuiltinSkills has 9 presets`() {
        assertEquals("应有 9 个内置预设", 9, BuiltinSkills.presets.size)
    }

    @Test
    fun `every builtin skill has required fields`() {
        BuiltinSkills.presets.forEach { s ->
            assertTrue("技能 ${s.id} 缺 id", s.id.isNotBlank())
            assertTrue("技能 ${s.id} 缺 name", s.name.isNotBlank())
            assertTrue("技能 ${s.id} 缺 promptGuide", s.promptGuide.isNotBlank())
            assertTrue("技能 ${s.id} category 无效", s.category in setOf("photo", "poster"))
            assertTrue("技能 ${s.id} 应标记为 builtIn", s.builtIn)
        }
    }

    @Test
    fun `photo category skills require image`() {
        val photoSkills = BuiltinSkills.presets.filter { it.category == "photo" }
        assertTrue("photo 类应有 requiresImage=true", photoSkills.all { it.requiresImage })
    }

    @Test
    fun `poster category skills do not require image`() {
        val posterSkills = BuiltinSkills.presets.filter { it.category == "poster" }
        assertTrue("poster 类应有 requiresImage=false", posterSkills.all { !it.requiresImage })
    }

    @Test
    fun `photo skills have suggested ratios`() {
        val photoSkills = BuiltinSkills.presets.filter { it.category == "photo" }
        assertTrue("photo 类应有建议比例", photoSkills.all { it.suggestedRatios.isNotBlank() })
    }

    @Test
    fun `preset ids are unique`() {
        val ids = BuiltinSkills.presets.map { it.id }
        assertEquals("id 应唯一", ids.size, ids.toSet().size)
    }

    @Test
    fun `can install and list skills`() {
        val registry = TestSkillRegistry()
        assertEquals("初始只有内置预设", 9, registry.all().size)

        val custom = DesignSkill(
            id = "test-vintage",
            name = "test-vintage",
            category = "photo",
            promptGuide = "Vintage style test",
            requiresImage = true,
            builtIn = false,
        )
        // 模拟 install（TestSkillRegistry 直接操作内存）
        registry.installForTest(custom)
        assertEquals("安装后应有 10 个", 10, registry.all().size)
        assertNotNull("可按 id 查找", registry.byId("test-vintage"))
    }

    @Test
    fun `can find skill by id`() {
        val revival = BuiltinSkills.presets.firstOrNull { it.id == "photo-revival" }
        assertNotNull("photo-revival 应存在", revival)
        assertEquals("photo-revival 是 photo 类", "photo", revival?.category)
        assertTrue("photo-revival 需原图", revival?.requiresImage == true)
    }

    @Test
    fun `all builtin skills have description`() {
        BuiltinSkills.presets.forEach { s ->
            assertTrue("技能 ${s.id} 缺 description", s.description.isNotBlank())
        }
    }
}

/** 内存版 SkillRegistry（不依赖 DataStore），仅用于测试 */
class TestSkillRegistry {
    private val installed = mutableListOf<DesignSkill>()

    fun all(): List<DesignSkill> = BuiltinSkills.presets + installed

    fun byId(id: String): DesignSkill? = all().firstOrNull { it.id == id }

    fun installForTest(skill: DesignSkill) {
        val idx = installed.indexOfFirst { it.id == skill.id }
        if (idx >= 0) installed[idx] = skill else installed.add(skill)
    }

    fun uninstall(id: String): Boolean = installed.removeAll { it.id == id }
}