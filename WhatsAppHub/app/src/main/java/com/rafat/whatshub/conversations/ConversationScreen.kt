package com.rafat.whatshub.conversations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.rafat.whatshub.R
import com.rafat.whatshub.database.GroupMemberRecord
import com.rafat.whatshub.database.GroupRecord
import com.rafat.whatshub.database.MessageRecord
import com.rafat.whatshub.ui.components.AccountBadge
import com.rafat.whatshub.ui.components.ConversationAvatar
import com.rafat.whatshub.ui.components.formatHubTimestamp
import com.rafat.whatshub.ui.theme.accountColor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val errorBanner by viewModel.errorBanner.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val acctColor = accountColor(state.accountId)

    var draftText by rememberSaveable { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var pendingAttachmentType by rememberSaveable { mutableStateOf("document") }
    var selectedMessageForActions by remember { mutableStateOf<MessageRecord?>(null) }
    var showGroupDetailsSheet by rememberSaveable { mutableStateOf(false) }

    // Voice note recording state
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStartMs by remember { mutableLongStateOf(0L) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    // Audio playback state
    var playingMsgId by remember { mutableStateOf<String?>(null) }
    val mediaPlayer = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            }
            runCatching {
                activeRecorder?.stop()
                activeRecorder?.release()
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.sendUriAttachment(context, uri, pendingAttachmentType, draftText.trim())
            draftText = ""
        }
    }

    val startVoiceRecording: () -> Unit = {
        runCatching {
            val outDir = File(context.filesDir, "media/account_${state.accountId}/voice").apply { mkdirs() }
            val outFile = File(outDir, "vn_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(32_000)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()
            recordingFile = outFile
            activeRecorder = recorder
            recordingStartMs = System.currentTimeMillis()
            isRecordingVoice = true
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording()
    }

    val chatTitle = state.chat?.title
        ?: state.group?.name
        ?: state.contact?.displayName
        ?: state.chatJid.substringBefore('@')
    val isGroup = state.chatJid.endsWith("@g.us")

    val subtitle = when {
        state.chat?.isTyping == true -> stringResource(R.string.typing_indicator)
        isGroup && state.group != null -> "${state.group!!.participantCount} members"
        state.contact?.isOnline == true -> "online"
        else -> state.chatJid.substringBefore('@')
    }

    Scaffold(
        topBar = {
            Column {
                // Signature account color stripe across the top of the conversation header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(acctColor)
                )
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        ConversationAvatar(
                            title = chatTitle,
                            accountId = state.accountId,
                            isGroup = isGroup,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = isGroup) { showGroupDetailsSheet = true }
                        ) {
                            Text(
                                text = chatTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitle,
                                fontSize = 12.sp,
                                color = if (state.chat?.isTyping == true) acctColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        AccountBadge(
                            accountId = state.accountId,
                            label = state.account?.label
                        )
                        if (isGroup) {
                            IconButton(onClick = { showGroupDetailsSheet = true }) {
                                Icon(Icons.Default.Info, contentDescription = "Group Info")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Error banner if sending/downloading fails
            errorBanner?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::dismissError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = state.messages,
                    key = { it.compositeKey }
                ) { msg ->
                    MessageBubble(
                        msg = msg,
                        accountColor = acctColor,
                        isPlayingAudio = playingMsgId == msg.messageId,
                        onLongPress = { selectedMessageForActions = msg },
                        onDownloadMedia = { viewModel.downloadMedia(msg) },
                        onOpenMedia = { path, mime -> openFileExternally(context, path, mime) },
                        onTogglePlayAudio = { path ->
                            runCatching {
                                if (playingMsgId == msg.messageId && mediaPlayer.isPlaying) {
                                    mediaPlayer.pause()
                                    playingMsgId = null
                                } else {
                                    mediaPlayer.reset()
                                    mediaPlayer.setDataSource(path)
                                    mediaPlayer.prepare()
                                    mediaPlayer.setOnCompletionListener { playingMsgId = null }
                                    mediaPlayer.start()
                                    playingMsgId = msg.messageId
                                }
                            }
                        }
                    )
                }
            }

            // Explicit account ownership bar above composer so user never confuses sending account
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(acctColor.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                val acctLabel = state.account?.label ?: "Account ${state.accountId}"
                val acctPhone = state.account?.phone?.takeIf { it.isNotBlank() }?.let { "+$it" } ?: acctLabel
                Text(
                    text = stringResource(R.string.sending_via_account, state.accountId, acctPhone),
                    fontSize = 11.sp,
                    color = acctColor,
                    fontWeight = FontWeight.Medium
                )
            }

            // Reply preview banner
            replyTarget?.let { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${stringResource(R.string.reply_label)} ${target.senderName.ifBlank { target.senderJid.substringBefore('@') }}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = acctColor
                        )
                        Text(
                            text = target.textBody.ifBlank { "[${target.mediaType}]" },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.setReplyTarget(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                    }
                }
            }

            // Composer bar
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                if (isRecordingVoice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recording voice note…",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        activeRecorder?.stop()
                                        activeRecorder?.release()
                                    }
                                    recordingFile?.delete()
                                    activeRecorder = null
                                    isRecordingVoice = false
                                }
                            ) {
                                Text("Cancel")
                            }
                            IconButton(
                                onClick = {
                                    val durSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(1)
                                    runCatching {
                                        activeRecorder?.stop()
                                        activeRecorder?.release()
                                    }
                                    activeRecorder = null
                                    isRecordingVoice = false
                                    recordingFile?.let { file ->
                                        if (file.exists() && file.length() > 0) {
                                            viewModel.sendRecordedVoiceNote(file, durSec)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = stringResource(R.string.btn_stop_send_voice),
                                    tint = acctColor
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box {
                            IconButton(onClick = { showAttachMenu = true }) {
                                Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.btn_attach))
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Image / Photo") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        pendingAttachmentType = "image"
                                        openDocumentLauncher.launch(arrayOf("image/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Video") },
                                    leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        pendingAttachmentType = "video"
                                        openDocumentLauncher.launch(arrayOf("video/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Audio File") },
                                    leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        pendingAttachmentType = "audio"
                                        openDocumentLauncher.launch(arrayOf("audio/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Document / File") },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        pendingAttachmentType = "document"
                                        openDocumentLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sticker (WebP)") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        pendingAttachmentType = "sticker"
                                        openDocumentLauncher.launch(arrayOf("image/webp", "image/*"))
                                    }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = draftText,
                            onValueChange = {
                                draftText = it
                                viewModel.onComposerTextChanged(it)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp, max = 140.dp),
                            maxLines = 5,
                            shape = RoundedCornerShape(22.dp),
                            placeholder = {
                                Text(stringResource(R.string.composer_hint, state.accountId))
                            }
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        if (isSending) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = acctColor)
                            }
                        } else if (draftText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    viewModel.sendText(draftText) { draftText = "" }
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(acctColor)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = Color.White
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        startVoiceRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.btn_record_voice),
                                    tint = acctColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Message actions dialog (Reply, React, Copy, Delete for Everyone)
    selectedMessageForActions?.let { target ->
        AlertDialog(
            onDismissRequest = { selectedMessageForActions = null },
            title = { Text("Message Actions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (emoji in listOf("👍", "❤️", "😂", "😮", "😢", "🙏")) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        viewModel.reactToMessage(target, emoji)
                                        selectedMessageForActions = null
                                    }
                                    .padding(6.dp)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.setReplyTarget(target)
                            selectedMessageForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reply")
                    }

                    if (target.textBody.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(target.textBody))
                                selectedMessageForActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Text")
                        }
                    }

                    if (target.isFromMe && !target.isDeleted) {
                        OutlinedButton(
                            onClick = {
                                viewModel.deleteForEveryone(target)
                                selectedMessageForActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete for Everyone", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForActions = null }) { Text("Close") }
            }
        )
    }

    if (showGroupDetailsSheet && state.group != null) {
        ModalBottomSheet(onDismissRequest = { showGroupDetailsSheet = false }) {
            GroupDetailsSheetContent(
                group = state.group!!,
                members = state.groupMembers
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: MessageRecord,
    accountColor: Color,
    isPlayingAudio: Boolean,
    onLongPress: () -> Unit,
    onDownloadMedia: () -> Unit,
    onOpenMedia: (path: String, mime: String) -> Unit,
    onTogglePlayAudio: (path: String) -> Unit
) {
    val align = if (msg.isFromMe) Alignment.End else Alignment.Start
    val bubbleBg = if (msg.isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val bubbleText = if (msg.isFromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 90.dp, max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (msg.isFromMe) 4.dp else 16.dp
                    )
                )
                .background(bubbleBg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
                .padding(horizontal = 11.dp, vertical = 8.dp)
        ) {
            if (msg.isGroup && !msg.isFromMe) {
                Text(
                    text = msg.senderName.ifBlank { msg.senderJid.substringBefore('@') },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accountColor
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (msg.quotedMessageId.isNotBlank() && !msg.isDeleted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = msg.quotedSenderJid.substringBefore('@').ifBlank { "Quoted" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accountColor
                    )
                    Text(
                        text = msg.quotedText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = bubbleText.copy(alpha = 0.85f)
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
            }

            if (msg.isDeleted) {
                Text(
                    text = stringResource(R.string.msg_deleted),
                    fontStyle = FontStyle.Italic,
                    color = bubbleText.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
            } else {
                // Media attachment rendering
                msg.media?.let { media ->
                    val localFile = media.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
                    val isReady = localFile != null && localFile.exists()

                    when (media.mediaType) {
                        "image", "sticker" -> {
                            if (isReady) {
                                AsyncImage(
                                    model = localFile,
                                    contentDescription = media.mediaType,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = if (media.mediaType == "sticker") 150.dp else 220.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenMedia(localFile!!.absolutePath, media.mimeType) },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                MediaDownloadRow(
                                    title = if (media.mediaType == "sticker") "Sticker" else "Image",
                                    subtitle = formatByteSize(media.fileSize),
                                    downloadState = media.downloadState,
                                    onDownload = onDownloadMedia
                                )
                            }
                        }

                        "audio", "voice" -> {
                            if (isReady) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.14f))
                                        .padding(6.dp)
                                ) {
                                    IconButton(onClick = { onTogglePlayAudio(localFile!!.absolutePath) }) {
                                        Icon(
                                            imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = accountColor
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = if (media.isVoiceNote) "Voice note" else "Audio",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = bubbleText
                                        )
                                        if (media.durationSec > 0) {
                                            Text(
                                                text = "${media.durationSec}s",
                                                fontSize = 11.sp,
                                                color = bubbleText.copy(alpha = 0.75f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                MediaDownloadRow(
                                    title = if (media.isVoiceNote) "Voice note (${media.durationSec}s)" else "Audio",
                                    subtitle = formatByteSize(media.fileSize),
                                    downloadState = media.downloadState,
                                    onDownload = onDownloadMedia
                                )
                            }
                        }

                        else -> {
                            // Video or Document
                            if (isReady) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.14f))
                                        .clickable { onOpenMedia(localFile!!.absolutePath, media.mimeType) }
                                        .padding(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = media.fileName.ifBlank { media.mediaType.uppercase() },
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = bubbleText
                                        )
                                        Text(
                                            text = formatByteSize(localFile!!.length()),
                                            fontSize = 11.sp,
                                            color = bubbleText.copy(alpha = 0.75f)
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.btn_open_media), tint = accountColor)
                                }
                            } else {
                                MediaDownloadRow(
                                    title = media.fileName.ifBlank { media.mediaType.replaceFirstChar { it.uppercase() } },
                                    subtitle = formatByteSize(media.fileSize),
                                    downloadState = media.downloadState,
                                    onDownload = onDownloadMedia
                                )
                            }
                        }
                    }
                    if (msg.textBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                if (msg.textBody.isNotBlank()) {
                    Text(
                        text = msg.textBody,
                        style = MaterialTheme.typography.bodyLarge,
                        color = bubbleText
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (msg.isEdited && !msg.isDeleted) {
                    Text("edited", fontSize = 10.sp, color = bubbleText.copy(alpha = 0.65f))
                }
                Text(
                    text = formatHubTimestamp(msg.timestampMs),
                    fontSize = 10.sp,
                    color = bubbleText.copy(alpha = 0.7f)
                )
                if (msg.isFromMe && !msg.isDeleted) {
                    val ticks = when (msg.status) {
                        "read" -> "✓✓"
                        "delivered" -> "✓✓"
                        "sent" -> "✓"
                        else -> "…"
                    }
                    val tickColor = if (msg.status == "read") Color(0xFF53BDEB) else bubbleText.copy(alpha = 0.75f)
                    Text(text = ticks, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tickColor)
                }
            }
        }

        if (msg.reactionsSummary.isNotBlank() && !msg.isDeleted) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text(text = msg.reactionsSummary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MediaDownloadRow(
    title: String,
    subtitle: String,
    downloadState: String,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, fontSize = 11.sp)
            }
        }
        if (downloadState == "downloading") {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.btn_download_media))
            }
        }
    }
}

@Composable
private fun GroupDetailsSheetContent(
    group: GroupRecord,
    members: List<GroupMemberRecord>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = group.name, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Account ${group.accountId} • ${group.participantCount} participants",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (group.topic.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = group.topic, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(members, key = { it.memberJid }) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = m.phoneJid.ifBlank { m.memberJid }.substringBefore('@'),
                        fontSize = 13.sp
                    )
                    if (m.isSuperAdmin || m.isAdmin) {
                        Text(
                            text = if (m.isSuperAdmin) "Owner" else "Admin",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}

private fun openFileExternally(context: Context, filePath: String, mimeType: String) {
    runCatching {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }
}
