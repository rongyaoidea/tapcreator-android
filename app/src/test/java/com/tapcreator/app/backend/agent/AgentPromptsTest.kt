package com.tapcreator.app.backend.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentPrompts 单测：系统提示词包含关键流程约束（与 AgentBrain 解耦后独立覆盖）。
 */
class AgentPromptsTest {

    @Test
    fun `systemPrompt contains tool table and json-only rule`() {
        val prompt = AgentPrompts.systemPrompt(cinematic = true)
        assertTrue(prompt.contains("思考→行动→观察"))
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("finish"))
    }

    @Test
    fun `systemPrompt cinematic toggles storyboard block`() {
        val withVideo = AgentPrompts.systemPrompt(cinematic = true)
        val withoutVideo = AgentPrompts.systemPrompt(cinematic = false)
        assertTrue(withVideo.contains("视频分镜优化"))
        assertTrue(!withoutVideo.contains("视频分镜优化"))
    }

    @Test
    fun `systemPrompt includes personal style when provided`() {
        val prompt = AgentPrompts.systemPrompt(cinematic = false, style = "赛博朋克")
        assertTrue(prompt.contains("赛博朋克"))
        assertTrue(prompt.contains("个人风格偏好"))
    }

    @Test
    fun `promptOnly prompt drops generate from tool table and keeps skill tools`() {
        val normal = AgentPrompts.systemPrompt(cinematic = false)
        val writer = AgentPrompts.systemPrompt(cinematic = false, promptOnly = true)
        // 常规模式工具表含 generate；提示词撰写模式不含（白名单裁剪）
        assertTrue(normal.contains("\"name\":\"generate\""))
        assertTrue(!writer.contains("\"name\":\"generate\""))
        assertTrue(writer.contains("提示词撰写"))
        assertTrue(writer.contains("\"name\":\"apply_skill\""))
        assertTrue(writer.contains("\"name\":\"finish\""))
    }

    @Test
    fun `promptOnly action whitelist forbids side effects`() {
        assertTrue(AgentPrompts.PROMPT_ONLY_ACTIONS.contains("apply_skill"))
        assertTrue(AgentPrompts.PROMPT_ONLY_ACTIONS.contains("finish"))
        listOf("generate", "delete_card", "shell_execute", "install_package", "mcp_add_server").forEach {
            assertTrue("提示词模式不应允许 $it", it !in AgentPrompts.PROMPT_ONLY_ACTIONS)
        }
    }
}
