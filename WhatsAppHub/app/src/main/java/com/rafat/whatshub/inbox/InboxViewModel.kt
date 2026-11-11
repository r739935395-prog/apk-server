package com.rafat.whatshub.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.ChatSummaryRecord
import com.rafat.whatshub.repository.HubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class InboxFilterState(
    val selectedAccountId: Int? = null,
    val unreadOnly: Boolean = false,
    val chatType: String = "all", // "all", "groups", "direct"
    val searchQuery: String = ""
)

data class InboxUiState(
    val accounts: List<AccountRecord> = emptyList(),
    val conversations: List<ChatSummaryRecord> = emptyList(),
    val filters: InboxFilterState = InboxFilterState()
)

class InboxViewModel(private val repository: HubRepository) : ViewModel() {
    private val _filters = MutableStateFlow(InboxFilterState())

    val uiState: StateFlow<InboxUiState> = combine(
        repository.accounts,
        repository.database.changeVersion,
        _filters
    ) { accounts, _, filters ->
        val chats = repository.database.getUnifiedInboxChats(
            accountFilter = filters.selectedAccountId,
            unreadOnly = filters.unreadOnly,
            chatTypeFilter = filters.chatType,
            searchQuery = filters.searchQuery
        )
        InboxUiState(
            accounts = accounts,
            conversations = chats,
            filters = filters
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun selectAccountFilter(accountId: Int?) {
        _filters.value = _filters.value.copy(
            selectedAccountId = if (_filters.value.selectedAccountId == accountId) null else accountId
        )
    }

    fun toggleUnreadOnly() {
        _filters.value = _filters.value.copy(unreadOnly = !_filters.value.unreadOnly)
    }

    fun setChatType(type: String) {
        _filters.value = _filters.value.copy(chatType = type)
    }

    fun setSearchQuery(query: String) {
        _filters.value = _filters.value.copy(searchQuery = query)
    }

    fun startNewChat(accountId: Int, phoneOrJid: String, name: String): String =
        repository.startNewConversation(accountId, phoneOrJid, name)

    class Factory(private val repository: HubRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InboxViewModel(repository) as T
    }
}
