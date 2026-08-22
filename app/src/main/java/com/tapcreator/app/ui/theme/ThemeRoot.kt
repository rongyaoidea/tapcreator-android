package com.tapcreator.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.tapcreator.app.data.prefs.SettingsStore

/** 读取用户主题偏好并应用 TapcreatorTheme */
@Composable
fun TapcreatorRootTheme(
    settings: SettingsStore,
    content: @Composable () -> Unit,
) {
    val dark by remember(settings) { settings.darkTheme }
        .collectAsState(initial = false)
    TapcreatorTheme(darkTheme = dark, content = content)
}