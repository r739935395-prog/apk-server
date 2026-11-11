package com.rafat.whatshub.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.WhatsAppHubDatabase
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.DiagnosticEntry
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUiState(
    val languageCode: String = "en",
    val themeMode: String = "dark",
    val notificationsEnabled: Boolean = true,
    val debugLogsEnabled: Boolean = false,
    val mediaCacheBytes: Long = 0L,
    val engineVersion: String = "",
    val diagnosticLogs: List<DiagnosticEntry> = emptyList()
)

class SettingsViewModel(private val repository: HubRepository) : ViewModel() {
    private val _mediaBytes = MutableStateFlow(0L)
    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.database.changeVersion,
        _mediaBytes,
        SanitizedLogger.entries
    ) { _, bytes, logs ->
        SettingsUiState(
            languageCode = repository.getLanguageCode(),
            themeMode = repository.getThemeMode(),
            notificationsEnabled = repository.notificationsEnabled(),
            debugLogsEnabled = repository.isDebugLoggingEnabled(),
            mediaCacheBytes = bytes,
            engineVersion = repository.getEngineVersion(),
            diagnosticLogs = logs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshStorageSize()
    }

    fun refreshStorageSize() {
        viewModelScope.launch {
            _mediaBytes.value = repository.calculateMediaStorageBytes()
        }
    }

    fun selectLanguage(langCode: String) {
        repository.setLanguageCode(langCode)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
    }

    fun selectTheme(mode: String) {
        repository.setThemeMode(mode)
    }

    fun setNotifications(enabled: Boolean) {
        repository.setNotificationsEnabled(enabled)
    }

    fun setDebugLogging(enabled: Boolean) {
        repository.setDebugLoggingEnabled(enabled)
    }

    fun clearMediaCache() {
        viewModelScope.launch {
            val freed = repository.clearMediaCache()
            _mediaBytes.value = repository.calculateMediaStorageBytes()
            val mb = "%.2f".format(freed / (1024.0 * 1024.0))
            _statusToast.value = "Cleared downloaded media cache ($mb MB freed)."
        }
    }

    fun clearDiagnosticLogs() {
        SanitizedLogger.clear()
    }

    fun exportEncryptedBackupToUri(context: Context, destUri: Uri, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                val dbFile = context.getDatabasePath(WhatsAppHubDatabase.DB_FILE_NAME)
                val encryptedBytes = repository.exportEncryptedBackup(dbFile, passphrase)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(encryptedBytes)
                    } ?: error("Cannot open backup destination")
                }
                _statusToast.value = "Encrypted backup exported (${encryptedBytes.size / 1024} KB)."
            }.onFailure { err ->
                _statusToast.value = "Backup failed: ${err.message}"
            }
        }
    }

    fun restoreEncryptedBackupFromUri(context: Context, srcUri: Uri, passphrase: String) {
        viewModelScope.launch {
            runCatching {
                val archiveBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(srcUri)?.use { it.readBytes() }
                        ?: error("Cannot read selected backup file")
                }
                val staging = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}")
                val entries = repository.verifyAndExtractEncryptedBackup(archiveBytes, passphrase, staging)
                staging.deleteRecursively()
                _statusToast.value = "Backup verified & decrypted (${entries.size} encrypted items validated)."
            }.onFailure { err ->
                _statusToast.value = "Restore failed: ${err.message}"
            }
        }
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
