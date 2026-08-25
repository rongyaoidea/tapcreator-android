package com.tapcreator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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

/** 无需登录：直接以首页为起始路由（账号/认证流程已移除） */
@Composable
private fun MainNav(settings: SettingsStore) {
    TapcreatorNav(startRoute = "home")
}