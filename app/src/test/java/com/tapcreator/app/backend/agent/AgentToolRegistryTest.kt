package com.tapcreator.app.backend.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentToolRegistry 纯逻辑单测：校验工具清单的完整性与结构正确性。
 * 这两个函数（toPromptTable / toFunctionSchemas）是注入 Agent system prompt 的数据源，
 * 输出错误会导致 Agent 拿到错误工具清单而无法正确规划。纯 JVM 可测，无 Android 依赖。
 */
class AgentToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `tools list contains all core declared tools`() {
        val names = AgentToolRegistry.tools.map { it.name }.toSet()
        // 核心工具必须存在：生成 + 卡片 CRUD + 素材库 + 记忆 + 搜索 + 设计 Skill + 关系图 + finish
        listOf(
            "generate", "list_cards", "read_card", "update_card", "delete_card",
            "list_assets", "update_asset", "move_asset", "delete_asset",
            "create_folder", "delete_folder",
            "memorize", "recall", "list_runs", "read_trace",
            "web_search", "fetch_url",
            "configure_resolution",
            "list_skills", "apply_skill", "skill_creator", "uninstall_skill",
            "link_cards", "unlink_cards", "layout_canvas", "finish",
        ).forEach {
            assertTrue("缺少工具: $it", names.contains(it))
        }
        // 数量不硬编码：避免新增工具时误报。只校验不低于核心集合大小。
        assertTrue("工具数应不少于核心集合", AgentToolRegistry.tools.size >= 23)
    }

    @Test
    fun `every tool has name desc and params list`() {
        AgentToolRegistry.tools.forEach { t ->
            assertTrue("工具 ${t.name} 缺 desc", t.desc.isNotBlank())
            // params 可为空（如 finish 之外的 list 类），但 required 字段必须齐全
            t.params.forEach { p ->
                assertTrue("工具 ${t.name} 参数 ${p.name} 缺 type", p.type.isNotBlank())
                assertTrue("工具 ${t.name} 参数 ${p.name} 缺 desc", p.desc.isNotBlank())
            }
        }
    }

    @Test
    fun `generate tool has required params tool and prompt`() {
        val gen = AgentToolRegistry.tools.first { it.name == "generate" }
        val required = gen.params.filter { it.required }.map { it.name }
        assertTrue("generate 必须要求 tool", required.contains("tool"))
        assertTrue("generate 必须要求 prompt", required.contains("prompt"))
    }

    @Test
    fun `toPromptTable contains every tool name and is valid JSON-ish`() {
        val table = AgentToolRegistry.toPromptTable()
        // 每个工具名都应出现在渲染结果里（注入 system prompt 后 Agent 据此选 action）
        AgentToolRegistry.tools.forEach { t ->
            assertTrue("toPromptTable 缺工具名 ${t.name}", table.contains("\"${t.name}\""))
        }
        // 含 generate 示例与规则说明
        assertTrue(table.contains("generate 示例"))
        assertTrue(table.contains("action"))
    }

    @Test
    fun `toFunctionSchemas returns valid JSON array with one entry per tool`() {
        val schemas: JsonArray = AgentToolRegistry.toFunctionSchemas()
        assertEquals(AgentToolRegistry.tools.size, schemas.size)
        // 每项结构：{type:function, function:{name, description, parameters}}
        schemas.forEach { el ->
            val obj = el.jsonObject
            assertEquals("function", obj["type"]!!.jsonPrimitive.content)
            val fn = obj["function"]!!.jsonObject
            assertNotNull(fn["name"])
            assertNotNull(fn["description"])
            val params = fn["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
            assertNotNull(params["properties"])
        }
    }

    @Test
    fun `function schemas include required array when tool has required params`() {
        val schemas: JsonArray = AgentToolRegistry.toFunctionSchemas()
        val gen = schemas.first {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "generate"
        }
        val params = gen.jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        val required = params["required"]?.jsonArray
        assertNotNull("generate 有 required 参数，schema 应含 required 数组", required)
        val reqNames = required!!.map { it.jsonPrimitive.content }
        assertTrue("generate 的 required 应含 tool", reqNames.contains("tool"))
        assertTrue("generate 的 required 应含 prompt", reqNames.contains("prompt"))
    }

    @Test
    fun `tools with no required params omit required array`() {
        val schemas: JsonArray = AgentToolRegistry.toFunctionSchemas()
        val listCards = schemas.first {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "list_cards"
        }
        val params = listCards.jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        // list_cards 无 required 参数，不应出现 required 键
        assertFalse("list_cards 无必填参数，不应有 required 数组", params.containsKey("required"))
    }
}
