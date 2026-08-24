package com.tapcreator.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AgentAction JSON 解析单测：Agent 从 LLM 输出中解析 JSON 动作的边界情况。
 * 这是 Agent 工具调用的关键入口，解析错误会导致 Agent 无法正确执行。
 */
class AgentActionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parse generate action`() {
        val raw = """{"action":"generate","tool":"GENERATE_IMAGE","prompt":"一只猫","ratio":"1:1","quality":"high"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("generate", action.action)
        assertEquals("GENERATE_IMAGE", action.tool)
        assertEquals("一只猫", action.prompt)
        assertEquals("1:1", action.ratio)
        assertEquals("high", action.quality)
    }

    @Test
    fun `parse generate video action`() {
        val raw = """{"action":"generate","tool":"GENERATE_VIDEO","prompt":"日落海滩","seconds":10,"ratio":"16:9"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("generate", action.action)
        assertEquals("GENERATE_VIDEO", action.tool)
        assertEquals(10, action.seconds)
        assertEquals("16:9", action.ratio)
    }

    @Test
    fun `parse finish action`() {
        val raw = """{"action":"finish","summary":"已完成，共生成 3 张图片"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("finish", action.action)
        assertEquals("已完成，共生成 3 张图片", action.summary)
    }

    @Test
    fun `parse generate with reference`() {
        val raw = """{"action":"generate","tool":"GENERATE_IMAGE","prompt":"延续上一张风格","reference":1,"reference_card":"card_abc123"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals(1, action.reference)
        assertEquals("card_abc123", action.reference_card)
    }

    @Test
    fun `parse list_cards action`() {
        val raw = """{"action":"list_cards"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("list_cards", action.action)
    }

    @Test
    fun `parse web_search action`() {
        val raw = """{"action":"web_search","query":"今日天气"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("web_search", action.action)
        assertEquals("今日天气", action.query)
    }

    @Test
    fun `parse apply_skill action`() {
        val raw = """{"action":"apply_skill","skill_id":"photo-revival"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("apply_skill", action.action)
        assertEquals("photo-revival", action.skill_id)
    }

    @Test
    fun `parse skill_creator action`() {
        val raw = """{"action":"skill_creator","name":"vintage-film","category":"photo","prompt_guide":"Vintage film grain style","description":"复古胶片质感","suggested_ratios":"1:1,4:5"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("skill_creator", action.action)
        assertEquals("vintage-film", action.name)
        assertEquals("photo", action.category)
        assertEquals("Vintage film grain style", action.prompt_guide)
        assertEquals("1:1,4:5", action.suggested_ratios)
    }

    @Test
    fun `parse with extra unknown fields`() {
        // 模型可能输出多余字段，ignoreUnknownKeys=true 应忽略
        val raw = """{"action":"finish","summary":"完成","extra_field":"value","another":123}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("finish", action.action)
        assertEquals("完成", action.summary)
    }

    @Test
    fun `parse generate with reference_folder`() {
        val raw = """{"action":"generate","tool":"GENERATE_VIDEO","prompt":"主角出场","reference_folder":"主角小薇"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("主角小薇", action.reference_folder)
    }

    @Test
    fun `parse link_cards action`() {
        val raw = """{"action":"link_cards","from_card_id":"card_a","to_card_id":"card_b","role":"reference"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("link_cards", action.action)
        assertEquals("card_a", action.from_card_id)
        assertEquals("card_b", action.to_card_id)
        assertEquals("reference", action.role)
    }

    @Test
    fun `numeric fields are nullable`() {
        val raw = """{"action":"generate","tool":"GENERATE_IMAGE","prompt":"test"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertNull(action.seconds)
        assertNull(action.reference)
        assertNull(action.motion)
        assertNull(action.cfgScale)
    }
}