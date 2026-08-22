package com.tapcreator.app.data.model

import kotlinx.serialization.Serializable

/**
 * Agent Loop 动作协议：Agent 大脑每轮只输出一个这样的 JSON 对象，驱动「思考→行动→观察」。
 * action 取值：
 *  - generate     ：调用生成工具，tool ∈ GENERATE_TEXT/GENERATE_IMAGE/GENERATE_VIDEO/GENERATE_AUDIO
 *  - list_cards   ：列出本会话内的卡片（id/类型/标题/内容摘要）
 *  - read_card    ：读取单张卡片完整内容（card_id 传卡片 id）
 *  - update_card  ：修改卡片标题 title / 文本内容 content（card_id 指定）
 *  - delete_card  ：删除卡片（引用感知：被引用则隐藏，未引用则连同媒体文件删除）
 *  - list_assets  ：列出素材库文件夹与素材，供引用/整理
 *  - update_asset ：修改素材登记标题（asset_id + title）
 *  - move_asset   ：把素材移动到某文件夹（asset_id + folder_id 或 folder_name；folder_name 不存在会自动创建）
 *  - delete_asset ：删除素材（连同媒体二进制文件与登记）
 *  - create_folder：新建素材文件夹（folder_name + folder_kind∈folder/role/product）
 *  - delete_folder：删除文件夹（其内素材移出未归档，不删素材文件，folder_id 或 folder_name）
 *  - web_search   ：联网搜索（query 传搜索词），返回标题+链接列表
 *  - fetch_url    ：读取网页正文（url 传 http/https 链接），用于参考链接内容
 *  - memorize     ：记忆一条事实（memory 为内容）
 *  - recall       ：回顾已记忆内容（memory 为关键字）
 *  - finish       ：结束本轮，summary 为给用户的收尾回复
 */
@Serializable
data class AgentAction(
    val action: String = "finish",
    val tool: String? = null,
    val prompt: String? = null,
    val ratio: String? = null,
    /** generate 的视频/图像输出分辨率（如图片 1024x1024，视频 1080P/2K），透传给上游 */
    val resolution: String? = null,
    /** configure_resolution 目标模型名 */
    val model_name: String? = null,
    /** configure_resolution 要写入的可选分辨率（逗号分隔，仅当需要设置时填写） */
    val resolutions: String? = null,
    val quality: String? = null,
    val seconds: Int? = null,
    /** 引用本运行内第 N 个产出（从 1 计），用于图→视频、音频音色、风格参考 */
    val reference: Int? = null,
    /** 引用本会话任意既有卡片 id（含参考矩阵校验：图像不能引用视频卡） */
    val reference_card: String? = null,
    /** 引用素材库中的文件夹（传文件夹名），其内多视角素材用于固定人物/产品形象一致性 */
    val reference_folder: String? = null,
    /** read_card/update_card/delete_card 目标卡片 id */
    val card_id: String? = null,
    /** update_asset/delete_asset/move_asset 目标素材 id */
    val asset_id: String? = null,
    /** delete_folder/move_asset 目标文件夹 id */
    val folder_id: String? = null,
    /** create_folder 的文件夹名 / move_asset 的目标文件夹名（不存在自动建） */
    val folder_name: String? = null,
    /** create_folder 的文件夹类型：folder/role/product */
    val folder_kind: String? = null,
    /** web_search 搜索词 */
    val query: String? = null,
    /** fetch_url 要读取的网页链接 */
    val url: String? = null,
    /** update_card 的新标题 / update_asset 的新标题 */
    val title: String? = null,
    /** update_card 的新文本内容 */
    val content: String? = null,
    /** MadStory：运动强度 scale（H3 透传 motion.scale） */
    val motion: Float? = null,
    /** MadStory：CFG scale（透传 H3 cfg_scale） */
    val cfgScale: Float? = null,
    /** memorize 内容 / recall 关键字 */
    val memory: String? = null,
    /** write_skill 要沉淀的技能分类：generate/flow/reference/folder/toolfix/layout */
    val category: String? = null,
    /** write_skill 的风险分级：low/high，默认 low */
    val risk: String? = null,
    /** read_trace 目标执行批次 id（list_runs 返回） */
    val run_id: String? = null,
    /** retire_skill 目标技能 id */
    val skill_id: String? = null,
    /** link_cards/unlink_cards 源卡片 id */
    val from_card_id: String? = null,
    /** link_cards/unlink_cards 目标卡片 id */
    val to_card_id: String? = null,
    /** link_cards/unlink_cards 关系角色：reference/parent */
    val role: String? = null,
    /** finish 时给用户的收尾文本 */
    val summary: String? = null,
)