package com.tapcreator.app.ui.theme

import androidx.compose.ui.graphics.Color

// huashu-design 色彩纪律：一个有温度的底色 + 单一 accent 贯穿全场，拒绝多色聚类。
val Anchor = Color(0xFFA64B2A)     // rust 橙 —— 唯一强调色
val AnchorDim = Color(0xFF7C3A20)
val AnchorSoft = Color(0xFFF3E2D8)

// 暖纸底（浅色）
val Paper = Color(0xFFF7F4EE)
val PaperElevated = Color(0xFFFFFFFF)
val Ink = Color(0xFF1C1A17)
val InkMuted = Color(0xFF5A544B)  // 次要文字：纸底对比 6.8:1，保证小字号可读
val Hairline = Color(0xFFE4DED2)

// 深色：纸色反转为深底，用燃炭暖，不用纯黑
val PaperDark = Color(0xFF17150F)
val PaperElevatedDark = Color(0xFF221F18)
val InkDark = Color(0xFFE9E3D8)
val InkMutedDark = Color(0xFFB0A79A)  // 次要文字：深底对比 7.7:1
val HairlineDark = Color(0xFF332E25)

// 语义色：用于生成任务状态，仅在不与 accent 冲突时使用
val StatusOk = Color(0xFF3E6B4F)     // 墨绿
val StatusErr = Color(0xFF9C2A28)    // 深红
val StatusWarn = Color(0xFFB7791F)