package com.tapcreator.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.backend.service.RunService
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 应用启动阶段（供 Splash/设置页观察沙箱预热进度） */
enum class AppInitStage { IDLE, SEEDING, RECONCILING, PLUGINS, SANDBOX_WARMING, READY, FAILED }

@HiltAndroidApp
class TapcreatorApp : Application() {

    @Inject
    lateinit var channels: ChannelRepository

    @Inject
    lateinit var runService: RunService

    @Inject
    lateinit var sandbox: com.tapcreator.app.backend.sandbox.PRootSandbox

    @Inject
    lateinit var skillRegistry: com.tapcreator.app.backend.skill.SkillRegistry

    @Inject
    lateinit var mcpManager: com.tapcreator.app.backend.mcp.MCPManager

    @Inject
    lateinit var settings: com.tapcreator.app.data.prefs.SettingsStore

    /** 应用级结构化并发域：失败隔离，进程结束时取消 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _initStage = MutableStateFlow(AppInitStage.IDLE)
    val initStage: StateFlow<AppInitStage> = _initStage.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // 结构化并发启动：种子数据与插件并行，沙箱延迟预热，全程不阻塞首帧
        _initStage.value = AppInitStage.SEEDING
        appScope.launch {
            runCatching {
                // 渠道种子是关键路径：失败则整体 FAILED；插件/覆盖项失败隔离，并行加载
                channels.seedDefaults()
                awaitAll(
                    async { runCatching { skillRegistry.init() } },
                    async { runCatching { mcpManager.init() } },
                    async { runCatching { loadModelProfileOverrides() } },
                )
                _initStage.value = AppInitStage.RECONCILING
                runCatching { runService.reconcileStaleRuns() }
                    .onFailure { android.util.Log.w("TapcreatorApp", "reconcileStaleRuns 失败", it) }
                runCatching { channels.autoFillAllResolutions() }
                    .onFailure { android.util.Log.w("TapcreatorApp", "autoFillAllResolutions 失败", it) }
                _initStage.value = AppInitStage.SANDBOX_WARMING
                // 沙箱预热最慢且非首屏必需：独立子协程，失败只打日志不影响 READY
                launch {
                    runCatching { sandbox.ensureReady() }
                        .onFailure { android.util.Log.w("TapcreatorApp", "沙箱预热失败，Agent 搜索/拼接工具暂不可用", it) }
                }
                _initStage.value = AppInitStage.READY
            }.onFailure {
                android.util.Log.e("TapcreatorApp", "应用初始化失败", it)
                _initStage.value = AppInitStage.FAILED
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }

    /** 把 DataStore 里的「图生图参考形态覆盖」灌进进程内的 ModelProfileCatalog（须在生成前完成）。 */
    private suspend fun loadModelProfileOverrides() {
        val raw = settings.imageRefOverridesJson()
        if (raw.isBlank()) return
        val map = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
        map.forEach { (modelId, modeName) ->
            runCatching {
                val mode = com.tapcreator.app.backend.providers.ImageRefMode.valueOf(modeName)
                com.tapcreator.app.backend.providers.ModelProfileCatalog.setImageRefOverride(modelId, mode)
            }
        }
    }

    /**
     * 把未捕获异常写入公共「下载」目录 crash.log（API 29+ 走 MediaStore Downloads，
     * 更老机型回退到私有 filesDir），方便在设备文件管理器直接取栈，无需 adb。
     * 链式保留上一个处理器，不吞掉系统默认行为。
     */
    private fun installCrashLogger() {
        // 预创建 Crashlytics 实例（无 google-services.json 时静默 no-op，不崩溃）
        val crashlytics = runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
        }.getOrNull()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 同时上报 Crashlytics（若已配置 Firebase）
            crashlytics?.recordException(throwable)
            crashlytics?.setCustomKey("thread", thread.name)
            // SimpleDateFormat 非线程安全：崩溃线程每次新建实例，避免静态复用竞态
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sw = java.io.StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val body = "==== $stamp | thread=${thread.name} ====\n${sw}\n"
            // 崩溃线程做 IO 必须加保护：查询/写入失败不得掩盖原始崩溃
            runCatching { writeCrashLog(body) }
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先查已有文件，存在则追加内容；不存在则新建
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf("tapcreator-crash.log")
            var existingUri: android.net.Uri? = null
            contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    existingUri = android.net.Uri.withAppendedPath(collection, id.toString())
                }
            }
            val uri = existingUri ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "tapcreator-crash.log")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val newUri = contentResolver.insert(collection, values) ?: return
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(newUri, values, null, null)
                newUri
            }
            // 追加写入（覆盖已有内容会导致历史丢失）
            contentResolver.openOutputStream(uri, "wa")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } else {
            File(filesDir, "crash.log").appendText(body)
        }
    }
}