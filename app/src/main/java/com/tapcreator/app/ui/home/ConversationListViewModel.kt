package com.tapcreator.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.backend.auth.AuthService
import com.tapcreator.app.backend.service.ChannelRepository
import com.tapcreator.app.backend.service.RunService
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.ConversationEntity
import com.tapcreator.app.data.db.UserEntity
import com.tapcreator.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    db: AppDatabase,
    private val settings: SettingsStore,
    private val auth: AuthService,
    private val runs: RunService,
    private val channels: ChannelRepository,
) : ViewModel() {

    private val tokenFlow = MutableStateFlow<String?>(null)

    /** 是否尚无可用的 API Key（没有任何渠道已配置密钥）→ 首页据此引导去设置页 */
    val needsSetup: StateFlow<Boolean> =
        db.channelDao().observeAll()
            .flatMapLatest { list ->
                flow {
                    val any = list.any { c -> channels.secrets(c.id).apiKey.isNotBlank() }
                    emit(!any)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val user = tokenFlow
        .flatMapLatest { t -> flow { emit(auth.currentUser(t)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val conversations: StateFlow<List<ConversationEntity>> =
        user.flatMapLatest { u ->
            db.conversationDao().observeByUser(u?.id ?: "nobody")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { tokenFlow.value = settings.token() }
    }

    fun createConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val t = tokenFlow.value ?: return@launch
            onCreated(runs.createConversation(t).id)
        }
    }

    suspend fun logout() {
        tokenFlow.value?.let { runCatching { auth.logout(it) } }
        settings.clearSession()
    }
}