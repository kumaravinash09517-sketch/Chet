package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.remote.BackendJsonParsers
import com.example.data.repository.MediaTransferProgress
import com.example.data.repository.MediaTransferTracker
import com.example.ui.theme.IncomingBubbleDark
import com.example.ui.theme.IncomingBubbleLight
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.OutgoingBubbleDark
import com.example.ui.theme.OutgoingBubbleLight
import java.io.File
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageBubble(
    message: MessageEntity,
    isMine: Boolean,
    transferProgress: MediaTransferProgress? = null,
    onLongPressDelete: (MessageEntity) -> Unit,
    onImagePreview: (String) -> Unit,
    onVideoPreview: (String) -> Unit = {},
    onEnsureVideoDownloaded: (MessageEntity) -> Unit = {},
    onStopLiveLocation: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = when {
        isMine && isDark -> OutgoingBubbleDark
        isMine && !isDark -> OutgoingBubbleLight
        !isMine && isDark -> IncomingBubbleDark
        else -> IncomingBubbleLight
    }
    val contentColor = if (isMine) Color.White else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (isMine) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isMine) 18.dp else 4.dp,
        bottomEnd = if (isMine) 4.dp else 18.dp
    )

    val resolvedMediaSource = remember(message.localUri, message.mediaUrl) {
        val local = message.localUri?.trim()?.takeIf { it.isNotBlank() }
        val usableLocal = if (local != null && local.startsWith("file://")) {
            val filePath = Uri.parse(local).path
            if (filePath != null && File(filePath).exists()) local else null
        } else {
            local
        }
        usableLocal ?: BackendJsonParsers.normalizeMediaUrl(message.mediaUrl)
    }
    val needsWideBubble = message.type != MessageType.TEXT && message.type != MessageType.CONTACT

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            tonalElevation = if (isMine) 0.dp else 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = if (needsWideBubble) 220.dp else 64.dp, max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {
                        if (message.type == MessageType.IMAGE && resolvedMediaSource != null) {
                            onImagePreview(resolvedMediaSource)
                        }
                    },
                    onLongClick = { onLongPressDelete(message) }
                )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                when (message.type) {
                    MessageType.TEXT -> {
                        val displayText = message.content.ifBlank {
                            when {
                                resolvedMediaSource != null -> message.fileName ?: "Attachment"
                                message.latitude != null && message.longitude != null ->
                                    String.format(Locale.US, "Location: %.5f, %.5f", message.latitude, message.longitude)
                                else -> "Message"
                            }
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                    }
                    MessageType.IMAGE -> {
                        val context = LocalContext.current
                        var imageLoadFailed by remember(resolvedMediaSource) { mutableStateOf(false) }
                        var retryNonce by remember(resolvedMediaSource) { mutableIntStateOf(0) }
                        if (resolvedMediaSource != null && !imageLoadFailed) {
                            val request = remember(resolvedMediaSource, retryNonce) {
                                ImageRequest.Builder(context)
                                    .data(resolvedMediaSource)
                                    .setParameter("retry", retryNonce, memoryCacheKey = null)
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = message.content.ifBlank { "Shared photo" },
                                contentScale = ContentScale.Crop,
                                onError = { imageLoadFailed = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.12f))
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(132.dp)
                                    .clickable(enabled = resolvedMediaSource != null) {
                                        retryNonce++
                                        imageLoadFailed = false
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = if (imageLoadFailed) Icons.Rounded.Refresh else Icons.Rounded.Image,
                                        contentDescription = "Photo attachment",
                                        tint = contentColor,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = when {
                                            imageLoadFailed -> "Tap to retry photo"
                                            resolvedMediaSource == null -> "Uploading photo…"
                                            else -> message.fileName ?: "Photo"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (message.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                    }
                    MessageType.VIDEO -> {
                        VideoPreviewCard(
                            messageId = message.id,
                            isMine = isMine,
                            title = message.fileName ?: "Video Clip",
                            subtitle = formatBytes(message.fileSizeBytes),
                            sourceUri = resolvedMediaSource,
                            localUri = message.localUri,
                            remoteUrl = message.mediaUrl,
                            caption = message.content,
                            transferProgress = transferProgress,
                            contentColor = contentColor,
                            mutedColor = mutedColor,
                            onEnsureDownloaded = { onEnsureVideoDownloaded(message) },
                            onPlay = { src -> onVideoPreview(src) }
                        )
                    }
                    MessageType.AUDIO -> {
                        AudioPlayerBubble(
                            title = message.fileName ?: "Audio Message",
                            sourceUri = resolvedMediaSource,
                            contentColor = contentColor,
                            mutedColor = mutedColor
                        )
                    }
                    MessageType.DOCUMENT -> {
                        MediaActionCard(
                            icon = Icons.Rounded.Description,
                            title = message.fileName ?: "Document",
                            subtitle = formatBytes(message.fileSizeBytes),
                            sourceUri = resolvedMediaSource,
                            mimeType = "*/*",
                            contentColor = contentColor,
                            mutedColor = mutedColor
                        )
                    }
                    MessageType.LOCATION, MessageType.LIVE_LOCATION -> {
                        LocationMapBubble(
                            message = message,
                            isMine = isMine,
                            contentColor = contentColor,
                            mutedColor = mutedColor,
                            onStopLiveLocation = onStopLiveLocation
                        )
                    }
                    MessageType.CONTACT -> {
                        val parts = message.content.split("|")
                        val cName = parts.getOrNull(0) ?: "Contact"
                        val cPhone = parts.getOrNull(1) ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ContactPhone,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = cName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = contentColor
                                )
                                Text(
                                    text = cPhone,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mutedColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatShortTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor
                    )
                    if (isMine) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(
                            status = message.status,
                            tintOverride = mutedColor,
                            isOnDarkBubble = isMine
                        )
                    }
                }
            }
        }
    }
}

private object VideoThumbnailCache {
    private val cache = LruCache<String, Bitmap>(24)

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
private fun VideoPreviewCard(
    messageId: String,
    isMine: Boolean,
    title: String,
    subtitle: String,
    sourceUri: String?,
    localUri: String?,
    remoteUrl: String?,
    caption: String,
    transferProgress: MediaTransferProgress?,
    contentColor: Color,
    mutedColor: Color,
    onEnsureDownloaded: () -> Unit,
    onPlay: (String) -> Unit
) {
    val context = LocalContext.current
    val trackedProgress by remember(messageId) {
        MediaTransferTracker.transfers
            .map { it[messageId] }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = transferProgress)
    val activeProgress = trackedProgress ?: transferProgress

    var thumbnail by remember(sourceUri, localUri) {
        mutableStateOf(
            localUri?.let { VideoThumbnailCache.get(it) }
                ?: sourceUri?.let { VideoThumbnailCache.get(it) }
        )
    }
    var thumbnailFailed by remember(sourceUri, localUri) { mutableStateOf(false) }

    LaunchedEffect(messageId, localUri, remoteUrl) {
        if (!isMine && localUri.isNullOrBlank() && !remoteUrl.isNullOrBlank()) {
            onEnsureDownloaded()
        }
    }

    LaunchedEffect(sourceUri, localUri) {
        val localCandidates = listOfNotNull(
            localUri?.takeIf { it.isNotBlank() },
            sourceUri?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("http://", ignoreCase = true) &&
                    !it.startsWith("https://", ignoreCase = true)
            }
        ).distinct()
        if (localCandidates.isEmpty() || thumbnail != null) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) {
            var frame: Bitmap? = null
            for (candidate in localCandidates) {
                val filePath = if (candidate.startsWith("file://")) Uri.parse(candidate).path else null
                if (filePath != null && !File(filePath).exists()) continue
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(candidate))
                    val raw = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime()
                    if (raw != null) {
                        frame = if (raw.width > 640) {
                            val targetW = 640
                            val targetH = ((raw.height.toFloat() / raw.width.toFloat()) * targetW)
                                .toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                            if (scaled !== raw) raw.recycle()
                            scaled
                        } else {
                            raw
                        }
                        break
                    }
                } catch (_: Throwable) {
                    // Try next local candidate URI
                } finally {
                    runCatching { retriever.release() }
                }
            }
            frame
        }
        if (loaded != null) {
            localCandidates.forEach { VideoThumbnailCache.put(it, loaded) }
            thumbnail = loaded
            thumbnailFailed = false
        } else {
            thumbnailFailed = true
        }
    }

    val isTransferring = activeProgress != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF152520),
            modifier = Modifier
                .fillMaxWidth()
                .height(186.dp)
                .clip(RoundedCornerShape(12.dp))
                .testTag("video_preview_card_$messageId")
                .clickable(enabled = sourceUri != null) {
                    if (sourceUri != null) {
                        if (!isMine && localUri.isNullOrBlank() && !remoteUrl.isNullOrBlank() && !isTransferring) {
                            onEnsureDownloaded()
                        }
                        onPlay(sourceUri)
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.28f))
                    )
                } else {
                    // Clear visual video tile placeholder while thumbnail loads/downloads (never a flat dead black box)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = "Video clip",
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // Center Play / Transfer Progress Badge
                if (isTransferring) {
                    val progress = activeProgress!!
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .testTag("video_transfer_progress_$messageId")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (progress.totalBytes > 0L) {
                                    CircularProgressIndicator(
                                        progress = { progress.fraction },
                                        color = Color.White,
                                        trackColor = Color.White.copy(alpha = 0.25f),
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Text(
                                    text = progress.statusLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (progress.totalBytes > 0L) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progress.fraction },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.24f),
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.58f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (sourceUri == null) Icons.Rounded.Videocam else Icons.Rounded.PlayArrow,
                                contentDescription = "Play video fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Bottom metadata strip
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isTransferring -> activeProgress!!.statusLabel
                            sourceUri == null -> "Preparing video…"
                            thumbnailFailed && localUri.isNullOrBlank() && !isMine -> "$subtitle • Tap to play / cache"
                            else -> "$subtitle • Tap to play fullscreen"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (sourceUri != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.54f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        IconButton(
                            onClick = { onPlay(sourceUri) },
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("video_fullscreen_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fullscreen,
                                contentDescription = "Fullscreen video",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
        if (caption.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun MediaActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    sourceUri: String?,
    mimeType: String,
    contentColor: Color,
    mutedColor: Color
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor
                )
            }
            if (sourceUri != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            val launchUri = resolveExternalShareableUri(context, sourceUri)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(launchUri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Text("Open", color = contentColor)
                }
            }
        }
    }
}

internal fun resolveExternalShareableUri(context: Context, sourceUri: String): Uri {
    val parsed = Uri.parse(sourceUri)
    if (parsed.scheme == "file" && parsed.path != null) {
        val file = File(parsed.path!!)
        if (file.exists()) {
            return runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrDefault(parsed)
        }
    }
    return parsed
}

@Composable
private fun AudioPlayerBubble(
    title: String,
    sourceUri: String?,
    contentColor: Color,
    mutedColor: Color
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(sourceUri) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
            player = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 210.dp)
    ) {
        IconButton(
            onClick = {
                if (sourceUri == null) return@IconButton
                if (isPlaying) {
                    runCatching { player?.pause() }
                    isPlaying = false
                } else {
                    runCatching {
                        if (player == null) {
                            player = MediaPlayer().apply {
                                setDataSource(context, Uri.parse(sourceUri))
                                prepare()
                                setOnCompletionListener { isPlaying = false }
                            }
                        }
                        player?.start()
                        isPlaying = true
                    }
                }
            }
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause audio" else "Play audio",
                tint = contentColor
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isPlaying) "Playing…" else "Tap to play audio",
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor
            )
        }
    }
}

@Composable
private fun LocationMapBubble(
    message: MessageEntity,
    isMine: Boolean,
    contentColor: Color,
    mutedColor: Color,
    onStopLiveLocation: () -> Unit
) {
    val context = LocalContext.current
    val lat = message.latitude ?: 0.0
    val lng = message.longitude ?: 0.0
    val gridColor = contentColor.copy(alpha = 0.16f)
    val accent = if (message.liveLocationActive) OnlineGreen else contentColor

    Column(modifier = Modifier.fillMaxWidth()) {
        // Custom Canvas Interactive GPS Radar Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val step = 22.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = accent.copy(alpha = 0.22f), radius = 34.dp.toPx(), center = center)
                drawCircle(color = accent.copy(alpha = 0.4f), radius = 18.dp.toPx(), center = center)
            }
            Icon(
                imageVector = Icons.Rounded.LocationOn,
                contentDescription = "GPS Pin",
                tint = accent,
                modifier = Modifier.size(34.dp)
            )
            if (message.liveLocationActive) {
                Surface(
                    shape = CircleShape,
                    color = OnlineGreen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "LIVE GPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message.content.ifBlank { "Shared Location" },
            style = MaterialTheme.typography.titleMedium,
            color = contentColor
        )
        Text(
            text = String.format(Locale.US, "%.5f, %.5f", lat, lng),
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = {
                    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(message.content)})")
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            ) {
                Icon(Icons.Rounded.Map, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Open Map", color = contentColor)
            }

            if (isMine && message.liveLocationActive) {
                TextButton(onClick = onStopLiveLocation) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop Live", color = contentColor)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Media file"
    val kb = bytes / 1024.0
    return if (kb < 1024) String.format(Locale.US, "%.1f KB", kb)
    else String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
