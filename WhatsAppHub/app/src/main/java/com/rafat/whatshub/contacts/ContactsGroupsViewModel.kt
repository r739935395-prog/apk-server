package com.rafat.whatshub.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.ContactRecord
import com.rafat.whatshub.database.GroupRecord
import com.rafat.whatshub.repository.HubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DirectoryUiState(
    val accounts: List<AccountRecord> = emptyList(),
    val selectedAccountFilter: Int? = null,
    val searchQuery: String = "",
    val contacts: List<ContactRecord> = emptyList(),
    val groups: List<GroupRecord> = emptyList()
)

@OptIn(FlowPreview::class)
class ContactsGroupsViewModel(private val repository: HubRepository) : ViewModel() {
    private val _selectedAccount = MutableStateFlow<Int?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val uiState: StateFlow<DirectoryUiState> = combine(
        repository.accounts,
        repository.database.contactsInvalidations.debounce(40L),
        repository.database.groupsInvalidations.debounce(40L),
        _selectedAccount,
        _searchQuery
    ) { accounts, _, _, acctFilter, query ->
        DirectoryUiState(
            accounts = accounts,
            selectedAccountFilter = acctFilter,
            searchQuery = query,
            contacts = repository.database.getContacts(acctFilter, query),
            groups = repository.database.getGroups(acctFilter, query)
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DirectoryUiState())

    fun selectAccount(accountId: Int?) {
        _selectedAccount.value = if (_selectedAccount.value == accountId) null else accountId
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun syncConnectedAccounts() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                for (acct in repository.database.getAccounts()) {
                    if (acct.status == AccountStatus.CONNECTED) {
                        repository.refreshContactsAndGroups(acct.accountId)
                    }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun openContactConversation(contact: ContactRecord): Pair<Int, String> {
        val targetJid = contact.pnJid.ifBlank { contact.contactJid }
        val canonical = repository.startNewConversation(contact.accountId, targetJid, contact.displayName)
        return contact.accountId to canonical
    }

    fun openGroupConversation(group: GroupRecord): Pair<Int, String> {
        val canonical = repository.database.ensureChatExists(group.accountId, group.groupJid, group.name, isGroup = true)
        return group.accountId to canonical
    }

    class Factory(private val repository: HubRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ContactsGroupsViewModel(repository) as T
    }
}
