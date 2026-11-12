package com.rafat.whatshub.conversations

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rafat.whatshub.database.AccountRecord
import com.rafat.whatshub.database.ChatSummaryRecord
import com.rafat.whatshub.database.ContactRecord
import com.rafat.whatshub.database.GroupMemberRecord
import com.rafat.whatshub.database.GroupRecord
import com.rafat.whatshub.database.IdentityResolver
import com.rafat.whatshub.database.MessageRecord
import com.rafat.whatshub.repository.HubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ConversationUiState(
    val accountId: Int,
    val chatJid: String,
    val account: AccountRecord? = null,
    val allAccounts: List<AccountRecord> = emptyList(),
    val chat: ChatSummaryRecord? = null,
    val contact: ContactRecord? = null,
    val group: GroupRecord? = null,
    val groupMembers: List<GroupMemberRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val inChatSearchQuery: String = "",
    val inChatSearchResults: List<MessageRecord> = emptyList(),
    val forwardCandidateChats: List<ChatSummaryRecord> = emptyList(),
    val canLoadOlder: Boolean = false
)

@OptIn(FlowPreview::class)
class ConversationViewModel(
    private val repository: HubRepository,
    val accountId: Int,
    val chatJid: String
) : ViewModel() {

    private val _replyTarget = MutableStateFlow<MessageRecord?>(null)
    val replyTarget: StateFlow<MessageRecord?> = _replyTarget.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _windowLimit = MutableStateFlow(300)
    private val _inChatSearchQuery = MutableStateFlow("")

    private var typingResetJob: Job? = null

    val uiState: StateFlow<ConversationUiState> = combine(
        repository.accounts,
        repository.database.observeConversationInvalidations(accountId, chatJid).debounce(25L),
        _windowLimit,
        _inChatSearchQuery
    ) { accounts, _, limit, searchQuery ->
        val canonicalJid = repository.database.resolveCanonicalChatJid(accountId, chatJid)
        val acct = accounts.firstOrNull { it.accountId == accountId }
        val chatSummary = repository.database.getChatSummary(accountId, canonicalJid)
        val contact = if (!IdentityResolver.isGroupJid(canonicalJid)) {
            repository.database.getContact(accountId, canonicalJid)
        } else null
        val group = if (IdentityResolver.isGroupJid(canonicalJid)) {
            repository.database.getGroup(accountId, canonicalJid)
        } else null
        val members = if (group != null) {
            repository.database.getGroupMembers(accountId, canonicalJid)
        } else emptyList()

        val msgs = repository.database.getConversationMessages(accountId, canonicalJid, limit = limit)
        val searchHits = if (searchQuery.trim().isNotEmpty()) {
            repository.database.searchConversationMessages(accountId, canonicalJid, searchQuery)
        } else {
            emptyList()
        }
        val forwardChats = repository.database.getUnifiedInboxChats(includeArchived = false).take(40)

        ConversationUiState(
            accountId = accountId,
            chatJid = canonicalJid,
            account = acct,
            allAccounts = accounts,
            chat = chatSummary,
            contact = contact,
            group = group,
            groupMembers = members,
            messages = msgs,
            inChatSearchQuery = searchQuery,
            inChatSearchResults = searchHits,
            forwardCandidateChats = forwardChats,
            canLoadOlder = msgs.size >= limit
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ConversationUiState(accountId = accountId, chatJid = chatJid)
        )

    init {
        repository.setActiveConversation(accountId, chatJid)
        viewModelScope.launch {
            repository.openAndMarkConversationRead(accountId, chatJid)
            if (IdentityResolver.isGroupJid(chatJid)) {
                repository.refreshSingleGroupDetails(accountId, chatJid)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.setActiveConversation(null, null)
    }

    fun loadOlderMessages() {
        _windowLimit.value = (_windowLimit.value + 300).coerceAtMost(3000)
    }

    fun setInChatSearchQuery(query: String) {
        _inChatSearchQuery.value = query
    }

    fun setReplyTarget(msg: MessageRecord?) {
        _replyTarget.value = msg
    }

    fun dismissError() {
        _errorBanner.value = null
    }

    fun onComposerTextChanged(text: String) {
        typingResetJob?.cancel()
        if (text.isNotBlank()) {
            viewModelScope.launch {
                repository.sendTypingState(accountId, chatJid, isTyping = true)
            }
            typingResetJob = viewModelScope.launch {
                delay(3_000L)
                repository.sendTypingState(accountId, chatJid, isTyping = false)
            }
        }
    }

    /**
     * Durable outgoing text send:
     *  - Clears composer ONLY inside [onPendingPersisted] after local SQLite commit succeeds.
     *  - Underlying repository send runs on NonCancellable IO scope so navigating Back never loses the message.
     */
    fun sendText(text: String, onClearedComposer: () -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val quoted = _replyTarget.value
        val activeTargetJid = uiState.value.chatJid.ifBlank { chatJid }

        viewModelScope.launch {
            _errorBanner.value = null
            val res = repository.sendTextMessage(
                accountId = accountId,
                chatJid = activeTargetJid,
                text = trimmed,
                quotedMessageId = quoted?.messageId.orEmpty(),
                quotedSenderJid = quoted?.senderJid.orEmpty(),
                quotedText = quoted?.textBody.orEmpty(),
                onPendingPersisted = {
                    // Composer clears immediately once Pending row is durably stored in SQLite!
                    _replyTarget.value = null
                    onClearedComposer()
                }
            )
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Failed to send via Account $accountId — tap Retry on the message" }
            }
        }
    }

    fun retryMessage(msg: MessageRecord) {
        viewModelScope.launch {
            _errorBanner.value = null
            val res = repository.retryFailedMessage(accountId, msg.chatJid, msg.messageId)
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Retry failed on Account $accountId" }
            }
        }
    }

    fun forwardMessage(msg: MessageRecord, targetAccountId: Int, targetChatJid: String) {
        viewModelScope.launch {
            val res = repository.forwardMessage(msg, targetAccountId, targetChatJid)
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Forward failed" }
            }
        }
    }

    fun sendUriAttachment(
        context: Context,
        uri: Uri,
        mediaType: String,
        caption: String,
        onPersisted: () -> Unit = {}
    ) {
        val quoted = _replyTarget.value
        val activeTargetJid = uiState.value.chatJid.ifBlank { chatJid }
        viewModelScope.launch {
            _isSending.value = true
            _errorBanner.value = null
            try {
                val (stagedFile, resolvedMime) = stageContentUriToAccountDir(context, uri)
                val res = repository.sendMediaMessage(
                    accountId = accountId,
                    chatJid = activeTargetJid,
                    mediaType = mediaType,
                    localFile = stagedFile,
                    mimeType = resolvedMime,
                    caption = caption,
                    quotedMessageId = quoted?.messageId.orEmpty(),
                    quotedSenderJid = quoted?.senderJid.orEmpty(),
                    quotedText = quoted?.textBody.orEmpty(),
                    onPendingPersisted = {
                        _replyTarget.value = null
                        onPersisted()
                    }
                )
                if (!res.ok) {
                    _errorBanner.value = res.error.ifBlank { "Media send failed on Account $accountId" }
                }
            } catch (e: Exception) {
                _errorBanner.value = e.message ?: "Attachment error"
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendRecordedVoiceNote(audioFile: File, durationSec: Int) {
        val activeTargetJid = uiState.value.chatJid.ifBlank { chatJid }
        viewModelScope.launch {
            _isSending.value = true
            _errorBanner.value = null
            try {
                val res = repository.sendMediaMessage(
                    accountId = accountId,
                    chatJid = activeTargetJid,
                    mediaType = "voice",
                    localFile = audioFile,
                    mimeType = "audio/mp4",
                    durationSec = durationSec
                )
                if (!res.ok) {
                    _errorBanner.value = res.error.ifBlank { "Voice note send failed" }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun reactToMessage(target: MessageRecord, emoji: String) {
        viewModelScope.launch {
            repository.sendReaction(
                accountId = accountId,
                chatJid = target.chatJid,
                senderJid = target.senderJid,
                targetMessageId = target.messageId,
                emoji = emoji
            )
        }
    }

    fun deleteForEveryone(target: MessageRecord) {
        viewModelScope.launch {
            val res = repository.deleteMessageForEveryone(
                accountId = accountId,
                chatJid = target.chatJid,
                senderJid = target.senderJid,
                targetMessageId = target.messageId
            )
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Delete for everyone failed" }
            }
        }
    }

    fun deleteForMe(target: MessageRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteMessageForMe(accountId, target.chatJid, target.messageId)
        }
    }

    fun downloadMedia(msg: MessageRecord) {
        viewModelScope.launch {
            val res = repository.downloadMessageMedia(accountId, msg)
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Media download failed" }
            }
        }
    }

    private suspend fun stageContentUriToAccountDir(context: Context, uri: Uri): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var displayName = "attachment_${System.currentTimeMillis()}"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val raw = c.getString(0)
                    if (!raw.isNullOrBlank()) displayName = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
                }
            }
            val dir = File(context.filesDir, "media/account_$accountId/outgoing").apply { mkdirs() }
            val outFile = File(dir, "${System.currentTimeMillis()}_$displayName")
            resolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read selected attachment")
            outFile to mime
        }

    class Factory(
        private val repository: HubRepository,
        private val accountId: Int,
        private val chatJid: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationViewModel(repository, accountId, chatJid) as T
    }
}
