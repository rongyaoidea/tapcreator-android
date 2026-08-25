package com.tapcreator.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import com.tapcreator.app.data.model.MediaKind

// 形状纪律：圆润圆角，不用直角；卡片/按钮统一暖圆角
private val TapcreatorShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val LightColors = lightColorScheme(
    primary = ClaudeDim,          // #C96442 加深橙（满足对比度）
    onPrimary = Color.White,
    primaryContainer = ClaudeSoft,
    onPrimaryContainer = Claude,
    secondary = ClaudeBlue,       // #6A9BCC 蓝
    onSecondary = Color.White,
    secondaryContainer = ClaudeBlueSoft,
    onSecondaryContainer = Color(0xFF1A3A5C),
    tertiary = ClaudeGreen,       // #788C5D 绿
    onTertiary = Color.White,
    tertiaryContainer = ClaudeGreenSoft,
    onTertiaryContainer = Color(0xFF2A3A1A),
    background = Cream,           // #FAF9F5 奶油色
    onBackground = Ink,            // #141413 柔和黑
    surface = CreamElevated,       // #F4F3EE 温暖米白（卡片与背景有对比）
    onSurface = Ink,
    surfaceVariant = CanvasWhite,  // #F0EEE6 奶油画布
    onSurfaceVariant = InkMuted,
    outline = Hairline,
    outlineVariant = Hairline,
    error = StatusErr,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE89968),
    onPrimary = Dark,
    primaryContainer = ClaudeDim,
    onPrimaryContainer = Color(0xFFF5E6DE),
    secondary = ClaudeBlueDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A3A5C),
    onSecondaryContainer = Color(0xFFD0E0F0),
    tertiary = ClaudeGreenDark,
    onTertiary = Dark,
    tertiaryContainer = Color(0xFF2A3A1A),
    onTertiaryContainer = Color(0xFFD0E0C0),
    background = Dark,
    onBackground = InkDark,
    surface = DarkElevated,
    onSurface = InkDark,
    surfaceVariant = Dark,
    onSurfaceVariant = InkMutedDark,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = Color(0xFFE85A4A),
    onError = Dark,
)

@Composable
fun TapcreatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = TapcreatorShapes,
        typography = TapcreatorTypography,
        content = content,
    )
}

// ── kindColor 派生：从主题读取而非硬编码，自动适配深色模式 ──
// 通过对比 colorScheme.background 亮度推断是否深色模式，与用户手动设置的主题偏好一致
@Composable
fun kindColor(kind: MediaKind): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return kindColorStatic(kind, dark)
}

// 非 Composable 版本：用于 Canvas(drawScope) 等无法 @Composable 的场景
fun kindColorStatic(kind: MediaKind, dark: Boolean): Color = when (kind) {
    MediaKind.IMAGE -> if (dark) KindImageDark else KindImage
    MediaKind.VIDEO -> if (dark) KindVideoDark else KindVideo
    MediaKind.AUDIO -> if (dark) KindAudioDark else KindAudio
    MediaKind.TEXT -> if (dark) KindTextDark else KindText
    else -> if (dark) KindFallbackDark else KindFallback
}

// ── kindIcon 矢量图标集：统一替代 emoji，全部带语义 ──
fun kindIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.AUDIO -> Icons.Filled.MusicNote
    MediaKind.TEXT -> Icons.Filled.TextFields
    else -> Icons.Filled.Image
}
