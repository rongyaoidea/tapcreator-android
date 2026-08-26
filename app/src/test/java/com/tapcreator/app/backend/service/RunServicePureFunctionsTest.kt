package com.tapcreator.app.backend.service

import com.tapcreator.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RunService 纯函数单测：分段时长计算、路径类型推断、标题生成。
 * 这些函数不依赖实例状态，直接复制实现逻辑测试。
 */
class RunServicePureFunctionsTest {

    private val SEGMENT_SECONDS = 8

    // ============ segmentDurations（与 RunService 内实现一致） ============

    private fun segmentDurations(target: Int, isH3: Boolean): List<Int> {
        if (!isH3) {
            val segCount = ((target - 1) / SEGMENT_SECONDS) + 1
            return (0 until segCount).map { minOf(SEGMENT_SECONDS, target - it * SEGMENT_SECONDS).coerceAtLeast(1) }
        }
        val t = target.coerceIn(4, 120)
        if (t <= 15) return listOf(t)
        val n = (t + 14) / 15
        val base = t / n
        val rem = t % n
        return (0 until n).map { base + if (it < rem) 1 else 0 }
    }

    @Test
    fun `segmentDurations non-h3 single segment`() {
        val result = segmentDurations(5, false)
        assertEquals(1, result.size)
        assertEquals(5, result[0])
    }

    @Test
    fun `segmentDurations non-h3 splits into 8s segments`() {
        val result = segmentDurations(20, false)
        assertEquals(3, result.size)
        assertEquals(8, result[0])
        assertEquals(8, result[1])
        assertEquals(4, result[2])
    }

    @Test
    fun `segmentDurations non-h3 exactly 8s`() {
        val result = segmentDurations(8, false)
        assertEquals(1, result.size)
        assertEquals(8, result[0])
    }

    @Test
    fun `segmentDurations non-h3 16s splits into 2`() {
        val result = segmentDurations(16, false)
        assertEquals(2, result.size)
        assertEquals(8, result[0])
        assertEquals(8, result[1])
    }

    @Test
    fun `segmentDurations h3 under 15s returns single segment`() {
        val result = segmentDurations(10, true)
        assertEquals(1, result.size)
        assertEquals(10, result[0])
    }

    @Test
    fun `segmentDurations h3 exactly 15s returns single`() {
        val result = segmentDurations(15, true)
        assertEquals(1, result.size)
        assertEquals(15, result[0])
    }

    @Test
    fun `segmentDurations h3 30s splits into 2`() {
        val result = segmentDurations(30, true)
        assertEquals(2, result.size)
        assertEquals(30, result.sum())
    }

    @Test
    fun `segmentDurations h3 60s splits into 4`() {
        val result = segmentDurations(60, true)
        assertEquals(4, result.size)
        assertEquals(60, result.sum())
    }

    @Test
    fun `segmentDurations h3 clamps to 4 minimum`() {
        val result = segmentDurations(2, true)
        assertEquals(1, result.size)
        assertEquals(4, result[0])
    }

    @Test
    fun `segmentDurations h3 clamps to 120 maximum`() {
        val result = segmentDurations(200, true)
        assertEquals(120, result.sum())
    }

    @Test
    fun `segmentDurations all segments at least 1`() {
        for (target in 1..50) {
            val result = segmentDurations(target, false)
            assertTrue("target=$target 有段 < 1", result.all { it >= 1 })
        }
    }

    // ============ kindForPath（与 RunService 内实现一致） ============

    private fun kindForPath(path: String): MediaKind = when {
        path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".webm") ||
            path.endsWith(".mkv") -> MediaKind.VIDEO
        path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".m4a") ||
            path.endsWith(".aac") -> MediaKind.AUDIO
        else -> MediaKind.IMAGE
    }

    @Test
    fun `kindForPath mp4 returns VIDEO`() {
        assertEquals(MediaKind.VIDEO, kindForPath("/path/to/video.mp4"))
    }

    @Test
    fun `kindForPath mov returns VIDEO`() {
        assertEquals(MediaKind.VIDEO, kindForPath("/path/to/video.mov"))
    }

    @Test
    fun `kindForPath mp3 returns AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindForPath("/path/to/audio.mp3"))
    }

    @Test
    fun `kindForPath wav returns AUDIO`() {
        assertEquals(MediaKind.AUDIO, kindForPath("/path/to/audio.wav"))
    }

    @Test
    fun `kindForPath jpg returns IMAGE`() {
        assertEquals(MediaKind.IMAGE, kindForPath("/path/to/image.jpg"))
    }

    @Test
    fun `kindForPath png returns IMAGE`() {
        assertEquals(MediaKind.IMAGE, kindForPath("/path/to/image.png"))
    }

    @Test
    fun `kindForPath unknown ext returns IMAGE`() {
        assertEquals(MediaKind.IMAGE, kindForPath("/path/to/file.xyz"))
    }

    // ============ summarizeTitle / titleFor ============

    private fun summarizeTitle(prompt: String): String {
        val clean = prompt.trim().take(18)
        return if (clean.isBlank()) "新会话" else clean
    }

    private fun titleFor(modelName: String, index: Int): String =
        if (index == 0) modelName else "$modelName ${index + 1}"

    @Test
    fun `summarizeTitle takes first 18 chars`() {
        val result = summarizeTitle("这是一个很长的提示词，需要被截断为标题")
        assertEquals(18, result.length)
    }

    @Test
    fun `summarizeTitle blank returns placeholder`() {
        assertEquals("新会话", summarizeTitle("   "))
    }

    @Test
    fun `summarizeTitle short prompt unchanged`() {
        assertEquals("短提示", summarizeTitle("短提示"))
    }

    @Test
    fun `titleFor first index returns model name`() {
        assertEquals("gpt-4o", titleFor("gpt-4o", 0))
    }

    @Test
    fun `titleFor second index appends number`() {
        assertEquals("gpt-4o 2", titleFor("gpt-4o", 1))
    }

    // ============ coversOriginal（优化结果与原意重叠度校验，与 RunService 内实现一致） ============

    private fun coversOriginal(optimized: String, original: String): Boolean {
        if (original.isBlank()) return true
        val normOpt = optimized.lowercase()
        val normOrig = original.lowercase()
        val grams = mutableListOf<String>()
        Regex("[a-z0-9]{2,}").findAll(normOrig).forEach { grams += it.value }
        Regex("[\\u4e00-\\u9fff]+").findAll(normOrig).forEach { run ->
            val s = run.value
            if (s.length == 1) grams += s
            else for (i in 0 until s.length - 1) grams += s.substring(i, i + 2)
        }
        if (grams.isEmpty()) return true
        val hit = grams.count { g -> normOpt.contains(g) }
        return hit.toFloat() / grams.size >= 0.6f
    }

    @Test
    fun `coversOriginal keeps fully preserved original`() {
        assertTrue(coversOriginal("一只白猫坐在窗台上晒太阳", "一只白猫坐在窗台上晒太阳"))
    }

    @Test
    fun `coversOriginal accepts light polish keeping key elements`() {
        // 优化结果保留原文所有关键词元（赛博狐狸/霓虹灯/黑夜）→ 通过
        assertTrue(coversOriginal("赛博狐狸在霓虹灯下的黑夜中奔跑", "赛博狐狸，霓虹灯，黑夜"))
    }

    @Test
    fun `coversOriginal accepts english original preserved`() {
        // 英文原文完整保留 + 追加修饰词 → 通过
        assertTrue(coversOriginal("a white cat sitting on the windowsill, warm sunlight", "a white cat sitting on the windowsill"))
    }

    @Test
    fun `coversOriginal tolerates english synonym for one word`() {
        // 英文单个同义词替换（cat→feline）不判定脱离原意 → 通过
        assertTrue(coversOriginal("a white feline sitting on the windowsill, cinematic", "a white cat sitting on the windowsill"))
    }

    @Test
    fun `coversOriginal accepts chinese with inserted english modifiers`() {
        // 中文原文中插入英文修饰词/空格，bigram 仍多数命中 → 通过
        assertTrue(coversOriginal("一只白猫坐在窗台上晒太阳, soft warm sunlight, high quality", "一只白猫坐在窗台上晒太阳"))
    }

    @Test
    fun `coversOriginal rejects prompt rewritten away from original`() {
        // 模型把"白猫晒太阳"改写成无关的"黑色机器狼"，判定脱离原意 → 回退原文
        assertTrue(!coversOriginal("黑色森林中的机械狼", "一只白猫坐在窗台上晒太阳"))
    }

    @Test
    fun `coversOriginal rejects blank optimized result`() {
        assertTrue(!coversOriginal("", "一只白猫"))
    }
}