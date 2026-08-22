package com.tapcreator.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tapcreator.app.ui.auth.AuthScreen
import com.tapcreator.app.ui.auth.AuthViewModel
import com.tapcreator.app.ui.chat.ChatScreen
import com.tapcreator.app.ui.home.ConversationListScreen
import com.tapcreator.app.ui.home.ConversationListViewModel
import com.tapcreator.app.ui.library.LibraryScreen
import com.tapcreator.app.ui.profile.ProfileScreen
import com.tapcreator.app.ui.settings.SettingsScreen
import com.tapcreator.app.ui.tasks.TasksScreen

private const val ROUTE_AUTH = "auth"
private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{conversationId}"
private const val ROUTE_TASKS = "tasks/{conversationId}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_PROFILE = "profile"

@Composable
fun TapcreatorNav(startRoute: String) {
    val nav = rememberNavController()
    // 全屏主题底色：确保深色模式下每个页面（含未铺满背景/转场瞬间）都落在主题底色上
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    NavHost(navController = nav, startDestination = startRoute, modifier = Modifier.fillMaxSize()) {
        composable(
            ROUTE_AUTH,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            val vm: AuthViewModel = hiltViewModel()
            AuthScreen(vm = vm, onSignedIn = {
                nav.navigate(ROUTE_HOME) { popUpTo(ROUTE_AUTH) { inclusive = true } }
            })
        }
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
                onProfile = { nav.navigate(ROUTE_PROFILE) },
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
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            ROUTE_LIBRARY,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            LibraryScreen(onBack = { nav.popBackStack() })
        }
        composable(
            ROUTE_PROFILE,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            ProfileScreen(
                onBack = { nav.popBackStack() },
                onLoggedOut = {
                    nav.navigate(ROUTE_AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
    }
}