package com.rafat.whatshub.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.OutlinedButton
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
import com.rafat.whatshub.database.GlobalMessageSearchHit
import com.rafat.whatshub.ui.components.AccountBadge
import com.rafat.whatshub.ui.components.ConversationAvatar
import com.rafat.whatshub.ui.components.RafatHubMonogramMark
import com.rafat.whatshub.ui.components.formatHubTimestamp
import com.rafat.whatshub.ui.theme.BrandWarmGold
import com.rafat.whatshub.ui.theme.accountColor

@Composable
fun UnifiedInboxScreen(
    viewModel: InboxViewModel,
    onOpenConversation: (accountId: Int, chatJid: String) -> Unit,
    onNavigateToAccounts: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showNewChatDialog by rememberSaveable { mutableStateOf(false) }
    var selectedChatForActions by remember { mutableStateOf<ChatSummaryRecord?>(null) }

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
            // Luxury Rafat Hub brand header + 4-account live status pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RafatHubMonogramMark(size = 38.dp)
                    Column {
                        Text(
                            text = stringResource(R.string.inbox_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.brand_subtitle),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ConnectionSummaryPill(
                    accounts = state.accounts,
                    connectedCount = connectedCount,
                    onClick = onNavigateToAccounts
                )
            }

            // Global search bar (chats, contacts, AND message bodies)
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

            // Account filter row (All + Account 1..4)
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

            // Secondary filters: Unread, Groups, Direct, Archived
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
                if (state.archivedCount > 0 || state.filters.showArchived) {
                    FilterChip(
                        selected = state.filters.showArchived,
                        onClick = viewModel::toggleArchivedView,
                        label = {
                            val labelBase = stringResource(R.string.filter_archived)
                            Text(if (!state.filters.showArchived && state.archivedCount > 0) "$labelBase (${state.archivedCount})" else labelBase)
                        }
                    )
                }
            }

            if (state.conversations.isEmpty() && state.messageHits.isEmpty()) {
                EmptyInboxPlaceholder(
                    modifier = Modifier.weight(1f),
                    isFiltered = state.filters.searchQuery.isNotBlank() || state.filters.unreadOnly || state.filters.showArchived,
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
                            onClick = { onOpenConversation(chat.accountId, chat.chatJid) },
                            onLongClick = { selectedChatForActions = chat }
                        )
                    }

                    if (state.messageHits.isNotEmpty()) {
                        item(key = "msg_search_header") {
                            Text(
                                text = "Matching Messages (${state.messageHits.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandWarmGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                        }
                        items(
                            items = state.messageHits,
                            key = { "hit:${it.accountId}:${it.chatJid}:${it.messageId}" }
                        ) { hit ->
                            MessageSearchHitCard(
                                hit = hit,
                                accountLabel = accountMap[hit.accountId]?.label,
                                onClick = { onOpenConversation(hit.accountId, hit.chatJid) }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedChatForActions?.let { target ->
        ChatContextActionsDialog(
            chat = target,
            onDismiss = { selectedChatForActions = null },
            onTogglePin = {
                viewModel.togglePin(target)
                selectedChatForActions = null
            },
            onToggleArchive = {
                viewModel.toggleArchive(target)
                selectedChatForActions = null
            },
            onToggleMute = {
                viewModel.toggleMute(target)
                selectedChatForActions = null
            },
            onToggleUnread = {
                viewModel.toggleUnreadState(target)
                selectedChatForActions = null
            }
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    chat: ChatSummaryRecord,
    accountLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val acctColor = accountColor(chat.accountId)
    val displayTitle = chat.cleanTitle
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
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
                    title = displayTitle,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (chat.isPinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = BrandWarmGold,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (chat.isMuted) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsOff,
                                    contentDescription = "Muted",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
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
private fun MessageSearchHitCard(
    hit: GlobalMessageSearchHit,
    accountLabel: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = hit.chatTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AccountBadge(accountId = hit.accountId, label = accountLabel)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hit.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatContextActionsDialog(
    chat: ChatSummaryRecord,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleUnread: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chat.cleanTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PushPin, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (chat.isPinned) R.string.chat_action_unpin else R.string.chat_action_pin))
                }
                OutlinedButton(onClick = onToggleArchive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (chat.isArchived) R.string.chat_action_unarchive else R.string.chat_action_archive))
                }
                OutlinedButton(onClick = onToggleMute, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NotificationsOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (chat.isMuted) R.string.chat_action_unmute else R.string.chat_action_mute))
                }
                OutlinedButton(onClick = onToggleUnread, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.MarkChatUnread, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (chat.unreadCount > 0) R.string.chat_action_mark_read else R.string.chat_action_mark_unread))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun EmptyInboxPlaceholder(
    modifier: Modifier = Modifier,
    isFiltered: Boolean = false,
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
            text = if (isFiltered) "No matching conversations" else stringResource(R.string.empty_inbox_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_inbox_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isFiltered) {
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onManageAccounts) {
                Text(stringResource(R.string.manage_accounts_cta))
            }
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
