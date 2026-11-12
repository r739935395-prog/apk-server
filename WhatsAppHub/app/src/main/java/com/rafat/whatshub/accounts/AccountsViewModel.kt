package com.rafat.whatshub.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.SyncStateRecord
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.repository.PairingCodeState
import com.rafat.whatshub.repository.PairingQrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountCardUiModel(
    val account: AccountRecord,
    val qrState: PairingQrState?,
    val pairingCodeState: PairingCodeState?,
    val latestSync: SyncStateRecord?
)

class AccountsViewModel(private val repository: HubRepository) : ViewModel() {

    private val _busyAccounts = MutableStateFlow<Set<Int>>(emptySet())
    val busyAccounts: StateFlow<Set<Int>> = _busyAccounts.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    val cards: StateFlow<List<AccountCardUiModel>> = combine(
        repository.accounts,
        repository.qrStates,
        repository.pairingCodes,
        repository.database.syncInvalidations
    ) { accounts, qrs, codes, _ ->
        accounts.map { acct ->
            AccountCardUiModel(
                account = acct,
                qrState = qrs[acct.accountId],
                pairingCodeState = codes[acct.accountId],
                latestSync = repository.database.getSyncStates(acct.accountId).firstOrNull()
            )
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun runSlotAction(accountId: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busyAccounts.value = _busyAccounts.value + accountId
            try {
                block()
            } finally {
                _busyAccounts.value = _busyAccounts.value - accountId
            }
        }
    }

    fun connectOrPairWithQr(accountId: Int) = runSlotAction(accountId) {
        val res = repository.connectAccount(accountId)
        if (!res.ok && res.error.isNotBlank()) {
            _feedbackMessage.value = "Account $accountId: ${res.error}"
        }
    }

    fun requestPhonePairingCode(accountId: Int, phone: String) = runSlotAction(accountId) {
        val res = repository.requestPairingCode(accountId, phone)
        if (!res.ok) {
            _feedbackMessage.value = "Account $accountId: ${res.error.ifBlank { "Pairing code request failed" }}"
        }
    }

    fun disconnect(accountId: Int) = runSlotAction(accountId) {
        repository.disconnectAccount(accountId)
    }

    fun remove(accountId: Int) = runSlotAction(accountId) {
        repository.removeAccount(accountId)
        _feedbackMessage.value = "Account $accountId removed and local credentials wiped."
    }

    fun replace(accountId: Int) = runSlotAction(accountId) {
        val res = repository.replaceAccount(accountId)
        if (!res.ok && res.error.isNotBlank()) {
            _feedbackMessage.value = "Account $accountId: ${res.error}"
        }
    }

    fun rename(accountId: Int, label: String) {
        repository.renameAccount(accountId, label)
    }

    fun consumeFeedback() {
        _feedbackMessage.value = null
    }

    class Factory(private val repository: HubRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountsViewModel(repository) as T
    }
}
