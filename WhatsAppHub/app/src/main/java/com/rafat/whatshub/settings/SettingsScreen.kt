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

            // Appearance / Theme
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_theme)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.themeMode == "dark",
                            onClick = { viewModel.selectTheme("dark") },
                            label = { Text(stringResource(R.string.theme_dark)) }
                        )
                        FilterChip(
                            selected = state.themeMode == "amoled",
                            onClick = { viewModel.selectTheme("amoled") },
                            label = { Text(stringResource(R.string.theme_amoled)) }
                        )
                        FilterChip(
                            selected = state.themeMode == "light",
                            onClick = { viewModel.selectTheme("light") },
                            label = { Text(stringResource(R.string.theme_light)) }
                        )
                    }
                }
            }

            // Notifications & Samsung One UI 7 Battery Optimization
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_notifications)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Message notifications", style = MaterialTheme.typography.bodyLarge)
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

            // Encrypted Local Backup & Restore
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_backup)) {
                    Text(
                        text = "Exports an AES-256-GCM + PBKDF2-HMAC-SHA256 encrypted archive. Raw authentication credentials are never exported unencrypted.",
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

            // Sanitized Diagnostic Logs
            item {
                SettingsSectionCard(title = stringResource(R.string.settings_diagnostics)) {
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
                    if (state.diagnosticLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            items(state.diagnosticLogs.takeLast(40)) { log ->
                                Text(
                                    text = log.formattedLine(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = viewModel::clearDiagnosticLogs) {
                            Text("Clear Diagnostic Buffer")
                        }
                    }
                }
            }

            // Security & Unofficial Client Disclosure
            item {
                SettingsSectionCard(title = stringResource(R.string.unofficial_warning_title)) {
                    Text(
                        text = stringResource(R.string.unofficial_warning_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "App: WhatsApp Hub v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})\nEngine: ${state.engineVersion}\nDatabase: SQLCipher AES-256 + Android Keystore GCM",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    passphraseMode?.let { mode ->
        var passInput by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passphraseMode = null },
            title = {
                Text(if (mode == "export") stringResource(R.string.btn_export_backup) else stringResource(R.string.btn_import_backup))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter encryption passphrase (minimum 8 characters):")
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Passphrase") }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = passInput.length >= 8,
                    onClick = {
                        pendingPassphrase = passInput
                        val currentMode = mode
                        passphraseMode = null
                        if (currentMode == "export") {
                            createBackupLauncher.launch("whatshub_backup_${System.currentTimeMillis()}.whbak")
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
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
