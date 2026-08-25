package com.tapcreator.app.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════
// Anthropic Claude 配色 + 新野兽主义（Neo-Brutalism，不用几何）
// 设计纪律：高对比、粗线条、无渐变、直角为主。暖陶土强调色贯穿全场。
// ══════════════════════════════════════════════════════════════════

// ── Claude 品牌色 ──
val Claude = Color(0xFFC96A3E)        // Claude 陶土橙 —— 唯一强调色（加深满足 WCAG 3:1 非文本对比度）
val ClaudeDim = Color(0xFFB5552D)     // 加深变体（hover/pressed）
val ClaudeSoft = Color(0xFFF5E6DE)    // 浅色 container

// ── 浅色：奶油底（Anthropic 官网风格）──
val Cream = Color(0xFFFAF9F5)         // 奶油白底
val CreamElevated = Color(0xFFFFFFFF) // 纯白卡片
val Ink = Color(0xFF1A1A1A)          // 近黑文字
val InkMuted = Color(0xFF6B6B6B)     // 灰色次要文字
val Hairline = Color(0xFFE5E5E0)     // 发丝线分隔

// ── 深色：近黑底（Anthropic 深色风格）──
val Dark = Color(0xFF1A1A1A)          // 近黑底
val DarkElevated = Color(0xFF2A2A2A)  // 深灰卡片
val InkDark = Color(0xFFFAF9F5)      // 奶油白文字
val InkMutedDark = Color(0xFFA0A0A0) // 浅灰次要文字
val HairlineDark = Color(0xFF3A3A3A)  // 深发丝线

// ── 语义色 ──
val StatusOk = Color(0xFF5B8A5A)      // 草绿
val StatusErr = Color(0xFFC84634)     // 砖红
val StatusWarn = Color(0xFFD4A03A)    // 金黄

// ── 主题槽位补全 ──
// tertiary：Claude 陶土橙加深（与 primary 同系）
val TertiaryClaude = Color(0xFFB5552D)       // 浅色
val TertiaryClaudeDark = Color(0xFFE89968)  // 深色
val TertiaryClaudeSoft = Color(0xFFF5E6DE)  // 浅色 container
val TertiaryClaudeSoftDark = Color(0xFF3A2A20) // 深色 container

// secondaryContainer：新野兽主义风格灰棕 container
val BrutalistContainer = Color(0xFFF0EDE6)       // 浅色：暖灰底
val BrutalistContainerDark = Color(0xFF2A2A2A)   // 深色
val OnBrutalist = Color(0xFF1A1A1A)              // 浅色前景
val OnBrutalistDark = Color(0xFFFAF9F5)          // 深色前景

// ── kindColor 派生体系 ──
// 图=Claude 陶土橙、视频=赭石、音频=金黄、文本=草绿，全部带深色变体
// 沿用 Anthropic 暖色调，不引入冷色
val KindImage = Color(0xFFC96A3E)        // 图像：Claude 陶土橙（品牌主角色，加深后对比度合规）
val KindImageDark = Color(0xFFE89968)
val KindVideo = Color(0xFFC8633A)        // 视频：赭石
val KindVideoDark = Color(0xFFE8A878)
val KindAudio = Color(0xFFD4A03A)        // 音频：金黄（与 StatusWarn 同系）
val KindAudioDark = Color(0xFFE8B85A)
val KindText = Color(0xFF5B8A5A)         // 文本：草绿（与 StatusOk 同系）
val KindTextDark = Color(0xFF7BA87A)
val KindFallback = Color(0xFF8B8B80)     // 保底：暖灰
val KindFallbackDark = Color(0xFFA0A0A0)
