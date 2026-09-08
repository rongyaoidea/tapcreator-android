package com.tapcreator.app.backend.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolLoopDetector 真实实现单测（纯 JVM，无 Android/org.json 依赖）。
 * 默认阈值：history=12, warn=3, unknown=4, pollCritical=5, breaker=7。
 */
class ToolLoopDetectorTest {

    private fun gen(prompt: String) = mapOf("tool" to "GENERATE_IMAGE", "prompt" to prompt)

    @Test
    fun `first identical call is not a loop`() {
        val d = ToolLoopDetector()
        assertEquals(LoopLevel.NONE, d.check("generate", gen("cat")).level)
        assertEquals(LoopLevel.NONE, d.record("generate", gen("cat"), result = "ok").level)
    }

    @Test
    fun `different args never warn`() {
        val d = ToolLoopDetector()
        repeat(6) { i ->
            assertEquals(LoopLevel.NONE, d.record("generate", gen("p$i"), result = "ok").level)
        }
    }

    @Test
    fun `non-poll identical args warn at threshold`() {
        val d = ToolLoopDetector()
        assertEquals(LoopLevel.NONE, d.record("generate", gen("same"), result = "r1").level)
        assertEquals(LoopLevel.NONE, d.record("generate", gen("same"), result = "r2").level)
        // 第 3 次同参数 -> WARNING
        assertEquals(LoopLevel.WARNING, d.record("generate", gen("same"), result = "r3").level)
    }

    @Test
    fun `warning is throttled within a bucket`() {
        val d = ToolLoopDetector()
        // 计数 1..3：3 触发告警（桶=1）
        d.record("generate", gen("s"), result = "a")
        d.record("generate", gen("s"), result = "b")
        assertEquals(LoopLevel.WARNING, d.record("generate", gen("s"), result = "c").level)
        // 计数 4 仍落在同一桶（4/3=1），应被节流为 NONE
        assertEquals(LoopLevel.NONE, d.record("generate", gen("s"), result = "d").level)
    }

    @Test
    fun `poll tool escalates to critical on frozen result`() {
        val d = ToolLoopDetector()
        val p = mapOf("command" to "ls /work/media")
        repeat(5) { d.record("shell_execute", p, result = "waiting") }
        val chk = d.check("shell_execute", p)
        assertEquals(LoopLevel.CRITICAL, chk.level)
        assertNotNull(chk.message)
    }

    @Test
    fun `poll tool progress resets no-progress streak`() {
        val d = ToolLoopDetector()
        val p = mapOf("command" to "cat log")
        repeat(3) { d.record("shell_execute", p, result = "same") }
        d.record("shell_execute", p, result = "changed") // 结果变化打断空转
        assertEquals(LoopLevel.NONE, d.check("shell_execute", p).level)
    }

    @Test
    fun `global circuit breaker blocks non-pool no-progress`() {
        val d = ToolLoopDetector()
        repeat(7) { d.record("read_card", mapOf("card_id" to "x"), result = "frozen") }
        val chk = d.check("read_card", mapOf("card_id" to "x"))
        assertEquals(LoopLevel.CRITICAL, chk.level)
        assertTrue(chk.message!!.contains("熔断"))
    }

    @Test
    fun `unknown action streak triggers critical`() {
        val d = ToolLoopDetector()
        repeat(ToolLoopConfig().unknownToolThreshold) {
            d.record(
                "frobnicate", emptyMap(), result = null,
                errorMessage = "[动作错误] 未知动作：frobnicate。请仅使用可用动作名。",
            )
        }
        assertEquals(LoopLevel.CRITICAL, d.check("frobnicate", emptyMap()).level)
    }

    @Test
    fun `extracts hallucinated name from english phrasing too`() {
        val d = ToolLoopDetector()
        repeat(ToolLoopConfig().unknownToolThreshold) {
            d.record("foo", emptyMap(), result = null, errorMessage = "unknown tool: foo")
        }
        assertEquals(LoopLevel.CRITICAL, d.check("foo", emptyMap()).level)
    }

    @Test
    fun `reset clears history`() {
        val d = ToolLoopDetector()
        repeat(5) { d.record("generate", gen("same"), result = "r") }
        d.reset()
        assertEquals(0, d.historySnapshot().size)
        assertEquals(LoopLevel.NONE, d.record("generate", gen("same"), result = "r").level)
    }

    @Test
    fun `argsHash ignores map insertion order`() {
        val d = ToolLoopDetector()
        val a = linkedMapOf("tool" to "GENERATE_IMAGE", "prompt" to "x")
        val b = linkedMapOf("prompt" to "x", "tool" to "GENERATE_IMAGE")
        d.record("generate", a, result = "r")
        d.record("generate", b, result = "r")
        // 同参数不同插入顺序应被识别为同一调用，第 3 次即告警
        assertEquals(LoopLevel.WARNING, d.record("generate", a, result = "r").level)
    }

    @Test
    fun `empty message on NONE result`() {
        val d = ToolLoopDetector()
        assertNull(d.check("generate", gen("only")).message)
    }
}