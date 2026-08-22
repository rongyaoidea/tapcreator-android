package com.tapcreator.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 本地 App 设置：仅存轻量偏好，不落任何敏感数据 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val darkTheme = booleanPreferencesKey("dark_theme")
        val lastUsername = stringPreferencesKey("last_username")
        val token = stringPreferencesKey("auth_token")
        val userId = stringPreferencesKey("auth_user_id")
        val seeded = booleanPreferencesKey("defaults_seeded")
        val agentMemoryEnabled = booleanPreferencesKey("agent_memory_enabled")
        val agentStyle = stringPreferencesKey("agent_style")
    }

    /** 默认渠道/模型是否已种子化（仅首次启动种子化一次，避免用户删除后重启又恢复） */
    suspend fun seeded(): Boolean = runCatching {
        context.dataStore.data.first()[Keys.seeded] ?: false
    }.getOrDefault(false)

    suspend fun markSeeded() {
        context.dataStore.edit { it[Keys.seeded] = true }
    }

    val darkTheme: Flow<Boolean> = context.dataStore.data.map { it[Keys.darkTheme] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.darkTheme] = enabled }
    }

    suspend fun lastUsername(): String? = context.dataStore.data.first()[Keys.lastUsername]

    suspend fun setLastUsername(username: String) {
        context.dataStore.edit { it[Keys.lastUsername] = username }
    }

    /** 会话 token —— 存 DataStore 以维持登录态；仅本 App 内使用，不作为敏感凭据对外暴露 */
    suspend fun token(): String? = context.dataStore.data.first()[Keys.token]

    suspend fun saveSession(token: String, userId: String) {
        context.dataStore.edit {
            it[Keys.token] = token
            it[Keys.userId] = userId
        }
    }

    suspend fun currentUserId(): String? = context.dataStore.data.first()[Keys.userId]

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.userId)
        }
    }

    /** 渠道 API Key（仅存 Keystore 加密后的密文，明文只在内存解密用于直连） */
    private fun secretKey(channelId: String) =
        stringPreferencesKey("apikey_cipher_$channelId")

    suspend fun savedSecretCipher(channelId: String): String? =
        context.dataStore.data.first()[secretKey(channelId)]

    suspend fun saveSecretCipher(channelId: String, cipher: String) {
        context.dataStore.edit { it[secretKey(channelId)] = cipher }
    }

    suspend fun removeSecret(channelId: String) {
        context.dataStore.edit { it.remove(secretKey(channelId)) }
    }

    /** Agent 记忆系统开关（默认开启）；关闭后 Agent 不再写入/召回记忆 */
    val agentMemoryEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.agentMemoryEnabled] ?: true }

    suspend fun setAgentMemoryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.agentMemoryEnabled] = enabled }
    }

    suspend fun agentMemoryEnabledValue(): Boolean =
        context.dataStore.data.first()[Keys.agentMemoryEnabled] ?: true

    /** Agent 个人风格偏好：一段自然语言描述，注入 Agent 系统提示，作为其创作风格基调 */
    val agentStyle: Flow<String> = context.dataStore.data.map { it[Keys.agentStyle] ?: "" }

    suspend fun setAgentStyle(style: String) {
        context.dataStore.edit { it[Keys.agentStyle] = style }
    }

    suspend fun agentStyleValue(): String = context.dataStore.data.first()[Keys.agentStyle] ?: ""
}