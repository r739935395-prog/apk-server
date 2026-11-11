package com.rafat.whatshub.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.R
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.AccountStatus
import com.rafat.whatshub.database.ChatSummaryRecord
import com.rafat.whatshub.ui.components.AccountBadge
import com.rafat.whatshub.ui.components.ConversationAvatar
import com.rafat.whatshub.ui.components.formatHubTimestamp
import com.rafat.whatshub.ui.theme.accountColor

@Composable
fun UnifiedInboxScreen(
    viewModel: InboxViewModel,
    onOpenConversation: (accountId: Int, chatJid: String) -> Unit,
    onNavigateToAccounts: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showNewChatDialog by rememberSaveable { mutableStateOf(false) }

    val accountMap = remember(state.accounts) {
        state.accounts.associateBy { it.accountId }
    }
    val connectedCount = state.accounts.count { it.status == AccountStatus.CONNECTED }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.AddComment, contentDescription = stringResource(R.string.new_chat))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top bar with title & 4-account live connection summary pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.inbox_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                ConnectionSummaryPill(
                    accounts = state.accounts,
                    connectedCount = connectedCount,
                    onClick = onNavigateToAccounts
                )
            }

            // Search bar
            OutlinedTextField(
                value = state.filters.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filters.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Account filter row (All + Account 1..4 with their signature Green/Blue/Purple/Orange colors)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.filters.selectedAccountId == null,
                    onClick = { viewModel.selectAccountFilter(null) },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
                for (acctId in 1..4) {
                    val acct = accountMap[acctId]
                    val acctColor = accountColor(acctId)
                    val selected = state.filters.selectedAccountId == acctId
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectAccountFilter(acctId) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(acctColor)
                                )
                                Text(acct?.label ?: stringResource(R.string.account_slot_format, acctId))
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = acctColor.copy(alpha = 0.22f),
                            selectedLabelColor = acctColor
                        )
                    )
                }
            }

            // Secondary filters: Unread, Groups, Direct
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.filters.unreadOnly,
                    onClick = viewModel::toggleUnreadOnly,
                    label = { Text(stringResource(R.string.filter_unread)) }
                )
                FilterChip(
                    selected = state.filters.chatType == "groups",
                    onClick = {
                        viewModel.setChatType(if (state.filters.chatType == "groups") "all" else "groups")
                    },
                    label = { Text(stringResource(R.string.filter_groups)) }
                )
                FilterChip(
                    selected = state.filters.chatType == "direct",
                    onClick = {
                        viewModel.setChatType(if (state.filters.chatType == "direct") "all" else "direct")
                    },
                    label = { Text(stringResource(R.string.filter_direct)) }
                )
            }

            if (state.conversations.isEmpty()) {
                EmptyInboxPlaceholder(
                    modifier = Modifier.weight(1f),
                    onManageAccounts = onNavigateToAccounts
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.conversations,
                        key = { it.compositeKey }
                    ) { chat ->
                        ConversationCard(
                            chat = chat,
                            accountLabel = accountMap[chat.accountId]?.label,
                            onClick = {
                                // Critical isolation rule: always open with the exact owning accountId + chatJid
                                onOpenConversation(chat.accountId, chat.chatJid)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        StartNewChatDialog(
            accounts = state.accounts,
            onDismiss = { showNewChatDialog = false },
            onStartChat = { chosenAccountId, phoneInput, displayName ->
                val jid = viewModel.startNewChat(chosenAccountId, phoneInput, displayName)
                showNewChatDialog = false
                onOpenConversation(chosenAccountId, jid)
            }
        )
    }
}

@Composable
private fun ConnectionSummaryPill(
    accounts: List<AccountRecord>,
    connectedCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (acctId in 1..4) {
            val status = accounts.firstOrNull { it.accountId == acctId }?.status ?: AccountStatus.NOT_CONNECTED
            val c = accountColor(acctId)
            val alpha = if (status == AccountStatus.CONNECTED) 1f else 0.25f
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(c.copy(alpha = alpha))
            )
        }
        Text(
            text = "$connectedCount/4",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConversationCard(
    chat: ChatSummaryRecord,
    accountLabel: String?,
    onClick: () -> Unit
) {
    val acctColor = accountColor(chat.accountId)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color-coded start stripe identifying the owning account at a glance
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(acctColor)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConversationAvatar(
                    title = chat.title,
                    accountId = chat.accountId,
                    isGroup = chat.isGroup
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatHubTimestamp(chat.lastMessageTsMs),
                            fontSize = 11.sp,
                            color = if (chat.unreadCount > 0) acctColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val previewText = when {
                            chat.isTyping -> stringResource(R.string.typing_indicator)
                            chat.isGroup && chat.lastSenderName.isNotBlank() && chat.lastMessagePreview.isNotBlank() ->
                                "${chat.lastSenderName}: ${chat.lastMessagePreview}"
                            else -> chat.lastMessagePreview
                        }
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (chat.isTyping) acctColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        AccountBadge(accountId = chat.accountId, label = accountLabel)

                        if (chat.unreadCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(acctColor)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = chat.unreadCount.coerceAtMost(999).toString(),
                                    color = MaterialTheme.colorScheme.surface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInboxPlaceholder(
    modifier: Modifier = Modifier,
    onManageAccounts: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.empty_inbox_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_inbox_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onManageAccounts) {
            Text(stringResource(R.string.manage_accounts_cta))
        }
    }
}

@Composable
private fun StartNewChatDialog(
    accounts: List<AccountRecord>,
    onDismiss: () -> Unit,
    onStartChat: (accountId: Int, phoneOrJid: String, displayName: String) -> Unit
) {
    val defaultAcct = accounts.firstOrNull { it.status == AccountStatus.CONNECTED }?.accountId ?: 1
    var selectedAccount by remember { mutableIntStateOf(defaultAcct) }
    var phoneInput by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select owning WhatsApp account:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (acctId in 1..4) {
                        val c = accountColor(acctId)
                        FilterChip(
                            selected = selectedAccount == acctId,
                            onClick = { selectedAccount = acctId },
                            label = {
                                Text(accounts.firstOrNull { it.accountId == acctId }?.label ?: "Account $acctId")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.copy(alpha = 0.25f),
                                selectedLabelColor = c
                            )
                        )
                    }
                }
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    singleLine = true,
                    label = { Text("Phone with country code (e.g. 967771234567)") }
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("Display name (optional)") }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = phoneInput.replace(Regex("[^0-9]"), "").length >= 7 || phoneInput.contains('@'),
                onClick = { onStartChat(selectedAccount, phoneInput, nameInput) }
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
