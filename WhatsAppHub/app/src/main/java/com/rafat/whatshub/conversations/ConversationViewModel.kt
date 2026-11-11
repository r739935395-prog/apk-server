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
import com.rafat.whatshub.database.MessageRecord
import com.rafat.whatshub.repository.HubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ConversationUiState(
    val accountId: Int,
    val chatJid: String,
    val account: AccountRecord? = null,
    val chat: ChatSummaryRecord? = null,
    val contact: ContactRecord? = null,
    val group: GroupRecord? = null,
    val groupMembers: List<GroupMemberRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList()
)

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

    private var typingStopJob: Job? = null

    val uiState: StateFlow<ConversationUiState> = combine(
        repository.accounts,
        repository.database.changeVersion
    ) { accounts, _ ->
        val acct = accounts.firstOrNull { it.accountId == accountId }
        val chat = repository.database.getChatSummary(accountId, chatJid)
        val isGroup = chatJid.endsWith("@g.us")
        val contact = if (!isGroup) {
            repository.database.getContacts(accountFilter = accountId).firstOrNull { it.contactJid == chatJid }
        } else null
        val group = if (isGroup) {
            repository.database.getGroups(accountFilter = accountId).firstOrNull { it.groupJid == chatJid }
        } else null
        val members = if (isGroup) {
            repository.database.getGroupMembers(accountId, chatJid)
        } else emptyList()

        ConversationUiState(
            accountId = accountId,
            chatJid = chatJid,
            account = acct,
            chat = chat,
            contact = contact,
            group = group,
            groupMembers = members,
            messages = repository.database.getConversationMessages(accountId, chatJid)
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
            if (chatJid.endsWith("@g.us")) {
                repository.refreshSingleGroupDetails(accountId, chatJid)
            }
        }
    }

    override fun onCleared() {
        repository.setActiveConversation(null, null)
        super.onCleared()
    }

    fun setReplyTarget(msg: MessageRecord?) {
        _replyTarget.value = msg
    }

    fun dismissError() {
        _errorBanner.value = null
    }

    fun onComposerTextChanged(text: String) {
        typingStopJob?.cancel()
        if (text.isBlank()) {
            viewModelScope.launch { repository.sendTypingState(accountId, chatJid, false) }
            return
        }
        viewModelScope.launch { repository.sendTypingState(accountId, chatJid, true) }
        typingStopJob = viewModelScope.launch {
            delay(3_500L)
            repository.sendTypingState(accountId, chatJid, false)
        }
    }

    fun sendText(text: String, onSuccess: () -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty() || _isSending.value) return
        val quote = _replyTarget.value
        viewModelScope.launch {
            _isSending.value = true
            try {
                typingStopJob?.cancel()
                repository.sendTypingState(accountId, chatJid, false)
                val res = repository.sendTextMessage(
                    accountId = accountId,
                    chatJid = chatJid,
                    text = clean,
                    quotedMessageId = quote?.messageId.orEmpty(),
                    quotedSenderJid = quote?.senderJid.orEmpty(),
                    quotedText = quote?.textBody.orEmpty()
                )
                if (res.ok) {
                    _replyTarget.value = null
                    onSuccess()
                } else {
                    _errorBanner.value = res.error.ifBlank { "Failed to send message via Account $accountId" }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendUriAttachment(context: Context, uri: Uri, forcedMediaType: String, caption: String = "") {
        viewModelScope.launch {
            _isSending.value = true
            try {
                val (tempFile, mimeType) = copyUriToTempFile(context, uri)
                if (tempFile == null) {
                    _errorBanner.value = "Unable to read selected attachment"
                    return@launch
                }
                val quote = _replyTarget.value
                val res = repository.sendMediaMessage(
                    accountId = accountId,
                    chatJid = chatJid,
                    mediaType = forcedMediaType,
                    localFile = tempFile,
                    mimeType = mimeType,
                    caption = caption,
                    quotedMessageId = quote?.messageId.orEmpty(),
                    quotedSenderJid = quote?.senderJid.orEmpty(),
                    quotedText = quote?.textBody.orEmpty()
                )
                if (res.ok) {
                    _replyTarget.value = null
                } else {
                    _errorBanner.value = res.error.ifBlank { "Media upload failed on Account $accountId" }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendRecordedVoiceNote(audioFile: File, durationSec: Int) {
        viewModelScope.launch {
            _isSending.value = true
            try {
                val quote = _replyTarget.value
                val res = repository.sendMediaMessage(
                    accountId = accountId,
                    chatJid = chatJid,
                    mediaType = "voice",
                    localFile = audioFile,
                    mimeType = "audio/mp4",
                    durationSec = durationSec,
                    quotedMessageId = quote?.messageId.orEmpty(),
                    quotedSenderJid = quote?.senderJid.orEmpty(),
                    quotedText = quote?.textBody.orEmpty()
                )
                if (res.ok) {
                    _replyTarget.value = null
                } else {
                    _errorBanner.value = res.error.ifBlank { "Voice note send failed" }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun reactToMessage(msg: MessageRecord, emoji: String) {
        viewModelScope.launch {
            val res = repository.sendReaction(
                accountId = accountId,
                chatJid = chatJid,
                senderJid = msg.senderJid,
                targetMessageId = msg.messageId,
                emoji = emoji
            )
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Reaction failed" }
            }
        }
    }

    fun deleteForEveryone(msg: MessageRecord) {
        viewModelScope.launch {
            val res = repository.deleteMessageForEveryone(
                accountId = accountId,
                chatJid = chatJid,
                senderJid = msg.senderJid,
                targetMessageId = msg.messageId
            )
            if (!res.ok) {
                _errorBanner.value = res.error.ifBlank { "Delete failed" }
            }
        }
    }

    fun downloadMedia(msg: MessageRecord) {
        viewModelScope.launch {
            val res = repository.downloadMessageMedia(accountId, msg)
            if (!res.ok && res.error.isNotBlank()) {
                _errorBanner.value = res.error
            }
        }
    }

    private suspend fun copyUriToTempFile(context: Context, uri: Uri): Pair<File?, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var displayName = "attachment_${System.currentTimeMillis()}"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) displayName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    }
                }
                val outDir = File(context.filesDir, "media/account_$accountId/outgoing").apply { mkdirs() }
                val dest = File(outDir, "${System.currentTimeMillis()}_$displayName")
                cr.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext (null to mime)
                dest to mime
            }.getOrElse { null to "application/octet-stream" }
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
