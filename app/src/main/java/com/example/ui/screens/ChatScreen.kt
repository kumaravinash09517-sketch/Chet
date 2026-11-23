package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.repository.MediaTransferProgress
import com.example.ui.theme.OnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    peerName: String,
    peerIsOnline: Boolean = false,
    myUserId: String,
    messages: List<MessageEntity>,
    mediaTransferProgress: Map<String, MediaTransferProgress> = emptyMap(),
    isPeerTyping: Boolean,
    liveLocationMessageId: String?,
    statusBanner: String?,
    onBack: () -> Unit,
    onMarkRead: () -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSendMedia: (uri: android.net.Uri, type: MessageType) -> Unit,
    onEnsureVideoDownloaded: (MessageEntity) -> Unit = {},
    onShareCurrentLocation: () -> Unit,
    onStartLiveLocation: () -> Unit,
    onStopLiveLocation: () -> Unit,
    onDeleteMessage: (MessageEntity) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit
) {
    val context = LocalContext.current
    var composerText by remember { mutableStateOf("") }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var previewVideoUrl by remember { mutableStateOf<String?>(null) }
    var pendingLocationAction by remember { mutableStateOf<Boolean?>(null) } // false = current, true = live
    var pendingCallIsVideo by remember { mutableStateOf<Boolean?>(null) }
    var showLocationRationale by remember { mutableStateOf(false) }
    var showCallPermRationale by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    DisposableEffect(peerId) {
        val app = context.applicationContext as? com.example.HelloSirApplication
        app?.activeChatPeerId = peerId
        onDispose {
            if (app?.activeChatPeerId == peerId) {
                app.activeChatPeerId = null
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            onMarkRead()
        }
    }

    // Zero-permission Android Photo Picker (Images + Videos)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val type = if (mime.startsWith("video/")) MessageType.VIDEO else MessageType.IMAGE
            onSendMedia(uri, type)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendMedia(it, MessageType.AUDIO) }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendMedia(it, MessageType.DOCUMENT) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (pendingLocationAction == true) onStartLiveLocation()
            else onShareCurrentLocation()
        }
        pendingLocationAction = null
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val isVideo = pendingCallIsVideo == true
        val camGranted = !isVideo || results[Manifest.permission.CAMERA] == true
        if (micGranted && camGranted) {
            if (isVideo) onStartVideoCall() else onStartAudioCall()
        }
        pendingCallIsVideo = null
    }

    fun requestLocationOrRun(isLive: Boolean) {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCoarse || hasFine) {
            if (isLive) onStartLiveLocation() else onShareCurrentLocation()
        } else {
            pendingLocationAction = isLive
            showLocationRationale = true
        }
    }

    fun requestCallPermissionsOrStart(isVideo: Boolean) {
        val micOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val camOk = !isVideo || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk && camOk) {
            if (isVideo) onStartVideoCall() else onStartAudioCall()
        } else {
            pendingCallIsVideo = isVideo
            showCallPermRationale = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peerName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isPeerTyping -> "typing…"
                                    peerIsOnline -> "Online • @$peerId"
                                    else -> "Offline • @$peerId"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isPeerTyping -> MaterialTheme.colorScheme.primary
                                    peerIsOnline -> OnlineGreen
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { requestCallPermissionsOrStart(isVideo = false) },
                        modifier = Modifier.testTag("audio_call_button")
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = "Audio Call")
                    }
                    IconButton(
                        onClick = { requestCallPermissionsOrStart(isVideo = true) },
                        modifier = Modifier.testTag("video_call_button")
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = "Video Call")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(
                        onClick = { showAttachmentSheet = true },
                        modifier = Modifier.testTag("attach_media_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "Attach Media or Location"
                        )
                    }

                    OutlinedTextField(
                        value = composerText,
                        onValueChange = {
                            composerText = it
                            onComposerTextChanged(it)
                        },
                        placeholder = { Text("Message $peerName…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_send_button")
                    ) {
                        IconButton(
                            onClick = {
                                if (composerText.isNotBlank()) {
                                    onSendText(composerText)
                                    composerText = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedVisibility(visible = liveLocationMessageId != null) {
                Surface(
                    color = OnlineGreen.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live GPS Location sharing active",
                            style = MaterialTheme.typography.labelMedium,
                            color = OnlineGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onStopLiveLocation) {
                            Text("Stop", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (statusBanner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusBanner,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    ChatMessageBubble(
                        message = message,
                        isMine = message.senderId == myUserId,
                        transferProgress = mediaTransferProgress[message.id],
                        onLongPressDelete = { msg -> messageToDelete = msg },
                        onImagePreview = { url -> previewImageUrl = url },
                        onVideoPreview = { url -> previewVideoUrl = url },
                        onEnsureVideoDownloaded = onEnsureVideoDownloaded,
                        onStopLiveLocation = onStopLiveLocation
                    )
                }
            }
        }
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Share in Chat", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                AttachmentOptionRow(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Photo or Video",
                    subtitle = "Pick securely via Android Photo Picker"
                ) {
                    showAttachmentSheet = false
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
                AttachmentOptionRow(
                    icon = Icons.Rounded.AudioFile,
                    title = "Audio Clip",
                    subtitle = "Share voice notes or audio files"
                ) {
                    showAttachmentSheet = false
                    audioPickerLauncher.launch(arrayOf("audio/*"))
                }
                AttachmentOptionRow(
                    icon = Icons.Rounded.Description,
                    title = "Document",
                    subtitle = "PDFs, spreadsheets, or archives"
                ) {
                    showAttachmentSheet = false
                    documentPickerLauncher.launch(arrayOf("*/*"))
                }
                AttachmentOptionRow(
                    icon = Icons.Rounded.LocationOn,
                    title = "Current GPS Location",
                    subtitle = "Share exact coordinates on interactive map"
                ) {
                    showAttachmentSheet = false
                    requestLocationOrRun(isLive = false)
                }
                AttachmentOptionRow(
                    icon = Icons.Rounded.MyLocation,
                    title = "Live GPS Location",
                    subtitle = "Stream real-time movement updates"
                ) {
                    showAttachmentSheet = false
                    requestLocationOrRun(isLive = true)
                }
            }
        }
    }

    if (messageToDelete != null) {
        val target = messageToDelete!!
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Delete message?") },
            text = {
                Text("This will delete the message, media, or location from this chat and notify the server.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMessage(target)
                        messageToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (previewImageUrl != null) {
        Dialog(onDismissRequest = { previewImageUrl = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                AsyncImage(
                    model = previewImageUrl,
                    contentDescription = "Full image preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }
        }
    }

    if (previewVideoUrl != null) {
        val videoSource = previewVideoUrl!!
        Dialog(
            onDismissRequest = { previewVideoUrl = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            var isBuffering by remember(videoSource) { mutableStateOf(true) }
            var videoError by remember(videoSource) { mutableStateOf(false) }
            var videoViewRef by remember(videoSource) { mutableStateOf<VideoView?>(null) }

            DisposableEffect(videoSource) {
                onDispose {
                    runCatching { videoViewRef?.stopPlayback() }
                    videoViewRef = null
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("fullscreen_video_player"),
                color = Color.Black
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { previewVideoUrl = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back from video player",
                                    tint = Color.White
                                )
                            }
                            Text(
                                text = "Video Player",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        val openUri = resolveExternalShareableUri(context, videoSource)
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(openUri, "video/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            ) {
                                Text("Open External", color = Color.White)
                            }
                            IconButton(onClick = { previewVideoUrl = null }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close video",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener { mp ->
                                        isBuffering = false
                                        mp.isLooping = false
                                        start()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isBuffering = false
                                        videoError = true
                                        true
                                    }
                                    setVideoURI(Uri.parse(videoSource))
                                    requestFocus()
                                }.also { videoViewRef = it }
                            },
                            update = { view ->
                                videoViewRef = view
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isBuffering && !videoError) {
                            CircularProgressIndicator(color = Color.White)
                        } else if (videoError) {
                            Text(
                                text = "Unable to play video in-app. Tap Open External.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text(stringResource(R.string.location_rationale_title)) },
            text = { Text(stringResource(R.string.location_rationale_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationRationale = false
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRationale = false }) { Text("Cancel") }
            }
        )
    }

    if (showCallPermRationale) {
        AlertDialog(
            onDismissRequest = { showCallPermRationale = false },
            title = { Text(stringResource(R.string.call_permissions_title)) },
            text = { Text(stringResource(R.string.call_permissions_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCallPermRationale = false
                        val perms = if (pendingCallIsVideo == true) {
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                        } else {
                            arrayOf(Manifest.permission.RECORD_AUDIO)
                        }
                        callPermissionLauncher.launch(perms)
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCallPermRationale = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AttachmentOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
