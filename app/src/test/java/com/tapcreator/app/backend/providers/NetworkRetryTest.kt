package com.tapcreator.app.backend.providers

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetworkRetry 单测：底层异常 → 可读 TapcreatorException 映射。
 */
class NetworkRetryTest {

    @Test
    fun `socket timeout maps to timeout message`() {
        val e = NetworkRetry.friendlyNetworkError(SocketTimeoutException("Read timed out"))
        assertEquals("NETWORK", e.code)
        assertTrue(e.message!!.contains("超时"))
    }

    @Test
    fun `connect exception maps to unreachable message`() {
        val e = NetworkRetry.friendlyNetworkError(ConnectException("refused"))
        assertTrue(e.message!!.contains("无法连接"))
    }

    @Test
    fun `reset abort maps to interrupted message`() {
        val e = NetworkRetry.friendlyNetworkError(IOException("software caused connection abort"))
        assertTrue(e.message!!.contains("中断"))
    }

    @Test
    fun `unknown io maps to generic message`() {
        val e = NetworkRetry.friendlyNetworkError(IOException("boom"))
        assertTrue(e.message!!.contains("boom"))
    }
}
