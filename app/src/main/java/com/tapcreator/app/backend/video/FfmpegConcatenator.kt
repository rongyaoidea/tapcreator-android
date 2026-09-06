package com.tapcreator.app.backend.video

import com.tapcreator.app.backend.sandbox.PRootSandbox
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 ffmpeg 的视频拼接器——替代 MediaMuxer 的脆弱实现。
 *
 * 优势：
 *  - 容忍各段编码参数差异（自动 re-encode 或用 concat demuxer）
 *  - 支持任意格式/编解码器组合
 *  - 失败时 ffmpeg 有详细错误日志，便于诊断
 *
 * 策略：
 *  1. 各段编码参数一致 → 用 concat demuxer（不重编码、快、无损）
 *  2. 不一致或步骤 1 失败 → 重新编码（libx264+aac，兼容性最高）
 *  3. 仍失败 → 返回 false，由上层降级到 MediaMuxer 兜底
 */
@Singleton
class FfmpegConcatenator @Inject constructor(
    private val sandbox: PRootSandbox,
) {

    /**
     * 把 [parts] 按顺序拼成 [outFile]。
     * @return 成功返回 true；失败返回 false（调用方可降级到 Mp4Concatenator）
     */
    suspend fun concat(parts: List<File>, outFile: File): Boolean {
        if (parts.isEmpty()) return false
        if (!sandbox.hasCommand("ffmpeg")) return false

        return withContext(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            try {
                // 把分段文件复制到沙箱工作目录，获得沙箱内路径
                val sandboxParts = parts.mapIndexed { i, f ->
                    val target = File(f.parentFile, "seg_concat_$i.mp4")
                    if (f.absolutePath != target.absolutePath) {
                        f.copyTo(target, overwrite = true)
                        tempFiles += target
                    }
                    "${sandbox.sandboxMediaDir()}/${target.name}"
                }

                outFile.parentFile?.mkdirs()
                val sandboxOut = "${sandbox.sandboxMediaDir()}/${outFile.name}"

                // 策略 1：concat demuxer（编码参数一致时无损拼接）
                // 转义单引号路径：' → '\''
                val concatList = sandboxParts.joinToString("\n") { p -> "file '${escapeSingleQuote(p)}'" }
                val concatListFile = File(outFile.parentFile, "concat_list.txt").apply { writeText(concatList) }
                tempFiles += concatListFile
                val sandboxConcatList = "${sandbox.sandboxMediaDir()}/concat_list.txt"

                val result1 = sandbox.exec(
                    "ffmpeg -y -f concat -safe 0 -i '${escapeSingleQuote(sandboxConcatList)}' -c copy '${escapeSingleQuote(sandboxOut)}' 2>&1",
                    timeoutMs = 60_000L
                )
                if (result1.isSuccess && outFile.exists() && outFile.length() > 0) {
                    return@withContext true
                }

                // 策略 2：重新编码（容忍编码差异）
                val result2 = sandbox.exec(
                    "ffmpeg -y -f concat -safe 0 -i '${escapeSingleQuote(sandboxConcatList)}' " +
                        "-c:v libx264 -preset fast -crf 23 " +
                        "-c:a aac -b:a 128k " +
                        "'${escapeSingleQuote(sandboxOut)}' 2>&1",
                    timeoutMs = 120_000L
                )
                result2.isSuccess && outFile.exists() && outFile.length() > 0
            } finally {
                // 清理 seg_concat_* 复制件与 concat_list.txt，失败/异常路径也不残留
                tempFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    /** 转义 shell 单引号内的单引号：' → '\'' */
    private fun escapeSingleQuote(s: String): String = s.replace("'", "'\\''")

    /**
     * 从视频提取末帧（用于续生成）。
     * @return JPEG 字节，失败返回 null
     */
    suspend fun extractLastFrame(videoPath: String): ByteArray? {
        if (!sandbox.hasCommand("ffmpeg")) return null
        return withContext(Dispatchers.IO) {
            val sandboxVideo = "${sandbox.sandboxMediaDir()}/${File(videoPath).name}"
            val sandboxFrame = "${sandbox.sandboxMediaDir()}/last_frame.jpg"
            val result = sandbox.exec(
                "ffmpeg -y -sseof -0.1 -i '${escapeSingleQuote(sandboxVideo)}' -vframes 1 -q:v 2 '${escapeSingleQuote(sandboxFrame)}' 2>&1",
                timeoutMs = 15_000L
            )
            if (result.isSuccess) {
                File(File(videoPath).parentFile, "last_frame.jpg").let { f ->
                    if (f.exists()) f.readBytes() else null
                }
            } else null
        }
    }
}
