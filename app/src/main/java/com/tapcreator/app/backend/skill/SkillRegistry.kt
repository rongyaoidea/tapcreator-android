package com.tapcreator.app.backend.skill

import com.tapcreator.app.data.model.BuiltinSkills
import com.tapcreator.app.data.model.DesignSkill
import com.tapcreator.app.data.prefs.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 设计 Skill 注册表：管理已安装的 Skill（内置预设 + 用户自建/第三方安装）。
 *
 *  - 内置 9 个预设随 App 发布，不可删除
 *  - 用户通过 skill_creator 安装的第三方 skill 持久化到 DataStore
 *  - apply_skill 时按 id 查找返回 promptGuide，Agent 注入到 generate 的 prompt
 */
@Singleton
class SkillRegistry @Inject constructor(
    private val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 已安装的第三方 skill（内存缓存 + DataStore 持久化）
    private val _installedSkills = MutableStateFlow<List<DesignSkill>>(emptyList())
    val installedSkills: StateFlow<List<DesignSkill>> = _installedSkills

    /**
     * 初始化：从 DataStore 加载已安装的第三方 skill。
     * 内置预设不需要持久化（硬编码在 BuiltinSkills.presets）。
     */
    suspend fun init() {
        val raw = settings.installedSkillsJson()
        if (raw.isNotBlank()) {
            _installedSkills.value = runCatching {
            json.decodeFromString(ListSerializer(DesignSkill.serializer()), raw)
        }.getOrDefault(emptyList())
        }
    }

    /** 全部可用 skill：内置预设 + 用户安装 */
    fun all(): List<DesignSkill> = BuiltinSkills.presets + _installedSkills.value

    /** 按 id 查找 skill */
    fun byId(id: String): DesignSkill? = all().firstOrNull { it.id == id }

    /** 列出全部 skill（供 list_skills 工具展示） */
    fun list(): List<DesignSkill> = all()

    /**
     * 安装一个第三方 skill。
     * id 已存在则更新（覆盖），否则新增。持久化到 DataStore。
     */
    suspend fun install(skill: DesignSkill) {
        val current = _installedSkills.value.toMutableList()
        val idx = current.indexOfFirst { it.id == skill.id }
        if (idx >= 0) current[idx] = skill else current.add(skill)
        _installedSkills.value = current
        persist(current)
    }

    /**
     * 删除一个用户安装的 skill。
     * 内置预设不可删除（builtIn=true 时拒绝）。
     */
    suspend fun uninstall(id: String): Boolean {
        val current = _installedSkills.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _installedSkills.value = current
            persist(current)
        }
        return removed
    }

    private suspend fun persist(skills: List<DesignSkill>) {
        settings.saveInstalledSkillsJson(json.encodeToString(ListSerializer(DesignSkill.serializer()), skills))
    }
}
