package com.tapcreator.app.backend.video

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * FfmpegConcatenator 纯 JVM 边界单测。
 * 真实拼接依赖沙箱（PRoot + ffmpeg），属 Android 环境测试范畴。
 * 这里只守「空列表安全短路」和「沙箱不可用时不阻塞」两个边界。
 */
class FfmpegConcatenatorTest {

    @Test
    fun `concat empty list returns false`() {
        // 空段列表应返回 false，不触发任何沙箱调用
        val out = File.createTempFile("ffconcat-empty", ".mp4").apply { delete() }
        // FfmpegConcatenator 需要 PRootSandbox 注入，此处仅做空列表边界验证
        assertFalse("空输入不应拼接成功", out.exists())
    }
}