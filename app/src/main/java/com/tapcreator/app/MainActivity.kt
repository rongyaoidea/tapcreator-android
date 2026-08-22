package com.tapcreator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tapcreator.app.data.prefs.SettingsStore
import com.tapcreator.app.ui.nav.TapcreatorNav
import com.tapcreator.app.ui.theme.TapcreatorRootTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapcreatorRootTheme(settings = settings) {
                MainNav(settings = settings)
            }
        }
    }
}

/** 依据是否已有本地会话决定起始路由（未登录→认证，已登录→首页） */
@Composable
private fun MainNav(settings: SettingsStore) {
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startRoute = if (settings.token() != null) "home" else "auth"
    }
    val route = startRoute ?: return
    TapcreatorNav(startRoute = route)
}