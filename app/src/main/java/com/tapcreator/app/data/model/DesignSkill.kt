package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable

/**
 * 设计类 Skill：一段可复用的风格创作指导，Agent 调 apply_skill 后注入 generate prompt。
 *
 * 两种来源：
 *  - 内置预设（photo / poster / video 三类，随 App 发布，见 BuiltinSkills）
 *  - 用户通过 skill_creator 自建或从第三方安装
 *
 * 分类：
 *  - photo: 需提供原图的照片风格重塑（photo-revival / photo-relic / photo-abstract / travel-photo-abstraction / scenes-gathered）
 *  - poster: 快速氛围海报（gc-minimal / pixel / muted / deconstructed-duotone + 瑞士网格/孔版/新艺术/复古旅行/Y2K/粗野/弥散光晕/FrutigerAero）
 *  - video: 视频创作提示词规范（minimax-h3 官方镜头语言/时间轴写法）
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
        // —— video 类：MiniMax H3 官方视频提示词规范 ——
        DesignSkill(
            id = "minimax-h3",
            name = "MiniMax H3 视频",
            category = "video",
            description = "MiniMax H3 官方提示词规范：按时间轴写镜头语言，图生视频先锚定首帧，单条视频单一动作链",
            promptGuide = "H3 对提示词结构极敏感，按时间轴写、每句落到可见可听的具象细节，避免堆抽象形容词。" +
                "文生视频：以 [Shot 1] + 整体风格（Cinematic/live-action/2D-animated/watercolor）+ 初始构图开头，" +
                "再按时间顺序写主体动作、场景与台词；图生视频：先锚定首帧（保持主体、服装、构图一致），" +
                "再写「动作开始→连续发展→结果」。镜头语言用官方词：Push In/Pull Out、Pan Left/Right、Tilt Up/Down、" +
                "Zoom In/Out、Truck、Pedestal、Arc Shot、Tracking Shot、Static Shot、Roll、POV，" +
                "需要时加幅度与速度（with small amplitude at slow speed），写成一句自然动作。" +
                "一条 4–15 秒视频只做单一动作链，少切镜、忌快切与多动作；画面文字用引号保留原文；" +
                "另用 1-2 句写环境音与背景音乐。",
            suggestedRatios = "16:9, 9:16, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
        // —— poster 类：2024–2026 热门风格（调研自社区高下载风格库，已与现有 4 个去重）——
        DesignSkill(
            id = "swiss-bauhaus-grid",
            name = "瑞士构成网格",
            category = "poster",
            description = "瑞士国际主义/包豪斯几何网格海报，理性克制，适合展览讲座品牌主视觉",
            promptGuide = "瑞士国际主义风格海报：米白背景，红/钴蓝/黄原色几何形不对称咬合排布，细黑线分栏，" +
                "Helvetica 式粗无衬线大字左对齐，大量留白、零装饰，轻微印刷套色偏移，冷静功能性气质。",
            suggestedRatios = "3:4, 2:3, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "riso-print",
            name = "孔版印刷",
            category = "poster",
            description = "Riso 双色错位+半调颗粒的 DIY 演出海报美学，适合音乐市集文创",
            promptGuide = "Risograph 孔版印刷海报：奶油色纸底，双色高对比叠印（荧光粉+电光青或橙+墨绿），" +
                "半调网点颗粒、错位漏墨质感，拼贴式图形与手绘波浪线，粗压缩体大标题，复古 zine 气质。",
            suggestedRatios = "3:4, 2:3, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "art-nouveau-mucha",
            name = "新艺术慕夏",
            category = "poster",
            description = "慕夏式装饰新艺术海报，繁复典雅，适合香水展览婚礼节庆",
            promptGuide = "新艺术运动海报：慕夏风格，拱形装饰边框与柔和光环，女性剪影被流动藤蔓花环环绕，" +
                "平涂粉彩加金箔线条，细衬线字体沿弧线排布，繁复典雅的仪式感。",
            suggestedRatios = "2:3, 3:4",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "retro-travel-wpa",
            name = "复古旅行海报",
            category = "poster",
            description = "1950s WPA 丝网旅游海报风，明亮怀旧，适合城市自然旅行",
            promptGuide = "1950 年代复古旅行海报：WPA 丝网印刷风，限定米白+钴蓝+深红+芥末黄+森绿五色，" +
                "几何化的雪山/海岸/地标色块构成，微小人物或缆车做比例点缀，粗压缩「VISIT」式标题，纸纹颗粒，明亮度假感。",
            suggestedRatios = "2:3, 3:4, 16:9",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "y2k-liquid-chrome",
            name = "Y2K 液态金属",
            category = "poster",
            description = "千禧复古未来+液态铬字，张扬乐观，适合潮流音乐服饰 Z 世代",
            promptGuide = "Y2K 千禧风格海报：液态铬金属 3D 字体与镜面金属球，洋红-电青-紫罗兰渐变背景，" +
                "星芒、翅膀、CD 光盘与果冻质感元素，银色打底配霓虹点缀，复古未来主义的乐观张扬。",
            suggestedRatios = "1:1, 4:5, 3:4",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "brutalist-raw",
            name = "粗野主义",
            category = "poster",
            description = "反装饰、复印机质感、硬碰硬栅格排印，适合独立杂志赛事",
            promptGuide = "粗野主义海报：哑光黑+奶油白加单一警示色（橙红或荧光黄），巨型模板体压缩字与黑色信息块" +
                "硬碰硬三行栅格排布，单通道高对比半调照片带，等宽字体细节条，复印机颗粒与套印错位，无装饰的街头秩序感。",
            suggestedRatios = "2:3, 3:4, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "aura-grain-gradient",
            name = "弥散光晕",
            category = "poster",
            description = "柔化弥散渐变光斑+胶片颗粒，朦胧情绪，适合冥想专辑音乐节",
            promptGuide = "弥散光晕海报：双色至三色彩虹渐变光斑柔化弥散整版，中心主体若隐若现，全场覆盖胶片噪点颗粒，" +
                "超细衬线或小号字距字浮于光晕之上，朦胧梦幻的情绪化氛围。",
            suggestedRatios = "3:4, 1:1, 2:3",
            requiresImage = false,
            builtIn = true,
        ),
        DesignSkill(
            id = "frutiger-aero",
            name = "清透科技风",
            category = "poster",
            description = "Frutiger Aero 回潮：玻璃气泡水珠光晕，通透治愈，适合科技环保生活",
            promptGuide = "Frutiger Aero 风格海报：2000 年代末乐观科技美学，高光泽玻璃气泡、水珠、镜头光晕，" +
                "蓝天草地绿叶与清澈水波意象，通透饱和的青蓝绿配色，圆润白色无衬线字，清爽治愈的复古未来感。",
            suggestedRatios = "16:9, 3:4, 1:1",
            requiresImage = false,
            builtIn = true,
        ),
    )
}
