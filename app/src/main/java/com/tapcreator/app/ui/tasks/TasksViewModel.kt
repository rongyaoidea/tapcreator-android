package com.tapcreator.app.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.TaskEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TasksViewModel @Inject constructor(
    db: AppDatabase,
    savedState: SavedStateHandle,
) : ViewModel() {

    val conversationId: String = checkNotNull(savedState["conversationId"])

    val tasks: StateFlow<List<TaskEntity>> =
        db.taskDao().observeByConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}