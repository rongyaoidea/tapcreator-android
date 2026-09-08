package com.tapcreator.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tapcreator.app.data.db.ChannelEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.ui.theme.Dimens

private val KIND_ORDER = listOf(
    MediaKind.TEXT to "文本模型",
    MediaKind.IMAGE to "生图模型",
    MediaKind.VIDEO to "视频模型",
    MediaKind.AUDIO to "音频模型",
)
private val KIND_HINT = mapOf(
    MediaKind.TEXT to "如 gpt-4o、deepseek-chat、qwen2.5",
    MediaKind.IMAGE to "如 上游图模型-3、sd-xl、上游图模型",
    MediaKind.VIDEO to "如 veo-2、MiniMax-H3、sora",
    MediaKind.AUDIO to "如 tts-1、whisper",
)

/** 设置 —— 外观（深色模式）+ 模型渠道与密钥管理，按 文本/生图/视频/音频 分类（整页可滚动）。
 *  底部导航固定 tab 页：无返回按钮，由全局底部栏切换。 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
) {
    val byKind by vm.modelsByKind.collectAsState()
    val channelList by vm.channelList.collectAsState()
    val designSkills by vm.designSkills.collectAsState()
    val designCount = designSkills.count { !it.builtIn }

    // 进入设置页即自动发现：为已配置的空分辨率模型补全调研到的档位（幂等）
    LaunchedEffect(Unit) { vm.autoFillResolutions() }

    // 子页面状态：null=入口列表，"models"=模型配置，"agent"=Agent设置，"alpine"=Alpine Linux
    var subPage by remember { mutableStateOf<String?>(null) }

    if (subPage == null) {
        // 入口列表
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }

            AppearanceSection(vm)

            vm.feedback?.let { fb ->
                Text(
                    text = fb,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (vm.feedbackOk) com.tapcreator.app.ui.theme.StatusOk else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // 子页面入口
            SettingsEntryRow(
                icon = "[M]",
                title = "模型配置",
                subtitle = "渠道、API Key、模型目录与类型",
            ) { subPage = "models" }
            SettingsEntryRow(
                icon = "[A]",
                title = "Agent 设置",
                subtitle = "风格偏好与记忆系统",
            ) { subPage = "agent" }
            SettingsEntryRow(
                icon = "[L]",
                title = "Alpine Linux",
                subtitle = "沙箱环境与已安装依赖",
            ) { subPage = "alpine" }
            SettingsEntryRow(
                icon = "[S]",
                title = "设计 Skill",
                subtitle = "内置 ${com.tapcreator.app.data.model.BuiltinSkills.presets.size} 个 + 已安装 ${designCount} 个，风格创作指导",
            ) { subPage = "skills" }
        }
    } else {
        // 子页面：带返回按钮
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = { subPage = null }) {
                    Text("返回", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = when (subPage) {
                        "models" -> "模型配置"
                        "agent" -> "Agent 设置"
                        "alpine" -> "Alpine Linux"
                        "skills" -> "设计 Skill"
                        else -> "设置"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                when (subPage) {
                    "models" -> {
                        Text(
                            text = "按媒体类型配置供应商：填 渠道 + API Key + 模型目录 后，模型才会出现在创作页可选、才能被 Agent 调用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
                        )
                        KIND_ORDER.forEach { (kind, label) ->
                            KindSection(kind, label, byKind[kind].orEmpty(), channelList, vm)
                        }
                    }
                    "agent" -> AgentPrefsSection(vm)
                    "alpine" -> AlpineSandboxSection(vm)
                    "skills" -> DesignSkillsSection(vm)
                }
            }
        }
    }
}

/** 设置入口行：emoji 图标 + 标题 + 副标题 + 点击进入子页面 */
@Composable
private fun SettingsEntryRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.PagePadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** 外观设置区：深色模式开关（自原「我的」页面迁移而来） */
@Composable
private fun AppearanceSection(vm: SettingsViewModel) {
    val dark by vm.darkTheme.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "外观",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "开启后整个应用使用深色配色",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dark, onCheckedChange = vm::setDarkTheme)
        }
    }
}

/** Alpine 沙箱状态区：显示沙箱是否就绪 + 已安装的命令 */
@Composable
private fun AlpineSandboxSection(vm: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "Alpine 沙箱",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        Text(
            text = "内置 Alpine Linux + PRoot 沙箱，Agent 的 ffmpeg 视频拼接、curl 搜索、python3 脚本在沙箱内执行。首次启动需联网安装包（约 30-60 秒）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
        // 沙箱状态
        Row(
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "沙箱状态：",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (vm.sandboxReady) "已就绪" else "未启动",
                style = MaterialTheme.typography.bodyMedium,
                color = if (vm.sandboxReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "内置命令：ffmpeg（视频拼接/转码）、curl（联网）、python3（脚本执行）、grep/jq（文本处理）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
        // 手动操作按钮
        Row(
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                enabled = !vm.sandboxBusy,
                onClick = { vm.initSandbox() },
            ) {
                Text(if (vm.sandboxBusy) "处理中…" else "手动启动")
            }
            OutlinedButton(
                enabled = !vm.sandboxBusy,
                onClick = {
                    // 确认重置
                    vm.resetSandbox()
                },
            ) {
                Text("重置沙箱", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            text = "升级后沙箱无法启动时，点「重置沙箱」删除旧 rootfs 并重新解压启动。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
    }
}

/** 搜索 API 配置区：用户可配置搜索 API 替代默认的 Bing HTML 抓取 */
@Composable
private fun SearchApiSection(vm: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "搜索 API",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        Text(
            text = "配置搜索 API 后，Agent 的 web_search 优先用此 API（更稳定）。未配置时回退到 cn.bing.com HTML 抓取。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
        var apiUrl by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = { Text("搜索 API URL") },
            placeholder = { Text("https://api.bing.microsoft.com/v7.0/search") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("输入搜索 API 密钥") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        Button(
            onClick = { vm.saveSearchApi(apiUrl.trim(), apiKey.trim()) },
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 8.dp),
        ) { Text("保存") }
    }
}

/** Agent 智能体偏好：记忆系统开关 + 个人风格偏好 */
@Composable
private fun AgentPrefsSection(vm: SettingsViewModel) {
    val mcpServers by vm.mcpServers

    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "Agent 智能体偏好",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        // 记忆系统开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Agent 记忆系统", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "开启后 Agent 跨轮次记住关键产出与偏好；关闭则不再写入/召回记忆",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.agentMemoryEnabled, onCheckedChange = vm::toggleAgentMemory)
        }

        // MCP 服务器列表
        Text(
            text = "已安装配置的 MCP 服务器",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = Dimens.PagePadding, vertical = 4.dp)
                .padding(top = 12.dp),
        )
        if (mcpServers.isEmpty()) {
            Text(
                text = "暂未配置 MCP 服务器。MCP 服务器可扩展 Agent 的工具能力（如搜索、文件操作等）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            )
        } else {
            mcpServers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "类型：${server.type}  " +
                                if (server.type == "stdio") "命令：${server.command}" else "URL：${server.url}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { checked -> vm.toggleMcpEnabled(server.name, checked) },
                    )
                    TextButton(
                        onClick = { vm.removeMcpServer(server.name) },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }

        // MCP 市场入口
        var showMarket by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showMarket = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 8.dp),
        ) { Text("从市场安装 MCP 工具") }

        if (showMarket) {
            McpMarketDialog(
                installedNames = mcpServers.map { it.name }.toSet(),
                onInstall = { entry, apiKey ->
                    vm.installMcp(entry, apiKey) { }
                },
                onDismiss = { showMarket = false },
            )
        }
    }
}

/** MCP 市场搜索+安装弹窗 */
@Composable
private fun McpMarketDialog(
    installedNames: Set<String>,
    onInstall: (com.tapcreator.app.backend.mcp.McpMarketEntry, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { com.tapcreator.app.backend.mcp.McpMarket.search(query) }
    // 待安装条目：选中后如果需要 API Key 则先弹输入框
    var pendingEntry by remember { mutableStateOf<com.tapcreator.app.backend.mcp.McpMarketEntry?>(null) }
    var apiKeyInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCP 工具市场") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索 MCP 工具…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    results.forEach { entry ->
                        val installed = entry.name in installedNames
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = entry.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = if (entry.requiresApiKey) "${entry.category} · 需 API Key" else entry.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.requiresApiKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(
                                enabled = !installed,
                                onClick = {
                                    if (entry.requiresApiKey) {
                                        pendingEntry = entry
                                        apiKeyInput = ""
                                    } else {
                                        onInstall(entry, "")
                                    }
                                },
                            ) {
                                Text(if (installed) "已安装" else "安装")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    // API Key 输入弹窗
    pendingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingEntry = null },
            title = { Text("配置 ${entry.name}") },
            text = {
                Column {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text(entry.apiKeyLabel) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = apiKeyInput.isNotBlank(),
                    onClick = {
                        onInstall(entry, apiKeyInput)
                        pendingEntry = null
                    },
                ) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEntry = null }) { Text("取消") }
            },
        )
    }
}

/** Agent 自进化学习区：低危技能自动生效，高危技能待人工审批；支持撤销与清空整个学习库 */
@Composable
private fun KindSection(kind: MediaKind, label: String, models: List<ModelInfo>, channelList: List<ChannelEntity>, vm: SettingsViewModel) {
    var showAdd by remember(kind) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )

        if (models.isEmpty()) {
            Text(
                text = "未配置 ${label} 模型，点击下方按钮新增供应商",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            )
        } else {
            models.forEach { info -> ModelCard(info, channelList, vm) }
        }

        OutlinedButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
        ) {
            Text("新增${label}供应商")
        }
    }

    if (showAdd) {
        AddKindDialog(kind, label, vm, onDismiss = { showAdd = false })
    }
}

@Composable
private fun ModelCard(info: ModelInfo, channelList: List<ChannelEntity>, vm: SettingsViewModel) {
    var showKeyDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val model = info.model
    val channel = channelList.firstOrNull { it.id == model.channelId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                Text("供应商：${info.channelName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (channel != null) {
                    Text(
                        text = "Base URL：${channel.baseUrl.ifBlank { "（未配置）" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (channel.baseUrl.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = model.enabled, onCheckedChange = { vm.toggleModelEnabled(info) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !vm.testing,
                onClick = { vm.testConnection(model.channelId) },
            ) { Text("测试连接") }
            if (channel != null) {
                TextButton(onClick = { showEditDialog = true }) { Text("编辑") }
            }
            TextButton(onClick = { showKeyDialog = true }) { Text("API Key") }
            TextButton(
                onClick = { vm.deleteModel(info) },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
        if (channel != null) {
            var refMode by remember {
                mutableStateOf(vm.modelImageRefMode(model.id, channel.baseUrl, model.name))
            }
            Text(
                text = "参考形态：${refMode.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                items(com.tapcreator.app.backend.providers.ImageRefMode.values().toList()) { mode ->
                    FilterChip(
                        selected = refMode == mode,
                        onClick = { refMode = mode; vm.setModelImageRefMode(model.id, mode) },
                        label = { Text(mode.name) },
                    )
                }
            }
            TextButton(onClick = {
                vm.clearModelImageRefMode(model.id)
                refMode = vm.modelImageRefMode(model.id, channel.baseUrl, model.name)
            }) { Text("恢复默认", style = MaterialTheme.typography.labelMedium) }
        }
    }

    if (channel != null && showEditDialog) {
        EditChannelDialog(channel, vm, onDismiss = { showEditDialog = false })
    }

    if (showKeyDialog) {
        KeyDialog(channelId = model.channelId, channelName = info.channelName, vm = vm, onDismiss = { showKeyDialog = false })
    }
}

@Composable
private fun KeyDialog(channelId: String, channelName: String, vm: SettingsViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$channelName · API Key") },
        text = {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSecret(channelId, key) { onDismiss() }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun EditChannelDialog(channel: ChannelEntity, vm: SettingsViewModel, onDismiss: () -> Unit) {
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    var baseUrl by remember(channel.id) { mutableStateOf(channel.baseUrl) }
    var models by remember(channel.id) { mutableStateOf(channel.modelCatalog) }
    var pulled by remember(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var pulling by remember(channel.id) { mutableStateOf(false) }
    var baseError by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑供应商") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("供应商名称") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; baseError = false },
                    label = { Text("Base URL（只填到 /v1，如 https://api.xxx.com/v1）") },
                    isError = baseError,
                    supportingText = if (baseError) { { Text("请填写 Base URL") } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = models, onValueChange = { models = it; modelsError = false },
                    label = { Text("模型目录（逗号分隔）") },
                    isError = modelsError,
                    supportingText = if (modelsError) { { Text("请至少填写一个模型") } } else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedButton(
                    enabled = !pulling,
                    onClick = {
                        pulling = true
                        vm.listModelsForChannel(channel.id) { ids ->
                            pulled = ids
                            pulling = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(if (pulling) "拉取中…" else "拉取模型（用已保存 Key 获取模型 ID）")
                }
                if (pulled.isNotEmpty()) {
                    Text(
                        text = "点选模型加入目录（可多选）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(pulled, key = { it }) { id ->
                            FilterChip(
                                selected = false,
                                onClick = { models = appendModel(models, id) },
                                label = { Text(id, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                Text(
                    text = "改动会作用于该供应商下的所有模型；保存后新模型名会自动出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var hasErr = false
                if (baseUrl.isBlank()) { baseError = true; hasErr = true }
                if (models.isBlank()) { modelsError = true; hasErr = true }
                if (!hasErr) vm.updateChannel(channel, name, baseUrl, models) { onDismiss() }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun appendModel(models: String, id: String): String {
    val parts = models.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
    return (parts + id).distinct().joinToString(",")
}

/** 拉取真实模型：填 Base URL + API Key 后点按钮，从供应商拉到模型 id 列表，点选加入 */
@Composable
private fun ModelPicker(
    baseUrl: String,
    apiKey: String,
    onPick: (String) -> Unit,
    vm: SettingsViewModel,
) {
    var pulled by remember { mutableStateOf<List<String>>(emptyList()) }
    var pulling by remember { mutableStateOf(false) }

    Column {
        OutlinedButton(
            enabled = baseUrl.isNotBlank() && !pulling,
            onClick = {
                pulling = true
                vm.fetchModels(baseUrl, apiKey) { ids ->
                    pulled = ids
                    pulling = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(if (pulling) "拉取中…" else "拉取模型（从供应商获取模型 ID）")
        }
        if (pulled.isNotEmpty()) {
            Text(
                text = "点选模型加入目录（可多选）：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                items(pulled, key = { it }) { id ->
                    FilterChip(
                        selected = false,
                        onClick = { onPick(id) },
                        label = { Text(id, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddKindDialog(kind: MediaKind, label: String, vm: SettingsViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var baseError by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增$label") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameError = false },
                    label = { Text("供应商名称") },
                    isError = nameError,
                    supportingText = if (nameError) { { Text("请填写供应商名称") } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; baseError = false },
                    label = { Text("Base URL（如 https://api.openai.com/v1）") },
                    isError = baseError,
                    supportingText = if (baseError) { { Text("请填写 Base URL") } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; keyError = false },
                    label = { Text("API Key（sk-…）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = keyError,
                    supportingText = if (keyError) { { Text("请填写 API Key") } } else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = models, onValueChange = { models = it; modelsError = false },
                    label = { Text("模型目录（逗号分隔，可手填或点上拉取）") },
                    isError = modelsError,
                    supportingText = if (modelsError) { { Text("请至少填写一个模型") } } else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                ModelPicker(baseUrl = baseUrl, apiKey = key, onPick = { models = appendModel(models, it) }, vm = vm)
                Text(
                    text = "示例：${KIND_HINT[kind]}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !vm.busy,
                onClick = {
                    var hasErr = false
                    if (name.isBlank()) { nameError = true; hasErr = true }
                    if (baseUrl.isBlank()) { baseError = true; hasErr = true }
                    if (key.isBlank()) { keyError = true; hasErr = true }
                    if (models.isBlank()) { modelsError = true; hasErr = true }
                    if (!hasErr) vm.addChannel(kind, name, baseUrl, models, key) { onDismiss() }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
/** 设计 Skill 列表：内置预设 + 用户安装。展示分类/来源/描述/建议比例，非内置项可删除。 */
@Composable
private fun DesignSkillsSection(vm: SettingsViewModel) {
    val skills by vm.designSkills.collectAsState()
    val categoryLabel = mapOf("photo" to "照片重塑", "poster" to "氛围海报", "video" to "视频创作")
    var showEditor by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<com.tapcreator.app.data.model.DesignSkill?>(null) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "已安装的设计 Skill（${skills.size}）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        Text(
            text = "内置预设随 App 发布、不可删除；Agent 创作时可用 apply_skill 应用某个风格。也可在对话里让 Agent 用 skill_creator 新建风格。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
        )
        OutlinedButton(
            onClick = { editingSkill = null; showEditor = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 8.dp),
        ) { Text("新建 Skill") }
        skills.forEach { skill ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.PagePadding, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "[${categoryLabel[skill.category] ?: skill.category}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (skill.builtIn) "  [内置]" else "  [自建]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val meta = buildString {
                    if (skill.suggestedRatios.isNotBlank()) append("建议比例 ${skill.suggestedRatios}")
                    if (skill.requiresImage) {
                        if (isNotEmpty()) append(" · ")
                        append("需原图")
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!skill.builtIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { editingSkill = skill; showEditor = true }) {
                            Text("编辑", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { vm.deleteDesignSkill(skill.id) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (showEditor) {
            SkillEditorDialog(
                original = editingSkill,
                onDismiss = { showEditor = false },
                onSave = { skill -> vm.saveDesignSkill(skill); showEditor = false },
            )
        }
    }
}

/** 新建/编辑用户设计 Skill 的表单弹窗。original=null 表示新建；id 由名称 slug 化，编辑时保留原 id。 */
@Composable
private fun SkillEditorDialog(
    original: com.tapcreator.app.data.model.DesignSkill?,
    onDismiss: () -> Unit,
    onSave: (com.tapcreator.app.data.model.DesignSkill) -> Unit,
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var category by remember { mutableStateOf(original?.category ?: "poster") }
    var description by remember { mutableStateOf(original?.description ?: "") }
    var promptGuide by remember { mutableStateOf(original?.promptGuide ?: "") }
    var suggestedRatios by remember { mutableStateOf(original?.suggestedRatios ?: "") }
    val derivedId = name.trim().lowercase().replace(Regex("\\s+"), "-")
    val id = if (original != null) original.id else derivedId
    val canSave = name.isNotBlank() && promptGuide.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新建设计 Skill" else "编辑设计 Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (original == null && derivedId.isNotBlank()) {
                    Text(
                        text = "生成 id：$derivedId",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("分类", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("photo" to "照片重塑", "poster" to "氛围海报", "video" to "视频创作").forEach { (k, label) ->
                        FilterChip(selected = category == k, onClick = { category = k }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, label = { Text("描述（可选）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = suggestedRatios, onValueChange = { suggestedRatios = it },
                    label = { Text("建议比例（逗号分隔，如 1:1, 16:9）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = promptGuide, onValueChange = { promptGuide = it }, label = { Text("风格指导 prompt") },
                    minLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        com.tapcreator.app.data.model.DesignSkill(
                            id = id,
                            name = name.trim(),
                            category = category,
                            promptGuide = promptGuide.trim(),
                            suggestedRatios = suggestedRatios.trim(),
                            requiresImage = category == "photo",
                            description = description.trim(),
                            builtIn = false,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
