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

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // 后台线程种子化默认渠道/模型 + 回收上次进程遗留的在途 run，不阻塞首帧
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    channels.seedDefaults()
                    // 自动发现：为已存在但分辨率空缺的模型补全调研到的档位（幂等，不覆盖手动设置）
                    channels.autoFillAllResolutions()
                    runService.reconcileStaleRuns()
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
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "tapcreator-crash.log")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } else {
            File(filesDir, "crash.log").appendText(body)
        }
    }
}