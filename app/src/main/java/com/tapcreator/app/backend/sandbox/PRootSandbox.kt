package com.tapcreator.app.backend.sandbox

import android.content.Context
import com.tapcreator.app.data.model.TapcreatorException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android 上的 Alpine Linux + PRoot 沙箱。
 *
 * 用 PRoot 静态二进制在 Android 用户态（无需 root）启动一个完整 Linux 环境。
 * Agent 的 shell_execute / ffmpeg / curl / python3 等命令在沙箱里执行，
 * 使 Agent 拥有「一台真正的计算机」的能力。
 *
 * 生命周期：
 *  - ensureReady()：首次调用时解压 rootfs + 启动 PRoot（幂等，重复调用安全）
 *  - exec(command)：在沙箱里执行命令，返回 stdout+stderr+exitCode
 *  - close()：停止沙箱（进程退出，rootfs 保留供下次快速启动）
 *
 * 线程安全：ensureReady/exec 串行化（单进程沙箱，不支持并发命令）。
 */
@Singleton
class PRootSandbox @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PRootSandbox"
        private const val ROOTFS_DIR = "alpine-rootfs"
        private const val PROOT_ASSET = "proot-aarch64"
        private const val ALPINE_ROOTFS_ASSET = "alpine-minirootfs.tar.gz"
        // 沙箱内的工作目录（映射到 Android 私有 filesDir/media）
        private const val SANDBOX_WORK = "/work"
        /** 单次 exec 输出的最大字节数（超出截断，防 OOM） */
        private const val MAX_OUTPUT_BYTES = 1_048_576 // 1MB
    }

    private val ready = AtomicBoolean(false)
    private var process: Process? = null
    private val mutex = Mutex()

    /** 沙箱 rootfs 根目录（App 私有目录下） */
    private val rootfsDir: File get() = File(context.filesDir, ROOTFS_DIR).apply { mkdirs() }

    /** PRoot 二进制路径（从 assets 复制到私有目录） */
    private val prootBinary: File get() = File(context.filesDir, "proot")

    /** 沙箱内可访问的媒体目录（映射到 App 私有 media 目录） */
    fun sandboxMediaDir(): String = SANDBOX_WORK + "/media"

    /**
     * 确保沙箱就绪：解压 rootfs（首次）+ 复制 PRoot + 启动 PRoot 交互进程。
     * 幂等：已就绪时直接返回。线程安全：synchronized 防止并发初始化。
     */
    suspend fun ensureReady(): Unit = mutex.withLock {
        if (ready.get() && process?.isAlive == true) return

        withContext(Dispatchers.IO) {
            // 1. 复制 PRoot 二进制（如不存在或大小为 0）
            if (!prootBinary.exists() || prootBinary.length() == 0L) {
                context.assets.open(PROOT_ASSET).use { input ->
                    prootBinary.outputStream().use { output -> input.copyTo(output) }
                }
                prootBinary.setExecutable(true)
            }

            // 2. 解压 Alpine rootfs（首次启动或 rootfs 不完整时）
            val sentinel = File(rootfsDir, ".tapcreator_ready")
            if (!sentinel.exists()) {
                rootfsDir.mkdirs()
                // 用 Java 手动解压 tar.gz（避免引入 Apache Commons Compress 依赖）
                extractTarGz()
                sentinel.writeText("ready")
            }

            // 3. 启动 PRoot 交互进程
            startPRoot()
            ready.set(true)
        }
    }

    /**
     * 手动解压 tar.gz 到 rootfsDir（纯 Java，不依赖第三方库）。
     * Tar 格式：512 字节 header + 文件数据（补齐到 512 对齐）+ 两个空 512 块结尾。
     */
    private fun extractTarGz() {
        val tmpTar = File(context.cacheDir, "alpine-rootfs.tar")
        context.assets.open(ALPINE_ROOTFS_ASSET).use { input ->
            val gzip = java.util.zip.GZIPInputStream(input)
            tmpTar.outputStream().use { output -> gzip.copyTo(output) }
        }

        val buf = ByteArray(512)
        tmpTar.inputStream().use { input ->
            while (true) {
                val read = readFully(input, buf, 512)
                if (read < 512) break
                // 解析 tar header
                val name = String(buf, 0, 100).trimEnd('\u0000', ' ').trim()
                if (name.isEmpty()) break // 空块 = 结束
                val sizeStr = String(buf, 124, 12).trimEnd('\u0000', ' ').trim()
                val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
                val typeFlag = buf[156].toInt().toChar()

                // 清理路径前缀 ./
                val cleanName = name.removePrefix("./").removePrefix("/")
                if (cleanName.isEmpty()) {
                    // 跳过数据块
                    skipFully(input, ((size + 511) / 512) * 512)
                    continue
                }

                val outFile = File(rootfsDir, cleanName)
                when (typeFlag) {
                    '5' -> { // 目录
                        outFile.mkdirs()
                    }
                    '0', '\u0000' -> { // 普通文件
                        outFile.parentFile?.mkdirs()
                        if (size > 0) {
                            outFile.outputStream().use { output ->
                                var remaining = size
                                val copyBuf = ByteArray(8192)
                                while (remaining > 0) {
                                    val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                                    val n = input.read(copyBuf, 0, toRead)
                                    if (n < 0) break
                                    output.write(copyBuf, 0, n)
                                    remaining -= n
                                }
                            }
                            // 补齐到 512 对齐
                            val padding = ((size + 511) / 512) * 512 - size
                            skipFully(input, padding)
                        }
                    }
                    '1' -> { // 硬链接，跳过
                        if (size > 0) skipFully(input, ((size + 511) / 512) * 512)
                    }
                    else -> { // 其他类型（符号链接等），跳过数据
                        if (size > 0) skipFully(input, ((size + 511) / 512) * 512)
                    }
                }
            }
        }
        tmpTar.delete()
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) return total
            total += n
        }
        return total
    }

    private fun skipFully(input: java.io.InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip 返回 0，用 read 消费
                val buf = ByteArray(8192)
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    /**
     * 启动 PRoot 交互进程：绑定 rootfs + 映射 work/media 目录。
     * 用 sh -i 保持交互模式，通过 stdin/stdout 发送命令、读取输出。
     */
    private fun startPRoot() {
        val workDir = File(context.filesDir, "media").apply { mkdirs() }
        val builder = ProcessBuilder(
            prootBinary.absolutePath,
            "--rootfs=${rootfsDir.absolutePath}",
            "--cwd=$SANDBOX_WORK",
            "--bind=${workDir.absolutePath}:$SANDBOX_WORK/media",
            "--kill-on-exit",
            "/bin/sh"
        )
        builder.redirectErrorStream(true)
        process = builder.start()

        // 发送初始化命令：设置 PATH + 后台安装基础包（不阻塞就绪）
        val initCmds = buildString {
            appendLine("export PATH=/usr/bin:/usr/sbin:/bin:/sbin:\$PATH")
            appendLine("export HOME=/root")
            appendLine("export TERM=dumb")
            // 后台静默安装 ffmpeg/curl/python3（不阻塞沙箱就绪，exec 时若不可用会回退）
            appendLine("(apk update 2>/dev/null && apk add --no-cache ffmpeg curl python3 ca-certificates 2>/dev/null) &")
            appendLine("echo '___SANDBOX_READY___'")
            appendLine("stty -echo 2>/dev/null; true")
        }
        process?.outputStream?.apply {
            write(initCmds.toByteArray())
            flush()
        }

        // 等待 ready 标记（最长 10 秒，仅等 shell 启动，不等 apk 安装）
        val stdout = process?.inputStream ?: return
        val readyTimeout = 10_000L
        val started = System.currentTimeMillis()
        val sb = StringBuilder()
        try {
            while (System.currentTimeMillis() - started < readyTimeout) {
                val b = stdout.read()
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.contains("___SANDBOX_READY___")) return
            }
        } catch (e: Exception) {
            // 超时或读取异常，继续——沙箱可能仍可用
        }
    }

    /**
     * 在沙箱里执行命令，返回 stdout+stderr 合并输出与退出码。
     * 通过唯一标记分隔每次命令的输出，避免上一次命令残留。
     */
    suspend fun exec(command: String, timeoutMs: Long = 30_000L): ShellResult = mutex.withLock {
        if (!ready.get() || process?.isAlive != true) {
            throw TapcreatorException("沙箱未就绪，请先调用 ensureReady()", "SANDBOX_NOT_READY")
        }
        withContext(Dispatchers.IO) {
            val marker = "___CMD_END_${System.nanoTime()}_${Thread.currentThread().id}___"
            val fullCmd = "$command\necho $marker:\$?\n"

            val proc = process!!
            proc.outputStream.apply {
                write(fullCmd.toByteArray())
                flush()
            }

            val sb = StringBuilder()
            val started = System.currentTimeMillis()
            val stdout = proc.inputStream
            try {
                while (System.currentTimeMillis() - started < timeoutMs) {
                    val b = stdout.read()
                    if (b < 0) break
                    sb.append(b.toChar())
                    // 防 OOM：输出超 1MB 时截断并终止
                    if (sb.length > MAX_OUTPUT_BYTES) {
                        // 截断并等待 marker 提前退出
                        break
                    }
                    // 检测 marker 行
                    val markerIdx = sb.indexOf("$marker:")
                    if (markerIdx >= 0) {
                        // 提取退出码
                        val afterMarker = sb.substring(markerIdx + marker.length + 1)
                        val exitCode = afterMarker.substringBefore('\n').trim().toIntOrNull() ?: -1
                        // 输出是 marker 之前的内容（去掉最后的换行）
                        var output = sb.substring(0, markerIdx)
                        if (output.endsWith('\n')) output = output.dropLast(1)
                        return@withContext ShellResult(output, exitCode)
                    }
                }
                ShellResult(sb.toString(), -1) // 超时或截断
            } catch (e: Exception) {
                ShellResult("沙箱执行异常：${e.message}", -1)
            }
        }
    }

    /**
     * 安装额外的 Alpine 包（幂等）。
     */
    suspend fun installPackage(vararg packages: String): ShellResult {
        return exec("apk add --no-cache ${packages.joinToString(" ")}")
    }

    /** 检查沙箱内某个命令是否可用 */
    suspend fun hasCommand(cmd: String): Boolean {
        val r = exec("which $cmd 2>/dev/null")
        return r.exitCode == 0 && r.output.isNotBlank()
    }

    /** 关闭沙箱（PRoot 进程退出，rootfs 保留） */
    fun close() {
        synchronized(mutex) {
            process?.destroy()
            process = null
            ready.set(false)
        }
    }
}

/** 命令执行结果 */
data class ShellResult(
    val output: String,
    val exitCode: Int,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
