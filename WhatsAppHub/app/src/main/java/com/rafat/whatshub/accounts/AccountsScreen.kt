package com.rafat.whatshub.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.R
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.ui.components.AccountStatusChip
import com.rafat.whatshub.ui.components.QrBitmapGenerator
import com.rafat.whatshub.ui.theme.accountColor

@Composable
fun AccountsScreen(viewModel: AccountsViewModel) {
    val cards by viewModel.cards.collectAsState()
    val busySet by viewModel.busyAccounts.collectAsState()
    val feedback by viewModel.feedbackMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var phoneDialogAccount by rememberSaveable { mutableStateOf<Int?>(null) }
    var renameDialogAccount by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmRemoveAccount by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFeedback()
        }
    }

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
                    text = stringResource(R.string.nav_accounts),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Each slot runs an isolated whatsmeow client, Keystore-encrypted SQLite store, and independent reconnect supervisor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(cards, key = { it.account.accountId }) { item ->
                AccountSlotCard(
                    model = item,
                    isBusy = item.account.accountId in busySet,
                    onConnectQr = { viewModel.connectOrPairWithQr(item.account.accountId) },
                    onOpenPhoneCodeDialog = { phoneDialogAccount = item.account.accountId },
                    onDisconnect = { viewModel.disconnect(item.account.accountId) },
                    onRemove = { confirmRemoveAccount = item.account.accountId },
                    onReplace = { viewModel.replace(item.account.accountId) },
                    onRename = { renameDialogAccount = item.account.accountId }
                )
            }
        }
    }

    phoneDialogAccount?.let { acctId ->
        PhonePairingCodeDialog(
            accountId = acctId,
            onDismiss = { phoneDialogAccount = null },
            onSubmit = { phone ->
                phoneDialogAccount = null
                viewModel.requestPhonePairingCode(acctId, phone)
            }
        )
    }

    renameDialogAccount?.let { acctId ->
        val current = cards.firstOrNull { it.account.accountId == acctId }?.account?.label.orEmpty()
        RenameAccountDialog(
            accountId = acctId,
            initialLabel = current,
            onDismiss = { renameDialogAccount = null },
            onSave = { newLabel ->
                viewModel.rename(acctId, newLabel)
                renameDialogAccount = null
            }
        )
    }

    confirmRemoveAccount?.let { acctId ->
        AlertDialog(
            onDismissRequest = { confirmRemoveAccount = null },
            title = { Text("${stringResource(R.string.btn_remove)} #$acctId?") },
            text = {
                Text("This logs out Account $acctId (if connected) and permanently deletes its local session keys, chats, and downloaded media without affecting other accounts.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmRemoveAccount = null
                        viewModel.remove(acctId)
                    }
                ) {
                    Text(stringResource(R.string.btn_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAccount = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSlotCard(
    model: AccountCardUiModel,
    isBusy: Boolean,
    onConnectQr: () -> Unit,
    onOpenPhoneCodeDialog: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onReplace: () -> Unit,
    onRename: () -> Unit
) {
    val acct = model.account
    val color = accountColor(acct.accountId)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = acct.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = onRename,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (acct.phone.isNotBlank() || acct.pushName.isNotBlank()) {
                            val identity = listOfNotNull(
                                acct.pushName.takeIf { it.isNotBlank() },
                                acct.phone.takeIf { it.isNotBlank() }?.let { "+$it" }
                            ).joinToString(" • ")
                            Text(
                                text = identity,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = color)
                } else {
                    AccountStatusChip(status = acct.status, reconnectAttempt = acct.reconnectAttempt)
                }
            }

            if (acct.errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = acct.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }

            // Live QR pairing display (only rendered from real whatsmeow QR events)
            model.qrState?.let { qr ->
                Spacer(modifier = Modifier.height(14.dp))
                val qrBitmap by produceState<ImageBitmap?>(initialValue = null, qr.qrPayload) {
                    value = QrBitmapGenerator.generate(qr.qrPayload, 600)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(10.dp)
                        ) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "WhatsApp Pairing QR",
                                modifier = Modifier.size(210.dp)
                            )
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.qr_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Live Phone Pairing Code display (state stays PAIRING until confirmed on phone)
            model.pairingCodeState?.let { codeState ->
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = codeState.formattedCode,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = 3.sp,
                        color = color
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pair_code_waiting_confirmation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // History synchronization progress indicator
            model.latestSync?.let { sync ->
                if (sync.importedMessages > 0 || sync.status == "in_progress") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "History Sync (${sync.syncType}): ${sync.importedChats} chats, ${sync.importedMessages} messages (${sync.progressPct}%)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sync.status == "in_progress") {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (sync.progressPct.coerceIn(5, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = color
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (acct.status) {
                    AccountStatus.CONNECTED -> {
                        OutlinedButton(onClick = onDisconnect, enabled = !isBusy) {
                            Text(stringResource(R.string.btn_disconnect))
                        }
                        OutlinedButton(onClick = onReplace, enabled = !isBusy) {
                            Text(stringResource(R.string.btn_replace))
                        }
                        OutlinedButton(onClick = onRemove, enabled = !isBusy) {
                            Text(stringResource(R.string.btn_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    AccountStatus.CONNECTING, AccountStatus.WAITING_FOR_QR, AccountStatus.PAIRING, AccountStatus.RECONNECTING -> {
                        OutlinedButton(onClick = onDisconnect, enabled = !isBusy) {
                            Text(stringResource(R.string.btn_disconnect))
                        }
                        if (!acct.hasSavedSession) {
                            OutlinedButton(onClick = onOpenPhoneCodeDialog, enabled = !isBusy) {
                                Icon(Icons.Default.PhonelinkSetup, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_pair_code))
                            }
                        }
                    }

                    AccountStatus.DISCONNECTED, AccountStatus.CONNECTION_ERROR -> {
                        if (acct.hasSavedSession) {
                            Button(
                                onClick = onConnectQr,
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = color)
                            ) {
                                Text(stringResource(R.string.btn_reconnect))
                            }
                            OutlinedButton(onClick = onReplace, enabled = !isBusy) {
                                Text(stringResource(R.string.btn_replace))
                            }
                            OutlinedButton(onClick = onRemove, enabled = !isBusy) {
                                Text(stringResource(R.string.btn_remove), color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Button(
                                onClick = onConnectQr,
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = color)
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_connect_qr))
                            }
                            OutlinedButton(onClick = onOpenPhoneCodeDialog, enabled = !isBusy) {
                                Icon(Icons.Default.PhonelinkSetup, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_pair_code))
                            }
                        }
                    }

                    AccountStatus.AUTH_EXPIRED -> {
                        Button(
                            onClick = onReplace,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = color)
                        ) {
                            Text(stringResource(R.string.btn_connect_qr))
                        }
                        OutlinedButton(onClick = onRemove, enabled = !isBusy) {
                            Text(stringResource(R.string.btn_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    AccountStatus.NOT_CONNECTED -> {
                        Button(
                            onClick = onConnectQr,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = color)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_connect_qr))
                        }
                        OutlinedButton(onClick = onOpenPhoneCodeDialog, enabled = !isBusy) {
                            Icon(Icons.Default.PhonelinkSetup, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_pair_code))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhonePairingCodeDialog(
    accountId: Int,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var phone by rememberSaveable { mutableStateOf("") }
    val digitsOnly = phone.replace(Regex("[^0-9]"), "")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.btn_pair_code)} (#$accountId)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.pair_code_instructions),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    singleLine = true,
                    label = { Text("International Phone Number") },
                    placeholder = { Text("967771234567") }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = digitsOnly.length in 7..15,
                onClick = { onSubmit(digitsOnly) }
            ) {
                Text("Request Code")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameAccountDialog(
    accountId: Int,
    initialLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label for Account $accountId") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                singleLine = true,
                label = { Text("Account label") }
            )
        },
        confirmButton = {
            Button(onClick = { onSave(label) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
