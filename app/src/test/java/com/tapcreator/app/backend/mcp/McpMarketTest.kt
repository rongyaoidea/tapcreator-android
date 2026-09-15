package com.tapcreator.app.backend.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 市场目录单测。
 *
 * 背景：Android 宿主没有 Node.js/npx，npx 类 stdio 预置装了也跑不起来，
 * 市场因此只收录 HTTP(Streamable HTTP) 远程服务器；stdio 交给 Agent 手动注册设备可执行程序。
 * 这些断言防止 npx 预置被重新引入。
 */
class McpMarketTest {

    @Test
    fun `market only offers http remote servers`() {
        assertTrue("市场不应为空", McpMarket.entries.isNotEmpty())
        McpMarket.entries.forEach { e ->
            assertEquals("${e.name} 应为 http 类型", "http", e.type)
            assertTrue("${e.name} 的 url 应以 https:// 开头", e.url.startsWith("https://"))
            assertTrue("${e.name} 不应带 stdio 启动命令", e.command.isEmpty())
            assertTrue("${e.name} 不应带 stdio 参数", e.args.isEmpty())
        }
    }

    @Test
    fun `market no longer ships npx based entries`() {
        McpMarket.entries.forEach { e ->
            assertFalse(
                "${e.name} 不应依赖 npx/node（Android 宿主不可用）",
                e.command.contains("npx") || e.url.contains("npx") || e.description.contains("npx"),
            )
        }
    }

    @Test
    fun `market entries have unique names and non-blank descriptions`() {
        val names = McpMarket.entries.map { it.name }
        assertEquals("市场条目名称不应重复", names.size, names.toSet().size)
        McpMarket.entries.forEach { e ->
            assertTrue("${e.name} 缺描述", e.description.isNotBlank())
            assertTrue("${e.name} 缺分类", e.category.isNotBlank())
        }
    }

    @Test
    fun `market search filters by name description and category`() {
        val all = McpMarket.search("")
        assertEquals(McpMarket.entries.size, all.size)
        assertTrue("按名称应能命中 deepwiki", McpMarket.search("deepwiki").any { it.name == "deepwiki" })
        assertTrue("按描述应能命中文档类", McpMarket.search("文档").isNotEmpty())
        assertTrue("未知关键词应返回空", McpMarket.search("no-such-mcp-xyz").isEmpty())
    }
}
