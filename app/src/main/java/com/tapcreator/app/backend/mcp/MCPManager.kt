package com.tapcreator.app.backend.mcp

import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.data.prefs.SettingsStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.serializer

/**
 * MCP 服务器定义：子进程（stdio）或远程 HTTP 服务器。
 */
data class MCPServer(
    val name: String,
    /** "stdio" = 子进程（command 为可执行路径）；"http" = 远程 HTTP（url 为端点） */
    val type: String,
    /** stdio 类型：启动命令；http 类型：base URL */
    val command: String = "",
    /** 命令参数列表（stdio 类型） */
    val args: List<String> = emptyList(),
    /** HTTP 端点 URL（http 类型） */
    val url: String = "",
    /** 环境变量（可选的 Authorization 头等） */
    val env: Map<String, String> = emptyMap(),
    /** 运行中的子进程（stdio 类型），不参与序列化 */
    var process: Process? = null,
)

/** 可序列化的 MCP 服务器配置快照，用于持久化 */
@Serializable
data class MCPServerConfig(
    val name: String,
    val type: String,
    val command: String = "",
    val args: String = "", // 逗号分隔
    val url: String = "",
    val env: String = "{}", // JSON 对象字符串
)

/** 把 MCPServer 转为可序列化的配置快照 */
fun MCPServer.toConfig(): MCPServerConfig = MCPServerConfig(
    name = name, type = type, command = command,
    args = args.joinToString(","), url = url,
    env = Json.encodeToString(
        MapSerializer(serializer<String>(), serializer<String>()), env
    ),
)

/** 从配置快照恢复 MCPServer */
fun MCPServerConfig.toServer(): MCPServer = MCPServer(
    name = name, type = type, command = command,
    args = if (args.isNotBlank()) args.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
    url = url,
    env = runCatching {
        Json.decodeFromString<Map<String, String>>(env)
    }.getOrDefault(emptyMap()),
)

/**
 * MCP 工具定义：从 MCP 服务器返回的工具清单。
 */
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = buildJsonObject { },
)

/**
 * MCP 管理器：管理 MCP 服务器连接，处理 JSON-RPC 通信。
 *
 * 支持两种 MCP 服务器类型：
 *  - stdio：子进程（stdin/stdout JSON-RPC），每次请求启动/复用进程
 *  - http：远程 HTTP 服务器
 *
 * 服务器列表持久化到 DataStore（通过 SettingsStore），App 重启后恢复。
 */
@Singleton
class MCPManager @Inject constructor(
    private val settings: SettingsStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestId = AtomicInteger(0)

    /** 已注册的 MCP 服务器 */
    private val servers = ConcurrentHashMap<String, MCPServer>()

    /** 初始化：从 DataStore 恢复已注册的 MCP 服务器列表 */
    suspend fun init() {
        val raw = settings.mcpServersJson()
        if (raw.isNotBlank()) {
            val configs = runCatching {
                json.decodeFromString(ListSerializer(MCPServerConfig.serializer()), raw)
            }.getOrDefault(emptyList())
            configs.forEach { cfg -> servers[cfg.name] = cfg.toServer() }
        }
    }

    /** 持久化当前服务器列表到 DataStore */
    private suspend fun persist() {
        val configs = servers.values.map { it.toConfig() }
        val raw = json.encodeToString(ListSerializer(MCPServerConfig.serializer()), configs)
        settings.saveMcpServersJson(raw)
    }

    // ==================== 服务器管理 ====================

    /** 注册一个新的 MCP 服务器（自动持久化） */
    suspend fun addServer(server: MCPServer) {
        servers[server.name] = server
        persist()
    }

    /** 删除一个 MCP 服务器（自动持久化） */
    suspend fun removeServer(name: String): Boolean {
        val removed = servers.remove(name) != null
        if (removed) persist()
        return removed
    }

    /** 列出所有已注册的 MCP 服务器 */
    fun listServers(): List<MCPServer> = servers.values.toList()

    /** 按名称查找服务器 */
    fun getServer(name: String): MCPServer? = servers[name]

    // ==================== 工具枚举 ====================

    suspend fun listTools(serverName: String): List<MCPTool> {
        val server = servers[serverName] ?: throw TapcreatorException("MCP 服务器「$serverName」未注册", "MCP_NOT_FOUND")
        return when (server.type) {
            "stdio" -> listToolsStdio(server)
            "http" -> listToolsHttp(server)
            else -> throw TapcreatorException("不支持的 MCP 服务器类型：${server.type}", "MCP_TYPE_UNSUPPORTED")
        }
    }

    suspend fun callTool(serverName: String, toolName: String, arguments: JsonObject = buildJsonObject { }): String {
        val server = servers[serverName] ?: throw TapcreatorException("MCP 服务器「$serverName」未注册", "MCP_NOT_FOUND")
        return when (server.type) {
            "stdio" -> callToolStdio(server, toolName, arguments)
            "http" -> callToolHttp(server, toolName, arguments)
            else -> throw TapcreatorException("不支持的 MCP 服务器类型：${server.type}", "MCP_TYPE_UNSUPPORTED")
        }
    }

    // ==================== stdio 实现 ====================

    private fun startProcess(server: MCPServer): Process {
        val pb = ProcessBuilder(server.command, *server.args.toTypedArray())
            .redirectErrorStream(true) // 合并 stderr 到 stdout
        server.env.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }

    private suspend fun listToolsStdio(server: MCPServer): List<MCPTool> = withContext(Dispatchers.IO) {
        val proc = startProcess(server)
        try {
            val req = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "list_tools")
            }
            proc.outputStream.write((req.toString() + "\n").toByteArray())
            proc.outputStream.flush()
            proc.outputStream.close()
            val resp = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val root = json.parseToJsonElement(resp).jsonObject
            val result = root["result"]?.jsonObject
            val tools = result?.get("tools")?.jsonArray ?: return@withContext emptyList()
            tools.mapNotNull { el ->
                val obj = el.jsonObject
                val namePrim = obj["name"]?.jsonPrimitive
                val descPrim = obj["description"]?.jsonPrimitive
                MCPTool(
                    name = namePrim?.content ?: return@mapNotNull null,
                    description = descPrim?.content ?: "",
                    inputSchema = obj["inputSchema"]?.jsonObject ?: buildJsonObject { },
                )
            }
        } finally {
            proc.destroy()
        }
    }

    private suspend fun callToolStdio(server: MCPServer, toolName: String, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            val proc = startProcess(server)
            try {
                val req = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId.incrementAndGet())
                    put("method", "call_tool")
                    put("params", buildJsonObject {
                        put("name", toolName)
                        put("arguments", arguments)
                    })
                }
                proc.outputStream.write((req.toString() + "\n").toByteArray())
                proc.outputStream.flush()
                proc.outputStream.close()
                val resp = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                val root = json.parseToJsonElement(resp).jsonObject
                val result = root["result"]?.jsonObject
                val content = result?.get("content")?.jsonArray
                content?.joinToString("\n") { el ->
                    el.jsonObject["text"]?.jsonPrimitive?.content ?: ""
                } ?: "（MCP 工具返回空结果）"
            } finally {
                proc.destroy()
            }
        }

    // ==================== HTTP 实现 ====================

    private suspend fun httpRequest(server: MCPServer, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val url = java.net.URL("${server.url}/mcp")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            server.env.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.outputStream.write(body.toString().toByteArray())
            conn.outputStream.flush()
            val resp = conn.inputStream.bufferedReader().readText()
            json.parseToJsonElement(resp).jsonObject
        }

    private suspend fun listToolsHttp(server: MCPServer): List<MCPTool> {
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", "list_tools")
        }
        return try {
            val root = httpRequest(server, req)
            val result = root["result"]?.jsonObject
            val tools = result?.get("tools")?.jsonArray ?: return emptyList()
            tools.mapNotNull { el ->
                val obj = el.jsonObject
                val namePrim = obj["name"]?.jsonPrimitive
                val descPrim = obj["description"]?.jsonPrimitive
                MCPTool(
                    name = namePrim?.content ?: return@mapNotNull null,
                    description = descPrim?.content ?: "",
                    inputSchema = obj["inputSchema"]?.jsonObject ?: buildJsonObject { },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun callToolHttp(server: MCPServer, toolName: String, arguments: JsonObject): String {
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", "call_tool")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
        }
        try {
            val root = httpRequest(server, req)
            val result = root["result"]?.jsonObject
            val content = result?.get("content")?.jsonArray
            return content?.joinToString("\n") { el ->
                el.jsonObject["text"]?.jsonPrimitive?.content ?: ""
            } ?: "（MCP 工具返回空结果）"
        } catch (e: Exception) {
            throw TapcreatorException("MCP 调用失败：${e.message}", "MCP_CALL_FAILED")
        }
    }
}