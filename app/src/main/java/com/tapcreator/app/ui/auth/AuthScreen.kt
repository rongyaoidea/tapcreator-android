package com.tapcreator.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 登录/注册 —— huashu 风格：暖纸底 + 单一 rust 强调；标题走 Serif，克制留白 */
@Composable
fun AuthScreen(
    vm: AuthViewModel,
    onSignedIn: () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(vm.signedIn) {
        if (vm.signedIn) onSignedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "tapcreator",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "AI 创作工作台",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedTextField(
            value = vm.username,
            onValueChange = { vm.username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        OutlinedTextField(
            value = vm.password,
            onValueChange = { vm.password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )

        if (vm.error != null) {
            Text(
                text = vm.error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = vm::login,
            enabled = !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text(if (vm.loading) "登录中…" else "登录")
        }
        OutlinedButton(
            onClick = vm::register,
            enabled = !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text("创建账号")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = "设备内运行 · 数据本地存储",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}