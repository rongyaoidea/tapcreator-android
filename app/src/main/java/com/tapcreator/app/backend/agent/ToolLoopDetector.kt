package com.tapcreator.app.backend.agent

import java.security.MessageDigest

/** 每轮工具调用的滑动窗口记录。check 只读历史，record 执行后追加。 */
data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class LoopLevel { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: LoopLevel,
    val message: String? = null,
    val warningKey: String? = null,
) {
    companion object { val NONE = LoopCheckResult(LoopLevel.NONE) }
}

/**
 * 阈值契约：warning < critical < breaker <= historySize，构造期校验。
 * 按 tapcreator 短预算（默认 maxTurns=12）缩放；来源项目 200 轮的 10/20/30 在此永不触发。
 */
data class ToolLoopConfig(
    val historySize: Int = 12,
    val warningThreshold: Int = 3,
    val unknownToolThreshold: Int = 4,
    val criticalThreshold: Int = 5,
    val globalCircuitBreakerThreshold: Int = 7,
) {
    init {
        require(warningThreshold > 0)
        require(warningThreshold < criticalThreshold)
        require(criticalThreshold < globalCircuitBreakerThreshold)
        require(historySize >= globalCircuitBreakerThreshold)
    }
}

/**
 * 死循环检测器：滑动窗口识别工具调用空转。设计衍生自 OpenMinis 的同名组件（GPL-3.0），
 * 重写为零 org.json/Android 依赖以跑纯 JVM 单测；见仓库根 NOTICE.md。
 * 策略（优先级高→低）：unknown_tool_repeat / global_circuit_breaker / poll_no_progress / generic_repeat。
 * 一次 run 一个实例，单线程派发，无需线程安全。
 */
class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    private val history = ArrayDeque<ToolCallRecord>()
    private val warningBuckets = HashMap<String, Int>()

    fun reset() {
        history.clear()
        warningBuckets.clear()
    }

    internal fun historySnapshot(): List<ToolCallRecord> = history.toList()

    /** 执行前钩子：CRITICAL 时调用方必须跳过执行、把 message 作为错误结果回灌。 */
    fun check(toolName: String, params: Map<String, String>): LoopCheckResult {
        val argsHash = argsHashFor(toolName, params)

        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= config.unknownToolThreshold) {
            return LoopCheckResult(
                LoopLevel.CRITICAL,
                "[LOOP BLOCKED] CRITICAL：反复尝试不存在的工具「$toolName」达 $unknownStreak 次。停止重试，改用可用动作或 finish。",
            )
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)
        if (noProgressStreak >= config.globalCircuitBreakerThreshold) {
            return LoopCheckResult(
                LoopLevel.CRITICAL,
                "[LOOP BLOCKED] CRITICAL：$toolName 以相同参数与结果重复 $noProgressStreak 次，无进展，被全局熔断拦截。请换动作或 finish。",
            )
        }

        if (isPollTool(toolName) && noProgressStreak >= config.criticalThreshold) {
            return LoopCheckResult(
                LoopLevel.CRITICAL,
                "[LOOP BLOCKED] CRITICAL：$toolName 连续 $noProgressStreak 次返回相同无进展结果。执行被拦截：加大等待或判定失败后 finish。",
            )
        }
        // 执行前只产出 CRITICAL（硬拦）；WARNING 由执行后的 record() 回灌，避免 check() 死分支
        return LoopCheckResult.NONE
    }

    /** 执行后钩子：并入窗口；只可能返回 NONE 或 WARNING（CRITICAL 由 check 负责）。 */
    fun record(
        toolName: String,
        params: Map<String, String>,
        result: String?,
        errorMessage: String? = null,
    ): LoopCheckResult {
        val argsHash = argsHashFor(toolName, params)
        history.addLast(
            ToolCallRecord(
                toolName = toolName,
                argsHash = argsHash,
                resultHash = resultHashFor(result, errorMessage),
                unknownToolName = extractUnknownToolName(errorMessage),
            ),
        )
        while (history.size > config.historySize) history.removeFirst()

        if (isPollTool(toolName)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in config.warningThreshold until config.criticalThreshold &&
                shouldEmitWarning("poll:$toolName:$argsHash", streak)
            ) {
                return LoopCheckResult(
                    LoopLevel.WARNING,
                    "[LOOP WARNING] 你已用相同参数调用 $toolName $streak 次且无进展。加大等待或判定失败后 finish。",
                    "poll:$toolName:$argsHash",
                )
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold &&
                shouldEmitWarning("repeat:$toolName:$argsHash", totalCount)
            ) {
                return LoopCheckResult(
                    LoopLevel.WARNING,
                    "[LOOP WARNING] 你已用相同参数调用 $toolName $totalCount 次。若没推进任务，停止重试并报告失败或 finish。",
                    "repeat:$toolName:$argsHash",
                )
            }
        }
        return LoopCheckResult.NONE
    }

    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) { pinnedHash = rh; streak++ }
            else if (rh == pinnedHash) streak++
            else break
        }
        return streak
    }

    // tapcreator 无显式轮询工具；反复用 shell_execute 跑同一命令来等后台就绪即等价轮询。
    private fun isPollTool(toolName: String): Boolean = toolName == "shell_execute"

    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        if (warningBuckets[warningKey] == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    // argsHash：按 key 排序 + 长度前缀编码，天然抗碰撞，无需分隔符/转义。
    private fun argsHashFor(toolName: String, params: Map<String, String>): String {
        val sb = StringBuilder()
        appendToken(sb, toolName)
        params.entries.sortedBy { it.key }.forEach { (k, v) ->
            appendToken(sb, k); appendToken(sb, v)
        }
        return sha256(sb.toString())
    }

    private fun appendToken(sb: StringBuilder, s: String) {
        sb.append(s.length).append(':').append(s)
    }

    private fun resultHashFor(result: String?, errorMessage: String?): String {
        val sb = StringBuilder()
        appendToken(sb, errorMessage ?: "")
        appendToken(sb, result ?: "")
        return sha256(sb.toString())
    }

    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        UNKNOWN_ACTION_RE.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        UNKNOWN_TOOL_RE.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }

    companion object {
        // 覆盖 tapcreator 中文措辞「未知动作：X」与来源项目英文措辞。
        private val UNKNOWN_ACTION_RE = Regex("未知动作[:：]\\s*([A-Za-z0-9_.-]+)")
        private val UNKNOWN_TOOL_RE =
            Regex("unknown tool[:\\s]+['\"]?([A-Za-z0-9_.-]+)", RegexOption.IGNORE_CASE)
    }
}