package com.rafat.whatshub.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rafat.whatshub.R
import com.rafat.whatshub.accounts.AccountsScreen
import com.rafat.whatshub.accounts.AccountsViewModel
import com.rafat.whatshub.contacts.ContactsGroupsViewModel
import com.rafat.whatshub.contacts.ContactsScreen
import com.rafat.whatshub.contacts.GroupsScreen
import com.rafat.whatshub.conversations.ConversationScreen
import com.rafat.whatshub.conversations.ConversationViewModel
import com.rafat.whatshub.inbox.InboxViewModel
import com.rafat.whatshub.inbox.UnifiedInboxScreen
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.settings.SettingsScreen
import com.rafat.whatshub.settings.SettingsViewModel

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

@Composable
fun HubNavHost(
    repository: HubRepository,
    deepLinkConversation: Pair<Int, String>? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        BottomTab("inbox", R.string.nav_inbox, Icons.Default.Forum),
        BottomTab("accounts", R.string.nav_accounts, Icons.Default.ManageAccounts),
        BottomTab("contacts", R.string.nav_contacts, Icons.Default.Contacts),
        BottomTab("groups", R.string.nav_groups, Icons.Default.Groups),
        BottomTab("settings", R.string.nav_settings, Icons.Default.Settings)
    )

    fun openConversation(accountId: Int, chatJid: String) {
        val encodedJid = Uri.encode(chatJid)
        navController.navigate("conversation/$accountId/$encodedJid")
    }

    LaunchedEffect(deepLinkConversation) {
        deepLinkConversation?.let { (acctId, chatJid) ->
            openConversation(acctId, chatJid)
            onDeepLinkConsumed()
        }
    }

    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes), maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "inbox",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("inbox") {
                val vm: InboxViewModel = viewModel(factory = InboxViewModel.Factory(repository))
                UnifiedInboxScreen(
                    viewModel = vm,
                    onOpenConversation = ::openConversation,
                    onNavigateToAccounts = {
                        navController.navigate("accounts") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable("accounts") {
                val vm: AccountsViewModel = viewModel(factory = AccountsViewModel.Factory(repository))
                AccountsScreen(viewModel = vm)
            }

            composable("contacts") {
                val vm: ContactsGroupsViewModel = viewModel(factory = ContactsGroupsViewModel.Factory(repository))
                ContactsScreen(
                    viewModel = vm,
                    onOpenConversation = ::openConversation
                )
            }

            composable("groups") {
                val vm: ContactsGroupsViewModel = viewModel(factory = ContactsGroupsViewModel.Factory(repository))
                GroupsScreen(
                    viewModel = vm,
                    onOpenConversation = ::openConversation
                )
            }

            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(repository))
                SettingsScreen(
                    viewModel = vm,
                    onNavigateToAccounts = { navController.navigate("accounts") }
                )
            }

            composable(
                route = "conversation/{accountId}/{chatJid}",
                arguments = listOf(
                    navArgument("accountId") { type = NavType.IntType },
                    navArgument("chatJid") { type = NavType.StringType }
                )
            ) { entry ->
                val accountId = entry.arguments?.getInt("accountId") ?: 1
                val chatJid = Uri.decode(entry.arguments?.getString("chatJid").orEmpty())
                val vm: ConversationViewModel = viewModel(
                    key = "conv_${accountId}_$chatJid",
                    factory = ConversationViewModel.Factory(repository, accountId, chatJid)
                )
                ConversationScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
