package com.tapcreator.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tapcreator.app.ui.theme.Dimens

/** 我的 —— 个人资料、主题与登出（M5） */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: ProfileViewModel = hiltViewModel(),
) {
    val user by vm.user.collectAsState()

    if (vm.loggedOut) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onLoggedOut() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 6.dp),
    ) {
        androidx.compose.material3.Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("我的", style = MaterialTheme.typography.titleMedium)
                }
            },
        ) { pad ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.PagePadding),
            ) {
                item {
                    Text(
                        text = user?.username ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                        var darkVal by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(false)
                        }
                        Switch(
                            checked = darkVal,
                            onCheckedChange = { on ->
                                darkVal = on
                                vm.setDark(on)
                            },
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = vm::logout,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}