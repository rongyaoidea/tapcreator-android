package com.tapcreator.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tapcreator.app.data.db.ChannelEntity
import com.tapcreator.app.data.db.AgentSkillEntity
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
    MediaKind.IMAGE to "如 dall-e-3、sd-xl、flux",
    MediaKind.VIDEO to "如 veo-2、MiniMax-H3、sora",
    MediaKind.AUDIO to "如 tts-1、whisper",
)

/** 设置 —— 模型渠道与密钥管理，按 文本/生图/视频/音频 分类（整页可滚动） */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val byKind by vm.modelsByKind.collectAsState()
    val channelList by vm.channelList.collectAsState()

    // 进入设置页即自动发现：为已配置的空分辨率模型补全调研到的档位（幂等）
    LaunchedEffect(Unit) { vm.autoFillResolutions() }

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
            TextButton(onClick = onBack) { Text("←") }
            Text("设置 · 模型渠道", style = MaterialTheme.typography.titleMedium)
        }

        vm.feedback?.let { fb ->
            Text(
                text = fb,
                style = MaterialTheme.typography.labelMedium,
                color = if (vm.feedbackOk) com.tapcreator.app.ui.theme.StatusOk else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            )
        }

        Text(
            text = "按媒体类型配置供应商：填 渠道 + API Key + 模型目录 后，模型才会出现在创作页可选、才能被 Agent 调用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )

        KIND_ORDER.forEach { (kind, label) ->
            KindSection(kind, label, byKind[kind].orEmpty(), channelList, vm)
        }

        AgentPrefsSection(vm)

        SkillLearnSection(vm)
    }
}

/** Agent 智能体偏好：记忆系统开关 + 个人风格偏好 */
@Composable
private fun AgentPrefsSection(vm: SettingsViewModel) {
    var styleDraft by remember { mutableStateOf(vm.agentStyle) }
    var saved by remember { mutableStateOf(false) }

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
        // 个人风格偏好
        Text(
            text = "个人风格偏好",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = Dimens.PagePadding, vertical = 4.dp)
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = styleDraft,
            onValueChange = { styleDraft = it; saved = false },
            label = { Text("用自然语言描述你偏好的创作风格…") },
            placeholder = { Text("如：画面统一为暖色调胶片感，人物中近景，情绪克制，叙事留白多一些") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { vm.saveAgentStyle(styleDraft); saved = true }) {
                Text("保存风格偏好")
            }
            if (saved) {
                Text(
                    text = "已保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = com.tapcreator.app.ui.theme.StatusOk,
                )
            }
        }
        Text(
            text = "该偏好会注入 Agent 的创作系统提示，影响其生成图片/视频的基调。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )
    }
}

/** Agent 自进化学习区：低危技能自动生效，高危技能待人工审批；支持撤销与清空整个学习库 */
@Composable
private fun SkillLearnSection(vm: SettingsViewModel) {
    val pendingSkills by vm.pendingSkills.collectAsState()
    val activeSkills by vm.activeSkills.collectAsState()
    var confirmClearAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = "Agent 自进化学习",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding),
        )
        Text(
            text = "Agent 复盘后会把可复用经验沉淀为技能：低危技能自动生效并注入后续创作；高危技能（涉及删除/改写）需你审批后才启用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
        )

        // 高危待审批
        if (pendingSkills.isEmpty()) {
            Text(
                text = "暂无待审批的高危技能",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 6.dp),
            )
        } else {
            Text(
                text = "待审批（${pendingSkills.size} 条高危）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
            )
            pendingSkills.forEach { skill -> SkillRow(skill, pending = true, vm) }
        }

        // 已生效
        Text(
            text = if (activeSkills.isEmpty()) "暂无已生效技能"
            else "已生效技能（${activeSkills.size} 条，自动注入 Agent）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Dimens.PagePadding, vertical = 4.dp)
                .padding(top = 10.dp),
        )
        activeSkills.forEach { skill -> SkillRow(skill, pending = false, vm) }

        // 清空学习库
        if (activeSkills.isNotEmpty() || pendingSkills.isNotEmpty()) {
            OutlinedButton(
                onClick = { confirmClearAll = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.PagePadding, vertical = 8.dp),
            ) {
                Text("一键清空学习库", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空学习库？") },
            text = { Text("将撤销全部已学技能（含待审批与已生效），此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.clearSkills(); confirmClearAll = false }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SkillRow(skill: AgentSkillEntity, pending: Boolean, vm: SettingsViewModel) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PagePadding, vertical = 4.dp),
    ) {
        Text(
            text = "[${skill.category} / ${vm.riskLabel(skill.risk)}] ${skill.content}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pending) {
                TextButton(onClick = { vm.approveSkill(skill.id) }) { Text("批准生效") }
                TextButton(onClick = { vm.rejectSkill(skill.id) }) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirmClear = true }) {
                    Text("撤销", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmClear && !pending) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("撤销该技能？") },
            text = { Text("撤销后将不再注入 Agent 上下文。") },
            confirmButton = {
                TextButton(onClick = { vm.revokeSkill(skill.id); confirmClear = false }) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

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
            Text("＋ 新增${label}供应商")
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