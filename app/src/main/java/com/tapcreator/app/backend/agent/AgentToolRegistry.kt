package com.tapcreator.app.backend.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent 工具注册表：集中声明 Agent 能调用的所有动作及其参数 schema。
 * 生成器据此产出「工具清单」注入 system prompt，使 Agent 直接读取工具即可知道可调用什么，
 * 无需在提示词中手工维护重复的动作说明。
 */
object AgentToolRegistry {

    data class Param(val name: String, val type: String, val desc: String, val required: Boolean = false)
    data class Tool(val name: String, val desc: String, val params: List<Param>)

    val tools: List<Tool> = listOf(
        Tool(
            name = "generate",
            desc = "调用生成工具创作文本/图片/视频/音频并落为一张卡片。参考矩阵：图像卡只能引图像；视频卡仅作视频参考；生成图像时勿引用视频卡。",
            params = listOf(
                Param("tool", "string", "GENERATE_TEXT/GENERATE_IMAGE/GENERATE_VIDEO/GENERATE_AUDIO", true),
                Param("prompt", "string", "生成内容的提示词", true),
                Param("ratio", "string", "如 1:1 / 16:9 / 9:16"),
                Param("resolution", "string", "模型输出分辨率，如图片 1024x1024，视频 1080P/2K"),
                Param("quality", "string", "high/medium/low"),
                Param("seconds", "integer", "视频总秒数"),
                Param("reference", "integer", "引用本运行内第 N 个产出（从1计）"),
                Param("reference_card", "string", "引用本会话既有卡片 id"),
                Param("reference_folder", "string", "素材库文件夹名，固定人物/产品形象"),
            ),
        ),
        Tool("list_cards", "列出本会话所有卡片（id/类型/标题/内容摘要）。", emptyList()),
        Tool(
            "read_card", "读取单张卡片完整内容。", listOf(Param("card_id", "string", "卡片 id", true)),
        ),
        Tool(
            "update_card", "修改卡片标题与文本内容。",
            listOf(
                Param("card_id", "string", "卡片 id", true),
                Param("title", "string", "新标题"),
                Param("content", "string", "新文本内容"),
            ),
        ),
        Tool(
            "delete_card", "删除卡片（引用感知：被引用则隐藏，未引用则连同媒体文件删除）。",
            listOf(Param("card_id", "string", "卡片 id", true)),
        ),
        Tool("list_assets", "列出素材库所有文件夹与素材（含素材 id 供整理）。", emptyList()),
        Tool(
            "update_asset", "修改素材的登记标题。",
            listOf(Param("asset_id", "string", "素材 id", true), Param("title", "string", "新名称", true)),
        ),
        Tool(
            "move_asset", "把素材移动到文件夹归类（文件夹不存在自动创建；只传 asset_id 表示移回未归档）。",
            listOf(
                Param("asset_id", "string", "素材 id", true),
                Param("folder_name", "string", "目标文件夹名"),
                Param("folder_id", "string", "目标文件夹 id"),
            ),
        ),
        Tool(
            "delete_asset", "删除素材（连同媒体二进制文件，正被卡片使用会拒绝）。",
            listOf(Param("asset_id", "string", "素材 id", true)),
        ),
        Tool(
            "create_folder", "新建素材文件夹。",
            listOf(
                Param("folder_name", "string", "文件夹名", true),
                Param("folder_kind", "string", "folder/role/product，默认 folder"),
            ),
        ),
        Tool(
            "delete_folder", "删除文件夹（其内素材移回未归档，不删素材文件）。",
            listOf(Param("folder_id", "string", "文件夹 id"), Param("folder_name", "string", "文件夹名")),
        ),
        Tool("web_search", "联网搜索，返回标题+链接，用于获取外部知识。",
            listOf(Param("query", "string", "搜索词", true))),
        Tool("fetch_url", "读取网页正文（仅 http/https），用于参考链接内容。",
            listOf(Param("url", "string", "网页链接", true))),
        Tool(
            "configure_resolution", "查询或设置某图像/视频模型的可选分辨率。模型未声明分辨率时用它读取内置知识库给用户参考，或用调研结果把模型的可选分辨率写入配置（逗号分隔）。",
            listOf(
                Param("model_name", "string", "模型名", true),
                Param("resolutions", "string", "要写入的可选分辨率，逗号分隔；不填则仅查询当前/内置知识库"),
            ),
        ),
        Tool(
            "configure_model", "记录某模型的「图生图参考形态」，解决参考图不生效。各网关接口差异大：agnes 用 extra_body.image、SenseNova 用 JSON edits、其余 OpenAI 兼容用 multipart edits。先用 web_search 查官方文档确认该模型的参考图接入方式，再据此设置。",
            listOf(
                Param("model_name", "string", "模型名（channel 中登记的名称）", true),
                Param("image_ref", "string", "参考形态：AGNES_EXTRA_BODY_IMAGE / SENSENOVA_EDITS_JSON / EDITS_MULTIPART，不填则仅记录 note"),
                Param("note", "string", "调研结论/备注（供用户在设置页查看）"),
            ),
        ),
        Tool("memorize", "记住一条事实供后续复用。", listOf(Param("memory", "string", "要记忆的内容", true))),
        Tool("recall", "回顾已记忆内容。", listOf(Param("memory", "string", "检索关键词"))),
        Tool("list_runs", "回顾本会话此前的 Agent 执行批次（run_id/轮数/时长），供自我复盘。", emptyList()),
        Tool("read_trace", "读取某次执行批次 run_id 的完整思考/动作/观察轨迹，用于复盘失败或复制成功经验。",
            listOf(Param("run_id", "string", "list_runs 得到的执行批次 id", true))),
        Tool("link_cards", "在两卡片间建立引用关系边（主动梳理关系链/引用）。",
            listOf(
                Param("from_card_id", "string", "源卡片 id", true),
                Param("to_card_id", "string", "目标卡片 id", true),
                Param("role", "string", "reference/parent，默认 reference"),
            )),
        Tool("unlink_cards", "解除两卡片之间的引用关系边。",
            listOf(
                Param("from_card_id", "string", "源卡片 id", true),
                Param("to_card_id", "string", "目标卡片 id", true),
                Param("role", "string", "reference/parent，默认 reference"),
            )),
        Tool("layout_canvas", "重新整理本会话所有节点在画布上的网格布局（坐标持久化）。", emptyList()),
        Tool("shell_execute", "在 Alpine 沙箱里执行任意 Linux 命令（ffmpeg/curl/python3/grep/jq 等）。用于视频处理、文件操作、安装包、运行脚本等。",
            listOf(
                Param("command", "string", "要执行的 shell 命令", true),
                Param("timeout", "integer", "超时秒数，默认 30"),
            )),
        Tool("list_skills", "列出所有可用的设计 Skill（内置预设 + 已安装的第三方 Skill），供选择风格。", emptyList()),
        Tool("apply_skill", "应用设计 Skill 到后续 generate：注入风格指导 prompt。photo 类需在 generate 时引用原图。",
            listOf(
                Param("skill_id", "string", "Skill id（list_skills 获取）", true),
            )),
        Tool("skill_creator", "创建/安装一个第三方设计 Skill。用户可自定义风格指导 prompt，安装后 Agent 可 apply_skill 使用。",
            listOf(
                Param("name", "string", "Skill 名称（英文短词，如 vintage-film）", true),
                Param("category", "string", "分类：photo（需原图）/ poster（氛围海报）/ video（视频创作提示词）", true),
                Param("prompt_guide", "string", "风格指导 prompt（apply 时注入 generate 的提示词）", true),
                Param("description", "string", "简短描述"),
                Param("suggested_ratios", "string", "建议比例，逗号分隔（如 16:9,1:1）"),
            )),
        Tool("uninstall_skill", "删除一个用户安装的第三方 Skill（内置预设不可删）。",
            listOf(Param("skill_id", "string", "要删除的 Skill id", true))),
        Tool("finish", "结束本轮并把结果汇报给用户。", listOf(Param("summary", "string", "给用户的收尾文本"))),
        Tool("run_script", "在沙箱里创建并执行一个脚本（Python/shell）。Agent 写好脚本内容，autosave 为临时文件后执行。",
            listOf(
                Param("script_content", "string", "脚本内容（Python 或 shell）", true),
                Param("language", "string", "python 或 sh，默认 sh"),
            )),
        Tool("install_package", "在 Alpine 沙箱里安装一个软件包（apk add）。安装后可在 shell_execute 中使用。",
            listOf(Param("package", "string", "包名（如 ffmpeg、curl、python3、nodejs 等）", true))),
        Tool("mcp_add_server", "注册一个 MCP 服务器。支持 stdio（子进程）和 http（远程）两种类型。注册后可用 mcp_list_tools 和 mcp_call_tool 调用其工具。",
            listOf(
                Param("name", "string", "服务器名称（唯一标识）", true),
                Param("type", "string", "服务器类型：stdio（子进程）或 http（远程）", true),
                Param("command", "string", "stdio 类型时：启动命令；http 类型时：服务器 URL"),
                Param("args", "string", "命令参数，逗号分隔（仅 stdio 类型）"),
                Param("env", "string", "环境变量，JSON 格式（如 {\"API_KEY\":\"xxx\"}）"),
            )),
        Tool("mcp_remove_server", "删除一个已注册的 MCP 服务器。",
            listOf(Param("name", "string", "服务器名称", true))),
        Tool("mcp_list_tools", "列出某 MCP 服务器上可用的工具。",
            listOf(Param("server", "string", "服务器名称", true))),
        Tool("mcp_call_tool", "调用 MCP 服务器上的工具。",
            listOf(
                Param("server", "string", "服务器名称", true),
                Param("tool", "string", "工具名称", true),
                Param("arguments", "string", "工具参数，JSON 格式（如 {\"key\":\"value\"}）"),
            )),
    )

    /**
     * 按注册表 required 标记做统一预检：返回 action 名对应工具中「缺失的必填参数名」。
     * 工具名未知时返回空（交由 AgentBrain 的「未知动作」分支处理，避免双重报错）。
     */
    fun missingRequired(name: String, providedKeys: Set<String>): List<String> {
        val tool = tools.firstOrNull { it.name == name } ?: return emptyList()
        return tool.params.filter { it.required }.map { it.name }.filter { it !in providedKeys }
    }

    /** 渲染为 JSON 数组形式的工具清单（合法 JSON），供 Agent 直接读取以了解可调用能力 */
    fun toPromptTable(): String = buildString {
        appendLine("可用工具（输出对象的 action 必须取 name 之一；字段见 parameters）：")
        appendLine("[")
        tools.forEachIndexed { i, t ->
            val params = t.params.joinToString(", ") { p ->
                val req = if (p.required) " \"required\":true," else ""
                "{\"name\":\"${esc(p.name)}\",\"type\":\"${esc(p.type)}\",$req\"desc\":\"${esc(p.desc)}\"}"
            }
            append("  {\"name\":\"${esc(t.name)}\",\"desc\":\"${esc(t.desc)}\",\"parameters\":[$params]}")
            if (i == tools.lastIndex) appendLine() else appendLine(",")
        }
        appendLine("]")
        appendLine("generate 示例：{\"action\":\"generate\",\"tool\":\"GENERATE_VIDEO\",\"prompt\":\"...\",\"seconds\":10,\"ratio\":\"16:9\",\"reference_folder\":\"主角小薇\"}")
        append("每次只做一件事；需要的字段若拿不准，先用 read_card/list_cards/list_assets 或 web_search/fetch_url 获取后再原样填。")
    }

    /** OpenAI 兼容 function-calling schema。供 ProviderGateway.agentChat 在支持的端点上走结构化工具调用；不支持时回退到 toPromptTable 的文本解析。 */
    fun toFunctionSchemas(): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", t.name)
                            put("description", t.desc)
                            put(
                                "parameters",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            t.params.forEach { p ->
                                                put(
                                                    p.name,
                                                    buildJsonObject { put("type", p.type); put("description", p.desc) },
                                                )
                                            }
                                        },
                                    )
                                    val rq = t.params.filter { it.required }.map { it.name }
                                    if (rq.isNotEmpty()) {
                                        put("required", buildJsonArray { rq.forEach { add(JsonPrimitive(it)) } })
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}