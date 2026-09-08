package com.tapcreator.app.backend.skill

import com.tapcreator.app.data.model.DesignSkill

/**
 * 设计 Skill 目录的纯文本渲染：注入 Agent 上下文，使大脑在生成前就知道有哪些风格可直接
 * apply_skill，免去先调 list_skills 的一次往返。纯函数、无 Android/网络依赖，可单测。
 *
 * 输出刻意紧凑（id｜名称（标签）：描述），标签用「需原图」优先（photo 类最需要注意的前置约束），
 * 否则显示分类。无 skill 时返回空串（调用方据此跳过）。
 */
object SkillCatalog {

    /** 上限：防用户安装过多 skill 撑爆上下文。超出部分提示用 list_skills 查看全部。 */
    const val MAX_ROWS = 60

    fun render(skills: List<DesignSkill>): String {
        if (skills.isEmpty()) return ""
        val rows = skills.take(MAX_ROWS)
        return buildString {
            appendLine(
                "可用设计 Skill（在 generate 前用 apply_skill 传 id 应用风格；" +
                    "标「需原图」的须在 generate 时用 reference/reference_card 引用底图）：",
            )
            rows.forEach { s ->
                val tag = if (s.requiresImage) "需原图" else s.category
                val desc = if (s.description.isBlank()) s.name else s.description
                appendLine("  - ${s.id}｜${s.name}（$tag）：$desc")
            }
            if (skills.size > rows.size) {
                appendLine("  …（另有 ${skills.size - rows.size} 个未列，用 list_skills 查看全部）")
            }
        }
    }
}