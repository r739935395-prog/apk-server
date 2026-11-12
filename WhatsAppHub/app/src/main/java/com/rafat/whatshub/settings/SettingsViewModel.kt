package com.rafat.whatshub.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.repository.EngineHealthTelemetry
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.DiagnosticEntry
import com.rafat.whatshub.security.PortableRestoreOutcome
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val accounts: List<AccountRecord> = emptyList(),
    val themeMode: String = "amoled",
    val languageCode: String = "en",
    val fontScaleKey: String = "default",
    val notificationsEnabled: Boolean = true,
    val notificationShowPreview: Boolean = true,
    val accountNotifications: Map<Int, Boolean> = mapOf(1 to true, 2 to true, 3 to true, 4 to true),
    val debugLogsEnabled: Boolean = false,
    val mediaCacheBytes: Long = 0L,
    val engineVersion: String = "",
    val telemetry: EngineHealthTelemetry? = null,
    val recentLogs: List<DiagnosticEntry> = emptyList()
)

class SettingsViewModel(private val repository: HubRepository) : ViewModel() {

    private val _mediaBytes = MutableStateFlow(0L)
    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.accounts,
        repository.appPreferences,
        _mediaBytes,
        SanitizedLogger.entries
    ) { accounts, prefs, bytes, logs ->
        val telemetry = repository.getEngineHealthTelemetry()
        SettingsUiState(
            accounts = accounts,
            themeMode = prefs.themeMode,
            languageCode = prefs.languageCode,
            fontScaleKey = prefs.fontScaleKey,
            notificationsEnabled = prefs.notificationsEnabled,
            notificationShowPreview = prefs.notificationShowPreview,
            accountNotifications = prefs.accountNotifications,
            debugLogsEnabled = prefs.debugLogsEnabled,
            mediaCacheBytes = bytes,
            engineVersion = telemetry.engineVersion,
            telemetry = telemetry,
            recentLogs = logs.takeLast(80).reversed()
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshStorageMetrics()
    }

    fun refreshStorageMetrics() {
        viewModelScope.launch {
            _mediaBytes.value = repository.calculateMediaStorageBytes()
        }
    }

    fun selectTheme(mode: String) {
        repository.setThemeMode(mode)
    }

    fun selectLanguage(lang: String) {
        repository.setLanguageCode(lang)
    }

    fun selectFontScale(scaleKey: String) {
        repository.setFontScaleKey(scaleKey)
    }

    fun setNotifications(enabled: Boolean) {
        repository.setNotificationsEnabled(enabled)
    }

    fun setNotificationPreview(enabled: Boolean) {
        repository.setNotificationPreviewEnabled(enabled)
    }

    fun setAccountNotification(accountId: Int, enabled: Boolean) {
        repository.setAccountNotificationEnabled(accountId, enabled)
    }

    fun setDebugLogging(enabled: Boolean) {
        repository.setDebugLoggingEnabled(enabled)
    }

    fun clearMediaCache() {
        viewModelScope.launch {
            val freed = repository.clearMediaCache()
            refreshStorageMetrics()
            val mb = "%.2f".format(freed / (1024.0 * 1024.0))
            _statusToast.value = "Cleared $mb MB of cached media files."
        }
    }

    fun exportEncryptedBackupToUri(context: Context, destUri: Uri, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                val encryptedBytes = repository.exportPortableEncryptedBackup(passphrase)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(encryptedBytes)
                    } ?: error("Cannot write to selected backup file")
                }
                _statusToast.value = "Portable encrypted backup exported (${encryptedBytes.size / 1024} KB)."
            }.onFailure { err ->
                _statusToast.value = "Backup failed: ${err.message}"
            }
        }
    }

    fun restoreEncryptedBackupFromUri(context: Context, sourceUri: Uri, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                val archiveBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                        ?: error("Cannot read selected backup file")
                }
                when (val outcome = repository.restorePortableEncryptedBackup(archiveBytes, passphrase)) {
                    is PortableRestoreOutcome.RestoredV2 -> {
                        _statusToast.value =
                            "Restored ${outcome.importedChats} chats, ${outcome.importedMessages} messages, and ${outcome.importedContacts} contacts."
                    }
                    is PortableRestoreOutcome.LegacyV1KeystoreBound -> {
                        _statusToast.value =
                            "Verified legacy v1.0.0 archive (${outcome.verifiedEntries.size} file). v1.0.0 DB was hardware-Keystore-bound; re-link your accounts in Accounts tab to auto-sync history via WhatsApp HistorySync."
                    }
                }
            }.onFailure { err ->
                _statusToast.value = "Restore failed (verify passphrase): ${err.message}"
            }
        }
    }

    fun exportSanitizedLogsToUri(context: Context, destUri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = repository.exportSanitizedLogsText()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
                _statusToast.value = "Sanitized diagnostic logs exported."
            }.onFailure { err ->
                _statusToast.value = "Log export failed: ${err.message}"
            }
        }
    }

    fun clearLogs() {
        SanitizedLogger.clear()
    }

    fun consumeToast() {
        _statusToast.value = null
    }

    class Factory(private val repository: HubRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}
