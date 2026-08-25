package com.tapcreator.app.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════
// Anthropic Claude 完善主题色
// 暖中性色背景 + 少而精的三色强调系统
// ══════════════════════════════════════════════════════════════════

// ── 强调色（三色系统：少而精）──
val Claude = Color(0xFFD97757)        // Primary 橙色：品牌主色，用于主要操作和 Logo
val ClaudeDim = Color(0xFFC96442)     // 加深变体（hover/pressed，满足 WCAG 对比度）
val ClaudeSoft = Color(0xFFF5E6DE)    // 浅色 container

val ClaudeBlue = Color(0xFF6A9BCC)    // Secondary 蓝色：次要强调
val ClaudeBlueDark = Color(0xFF8BB8E0)
val ClaudeBlueSoft = Color(0xFFE8F0F7)

val ClaudeGreen = Color(0xFF788C5D)   // Tertiary 绿色：第三强调
val ClaudeGreenDark = Color(0xFF9AAE7A)
val ClaudeGreenSoft = Color(0xFFEFF2E8)

// ── 暖中性色背景（浅色）──
val Cream = Color(0xFFFAF9F5)         // 浅色背景：奶油色
val CreamElevated = Color(0xFFF4F3EE) // 温暖米白：卡片/raised surface
val CanvasWhite = Color(0xFFF0EEE6)   // 奶油画布：次级容器
val Ink = Color(0xFF141413)           // 深色文本：柔和黑
val InkMuted = Color(0xFF6B6B6B)      // 灰色次要文字
val Hairline = Color(0xFFE5E5E0)      // 发丝线分隔

// ── 暖中性色背景（深色）──
val Dark = Color(0xFF1A1A1A)          // 近黑底
val DarkElevated = Color(0xFF2A2A2A)  // 深灰卡片
val InkDark = Color(0xFFFAF9F5)      // 奶油白文字
val InkMutedDark = Color(0xFFA0A0A0) // 浅灰次要文字
val HairlineDark = Color(0xFF3A3A3A)  // 深发丝线

// ── 语义色 ──
val StatusOk = Color(0xFF788C5D)      // 草绿（与 Tertiary 同系）
val StatusErr = Color(0xFFC84634)     // 砖红
val StatusWarn = Color(0xFFD4A03A)    // 金黄

// ── 主题槽位补全 ──
val TertiaryClaude = Color(0xFFC96442)       // 浅色
val TertiaryClaudeDark = Color(0xFFE89968)  // 深色
val TertiaryClaudeSoft = Color(0xFFF5E6DE)  // 浅色 container
val TertiaryClaudeSoftDark = Color(0xFF3A2A20) // 深色 container

val BrutalistContainer = Color(0xFFF0EEE6)       // 浅色：奶油画布
val BrutalistContainerDark = Color(0xFF2A2A2A)   // 深色
val OnBrutalist = Color(0xFF141413)              // 浅色前景
val OnBrutalistDark = Color(0xFFFAF9F5)          // 深色前景

// ── kindColor 派生体系 ──
// 图=橙色系、视频=蓝色系、音频=金黄系、文本=绿色系，全部带深色变体
val KindImage = Color(0xFFD97757)        // 图像：Claude 橙
val KindImageDark = Color(0xFFE89968)
val KindVideo = Color(0xFF6A9BCC)        // 视频：Claude 蓝
val KindVideoDark = Color(0xFF8BB8E0)
val KindAudio = Color(0xFFD4A03A)        // 音频：金黄（与 StatusWarn 同系）
val KindAudioDark = Color(0xFFE8B85A)
val KindText = Color(0xFF788C5D)         // 文本：Claude 绿（与 StatusOk 同系）
val KindTextDark = Color(0xFF9AAE7A)
val KindFallback = Color(0xFF8B8B80)     // 保底：暖灰
val KindFallbackDark = Color(0xFFA0A0A0)
