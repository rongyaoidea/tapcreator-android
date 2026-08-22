# 跨会话素材复用 设计文档

日期：2026-08-21
状态：已实现（方案 1），`compileDebugKotlin` 通过

## 目标
个人创作效率：把全局素材库的素材（图/文/音频）作为参考，跨会话回填到创作（手动模式 + Agent 模式）中，并保证相册写入等安卓权限兜底。

## 现有机制
参考当前是"卡驱动"：
- `ChatViewModel.selectedReferenceCards: List<CardEntity>`（仅本会话的卡）
- 发送时 `referencedAssetIds = selectedReferenceCards.map { it.id }`（卡 id）
- `RunService.resolveReferenceMaterial(cardIds)` 按卡读 `mediaPath` → 图片/文本 data URI
- `resolveReferenceAudioUri(cardIds)` 音频卡 → H3 `reference_audio` data URI

全局素材库 `AssetEntity.mediaPath` 与卡指向同一种本地文件，故可推广引用源。

## 推荐方案（方案 1）
1. **后端**：`RunRequest` 增加 `referencedAssetPaths: List<String>`。`RunService.resolveReferenceMaterial` 扩展为合并解析"卡引用 + 素材路径引用"（图片→data URI、文本→文本、音频→`reference_audio`）。四条生成链照常。
2. **前端**：ChatScreen 参考选择器增加"素材库"入口，与"本会话卡"并列；选中素材以路径进 `referencedAssetPaths`，显示 chip。
3. **Agent**：修掉 Agent 模式"参考不上送"问题，同样走 `referencedAssetPaths`。
4. **数据流**：选素材 → `asset.mediaPath` → `RunRequest.referencedAssetPaths` → 生成后照常落卡 + 建 reference 边。素材路径引用的边建立：用材质记录标识，若该素材本就对应某张卡，按卡建边；否则以"素材+会话"归一。

## 权限保证
- 存相册已用 MediaStore 沙盒写入：API 29+ 无需写权限。
- minSdk=24：补 `WRITE_EXTERNAL_STORAGE maxSdkVersion=28`，并对 API<29 在存相册入口做运行时授权。
- 读取本应用私有素材做参考：无需任何权限。

## 范围
- 含：手动模式引用素材、Agent 模式引用素材、遗留写权限兜底。
- 不含：站内全文搜索、模板预设、会话管理（后置功能）。