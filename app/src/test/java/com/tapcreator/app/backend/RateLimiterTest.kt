package com.tapcreator.app.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RateLimiter 纯函数单测：
 *  - 默认 RPM 限制
 *  - setMaxRpm 配置
 *  - acquire 配额获取与等待
 *  - reset 重置
 *  - used 已用配额查询
 */
class RateLimiterTest {

    @Test
    fun `default rpm is 30`() {
        val limiter = RateLimiter()
        assertEquals(30, limiter.maxRpm)
    }

    @Test
    fun `setMaxRpm sets positive value`() {
        val limiter = RateLimiter()
        limiter.setMaxRpm(10)
        assertEquals(10, limiter.maxRpm)
    }

    @Test
    fun `setMaxRpm zero or negative means unlimited`() {
        val limiter = RateLimiter()
        limiter.setMaxRpm(0)
        assertEquals(Int.MAX_VALUE, limiter.maxRpm)
        limiter.setMaxRpm(-1)
        assertEquals(Int.MAX_VALUE, limiter.maxRpm)
    }

    @Test
    fun `acquire returns zero wait when under limit`() = runTest {
        val limiter = RateLimiter()
        limiter.setMaxRpm(5)
        repeat(5) { i ->
            val wait = limiter.acquire()
            assertEquals("第${i + 1}次获取配额应无需等待", 0L, wait)
        }
    }

    @Test
    fun `acquire under unlimited returns zero`() = runTest {
        val limiter = RateLimiter()
        limiter.setMaxRpm(0) // unlimited
        repeat(100) {
            assertEquals(0L, limiter.acquire())
        }
    }

    @Test
    fun `used returns count of requests in window`() = runTest {
        val limiter = RateLimiter()
        limiter.setMaxRpm(10)
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        assertEquals(3, limiter.used())
    }

    @Test
    fun `reset clears all timestamps`() = runTest {
        val limiter = RateLimiter()
        limiter.setMaxRpm(5)
        repeat(3) { limiter.acquire() }
        assertEquals(3, limiter.used())
        limiter.reset()
        assertEquals(0, limiter.used())
    }

    @Test
    fun `acquire at exact limit has zero wait`() = runTest {
        val limiter = RateLimiter()
        limiter.setMaxRpm(3)
        assertEquals(0L, limiter.acquire())
        assertEquals(0L, limiter.acquire())
        assertEquals(0L, limiter.acquire())
        assertEquals(3, limiter.used())
    }
}