package com.tapcreator.app.backend.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCPServer / MCPServerConfig 序列化与转换单测：
 *  - toConfig() / toServer() 双向转换
 *  - 序列化/反序列化往返
 *  - stdio/http 两种类型
 *  - args/env 解析
 */
class MCPManagerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `MCPServer stdio toConfig preserves all fields`() {
        val server = MCPServer(
            name = "test-server",
            type = "stdio",
            command = "/usr/bin/node",
            args = listOf("server.js", "--verbose"),
            url = "",
            env = mapOf("API_KEY" to "secret123"),
        )
        val config = server.toConfig()
        assertEquals("test-server", config.name)
        assertEquals("stdio", config.type)
        assertEquals("/usr/bin/node", config.command)
        assertEquals(listOf("server.js", "--verbose"), config.args)
        assertEquals("", config.url)
        assertTrue(config.env.contains("API_KEY"))
        assertTrue(config.env.contains("secret123"))
    }

    @Test
    fun `MCPServer http toConfig preserves url`() {
        val server = MCPServer(
            name = "remote",
            type = "http",
            command = "",
            url = "https://example.com/mcp",
            env = mapOf("Authorization" to "Bearer token"),
        )
        val config = server.toConfig()
        assertEquals("remote", config.name)
        assertEquals("http", config.type)
        assertEquals("https://example.com/mcp", config.url)
        assertEquals("", config.command)
        assertTrue(config.env.contains("Authorization"))
    }

    @Test
    fun `MCPServerConfig toServer reverses toConfig`() {
        val original = MCPServer(
            name = "test",
            type = "stdio",
            command = "python3",
            args = listOf("main.py"),
            url = "",
            env = mapOf("DEBUG" to "1"),
        )
        val restored = original.toConfig().toServer()
        assertEquals(original.name, restored.name)
        assertEquals(original.type, restored.type)
        assertEquals(original.command, restored.command)
        assertEquals(original.args, restored.args)
        assertEquals(original.url, restored.url)
        assertEquals(original.env, restored.env)
    }

    @Test
    fun `MCPServerConfig toServer preserves args list`() {
        val config = MCPServerConfig(
            name = "test",
            type = "stdio",
            command = "node",
            args = listOf("app.js", "--port", "3000"),
            url = "",
            env = "{}",
        )
        val server = config.toServer()
        assertEquals(listOf("app.js", "--port", "3000"), server.args)
    }

    @Test
    fun `MCPServerConfig toServer handles empty args`() {
        val config = MCPServerConfig(
            name = "test",
            type = "http",
            command = "",
            args = emptyList(),
            url = "https://api.example.com",
            env = "{}",
        )
        val server = config.toServer()
        assertTrue(server.args.isEmpty())
    }

    @Test
    fun `MCPServerConfig toServer parses env from JSON string`() {
        val config = MCPServerConfig(
            name = "test",
            type = "stdio",
            command = "node",
            args = "app.js",
            url = "",
            env = """{"KEY1":"val1","KEY2":"val2"}""",
        )
        val server = config.toServer()
        assertEquals("val1", server.env["KEY1"])
        assertEquals("val2", server.env["KEY2"])
    }

    @Test
    fun `MCPServerConfig toServer handles invalid env JSON gracefully`() {
        val config = MCPServerConfig(
            name = "test",
            type = "stdio",
            command = "node",
            args = "",
            url = "",
            env = "invalid json",
        )
        val server = config.toServer()
        assertTrue(server.env.isEmpty())
    }

    @Test
    fun `MCPServerConfig toServer handles empty env`() {
        val config = MCPServerConfig(
            name = "test",
            type = "http",
            command = "",
            args = "",
            url = "https://api.example.com",
            env = "",
        )
        val server = config.toServer()
        assertTrue(server.env.isEmpty())
    }

    @Test
    fun `MCPServerConfig serialization roundtrip`() {
        val config = MCPServerConfig(
            name = "roundtrip",
            type = "stdio",
            command = "/bin/sh",
            args = listOf("script.sh", "arg1"),
            url = "",
            env = """{"PATH":"/usr/bin"}""",
        )
        val jsonStr = json.encodeToString(MCPServerConfig.serializer(), config)
        val restored = json.decodeFromString(MCPServerConfig.serializer(), jsonStr)
        assertEquals(config, restored)
    }

    @Test
    fun `MCPServerConfig list serialization roundtrip`() {
        val configs = listOf(
            MCPServerConfig("s1", "stdio", "node", listOf("app.js"), "", "{}"),
            MCPServerConfig("s2", "http", "", emptyList(), "https://remote.example.com", """{"AUTH":"Bearer x"}"""),
        )
        val serializer = kotlinx.serialization.builtins.ListSerializer(MCPServerConfig.serializer())
        val jsonStr = json.encodeToString(serializer, configs)
        val restored = json.decodeFromString(serializer, jsonStr)
        assertEquals(configs, restored)
    }

    @Test
    fun `MCPTool default inputSchema is empty`() {
        val tool = MCPTool(name = "test_tool", description = "A test tool")
        assertTrue(tool.inputSchema.isEmpty())
    }

    @Test
    fun `MCPServer default values`() {
        val server = MCPServer(name = "default", type = "stdio")
        assertEquals("", server.command)
        assertTrue(server.args.isEmpty())
        assertEquals("", server.url)
        assertTrue(server.env.isEmpty())
        assertNull(server.process)
    }
}