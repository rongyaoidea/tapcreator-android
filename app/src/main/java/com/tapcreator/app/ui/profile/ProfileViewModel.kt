package com.tapcreator.app.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.backend.auth.AuthService
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val auth: AuthService,
) : ViewModel() {

    private val tokenFlow = MutableStateFlow<String?>(null)

    val user = tokenFlow
        .flatMapLatest { t -> flow { emit(auth.currentUser(t)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    var loggedOut by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { tokenFlow.value = settings.token() }
    }

    fun setDark(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(enabled) }
    }

    fun logout() {
        viewModelScope.launch {
            tokenFlow.value?.let { runCatching { auth.logout(it) } }
            settings.clearSession()
            loggedOut = true
        }
    }
}