package com.tapcreator.app.backend.video

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Mp4Concatenator 纯 JVM 边界单测：只校验不触碰 Android 媒体框架的入口路径。
 * 真实拼接（MediaMuxer/MediaExtractor）依赖 Android 设备环境，属 instrumented 测试范畴，
 * 这里只守「空输入安全短路、不进 concatTracks」这一纯逻辑边界，避免误抛污染上层 run 状态。
 */
class Mp4ConcatenatorTest {

    @Test
    fun `concat empty list returns false without touching MediaMuxer`() {
        // 空段列表在 concat() 入口即 return false，不构造 MediaMuxer（避免 NoClassDefFoundError）
        val out = File.createTempFile("concat-empty", ".mp4").apply { delete() }
        assertFalse("空段列表不应拼接成功", Mp4Concatenator.concat(emptyList(), out))
        assertFalse("空输入不应产生输出文件", out.exists())
    }
}
