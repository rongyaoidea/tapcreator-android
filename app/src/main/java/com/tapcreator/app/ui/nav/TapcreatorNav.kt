package com.tapcreator.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tapcreator.app.ui.chat.ChatScreen
import com.tapcreator.app.ui.home.ConversationListScreen
import com.tapcreator.app.ui.home.ConversationListViewModel
import com.tapcreator.app.ui.library.LibraryScreen
import com.tapcreator.app.ui.settings.SettingsScreen
import com.tapcreator.app.ui.tasks.TasksScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{conversationId}"
private const val ROUTE_TASKS = "tasks/{conversationId}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LIBRARY = "library"

/** 底部导航的三个固定 tab（首页 / 素材库 / 设置） */
private val TAB_ROUTES = setOf(ROUTE_HOME, ROUTE_LIBRARY, ROUTE_SETTINGS)
private val TAB_ITEMS = listOf(
    Triple(ROUTE_HOME, "首页", "home"),
    Triple(ROUTE_LIBRARY, "素材库", "library"),
    Triple(ROUTE_SETTINGS, "设置", "settings"),
)

@Composable
fun TapcreatorNav(startRoute: String) {
    val nav = rememberNavController()
    // 素材库选取模式：ChatScreen 调 onPickFromLibrary 时打开全屏 Dialog
    var libraryPickerOpen by androidx.compose.runtime.mutableStateOf(false)
    // 全屏主题底色：确保深色模式下每个页面（含未铺满背景/转场瞬间）都落在主题底色上
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            bottomBar = {
                // 底部导航栏固定在底部：仅在三个 tab 页显示；chat/tasks 等二级页面全屏
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                if (route in TAB_ROUTES) {
                    MainBottomBar(
                        current = route,
                        onSelect = { target ->
                            nav.navigate(target) {
                                // 清除堆叠：切 tab 时把之前的二级页面一并弹掉，避免返回键回到旧页
                                popUpTo(ROUTE_HOME)
                                launchSingleTop = true
                            }
                        },
                    )
                }
            },
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = startRoute,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                composable(
                    ROUTE_HOME,
                    enterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) {
                    val vm: ConversationListViewModel = hiltViewModel()
                    ConversationListScreen(
                        vm = vm,
                        onOpenChat = { id -> nav.navigate("chat/$id") },
                        onNewChat = { id -> vm.createConversation { newId -> nav.navigate("chat/$newId") } },
                        onSettings = { nav.navigate(ROUTE_SETTINGS) },
                        onLibrary = { nav.navigate(ROUTE_LIBRARY) },
                    )
                }
                composable(
                    ROUTE_CHAT,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) { entry ->
                    val conversationId = entry.arguments?.getString("conversationId").orEmpty()
                    ChatScreen(
                        onBack = { nav.popBackStack() },
                        onOpenTasks = { nav.navigate("tasks/$it") },
                        onPickFromLibrary = { libraryPickerOpen = true },
                    )
                }
                composable(
                    ROUTE_TASKS,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) {
                    TasksScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    ROUTE_SETTINGS,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) {
                    SettingsScreen()
                }
                composable(
                    ROUTE_LIBRARY,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) {
                    LibraryScreen()
                }
            }
        }

        // 素材库选取模式：全屏 Dialog 显示 LibraryScreen + 顶部完成按钮
        if (libraryPickerOpen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { libraryPickerOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { libraryPickerOpen = false }) {
                                Text("返回", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "选取素材",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { libraryPickerOpen = false }) {
                                Text("完成", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LibraryScreen()
                    }
                }
            }
        }
    }
}

/** 底部导航栏：首页 / 素材库 / 设置，固定显示在底部，当前 tab 高亮 */
@Composable
private fun MainBottomBar(
    current: String?,
    onSelect: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        TAB_ITEMS.forEach { (route, label, iconType) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { onSelect(route) },
                icon = {
                    val tint = if (current == route) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    when (iconType) {
                        "home" -> LineIconHome(tint = tint)
                        "library" -> LineIconLibrary(tint = tint)
                        "settings" -> LineIconSettings(tint = tint)
                        else -> LineIconHome(tint = tint)
                    }
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}