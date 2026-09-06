package com.tapcreator.app.backend.service

/**
 * 内置分辨率知识库：按模型名关键词自动匹配该模型已知支持的合法分辨率。
 *
 * 数据来源：各家官方 API 文档（阿里云百炼、腾讯混元、智谱 BIGModel、火山/豆包 Seedream 等）。
 * 说明：标准 OpenAI 兼容 /models 不会返回分辨率，只有已知模型能可靠预填。
 *
 * 目录表驱动：规则按「先到先得」顺序匹配（越靠前越特异），新增模型只需在表头加一行，
 * 不再维护脆弱的 if 链。运行时可通过 [ResolutionCatalog.overrides] 注入远程/配置动态档位。
 */
internal object ResolutionCatalog {

    /** 规则：命中 [keywords] 任一即匹配；[extraKeywords] 非空时还须再命中其一（用于 agnes video 区分） */
    data class Rule(
        val keywords: List<String>,
        val resolutions: String,
        val extraKeywords: List<String> = emptyList(),
    )

    /** 远程/动态覆盖档位（key=模型名小写，value=逗号分隔分辨率），优先级最高 */
    @Volatile
    var overrides: Map<String, String> = emptyMap()

    private val rules: List<Rule> = listOf(
        // —— 图片模型 —— OpenAI 系（上游网关命名）
        Rule(listOf("上游图模型"), "1024x1024,1536x1024,1024x1536"),
        Rule(listOf("上游图模型-2", "上游图模型2", "上游图模型 2"), "1024x1024,512x512,256x256"),
        Rule(listOf("上游图模型-3", "上游图模型3", "上游图模型 3"), "1024x1024,1792x1024,1024x1792"),
        // wan2.7-image-pro：1K/2K/4K，默认 2K，宽高比 1:8–8:1
        Rule(listOf("wan2.7", "wanx2.7"), "1024x1024,2048x2048,4096x4096"),
        // wan2.6-t2i / wan2.5-t2i-preview：总面积 [1280x1280, 1440x1440]，宽高比 1:4–4:1
        Rule(
            listOf("wan2.6", "wan2.5", "wanx2.6", "wanx2.5", "wan2.6-t2i", "wan2.5-t2i"),
            "1280x1280,1696x960,960x1696,1472x1104,1104x1472"
        ),
        // wan2.2/2.1/2.0 旧版：宽高 ∈ [512,1440]，总面积≤1440²
        Rule(
            listOf("wan2.2", "wan2.1", "wanx2.2", "wanx2.1", "wan2.0", "wanx2.0", "wan-image", "wanx"),
            "1024x1024,1280x720,720x1280,1280x1280,1440x1440"
        ),
        // qwen-image / qwen-image-plus / qwen-image-max：官方固定预设，默认 1664x928(16:9)
        Rule(listOf("qwen-image", "qwen-img", "qwen-2-5"), "1664x928,1328x1328,1472x1104,1104x1472,928x1664"),
        // 腾讯混元 Hy-Image：宽高[512,2048] 且面积≤1024²，官方 37 组预设，取常用档
        Rule(listOf("hy-image", "hunyuan-image", "hunyuanimg", "混元"), "1024x1024,1280x720,720x1280,1152x896,896x1152"),
        // 智谱 CogView-4 / GLM-Image：宽高[512,2048] 且整除 32，最大像素 ≤ 2^21
        Rule(listOf("cogview", "glm-image", "glm-4v"), "1024x1024,1440x720,720x1440,1536x1024,1024x1536,1280x1280"),
        // 字节豆包 / 即梦 Seedream 4.0+：1K/2K/4K 或具体像素，默认 2048x2048(1:1)
        Rule(
            listOf("seedream", "doubao", "jimeng", "即梦", "豆包", "born-in-speech"),
            "1K,2K,4K,2048x2048,2560x1440,1440x2560,2304x1728,1728x2304"
        ),
        // 上游 U1.5 Lite：U1 的 2K 基准上加 4K 档位
        Rule(
            listOf("u1.5", "u1-5", "u15"),
            "4K,2K,2048x2048,2496x1664,1664x2496,2368x1760,1760x2368,2272x1824,1824x2272,2752x1536,1536x2752,2752x1184,1184x2752,3840x2160,2160x3840,4096x4096"
        ),
        // 上游 U1 / U1 Fast：信息图专用，2K 基准输出，官方 11 种宽高比（9:21–21:9）
        Rule(
            listOf("上游模型", "sense-nova", "u1-fast"),
            "2048x2048,2496x1664,1664x2496,2368x1760,1760x2368,2272x1824,1824x2272,2752x1536,1536x2752,2752x1184,1184x2752"
        ),
        // Agnes（OpenAI 兼容网关）：图像 / 视频
        Rule(listOf("agnes-image", "agnes-image-2", "agnesvideo"), "1024x1024,1536x1024,1024x1536"),
        Rule(listOf("agnes"), "480P,720P,1080P", extraKeywords = listOf("video", "v2")),
        Rule(listOf("agnes"), "1024x1024,1536x1024,1024x1536"),
        // —— 视频模型 ——
        Rule(listOf("veo"), "720P,1080P"),
        Rule(listOf("minimax", "hailuo"), "768P,2K"),
        Rule(listOf("wan2.5-t2v", "wan2.2-t2v", "wan-t2v", "wan-i2v", "wan2.5-i2v"), "720P,1080P"),
        Rule(listOf("kling", "可灵"), "720P,2K"),
        Rule(listOf("sora", "runway", "pika"), "720P,1080P"),
    )

    /** 查表：先看远程覆盖，再按规则顺序首命中；未命中返回空串（让用户在生成页手动填写） */
    fun lookup(modelName: String): String {
        val n = modelName.lowercase()
        overrides[n]?.takeIf { it.isNotBlank() }?.let { return it }
        return rules.firstOrNull { rule ->
            rule.keywords.any { n.contains(it) } &&
                (rule.extraKeywords.isEmpty() || rule.extraKeywords.any { n.contains(it) })
        }?.resolutions ?: ""
    }
}