package com.tapcreator.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.backend.auth.AuthService
import com.tapcreator.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthService,
    private val settings: SettingsStore,
) : ViewModel() {

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(false)

    fun login() = submit(isRegister = false)
    fun register() = submit(isRegister = true)

    private fun submit(isRegister: Boolean) {
        error = null
        loading = true
        viewModelScope.launch {
            runCatching {
                val session = if (isRegister) {
                    auth.register(username, password)
                } else {
                    auth.login(username, password)
                }
                settings.setLastUsername(username.trim())
                settings.saveSession(session.token, session.userId)
                signedIn = true
            }.onFailure { e ->
                error = e.message ?: "操作失败"
            }
            loading = false
        }
    }
}