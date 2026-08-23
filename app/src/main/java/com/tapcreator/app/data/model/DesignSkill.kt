package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable

/**
 * 设计类 Skill：一段可复用的风格创作指导，Agent 调 apply_skill 后注入 generate prompt。
 *
 * 两种来源：
 *  - 内置预设（photo-revival / gc-minimal 等 9 个，随 App 发布）
 *  - 用户通过 skill_creator 自建或从第三方安装
 *
 * 分类：
 *  - photo: 需提供原图的照片风格重塑（photo-revival / photo-relic / photo-abstract / travel-photo-abstraction / scenes-gathered）
 *  - poster: 快速氛围海报（gc-minimal / pixel / muted / deconstructed-duotone）
 */
@Serializable
data class DesignSkill(
    val id: String,
    val name: String,
    /** photo = 需提供原图；poster = 氛围海报（一句话即可） */
    val category: String,
    /** 风格指导 prompt：apply_skill 时追加到 generate 的 prompt 前 */
    val promptGuide: String,
    /** 适用比例建议（如 "16:9, 1:1"），空 = 不限 */
    val suggestedRatios: String = "",
    /** 是否需要原图参考（category=photo 时 true） */
    val requiresImage: Boolean = false,
    /** 简短描述，供 list_skills 展示 */
    val description: String = "",
    /** 是否内置预设（true = 不可删除，false = 用户可删） */
    val builtIn: Boolean = false,
)

/**
 * Skill 系统的内置预设。
 *
 * 灵感来自设计社区的设计风格 Skill：用户定需求 → 选 Skill → 处理比例 → 挑原图 → 出图。
 * photo 类需提供原图（主体清楚+关系清楚），poster 类一句话即可。
 */
object BuiltinSkills {
    val presets: List<DesignSkill> = listOf(
        // —— photo 类：需提供原图 ——
        DesignSkill(
            id = "photo-revival",
            name = "photo-revival",
            category = "photo",
            description = "照片重塑：在保留原图构图与主体的基础上，以更具表现力的笔触重绘，常有惊喜",
            promptGuide = "以原图的构图和主体关系为骨架，用富有表现力的绘画笔触重塑画面。" +
                "保留主体姿态与空间关系，但允许色彩、光影、质感做艺术化增强，产出一张有绘画感的照片级作品。",
            suggestedRatios = "3:4, 4:3",
            requiresImage = true,
            builtIn = true,
        ),
        DesignSkill(
            id = "photo-relic",
            name = "photo-relic",
            category = "photo",
            description = "照片遗存：高度忠于原图，基本指哪打哪，仅做质感与色调的精致化处理",
            promptGuide = "严格忠于原图的构图、主体、色彩与光影关系，仅做质感提升与色调精修。" +
                "不改变画面内容，只让整体更精致、更具质感，如同一张经过专业修图的高品质照片。",
            suggestedRatios = "3:4, 4:3, 1:1",
            requiresImage = true,
            builtIn = true,
        ),
        DesignSkill(
            id = "photo-abstract",
            name = "photo-abstract",
            category = "photo",
            description = "照片抽象：以原图为基底做风格化抽象，可控性高",
            promptGuide = "以原图的主体与构图为基础，做风格化抽象处理。提炼核心视觉元素（轮廓、色块、光影方向），" +
                "用简洁而有力的抽象艺术语言重新表达，保留可辨识的原画骨架。",
            suggestedRatios = "1:1, 16:9",
            requiresImage = true,
            builtIn = true,
        ),
        DesignSkill(
            id = "travel-photo-abstraction",
            name = "travel-photo-abstraction",
            category = "photo",
            description = "旅行照片抽象：专为旅行/街景照片设计，保留场景记忆但艺术化",
            promptGuide = "这是一张旅行照片。在保留场景记忆（地标、环境特征）的前提下做艺术化抽象。" +
                "强调旅行的氛围与情绪，色彩可做风格化调整，但场景须可辨识。",
            suggestedRatios = "16:9, 3:4",
            requiresImage = true,
            builtIn = true,
        ),
        DesignSkill(
            id = "scenes-gathered",
            name = "scenes-gathered",
            category = "photo",
            description = "场景聚合：以原图元素为素材重组画面，随机性强常有惊喜",
            promptGuide = "从原图中提取关键元素（人物、物体、场景碎片），以全新的构图重新聚合。" +
                "保留原图的视觉DNA但打破原有布局，产出一张有惊喜感的新画面。",
            suggestedRatios = "1:1, 16:9",
            requiresImage = true,
            builtIn = true,
        ),
        // —— poster 类：快速氛围海报 ——
        DesignSkill(
            id = "gc-minimal",
            name = "gc-minimal",
            category = "poster",
            description = "极简海报：一句话即可，大量留白+核心元素，极致克制",
            promptGuide = "极简主义风格海报：大量留白，仅保留一个核心视觉元素，配色克制（不超过3色），" +
                "构图遵循网格对齐，字体无衬线细体，整体气质安静、高级。",
            suggestedRatios = "1:1, 4:5",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "pixel",
            name = "pixel",
            category = "poster",
            description = "像素风海报：复古像素艺术，适合游戏/科技主题",
            promptGuide = "像素艺术风格：低分辨率像素画，有限调色板（8-16色），锐利边缘无抗锯齿。" +
                "复古游戏机审美，色彩饱和度高，适合科技/游戏/怀旧主题。",
            suggestedRatios = "1:1, 16:9",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "muted",
            name = "muted",
            category = "poster",
            description = "低饱和海报：莫兰迪色系，柔和高级感",
            promptGuide = "低饱和度柔和色调海报：莫兰迪色系（灰粉、灰绿、灰蓝），高灰度低饱和，" +
                "画面柔和安静，有高级感和文艺气质，适合生活方式/品牌主题。",
            suggestedRatios = "1:1, 3:4",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "deconstructed-duotone",
            name = "deconstructed-duotone",
            category = "poster",
            description = "解构双色海报：双色调+解构排版，冲击力强",
            promptGuide = "解构主义双色海报：仅用两个对比色（如荧光绿+深黑、橙红+钴蓝），" +
                "排版打破网格做错位/重叠/切割，字体粗体变形，视觉冲击力强，适合音乐/运动/前卫主题。",
            suggestedRatios = "16:9, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
    )
}
