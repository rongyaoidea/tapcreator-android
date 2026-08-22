package com.tapcreator.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * huashu-design 排印规范：
 *  - 中西文数字一律走 Sans，等宽数字用于数据(tabular-nums 语义)。
 *  - 标题走 Serif Display 制造字重/形式对比；正文用 Sans，基准 15-16sp。
 *  - 中文强调用字重(600)，不用 faux italic。
 *  - display 档才收字距，正文不加横排字距。
 */
private val bodySans = FontFamily.SansSerif
private val displaySerif = FontFamily.Serif

val TapcreatorTypography = Typography(
    displayLarge = TextStyle(fontFamily = displaySerif, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = displaySerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = displaySerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = displaySerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = bodySans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)