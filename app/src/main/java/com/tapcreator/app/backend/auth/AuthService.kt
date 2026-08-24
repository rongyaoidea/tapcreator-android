package com.tapcreator.app.backend.auth

import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.SessionEntity
import com.tapcreator.app.data.db.UserEntity
import com.tapcreator.app.data.model.AuthFailedException
import com.tapcreator.app.data.model.TapcreatorException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内嵌后端认证：注册 / 登录 / 会话。密码用 PBKDF2-SHA256 加盐哈希，会话 token 存 Room。
 * 复刻语义：注册策略（可选邮箱）从简。
 */
@Singleton
class AuthService @Inject constructor(
    private val db: AppDatabase,
) {
    companion object {
        private const val PBKDF2_ITERATIONS = 120_000
        private const val KEY_LENGTH = 256
    }

    private val random = SecureRandom()

    /** 注册并返回带 token 的会话 */
    suspend fun register(username: String, password: String): SessionEntity {
        val name = username.trim()
        require(name.length in 2..32) { "用户名需 2-32 个字符" }
        require(password.length >= 6) { "密码至少 6 位" }
        if (db.userDao().byUsername(name) != null) {
            throw TapcreatorException("用户名已被占用", "USERNAME_TAKEN")
        }

        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = hash(password, salt)
        val user = UserEntity(
            id = UUID.randomUUID().toString(),
            username = name,
            email = null,
            passwordHash = hash,
            salt = toB64(salt),
            createdAt = System.currentTimeMillis(),
        )
        db.userDao().insert(user)
        return createSession(user.id)
    }

    suspend fun login(username: String, password: String): SessionEntity {
        val user = db.userDao().byUsername(username.trim())
            ?: throw AuthFailedException("用户名或密码错误")
        val salt = fromB64(user.salt)
        if (hash(password, salt) != user.passwordHash) {
            throw AuthFailedException("用户名或密码错误")
        }
        return createSession(user.id)
    }

    suspend fun sessionUserId(token: String): String? =
        db.sessionDao().byToken(token)?.userId

    suspend fun currentUser(token: String?): UserEntity? {
        if (token == null) return null
        val userId = sessionUserId(token) ?: return null
        return db.userDao().byId(userId)
    }

    suspend fun logout(token: String) {
        db.sessionDao().deleteByToken(token)
    }

    private suspend fun createSession(userId: String): SessionEntity {
        val session = SessionEntity(
            token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
            userId = userId,
            createdAt = System.currentTimeMillis(),
        )
        db.sessionDao().insert(session)
        return session
    }

    /** PBKDF2 哈希，输出 "iterations$b64(dk)" */
    internal fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return toB64(derived)
    }

    internal fun toB64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    internal fun fromB64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}