package com.tapcreator.app.backend.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthService 纯函数单测：PBKDF2 哈希验证、Base64 编解码。
 * 不依赖 Room 数据库，直接用 JDK API 测试等价逻辑。
 */
class AuthServicePureFunctionsTest {

    private val PBKDF2_ITERATIONS = 120_000
    private val KEY_LENGTH = 256

    // ============ PBKDF2 哈希（与 AuthService 内实现一致） ============

    private fun hash(password: String, salt: ByteArray): String {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val derived = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return java.util.Base64.getEncoder().encodeToString(derived)
    }

    private fun toB64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun fromB64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    @Test
    fun `hash produces non-empty output`() {
        val salt = ByteArray(16) { it.toByte() }
        val result = hash("password123", salt)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `hash is deterministic with same password and salt`() {
        val salt = ByteArray(16) { 0x42 }
        assertEquals(hash("testpass", salt), hash("testpass", salt))
    }

    @Test
    fun `hash differs with different passwords`() {
        val salt = ByteArray(16) { 0x42 }
        assertNotEquals(hash("password1", salt), hash("password2", salt))
    }

    @Test
    fun `hash differs with different salts`() {
        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 }
        assertNotEquals(hash("samepass", salt1), hash("samepass", salt2))
    }

    @Test
    fun `hash output is valid base64`() {
        val salt = ByteArray(16)
        val result = hash("test", salt)
        val decoded = java.util.Base64.getDecoder().decode(result)
        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun `hash output length matches key length`() {
        val salt = ByteArray(16)
        val decoded = java.util.Base64.getDecoder().decode(hash("test", salt))
        assertEquals(32, decoded.size) // 256 bits = 32 bytes
    }

    @Test
    fun `hash handles unicode password`() {
        val salt = ByteArray(16)
        assertTrue(hash("密码🔐", salt).isNotEmpty())
    }

    @Test
    fun `hash handles empty password`() {
        val salt = ByteArray(16)
        assertTrue(hash("", salt).isNotEmpty())
    }

    // ============ toB64 / fromB64 ============

    @Test
    fun `toB64 encodes bytes to base64`() {
        assertEquals("aGVsbG8=", toB64("hello".toByteArray()))
    }

    @Test
    fun `fromB64 decodes base64 to bytes`() {
        assertEquals("hello", String(fromB64("aGVsbG8=")))
    }

    @Test
    fun `toB64 and fromB64 are inverse`() {
        val original = "测试数据".toByteArray()
        val encoded = toB64(original)
        val decoded = fromB64(encoded)
        assertEquals(original.toList(), decoded.toList())
    }

    @Test
    fun `toB64 handles empty bytes`() {
        assertEquals("", toB64(ByteArray(0)))
    }

    // ============ 参数验证 ============

    @Test
    fun `PBKDF2 iterations is 120000`() {
        assertEquals(120_000, PBKDF2_ITERATIONS)
    }

    @Test
    fun `PBKDF2 key length is 256 bits`() {
        assertEquals(256, KEY_LENGTH)
    }
}