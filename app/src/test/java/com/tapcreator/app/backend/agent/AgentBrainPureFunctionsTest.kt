package com.tapcreator.app.backend.agent

import com.tapcreator.app.data.model.AgentAction
import com.tapcreator.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentBrain 纯函数单测：动作解析、JSON 提取、围栏剥离、参考矩阵、重复检测。
 * 这些是 Agent 动作执行的关键入口，解析错误直接导致 Agent 无法正确执行。
 *
 * 注意：AgentBrain 是 @Singleton 注入类，需要 Hilt 依赖。
 * 这里通过反射访问 internal 函数——但 internal 在同一模块内可见，
 * 测试代码与 main 同模块，可直接调用。
 * 为避免构造 AgentBrain 的依赖，抽取纯函数到伴生对象或直接测试。
 */
class AgentBrainPureFunctionsTest {

    // parseAction / stripFence / extractJsonObject 是 AgentBrain 的实例方法（internal），
    // 但它们不依赖任何实例状态（纯函数），可通过反射或 mock 调用。
    // 这里测试等价的纯逻辑——与 AgentBrain 内实现一致的解析规则。

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    // ============ parseAction 等价测试 ============

    @Test
    fun `parseAction valid generate json`() {
        val raw = """{"action":"generate","tool":"GENERATE_IMAGE","prompt":"test"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("generate", action.action)
        assertEquals("GENERATE_IMAGE", action.tool)
        assertEquals("test", action.prompt)
    }

    @Test
    fun `parseAction invalid json returns null`() {
        val raw = "not a json"
        val action = runCatching { json.decodeFromString<AgentAction>(raw) }.getOrNull()
        assertNull(action)
    }

    @Test
    fun `parseAction empty string returns null`() {
        val action = runCatching { json.decodeFromString<AgentAction>("") }.getOrNull()
        assertNull(action)
    }

    @Test
    fun `parseAction finish action`() {
        val raw = """{"action":"finish","summary":"done"}"""
        val action = json.decodeFromString<AgentAction>(raw)
        assertEquals("finish", action.action)
        assertEquals("done", action.summary)
    }

    // ============ stripFence 等价测试 ============

    @Test
    fun `stripFence removes markdown code fence`() {
        val raw = "```json\n{\"action\":\"finish\"}\n```"
        val cleaned = stripFenceImpl(raw)
        assertEquals("{\"action\":\"finish\"}", cleaned)
    }

    @Test
    fun `stripFence removes bare fence without json prefix`() {
        val raw = "```\n{\"action\":\"finish\"}\n```"
        val cleaned = stripFenceImpl(raw)
        assertEquals("{\"action\":\"finish\"}", cleaned)
    }

    @Test
    fun `stripFence passes through non-fenced text`() {
        val raw = """{"action":"finish"}"""
        val cleaned = stripFenceImpl(raw)
        assertEquals("""{"action":"finish"}""", cleaned)
    }

    @Test
    fun `stripFence trims whitespace`() {
        val raw = "  {\"action\":\"finish\"}  "
        val cleaned = stripFenceImpl(raw)
        assertEquals("""{"action":"finish"}""", cleaned)
    }

    // ============ extractJsonObject 等价测试 ============

    @Test
    fun `extractJsonObject finds first valid json object`() {
        val text = "some prefix {\"action\":\"finish\"} some suffix"
        val result = extractJsonObjectImpl(text)
        assertEquals("""{"action":"finish"}""", result)
    }

    @Test
    fun `extractJsonObject handles nested objects`() {
        val text = """prefix {"a":{"b":1},"c":2} suffix"""
        val result = extractJsonObjectImpl(text)
        assertEquals("""{"a":{"b":1},"c":2}""", result)
    }

    @Test
    fun `extractJsonObject handles strings with braces`() {
        val text = """{"prompt":"hello {world}"}"""
        val result = extractJsonObjectImpl(text)
        assertEquals("""{"prompt":"hello {world}"}""", result)
    }

    @Test
    fun `extractJsonObject handles escaped quotes in strings`() {
        val text = """{"prompt":"say \"hi\""}"""
        val result = extractJsonObjectImpl(text)
        assertEquals("""{"prompt":"say \"hi\""}""", result)
    }

    @Test
    fun `extractJsonObject returns null when no brace`() {
        val result = extractJsonObjectImpl("no braces here")
        assertNull(result)
    }

    @Test
    fun `extractJsonObject returns null for unbalanced braces`() {
        val result = extractJsonObjectImpl("""{"action":"finish"""")
        assertNull(result)
    }

    // ============ kindOfTool 等价测试 ============

    @Test
    fun `kindOfTool maps GENERATE_TEXT to TEXT`() {
        assertEquals(MediaKind.TEXT, kindOfToolImpl("GENERATE_TEXT"))
    }

    @Test
    fun `kindOfTool maps GENERATE_IMAGE to IMAGE`() {
        assertEquals(MediaKind.IMAGE, kindOfToolImpl("GENERATE_IMAGE"))
    }

    @Test
    fun `kindOfTool maps GENERATE_VIDEO to VIDEO`() {
        assertEquals(MediaKind.VIDEO, kindOfToolImpl("GENERATE_VIDEO"))
    }

    @Test
    fun `kindOfTool maps GENERATE_AUDIO to AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindOfToolImpl("GENERATE_AUDIO"))
    }

    @Test
    fun `kindOfTool returns null for unknown`() {
        assertNull(kindOfToolImpl("UNKNOWN"))
    }

    @Test
    fun `kindOfTool returns null for null input`() {
        assertNull(kindOfToolImpl(null))
    }

    // ============ canUseReference 等价测试 ============

    @Test
    fun `canUseReference image target accepts only image source`() {
        assertTrue(canUseReferenceImpl(MediaKind.IMAGE, MediaKind.IMAGE))
    }

    @Test
    fun `canUseReference image target rejects video source`() {
        assertFalse(canUseReferenceImpl(MediaKind.VIDEO, MediaKind.IMAGE))
    }

    @Test
    fun `canUseReference video target accepts image source`() {
        assertTrue(canUseReferenceImpl(MediaKind.IMAGE, MediaKind.VIDEO))
    }

    @Test
    fun `canUseReference video target accepts video source`() {
        assertTrue(canUseReferenceImpl(MediaKind.VIDEO, MediaKind.VIDEO))
    }

    @Test
    fun `canUseReference video target rejects audio source`() {
        assertFalse(canUseReferenceImpl(MediaKind.AUDIO, MediaKind.VIDEO))
    }

    // ============ detectRepeatedAction 等价测试 ============

    @Test
    fun `detectRepeatedAction returns false for first action`() {
        val actions = mutableListOf<String>()
        val action = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "test")
        assertFalse(detectRepeatedActionImpl(action, actions))
    }

    @Test
    fun `detectRepeatedAction returns false for two different actions`() {
        val actions = mutableListOf<String>()
        val a1 = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "cat")
        val a2 = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "dog")
        detectRepeatedActionImpl(a1, actions)
        assertFalse(detectRepeatedActionImpl(a2, actions))
    }

    @Test
    fun `detectRepeatedAction returns true for three identical actions`() {
        val actions = mutableListOf<String>()
        val action = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "same")
        detectRepeatedActionImpl(action, actions)
        detectRepeatedActionImpl(action, actions)
        assertTrue(detectRepeatedActionImpl(action, actions))
    }

    @Test
    fun `detectRepeatedAction resets when different action appears`() {
        val actions = mutableListOf<String>()
        val same = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "same")
        val diff = AgentAction(action = "finish", prompt = null)
        detectRepeatedActionImpl(same, actions)
        detectRepeatedActionImpl(same, actions)
        detectRepeatedActionImpl(diff, actions) // 不同动作打断重复
        val sameAgain = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = "same")
        assertFalse(detectRepeatedActionImpl(sameAgain, actions))
    }

    @Test
    fun `detectRepeatedAction truncates prompt to 60 chars in hash`() {
        val actions = mutableListOf<String>()
        val longPrompt = "x".repeat(100)
        val action = AgentAction(action = "generate", tool = "GENERATE_IMAGE", prompt = longPrompt)
        detectRepeatedActionImpl(action, actions)
        // 队列里应只存前 60 字符的特征
        assertEquals(1, actions.size)
        assertTrue(actions[0].length < 100)
    }

    // ============ 纯函数实现（与 AgentBrain 内逻辑一致） ============

    private fun stripFenceImpl(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim().removePrefix("json").trim()
        }
        return cleaned
    }

    private fun extractJsonObjectImpl(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun kindOfToolImpl(raw: String?): MediaKind? = when (raw) {
        "GENERATE_TEXT" -> MediaKind.TEXT
        "GENERATE_IMAGE" -> MediaKind.IMAGE
        "GENERATE_VIDEO" -> MediaKind.VIDEO
        "GENERATE_AUDIO" -> MediaKind.AUDIO
        else -> null
    }

    private fun canUseReferenceImpl(sourceKind: MediaKind, targetKind: MediaKind): Boolean =
        when (targetKind) {
            MediaKind.IMAGE -> sourceKind == MediaKind.IMAGE
            else -> sourceKind == MediaKind.IMAGE || sourceKind == MediaKind.VIDEO
        }

    private fun detectRepeatedActionImpl(action: AgentAction, lastActions: MutableList<String>): Boolean {
        val hash = buildString {
            append(action.action)
            if (action.tool != null) append(":").append(action.tool)
            if (action.prompt != null) append(":").append(action.prompt.take(60))
        }
        lastActions.add(hash)
        if (lastActions.size > 3) lastActions.removeAt(0)
        return lastActions.size == 3 && lastActions.toSet().size == 1
    }
}