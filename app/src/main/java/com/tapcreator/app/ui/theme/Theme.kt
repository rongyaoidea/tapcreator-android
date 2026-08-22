package com.tapcreator.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme

// 形状纪律：克制 —— 少一层、少一条 border。主卡片近乎直角，仅轻微圆角承载内容。
private val TapcreatorShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

private val LightColors = lightColorScheme(
    primary = Anchor,
    onPrimary = Color.White,
    primaryContainer = AnchorSoft,
    onPrimaryContainer = AnchorDim,
    secondary = StatusOk,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperElevated,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = InkMuted,
    outline = Hairline,
    outlineVariant = Hairline,
    error = StatusErr,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD97A52),
    onPrimary = PaperDark,
    primaryContainer = AnchorDim,
    onPrimaryContainer = Color(0xFFF3E2D8),
    secondary = StatusOk,
    onSecondary = Color.White,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperElevatedDark,
    onSurface = InkDark,
    surfaceVariant = PaperDark,
    onSurfaceVariant = InkMutedDark,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = Color(0xFFC95A55),
    onError = PaperDark,
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