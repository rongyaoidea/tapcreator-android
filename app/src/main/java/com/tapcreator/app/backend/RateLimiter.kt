package com.tapcreator.app.backend

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 每分钟请求数（RPM）限制器。
 *
 * 滑动窗口实现：记录每个请求的时间戳，每分钟检查窗口内请求数是否超过上限。
 * 超过时自动等待到窗口滑动，使每分钟的请求数不超过设定的 RPM 上限。
 *
 * 线程安全：所有操作通过 Mutex 串行化。
 * 等待时不持有锁，避免阻塞其他协程。
 */
@Singleton
class RateLimiter @Inject constructor() {

    /** 默认每分钟最大请求数（Agent 对文本模型的调用频率） */
    var maxRpm: Int = DEFAULT_RPM
        private set

    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()
    private val windowMs = 60_000L // 1 分钟窗口

    /**
     * 设置 RPM 上限。
     * @param rpm 每分钟最大请求数（<=0 表示不限制）
     */
    fun setMaxRpm(rpm: Int) {
        maxRpm = if (rpm <= 0) Int.MAX_VALUE else rpm
    }

    /**
     * 尝试获取一个请求配额。
     * 如果当前窗口内请求数已达上限，则等待直到旧请求过期，窗口内有空闲配额。
     * 等待时不持有锁，其他协程仍可正常获取配额。
     *
     * @return 等待的毫秒数（0 表示无需等待）
     */
    suspend fun acquire(): Long {
        // 计算需要等待的时间（在锁内计算）
        val waitMs = mutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - windowMs

            // 移除窗口外的时间戳
            while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                timestamps.removeFirst()
            }

            // 无限流（maxRpm == Int.MAX_VALUE）直接放行
            if (maxRpm == Int.MAX_VALUE) return@withLock 0L

            // 窗口内还有配额
            if (timestamps.size < maxRpm) {
                timestamps.addLast(now)
                return@withLock 0L
            }

            // 已达上限：计算需要等待多久（最早的请求过期时间 - 当前时间）
            val oldest = timestamps.first()
            oldest + windowMs - now + 1 // +1ms 确保过期
        }

        // 无等待或等待时间已过，直接返回
        if (waitMs <= 0) return 0L

        // 释放锁后等待，避免阻塞其他协程
        delay(waitMs)

        // 重新获取锁，记录本次请求时间戳
        mutex.withLock {
            val now = System.currentTimeMillis()
            val newWindowStart = now - windowMs
            // 移除已过期的请求
            while (timestamps.isNotEmpty() && timestamps.first() < newWindowStart) {
                timestamps.removeFirst()
            }
            timestamps.addLast(now)
        }

        return waitMs
    }

    /** 重置限制器（清空历史，用于测试/切换模型） */
    fun reset() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock { timestamps.clear() }
        }
    }

    /** 当前窗口内已用请求数 */
    suspend fun used(): Int = mutex.withLock {
        val now = System.currentTimeMillis()
        val windowStart = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
            timestamps.removeFirst()
        }
        timestamps.size
    }

    companion object {
        /** 默认每分钟最大请求数 */
        private const val DEFAULT_RPM = 30
    }
}