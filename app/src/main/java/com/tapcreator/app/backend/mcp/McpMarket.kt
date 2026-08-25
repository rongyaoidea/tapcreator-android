package com.tapcreator.app.backend.mcp

/**
 * MCP 工具市场目录：常用 MCP 服务器清单，可搜索过滤后一键安装。
 * 部分工具需要 API Key 配置，安装时提供输入 UI 并持久化到 env。
 */
data class McpMarketEntry(
    val name: String,
    val description: String,
    val type: String,        // "stdio" or "http"
    val command: String,     // stdio: 启动命令；http: 空
    val args: List<String>,  // stdio: 命令参数
    val url: String,         // http: 端点 URL
    val category: String,   // 分类：搜索/文件/数据库/开发/其他
    val requiresApiKey: Boolean = false,  // 是否需要 API Key 配置
    val apiKeyEnvKey: String = "",         // API Key 的环境变量名（如 BRAVE_API_KEY）
    val apiKeyLabel: String = "",          // UI 显示的 API Key 输入标签
)

object McpMarket {
    val entries = listOf(
        McpMarketEntry(
            name = "brave-search",
            description = "Brave 搜索：网络搜索能力，Agent 可联网搜索信息",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-brave-search"), url = "", category = "搜索",
            requiresApiKey = true, apiKeyEnvKey = "BRAVE_API_KEY", apiKeyLabel = "Brave API Key",
        ),
        McpMarketEntry(
            name = "fetch",
            description = "网页抓取：获取 URL 内容并转为 Markdown，Agent 可读取网页",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-fetch"), url = "", category = "搜索",
        ),
        McpMarketEntry(
            name = "filesystem",
            description = "文件系统：读写本地文件，Agent 可操作工作目录中的文件",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp/tapcreator"), url = "", category = "文件",
        ),
        McpMarketEntry(
            name = "git",
            description = "Git 操作：仓库状态、diff、log、commit 等 Git 工具",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-git"), url = "", category = "开发",
        ),
        McpMarketEntry(
            name = "github",
            description = "GitHub：搜索代码、读写 Issue/PR、管理仓库",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-github"), url = "", category = "开发",
            requiresApiKey = true, apiKeyEnvKey = "GITHUB_PERSONAL_ACCESS_TOKEN", apiKeyLabel = "GitHub Token",
        ),
        McpMarketEntry(
            name = "sqlite",
            description = "SQLite 数据库：查询、执行 SQL，Agent 可操作本地数据库",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-sqlite"), url = "", category = "数据库",
        ),
        McpMarketEntry(
            name = "postgres",
            description = "PostgreSQL：连接 PG 数据库执行查询",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-postgres"), url = "", category = "数据库",
        ),
        McpMarketEntry(
            name = "memory",
            description = "知识图谱记忆：Agent 跨会话持久化记忆与实体关系",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-memory"), url = "", category = "其他",
        ),
        McpMarketEntry(
            name = "puppeteer",
            description = "浏览器自动化：截图、点击、填表、爬取动态页面",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-puppeteer"), url = "", category = "搜索",
        ),
        McpMarketEntry(
            name = "time",
            description = "时间工具：获取当前时间、时区转换、时间计算",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-time"), url = "", category = "其他",
        ),
        McpMarketEntry(
            name = "sequential-thinking",
            description = "顺序思考：分步推理工具，Agent 可拆解复杂问题",
            type = "stdio", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"), url = "", category = "其他",
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
