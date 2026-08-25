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
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val auth: AuthService,
    private val runs: RunService,
    private val channels: ChannelRepository,
) : ViewModel() {

    private val tokenFlow = MutableStateFlow<String?>(null)
    // 创建会话防重入：避免动画/快速连点导致一次点击创建多个空会话
    private val isCreating = java.util.concurrent.atomic.AtomicBoolean(false)

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
        viewModelScope.launch {
            // 无需登录：自动建立/复用本机匿名会话，首页会话列表直接可用
            tokenFlow.value = auth.ensureAnonymousSession()
        }
    }

    fun createConversation(onCreated: (String) -> Unit) {
        // 防重入：上一次创建尚未完成时直接忽略，杜绝动画期间/快速连点产生多个空会话
        if (!isCreating.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val t = tokenFlow.value ?: return@launch
                onCreated(runs.createConversation(t).id)
            } finally {
                isCreating.set(false)
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            // 清理孤儿引用：先删关系边，再删卡片/消息/会话
            db.cardLinkDao().deleteByConversation(id)
            db.cardDao().deleteByConversation(id)
            db.messageDao().deleteByConversation(id)
            db.conversationDao().deleteById(id)
        }
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim()
        viewModelScope.launch {
            db.conversationDao().rename(id, clean, System.currentTimeMillis())
        }
    }

    suspend fun logout() {
        tokenFlow.value?.let { runCatching { auth.logout(it) } }
        settings.clearSession()
    }
}