package com.tapcreator.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.backend.service.RunService
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.util.Date
import javax.inject.Inject

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

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // 后台线程种子化默认渠道/模型 + 回收上次进程遗留的在途 run，不阻塞首帧
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    channels.seedDefaults()
                    channels.autoFillAllResolutions()
                    runService.reconcileStaleRuns()
                    // 加载已安装的第三方设计 Skill
                    skillRegistry.init()
                    // 加载已注册的 MCP 服务器
                    mcpManager.init()
                    // 后台预热沙箱：解压 rootfs + 启动 PRoot + 安装 ffmpeg/curl/python3
                    // 首次约 30-60 秒，不阻塞首帧；沙箱就绪后 Agent 的搜索/拼接工具可用
                    sandbox.ensureReady()
                }
            }
            Handler(Looper.getMainLooper()).post {}
        }.start()
    }

    /**
     * 把未捕获异常写入公共「下载」目录 crash.log（API 29+ 走 MediaStore Downloads，
     * 更老机型回退到私有 filesDir），方便在设备文件管理器直接取栈，无需 adb。
     * 链式保留上一个处理器，不吞掉系统默认行为。
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            val sw = java.io.StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val body = "==== $stamp | thread=${thread.name} ====\n${sw}\n"
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