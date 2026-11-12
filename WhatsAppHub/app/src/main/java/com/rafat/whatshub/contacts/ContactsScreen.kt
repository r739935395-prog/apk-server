package com.rafat.whatshub.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafat.whatshub.R
import com.rafat.whatshub.ui.components.AccountBadge
import com.rafat.whatshub.ui.components.ConversationAvatar
import com.rafat.whatshub.ui.theme.accountColor

@Composable
fun ContactsScreen(
    viewModel: ContactsGroupsViewModel,
    onOpenConversation: (accountId: Int, chatJid: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.nav_contacts),
                style = MaterialTheme.typography.titleLarge
            )
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = viewModel::syncConnectedAccounts) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync Contacts")
                }
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.search_placeholder)) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.selectedAccountFilter == null,
                onClick = { viewModel.selectAccount(null) },
                label = { Text(stringResource(R.string.filter_all)) }
            )
            for (acctId in 1..4) {
                val c = accountColor(acctId)
                FilterChip(
                    selected = state.selectedAccountFilter == acctId,
                    onClick = { viewModel.selectAccount(acctId) },
                    label = {
                        Text(state.accounts.firstOrNull { it.accountId == acctId }?.label ?: "Account $acctId")
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = c.copy(alpha = 0.22f),
                        selectedLabelColor = c
                    )
                )
            }
        }

        if (state.contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No synchronized contacts match your filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = state.contacts,
                    key = { "${it.accountId}:${it.contactJid}" }
                ) { contact ->
                    val acctLabel = state.accounts.firstOrNull { it.accountId == contact.accountId }?.label
                    val cleanName = contact.cleanDisplayName
                    val verifiedPhone = contact.verifiedPhoneDisplay
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val (acctId, jid) = viewModel.openContactConversation(contact)
                                onOpenConversation(acctId, jid)
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ConversationAvatar(
                                title = cleanName,
                                accountId = contact.accountId,
                                isGroup = false,
                                size = 42.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cleanName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (verifiedPhone.isNotBlank() && verifiedPhone != cleanName) {
                                    Text(
                                        text = verifiedPhone,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            AccountBadge(accountId = contact.accountId, label = acctLabel)
                        }
                    }
                }
            }
        }
    }
}
