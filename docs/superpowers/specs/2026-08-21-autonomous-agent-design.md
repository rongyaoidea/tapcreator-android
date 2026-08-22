# 自主 Agent（v1）设计

## 目标
让用户以一行自然语言下达完整创作诉求，Agent 自动拆解为多步工具调用并执行到底（全自动），同时保留现有「手动选择模型创作」入口，二者并存可切换。

## 已确认的关键决策
1. **决策大脑**：复用现有 OpenAI 兼容文本模型渠道，无需额外配置。
2. **规划方式（方案 A）**：用系统提示词让大脑模型输出结构化 JSON 计划 `{"steps":[...]}`，不依赖上游原生 function-calling。
3. **交互**：自动 + 手动并存，ChatScreen 顶部提供「创作模式」切换。
4. **计费**：每步工具调用 = 一个独立 run，各自预扣/落卡/退款；费用为各步之和对用户透明。

## 架构
```
用户自然语言指令
      │  ChatViewModel.sendAgent()
      ▼
AgentPlanner          ← 大脑：复用文本模型；system prompt 描述工具清单 + JSON 规则
  └─返回 {"steps":[{tool, ...参数}]}
      ▼
AgentExecutor         顺序执行每步；上一步产物 id 作为下一步 reference
  └─每步调用 RunService.launch()（suspend 等待完成，天然复用生成/计费/落卡）
      ▼
每步生成一个 run/卡，自动建立 reference 边 → 关系图形成完整链路
```

## 工具定义（data/model/AgentStep.kt）
- 枚举 `AgentTool`：`GENERATE_TEXT / GENERATE_IMAGE / GENERATE_VIDEO / GENERATE_AUDIO`
- 步骤数据类字段：`tool`、`prompt`、`ratio`、`quality`、`seconds`、`reference`（引用第 n 步产物，从 1 计）

## 组件与职责
### AgentPlanner（新增 backend/agent/AgentPlanner.kt）
- 组装系统提示词：列出 4 个工具、各自入参、JSON 输出格式、`reference` 引用规则、多步顺序规则。
- 调用 ProviderGateway 文本接口取原始文本。
- 解析：剥离 ```json 围栏 → 取 `steps` 数组 → 校验字段；失败重试一次，仍失败把模型原文抛给上层。

### AgentExecutor（新增 backend/agent/AgentExecutor.kt）
- 迭代 `steps`；对第 i 步构造 `RunRequest`：
  - `kind` 由 `AgentTool` 映射（text/image/video/audio）。
  - `seconds/ratio/quality` 透传；视频段续写、音频参考由既有管线处理。
  - `referencedAssetIds` = 第 n 步产物卡 id（解析 `reference` 序号）。
- 每步调 `runService.launch(...)`（suspend 等待完成）。
- 从该 run 读产物卡 id，存为后续步骤可用引用；新建 `reference` 边已由管线在接收 reference 时自动建立。
- 任一步失败：中止剩余步骤，该步走各自退款逻辑；已完成产出保留。
- 取消：中断整个 Agent 任务（用协程 Job + Structured Concurrency）。

### ProviderGateway 变更
- 新增供规划使用的方法：以给定 system prompt + 用户文本调用文本模型并返回纯文本（可复用现有 `openAiChat` 内核，仅调整请求体 system 组装与返回取 `message.content`）。

### RunService 辅助
- 提供按 `run.id` 读取其产物卡列表的 suspend 辅助（供 AgentExecutor 取上一步卡片 id）。

### UI（ChatScreen / ChatViewModel）
- 新增「创作模式」切换：`MANUAL` / `AGENT`。
- `AGENT` 模式：隐藏模型/数量/时长/比例等偏好，仅保留自然语言输入框 + 发送；显示提示文案「Agent 将自动规划多步生成」。
- `ChatViewModel` 新增 `sendAgent()`：调 AgentPlanner → AgentExecutor；维护 Agent 执行进度（已执行步/总数）；支持取消与 RETRY。
- 执行期间多步 run/卡按序写入时间线，无需额外注释消息。

## 错误与兼容性
- 无文本模型可用 → 提示去设置配置渠道。
- JSON 解析失败 → 重试一次；仍失败返回模型原文给用户参考。
- 大脑模型 JSON 稳定性依赖提示词约束；不依赖上游 `functions` 支持，兼容任意 OpenAI 兼容文本模型。

## 范围外（本版本不做）
- Agent 规划调用的单独计费（当前规划仅耗文本 token，不计积分）。
- Agent「会话级摘要 / 多轮记忆」。
- 对生成结果的自动后处理（放大/美化/翻译）。

## 随附修复（既有 bug，一并处理）
- `graph/{conversationId}` 路由未把 `conversationId` 传给 `GraphScreen`，`GraphViewModel` 的 `checkNotNull(savedState["conversationId"])` 会崩。修：路由与 `GraphScreen` 正确传参。