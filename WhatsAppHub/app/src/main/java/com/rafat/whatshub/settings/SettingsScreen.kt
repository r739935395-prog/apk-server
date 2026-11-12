package com.rafat.whatshub.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.BuildConfig
import com.rafat.whatshub.R
import com.rafat.whatshub.ui.components.AccountBadge
import com.rafat.whatshub.ui.components.RafatHubMonogramMark
import com.rafat.whatshub.ui.theme.BrandWarmGold

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToAccounts: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val toast by viewModel.statusToast.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var passphraseMode by rememberSaveable { mutableStateOf<String?>(null) } // "export" | "restore"
    var pendingPassphrase by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingPassphrase.length >= 8) {
            viewModel.exportEncryptedBackupToUri(context, uri, pendingPassphrase)
            pendingPassphrase = ""
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && pendingPassphrase.length >= 8) {
            viewModel.restoreEncryptedBackupFromUri(context, uri, pendingPassphrase)
            pendingPassphrase = ""
        }
    }

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            viewModel.exportSanitizedLogsToUri(context, uri)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Language (Arabic RTL / English LTR)
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_language)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = !state.languageCode.startsWith("ar"),
                            onClick = { viewModel.selectLanguage("en") },
                            label = { Text(stringResource(R.string.lang_english)) }
                        )
                        FilterChip(
                            selected = state.languageCode.startsWith("ar"),
                            onClick = { viewModel.selectLanguage("ar") },
                            label = { Text(stringResource(R.string.lang_arabic)) }
                        )
                    }
                }
            }

            // Theme & Font Scale
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_theme)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.themeMode == "amoled",
                            onClick = { viewModel.selectTheme("amoled") },
                            label = { Text(stringResource(R.string.theme_amoled)) }
                        )
                        FilterChip(
                            selected = state.themeMode == "dark",
                            onClick = { viewModel.selectTheme("dark") },
                            label = { Text(stringResource(R.string.theme_dark)) }
                        )
                        FilterChip(
                            selected = state.themeMode == "light",
                            onClick = { viewModel.selectTheme("light") },
                            label = { Text(stringResource(R.string.theme_light)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Font Size",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "small" to R.string.font_scale_small,
                            "default" to R.string.font_scale_default,
                            "large" to R.string.font_scale_large,
                            "xl" to R.string.font_scale_xl
                        ).forEach { (key, resId) ->
                            FilterChip(
                                selected = state.fontScaleKey == key,
                                onClick = { viewModel.selectFontScale(key) },
                                label = { Text(stringResource(resId)) }
                            )
                        }
                    }
                }
            }

            // Notifications, Per-Account Toggles, Preview Privacy & One UI 7 Battery Unrestricted
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_notifications)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Master message notifications", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = state.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setNotifications(enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Show message text in notification preview", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.notificationShowPreview,
                            enabled = state.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationPreview
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    for (acctId in 1..4) {
                        val acctLabel = state.accounts.firstOrNull { it.accountId == acctId }?.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AccountBadge(accountId = acctId, label = acctLabel)
                            Switch(
                                checked = state.accountNotifications[acctId] != false,
                                enabled = state.notificationsEnabled,
                                onCheckedChange = { viewModel.setAccountNotification(acctId, it) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val pm = context.getSystemService(PowerManager::class.java)
                                val pkg = context.packageName
                                val intent = if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$pkg")
                                    }
                                } else {
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_battery_opt))
                    }
                }
            }

            // Storage & Media Cache
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_storage)) {
                    val mb = "%.2f MB".format(state.mediaCacheBytes / (1024.0 * 1024.0))
                    Text(
                        text = "Downloaded media cache across 4 account folders: $mb",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::clearMediaCache) {
                            Text(stringResource(R.string.btn_clear_media_cache))
                        }
                        OutlinedButton(onClick = onNavigateToAccounts) {
                            Text(stringResource(R.string.manage_accounts_cta))
                        }
                    }
                }
            }

            // Portable Encrypted Backup & Migration (WHUBBK02)
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_backup)) {
                    Text(
                        text = "Exports a portable AES-256-GCM + PBKDF2-HMAC-SHA256 (210k iterations) WHUBBK02 encrypted snapshot of chats, messages, contacts, groups, LID/PN mappings, and account labels that restores safely across clean installs or new devices. Raw Signal/Noise session keys are never exported.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { passphraseMode = "export" }) {
                            Text(stringResource(R.string.btn_export_backup))
                        }
                        OutlinedButton(onClick = { passphraseMode = "restore" }) {
                            Text(stringResource(R.string.btn_import_backup))
                        }
                    }
                }
            }

            // Sanitized Diagnostic Logs & Engine Health Telemetry
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_diagnostics)) {
                    state.telemetry?.let { tel ->
                        Text(
                            text = "Poller Alive: ${tel.pollerAlive} • Processed Events: ${tel.processedEventsCount} • Isolated Errors: ${tel.isolatedEventErrors}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Verbose sanitized diagnostics", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.debugLogsEnabled,
                            onCheckedChange = viewModel::setDebugLogging
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportLogsLauncher.launch("rafat_hub_diagnostics.txt") }
                        ) {
                            Text("Export Logs")
                        }
                        TextButton(onClick = viewModel::clearLogs) {
                            Text("Clear")
                        }
                    }
                    if (state.recentLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.recentLogs) { entry ->
                                Text(
                                    text = entry.formattedLine(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // About Rafat Hub + Unofficial Protocol Disclosure
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_about)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RafatHubMonogramMark(size = 44.dp)
                        Column {
                            Text(
                                text = stringResource(R.string.brand_full_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) • arm64-v8a",
                                fontSize = 12.sp,
                                color = BrandWarmGold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Engine: ${state.engineVersion.ifBlank { "rafat-hub-engine/1.1.0" }}\nStorage: SQLCipher 4.6.1 AES-256 + Android Hardware Keystore\nPublisher Identity: CN=Rafat Al-Majidi (Permanent Release Key)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        text = stringResource(R.string.unofficial_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.unofficial_warning_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    passphraseMode?.let { mode ->
        var input by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passphraseMode = null },
            title = {
                Text(if (mode == "export") "Encrypt Portable Backup" else "Decrypt & Restore Backup")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a passphrase of at least 8 characters (PBKDF2-HMAC-SHA256 + AES-256-GCM):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Backup Passphrase") }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = input.length >= 8,
                    onClick = {
                        pendingPassphrase = input
                        val currentMode = mode
                        passphraseMode = null
                        if (currentMode == "export") {
                            createBackupLauncher.launch("rafat_hub_v1_1_backup.whub")
                        } else {
                            restoreBackupLauncher.launch(arrayOf("*/*"))
                        }
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { passphraseMode = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
