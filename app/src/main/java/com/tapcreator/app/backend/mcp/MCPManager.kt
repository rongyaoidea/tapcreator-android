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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 是否启用（禁用的服务器不参与 Agent 工具调用） */
    val enabled: Boolean = true,
    /** 运行中的子进程（stdio 类型），不参与序列化 */
    var process: Process? = null,
)

/** 可序列化的 MCP 服务器配置快照，用于持久化 */
@Serializable
data class MCPServerConfig(
    val name: String,
    val type: String,
    val command: String = "",
    val args: List<String> = emptyList(), // JSON 数组序列化，保留含逗号的参数
    val url: String = "",
    val env: String = "{}", // JSON 对象字符串
    val enabled: Boolean = true,
)

/** 把 MCPServer 转为可序列化的配置快照 */
fun MCPServer.toConfig(): MCPServerConfig = MCPServerConfig(
    name = name, type = type, command = command,
    args = args, url = url,
    env = Json.encodeToString(
        MapSerializer(serializer<String>(), serializer<String>()), env
    ),
    enabled = enabled,
)

/** 从配置快照恢复 MCPServer */
fun MCPServerConfig.toServer(): MCPServer = MCPServer(
    name = name, type = type, command = command,
    args = args,
    url = url,
    env = runCatching {
        Json.decodeFromString<Map<String, String>>(env)
    }.getOrDefault(emptyMap()),
    enabled = enabled,
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
 *  - stdio：子进程（stdin/stdout JSON-RPC），复用进程避免频繁启动开销
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
    private val processLock = Mutex()

    /** 已注册的 MCP 服务器 */
    private val servers = ConcurrentHashMap<String, MCPServer>()

    /** stdio 进程缓存：name → Process，避免每次调用都启动新进程 */
    private val stdioProcesses = ConcurrentHashMap<String, Process>()

    /** stdio 进程 stdout reader（每个进程只建一个 BufferedReader，避免跨调用读缓冲丢失数据） */
    private val stdioReaders = ConcurrentHashMap<String, java.io.BufferedReader>()

    /** 每个 server 是否已完成 MCP initialize 握手（官方 spec：tools 相关调用前须先 initialize + notifications/initialized）。 */
    private val initialized = ConcurrentHashMap<String, Boolean>()

    /** streamable-HTTP 有状态会话：服务端在 initialize 响应头 Mcp-Session-Id 下发，后续请求须回传。 */
    private val sessions = ConcurrentHashMap<String, String>()

    companion object {
        /** MCP 协议版本（2024-11-05 兼容性最广；官方后续版本 server 一般会回落）。 */
        const val PROTOCOL_VERSION = "2024-11-05"
        const val CLIENT_NAME = "tapcreator"
        const val CLIENT_VERSION = "1.0"
    }

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

    /** 删除一个 MCP 服务器（自动持久化，同时销毁复用进程） */
    suspend fun removeServer(name: String): Boolean {
        val removed = servers.remove(name) != null
        if (removed) {
            closeProcess(name)
            sessions.remove(name)
            persist()
        }
        return removed
    }

    /** 列出所有已注册的 MCP 服务器 */
    fun listServers(): List<MCPServer> = servers.values.toList()

    /** 设置 MCP 服务器的启用/禁用状态（自动持久化） */
    suspend fun setEnabled(name: String, enabled: Boolean) {
        servers[name]?.let {
            servers[name] = it.copy(enabled = enabled)
            persist()
        }
    }

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

    /** 获取或启动 stdio 进程（复用已存在的进程） */
    private suspend fun getOrCreateProcess(server: MCPServer): Process = processLock.withLock {
        val existing = stdioProcesses[server.name]
        if (existing != null && existing.isAlive) {
            return@withLock existing
        }
        // 清理已死亡的进程
        closeProcess(server.name)
        val newProc = startProcess(server)
        stdioProcesses[server.name] = newProc
        stdioReaders[server.name] = newProc.inputStream.bufferedReader()
        initialized.remove(server.name) // 新进程需重新 initialize 握手
        newProc
    }

    /** 关闭某个服务器的子进程并清理其 reader（幂等） */
    private fun closeProcess(name: String) {
        runCatching { stdioProcesses.remove(name)?.destroy() }
        runCatching { stdioReaders.remove(name)?.close() }
        initialized.remove(name)
    }

    /**
     * 读取 newline-delimited JSON-RPC 响应，**按 request id 匹配**（进程复用、且一次超时可能留下迟到响应，
     * 若只看"第一条 result/error"会把上一条的迟到响应误挂到本次调用）。支持单行与跨行 JSON。
     */
    private fun readResponseLine(serverName: String, wantId: Int, timeoutMs: Long = 30_000L): JsonObject? {
        val reader = stdioReaders[serverName] ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val line = reader.readLine() ?: return null
                if (line.isBlank()) continue
                // 先按完整单行解析（stdio 常见形态）
                val single = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (single != null) {
                    if (single["id"]?.jsonPrimitive?.content?.toIntOrNull() == wantId &&
                        (single.containsKey("result") || single.containsKey("error"))
                    ) return single
                    continue // 其它 id / 通知 / 日志行：丢弃
                }
                // 跨行 JSON：累积后再试
                sb.append(line).append('\n')
                val acc = runCatching { json.parseToJsonElement(sb.toString()).jsonObject }.getOrNull()
                if (acc != null) {
                    sb.setLength(0)
                    if (acc["id"]?.jsonPrimitive?.content?.toIntOrNull() == wantId &&
                        (acc.containsKey("result") || acc.containsKey("error"))
                    ) return acc
                }
            } else {
                Thread.sleep(25)
            }
        }
        return null // 超时
    }

    /** MCP 官方握手：initialize（阻塞取回 result）→ notifications/initialized（通知）。每个进程只做一次。 */
    private fun ensureInitializedStdio(serverName: String, proc: Process) {
        if (initialized[serverName] == true) return
        val initId = requestId.incrementAndGet()
        val initReq = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", initId)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject { put("name", CLIENT_NAME); put("version", CLIENT_VERSION) })
            })
        }
        proc.outputStream.write((initReq.toString() + "\n").toByteArray())
        proc.outputStream.flush()
        val root = readResponseLine(serverName, initId)
        if (root == null || root.containsKey("error")) {
            // 非标准/简易 server：不阻断，后续仍直连 tools/list|tools/call
            android.util.Log.w("MCPManager", "MCP initialize 未成功（$serverName），尝试直连标准方法")
            return
        }
        val notif = buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }
        runCatching {
            proc.outputStream.write((notif.toString() + "\n").toByteArray())
            proc.outputStream.flush()
        }
        initialized[serverName] = true
    }

    private suspend fun listToolsStdio(server: MCPServer): List<MCPTool> = withContext(Dispatchers.IO) {
        val proc = getOrCreateProcess(server)
        ensureInitializedStdio(server.name, proc)
        val id = requestId.incrementAndGet()
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/list")
        }
        proc.outputStream.write((req.toString() + "\n").toByteArray())
        proc.outputStream.flush()
        // 不关闭 outputStream 以复用进程
        val root = readResponseLine(server.name, id) ?: return@withContext emptyList()
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
    }

    private suspend fun callToolStdio(server: MCPServer, toolName: String, arguments: JsonObject): String =
        withContext(Dispatchers.IO) {
            val proc = getOrCreateProcess(server)
            ensureInitializedStdio(server.name, proc)
            val id = requestId.incrementAndGet()
            val req = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "tools/call")
                put("params", buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }
            proc.outputStream.write((req.toString() + "\n").toByteArray())
            proc.outputStream.flush()
            // 不关闭 outputStream 以复用进程
            val root = readResponseLine(server.name, id) ?: return@withContext "（MCP 响应超时或无响应）"
            root["error"]?.let { return@withContext "（MCP 错误：${it}）" }
            val result = root["result"]?.jsonObject
            val isError = result?.get("isError")?.jsonPrimitive?.content?.toBoolean() == true
            val text = result?.get("content")?.jsonArray
                ?.joinToString("\n") { el -> el.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            (if (isError) "（工具返回错误）" else "") + (text ?: "（MCP 工具返回空结果）")
        }

    /** 销毁所有 stdio 复用进程（用于清理/重置） */
    suspend fun destroyAllProcesses() {
        processLock.withLock {
            stdioProcesses.keys.toList().forEach { closeProcess(it) }
        }
    }

    // ==================== HTTP 实现 ====================

    /** 发送一条 JSON-RPC 并返回解析后的响应；兼容官方 streamable-HTTP 的 SSE(text/event-stream) 响应帧。 */
    private suspend fun httpRequest(server: MCPServer, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val isInit = body["method"]?.jsonPrimitive?.content == "initialize"
            val url = java.net.URL("${server.url}/mcp")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            // streamable-HTTP 有状态会话：除 initialize 外须回传服务端下发的 Mcp-Session-Id
            if (!isInit) sessions[server.name]?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.doOutput = true
            server.env.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.outputStream.write(body.toString().toByteArray())
            conn.outputStream.flush()
            // initialize 时捕获服务端下发的会话 id，供后续请求回传
            if (isInit) conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessions[server.name] = it }
            val ct = conn.contentType.orEmpty()
            val raw = conn.inputStream.bufferedReader().readText()
            val payload = if (ct.contains("text/event-stream") || raw.trimStart().startsWith("event:") || raw.contains("data:")) {
                raw.lineSequence().map { it.trim() }.filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.isNotEmpty() && it != "[DONE]" } ?: raw
            } else raw
            json.parseToJsonElement(payload).jsonObject
        }

    /** HTTP 传输的 MCP 握手（每个 server 一次）。失败不阻断，尽量直连标准方法。 */
    private suspend fun ensureInitializedHttp(server: MCPServer) {
        if (initialized[server.name] == true) return
        val initReq = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject { put("name", CLIENT_NAME); put("version", CLIENT_VERSION) })
            })
        }
        runCatching { httpRequest(server, initReq) }
            .onFailure { android.util.Log.w("MCPManager", "MCP(http) initialize 失败：${server.name} ${it.message}") }
        val notif = buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }
        runCatching { httpRequest(server, notif) } // 通知：部分 server 回 202 空体，忽略
        initialized[server.name] = true
    }

    private suspend fun listToolsHttp(server: MCPServer): List<MCPTool> {
        return try {
            ensureInitializedHttp(server)
            val req = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "tools/list")
            }
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
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
        }
        try {
            ensureInitializedHttp(server)
            val root = httpRequest(server, req)
            root["error"]?.let { return "（MCP 错误：${it}）" }
            val result = root["result"]?.jsonObject
            val isError = result?.get("isError")?.jsonPrimitive?.content?.toBoolean() == true
            val text = result?.get("content")?.jsonArray
                ?.joinToString("\n") { el -> el.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            return (if (isError) "（工具返回错误）" else "") + (text ?: "（MCP 工具返回空结果）")
        } catch (e: Exception) {
            throw TapcreatorException("MCP 调用失败：${e.message}", "MCP_CALL_FAILED")
        }
    }
}