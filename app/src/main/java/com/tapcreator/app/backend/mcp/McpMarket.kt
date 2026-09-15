package com.tapcreator.app.backend.mcp

/**
 * MCP 工具市场目录：常用 MCP 服务器清单，可搜索过滤后一键安装。
 * 部分工具需要 API Key 配置，安装时提供输入 UI 并持久化到 env。
 *
 * 仅收录 **HTTP（Streamable HTTP）远程服务器**：Android 上进程直接由宿主执行，
 * 而 npx/node 等 stdio 运行时不可用；stdio 仍可通过 Agent 的 mcp_add_server 手动注册
 * 设备上可执行的程序。下述端点均已实测可访问（initialize / tools/list 返回 200）。
 */
data class McpMarketEntry(
    val name: String,
    val description: String,
    val type: String,        // 目前市场条目均为 "http"；"stdio" 保留给 Agent 手动注册
    val command: String,     // stdio: 启动命令；http: 空
    val args: List<String>,  // stdio: 命令参数
    val url: String,         // http: Streamable HTTP 端点 URL
    val category: String,   // 分类：文档/开发/其他
    val requiresApiKey: Boolean = false,  // 是否需要 API Key 配置
    val apiKeyEnvKey: String = "",         // API Key 的环境变量名/请求头名（如 Authorization）
    val apiKeyLabel: String = "",          // UI 显示的 API Key 输入标签
)

object McpMarket {
    val entries = listOf(
        McpMarketEntry(
            name = "deepwiki",
            description = "DeepWiki：查询任意公开 GitHub 仓库的 AI 生成文档与结构，并可就仓库提问（无需 Key）",
            type = "http", command = "", args = emptyList(),
            url = "https://mcp.deepwiki.com/mcp", category = "文档",
        ),
        McpMarketEntry(
            name = "microsoft-learn",
            description = "Microsoft Learn：检索微软/Azure 官方文档与代码示例，可抓取文章全文（无需 Key）",
            type = "http", command = "", args = emptyList(),
            url = "https://learn.microsoft.com/api/mcp", category = "文档",
        ),
        McpMarketEntry(
            name = "cloudflare-docs",
            description = "Cloudflare 文档：检索 Cloudflare 官方文档，含 Workers 迁移等指引（无需 Key）",
            type = "http", command = "", args = emptyList(),
            url = "https://docs.mcp.cloudflare.com/mcp", category = "文档",
        ),
        McpMarketEntry(
            name = "context7",
            description = "Context7：查询任意库/框架的最新官方文档与代码示例（匿名可用，API Key 可提升限额）",
            type = "http", command = "", args = emptyList(),
            url = "https://mcp.context7.com/mcp", category = "开发",
        ),
    )

    /** 按关键词过滤（名称或描述含关键词） */
    fun search(query: String): List<McpMarketEntry> {
        if (query.isBlank()) return entries
        val q = query.lowercase()
        return entries.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || it.category.lowercase().contains(q)
        }
    }
}
