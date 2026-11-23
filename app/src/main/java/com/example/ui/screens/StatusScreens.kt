package com.example.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.core.security.UserSession
import com.example.data.local.StatusType
import com.example.data.local.StatusUpdateEntity
import com.example.data.remote.BackendJsonParsers
import com.example.ui.theme.OnlineGreen
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StatusUserGroup(
    val userId: String,
    val userName: String,
    val avatarUrl: String?,
    val items: List<StatusUpdateEntity>
) {
    val hasUnviewed: Boolean get() = items.any { !it.isViewedByMe }
    val latestTimestamp: Long get() = items.maxOfOrNull { it.createdAt } ?: 0L
}

private val STATUS_BG_PALETTE = listOf(
    "#1B5E20",
    "#0D47A1",
    "#6A1B9A",
    "#B71C1C",
    "#E65100",
    "#263238"
)

@Composable
fun StatusUpdatesTabContent(
    session: UserSession,
    statuses: List<StatusUpdateEntity>,
    isPostingStatus: Boolean,
    onPostTextStatus: (text: String, backgroundColorHex: String) -> Unit,
    onPostMediaStatus: (uri: Uri, isVideo: Boolean, caption: String) -> Unit,
    onMarkStatusViewed: (StatusUpdateEntity) -> Unit,
    onDeleteStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var showTextComposer by remember { mutableStateOf(false) }
    var activeViewerGroup by remember { mutableStateOf<StatusUserGroup?>(null) }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video/")
            onPostMediaStatus(uri, isVideo, "")
        }
    }

    val myStatuses = remember(statuses, session.userId) {
        statuses.filter { it.userId.equals(session.userId, ignoreCase = true) }
            .sortedBy { it.createdAt }
    }
    val myGroup = remember(myStatuses, session) {
        if (myStatuses.isEmpty()) null
        else StatusUserGroup(
            userId = session.userId,
            userName = session.displayName,
            avatarUrl = session.avatarUrl,
            items = myStatuses
        )
    }

    val peerGroups = remember(statuses, session.userId) {
        statuses
            .filter { !it.userId.equals(session.userId, ignoreCase = true) }
            .groupBy { it.userId.lowercase(Locale.ROOT) }
            .values
            .map { userItems ->
                val sorted = userItems.sortedBy { it.createdAt }
                val latest = sorted.last()
                StatusUserGroup(
                    userId = latest.userId,
                    userName = latest.userName,
                    avatarUrl = latest.userAvatarUrl,
                    items = sorted
                )
            }
            .sortedByDescending { it.latestTimestamp }
    }
    val recentGroups = remember(peerGroups) { peerGroups.filter { it.hasUnviewed } }
    val viewedGroups = remember(peerGroups) { peerGroups.filter { !it.hasUnviewed } }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 92.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("status_updates_list")
    ) {
        item(key = "my_status_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (myGroup != null) {
                            activeViewerGroup = myGroup
                        } else {
                            mediaPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("my_status_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (myStatuses.isNotEmpty()) {
                        StatusAvatarRing(
                            name = session.displayName,
                            avatarUrl = session.avatarUrl,
                            segmentCount = myStatuses.size,
                            ringColor = OnlineGreen,
                            sizeDp = 56
                        )
                    } else {
                        UserAvatarCircle(
                            name = session.displayName,
                            avatarUrl = session.avatarUrl,
                            sizeDp = 52
                        )
                        Surface(
                            shape = CircleShape,
                            color = OnlineGreen,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Add status",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "My Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            isPostingStatus -> "Uploading status update…"
                            myStatuses.isEmpty() -> "Tap to share photo, video, or text"
                            else -> "${myStatuses.size} active • ${formatRelativeStatusTime(myStatuses.last().createdAt)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { showTextComposer = true },
                    modifier = Modifier.testTag("add_text_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Post text status",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        mediaPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.testTag("add_media_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = "Post photo or video status",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (isPostingStatus) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }

        if (recentGroups.isNotEmpty()) {
            item(key = "recent_header") {
                Text(
                    text = "Recent Updates",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            items(recentGroups, key = { "recent_${it.userId}" }) { group ->
                PeerStatusRow(
                    group = group,
                    isUnviewed = true,
                    onClick = { activeViewerGroup = group }
                )
            }
        }

        if (viewedGroups.isNotEmpty()) {
            item(key = "viewed_header") {
                Text(
                    text = "Viewed Updates",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            items(viewedGroups, key = { "viewed_${it.userId}" }) { group ->
                PeerStatusRow(
                    group = group,
                    isUnviewed = false,
                    onClick = { activeViewerGroup = group }
                )
            }
        }

        if (recentGroups.isEmpty() && viewedGroups.isEmpty()) {
            item(key = "empty_peer_statuses") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No contact updates yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Share a text note, photo, or short video — updates disappear automatically after 24 hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showTextComposer) {
        TextStatusComposerDialog(
            onDismiss = { showTextComposer = false },
            onSubmit = { text, colorHex ->
                showTextComposer = false
                onPostTextStatus(text, colorHex)
            }
        )
    }

    activeViewerGroup?.let { group ->
        val freshGroup = remember(group.userId, statuses) {
            val freshItems = statuses
                .filter { it.userId.equals(group.userId, ignoreCase = true) }
                .sortedBy { it.createdAt }
            if (freshItems.isEmpty()) null else group.copy(items = freshItems)
        }
        if (freshGroup == null) {
            activeViewerGroup = null
        } else {
            StatusViewerDialog(
                group = freshGroup,
                isMine = freshGroup.userId.equals(session.userId, ignoreCase = true),
                onMarkViewed = onMarkStatusViewed,
                onDelete = { statusId ->
                    onDeleteStatus(statusId)
                    if (freshGroup.items.size <= 1) {
                        activeViewerGroup = null
                    }
                },
                onDismiss = { activeViewerGroup = null }
            )
        }
    }
}

@Composable
private fun PeerStatusRow(
    group: StatusUserGroup,
    isUnviewed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("status_user_${group.userId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusAvatarRing(
            name = group.userName,
            avatarUrl = group.avatarUrl,
            segmentCount = group.items.size,
            ringColor = if (isUnviewed) OnlineGreen else MaterialTheme.colorScheme.outlineVariant,
            sizeDp = 54
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.userName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "@${group.userId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatRelativeStatusTime(group.latestTimestamp)} • ${group.items.size} update${if (group.items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusAvatarRing(
    name: String,
    avatarUrl: String?,
    segmentCount: Int,
    ringColor: Color,
    sizeDp: Int
) {
    val count = segmentCount.coerceIn(1, 12)
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.6.dp.toPx()
            val gapDeg = if (count == 1) 0f else 10f
            val sweepPerSeg = (360f - gapDeg * count) / count
            for (i in 0 until count) {
                val startAngle = -90f + i * (sweepPerSeg + gapDeg)
                drawArc(
                    color = ringColor,
                    startAngle = startAngle,
                    sweepAngle = sweepPerSeg,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        UserAvatarCircle(
            name = name,
            avatarUrl = avatarUrl,
            sizeDp = sizeDp - 10
        )
    }
}

@Composable
private fun TextStatusComposerDialog(
    onDismiss: () -> Unit,
    onSubmit: (text: String, backgroundColorHex: String) -> Unit
) {
    var statusText by remember { mutableStateOf("") }
    var selectedHex by remember { mutableStateOf(STATUS_BG_PALETTE.first()) }
    val bgColor = remember(selectedHex) { parseStatusBgColor(selectedHex) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bgColor
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close text status composer",
                            tint = Color.White
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(STATUS_BG_PALETTE) { hex ->
                            val swatch = parseStatusBgColor(hex)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (hex == selectedHex) 3.dp else 1.5.dp,
                                        color = Color.White,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedHex = hex }
                            )
                        }
                    }
                }

                TextField(
                    value = statusText,
                    onValueChange = { statusText = it },
                    placeholder = {
                        Text(
                            text = "Type a status…",
                            style = TextStyle(fontSize = 26.sp, color = Color.White.copy(alpha = 0.65f)),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .testTag("text_status_input")
                )

                FloatingActionButton(
                    onClick = {
                        if (statusText.isNotBlank()) {
                            onSubmit(statusText.trim(), selectedHex)
                        }
                    },
                    containerColor = Color.White,
                    contentColor = bgColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .testTag("post_text_status_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Post status"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusViewerDialog(
    group: StatusUserGroup,
    isMine: Boolean,
    onMarkViewed: (StatusUpdateEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentIndex by remember(group.userId) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, (group.items.size - 1).coerceAtLeast(0))
    val currentStatus = group.items.getOrNull(safeIndex) ?: return
    var segmentProgress by remember(currentStatus.id) { mutableFloatStateOf(0f) }

    LaunchedEffect(currentStatus.id) {
        onMarkViewed(currentStatus)
        if (currentStatus.type != StatusType.VIDEO) {
            segmentProgress = 0f
            val steps = 50
            for (i in 1..steps) {
                delay(100L)
                segmentProgress = i / steps.toFloat()
            }
            if (safeIndex + 1 < group.items.size) {
                currentIndex = safeIndex + 1
            } else {
                onDismiss()
            }
        } else {
            segmentProgress = 0.2f
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("status_viewer_dialog"),
            color = if (currentStatus.type == StatusType.TEXT) {
                parseStatusBgColor(currentStatus.backgroundColorHex)
            } else {
                Color.Black
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .pointerInput(safeIndex, group.items.size) {
                        detectTapGestures { offset ->
                            if (offset.x < size.width * 0.35f) {
                                if (safeIndex > 0) currentIndex = safeIndex - 1 else onDismiss()
                            } else {
                                if (safeIndex + 1 < group.items.size) {
                                    currentIndex = safeIndex + 1
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                    }
            ) {
                // Center Content
                when (currentStatus.type) {
                    StatusType.TEXT -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentStatus.textContent,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    StatusType.IMAGE -> {
                        val source = resolveStatusMediaSource(currentStatus)
                        if (source != null) {
                            AsyncImage(
                                model = source,
                                contentDescription = "Status image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    StatusType.VIDEO -> {
                        val source = resolveStatusMediaSource(currentStatus)
                        var isBuffering by remember(source) { mutableStateOf(true) }
                        var videoViewRef by remember(source) { mutableStateOf<VideoView?>(null) }

                        DisposableEffect(source) {
                            onDispose {
                                runCatching { videoViewRef?.stopPlayback() }
                                videoViewRef = null
                            }
                        }

                        if (source != null) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setOnPreparedListener { mp ->
                                            isBuffering = false
                                            mp.isLooping = false
                                            start()
                                        }
                                        setOnCompletionListener {
                                            segmentProgress = 1f
                                            if (safeIndex + 1 < group.items.size) {
                                                currentIndex = safeIndex + 1
                                            } else {
                                                onDismiss()
                                            }
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            isBuffering = false
                                            true
                                        }
                                        setVideoURI(Uri.parse(source))
                                    }.also { videoViewRef = it }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // Top overlay: segmented bars + author header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        group.items.forEachIndexed { idx, _ ->
                            val prog = when {
                                idx < safeIndex -> 1f
                                idx == safeIndex -> segmentProgress
                                else -> 0f
                            }
                            LinearProgressIndicator(
                                progress = { prog },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatarCircle(
                            name = group.userName,
                            avatarUrl = group.avatarUrl,
                            sizeDp = 38
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.userName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatRelativeStatusTime(currentStatus.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        if (isMine) {
                            IconButton(
                                onClick = { onDelete(currentStatus.id) },
                                modifier = Modifier.testTag("delete_status_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete status",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close status viewer",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom caption / views overlay
                if (currentStatus.type != StatusType.TEXT && currentStatus.textContent.isNotBlank() || isMine) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (currentStatus.type != StatusType.TEXT && currentStatus.textContent.isNotBlank()) {
                            Text(
                                text = currentStatus.textContent,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (isMine) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Views: ${currentStatus.viewCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveStatusMediaSource(status: StatusUpdateEntity): String? {
    val local = status.localUri?.trim()?.takeIf { it.isNotBlank() }
    val usableLocal = if (local != null && local.startsWith("file://")) {
        val path = Uri.parse(local).path
        if (path != null && File(path).exists()) local else null
    } else {
        local
    }
    return usableLocal ?: BackendJsonParsers.normalizeMediaUrl(status.mediaUrl)
}

private fun parseStatusBgColor(hex: String): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color(0xFF1B5E20))
}

private fun formatRelativeStatusTime(millis: Long): String {
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    val mins = diff / 60_000L
    return when {
        mins < 1L -> "Just now"
        mins < 60L -> "${mins}m ago"
        mins < 1_440L -> "${mins / 60L}h ago"
        else -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }
}
