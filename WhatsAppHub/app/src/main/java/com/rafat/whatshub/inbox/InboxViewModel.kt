package com.rafat.whatshub.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.ChatSummaryRecord
import com.rafat.whatshub.database.GlobalMessageSearchHit
import com.rafat.whatshub.repository.HubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InboxFilterState(
    val selectedAccountId: Int? = null,
    val unreadOnly: Boolean = false,
    val showArchived: Boolean = false,
    val chatType: String = "all",
    val searchQuery: String = ""
)

data class InboxUiState(
    val accounts: List<AccountRecord> = emptyList(),
    val conversations: List<ChatSummaryRecord> = emptyList(),
    val messageHits: List<GlobalMessageSearchHit> = emptyList(),
    val archivedCount: Int = 0,
    val filters: InboxFilterState = InboxFilterState(),
    val totalUnread: Int = 0
)

@OptIn(FlowPreview::class)
class InboxViewModel(private val repository: HubRepository) : ViewModel() {

    private val _filters = MutableStateFlow(InboxFilterState())

    val uiState: StateFlow<InboxUiState> = combine(
        repository.accounts,
        repository.database.inboxInvalidations.debounce(35L),
        _filters
    ) { accounts, _, filters ->
        val effectiveChatType = if (filters.showArchived) "archived" else filters.chatType
        val chats = repository.database.getUnifiedInboxChats(
            accountFilter = filters.selectedAccountId,
            unreadOnly = filters.unreadOnly,
            chatTypeFilter = effectiveChatType,
            searchQuery = filters.searchQuery,
            includeArchived = filters.showArchived
        )
        val archivedCount = repository.database.getArchivedChatCount(filters.selectedAccountId)
        val msgHits = if (filters.searchQuery.trim().length >= 2) {
            repository.database.searchMessagesGlobal(
                query = filters.searchQuery,
                accountFilter = filters.selectedAccountId,
                limit = 40
            )
        } else {
            emptyList()
        }
        val unreadSum = chats.sumOf { it.effectiveUnreadCount }
        InboxUiState(
            accounts = accounts,
            conversations = chats,
            messageHits = msgHits,
            archivedCount = archivedCount,
            filters = filters,
            totalUnread = unreadSum
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun selectAccountFilter(accountId: Int?) {
        _filters.update { current ->
            current.copy(selectedAccountId = if (current.selectedAccountId == accountId) null else accountId)
        }
    }

    fun toggleUnreadOnly() {
        _filters.update { it.copy(unreadOnly = !it.unreadOnly) }
    }

    fun toggleArchivedView() {
        _filters.update { it.copy(showArchived = !it.showArchived) }
    }

    fun setChatType(type: String) {
        _filters.update { it.copy(chatType = type) }
    }

    fun setSearchQuery(query: String) {
        _filters.update { it.copy(searchQuery = query) }
    }

    fun togglePin(chat: ChatSummaryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.database.setChatPinned(chat.accountId, chat.chatJid, !chat.isPinned)
        }
    }

    fun toggleArchive(chat: ChatSummaryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.database.setChatArchived(chat.accountId, chat.chatJid, !chat.isArchived)
        }
    }

    fun toggleMute(chat: ChatSummaryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.database.setChatMuted(chat.accountId, chat.chatJid, !chat.isMuted)
        }
    }

    fun toggleUnreadState(chat: ChatSummaryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            if (chat.effectiveUnreadCount > 0) {
                repository.openAndMarkConversationRead(chat.accountId, chat.chatJid)
            } else {
                repository.database.setChatMarkedUnread(chat.accountId, chat.chatJid, true)
            }
        }
    }

    fun startNewChat(accountId: Int, rawPhoneOrJid: String, displayName: String): String {
        return repository.startNewConversation(accountId, rawPhoneOrJid, displayName)
    }

    class Factory(private val repository: HubRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InboxViewModel(repository) as T
    }
}
