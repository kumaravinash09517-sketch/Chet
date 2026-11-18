package com.example.ui.screens

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.ui.theme.IncomingBubbleDark
import com.example.ui.theme.IncomingBubbleLight
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.OutgoingBubbleDark
import com.example.ui.theme.OutgoingBubbleLight
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageBubble(
    message: MessageEntity,
    isMine: Boolean,
    onLongPressDelete: (MessageEntity) -> Unit,
    onImagePreview: (String) -> Unit,
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
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {
                        val mediaSource = message.localUri ?: message.mediaUrl
                        if (message.type == MessageType.IMAGE && mediaSource != null) {
                            onImagePreview(mediaSource)
                        }
                    },
                    onLongClick = { onLongPressDelete(message) }
                )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                when (message.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                    }
                    MessageType.IMAGE -> {
                        val source = message.localUri ?: message.mediaUrl
                        if (source != null) {
                            AsyncImage(
                                model = source,
                                contentDescription = "Shared photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
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
                        MediaActionCard(
                            icon = Icons.Rounded.Videocam,
                            title = message.fileName ?: "Video Clip",
                            subtitle = formatBytes(message.fileSizeBytes),
                            sourceUri = message.localUri ?: message.mediaUrl,
                            mimeType = "video/*",
                            contentColor = contentColor,
                            mutedColor = mutedColor
                        )
                    }
                    MessageType.AUDIO -> {
                        AudioPlayerBubble(
                            title = message.fileName ?: "Audio Message",
                            sourceUri = message.localUri ?: message.mediaUrl,
                            contentColor = contentColor,
                            mutedColor = mutedColor
                        )
                    }
                    MessageType.DOCUMENT -> {
                        MediaActionCard(
                            icon = Icons.Rounded.Description,
                            title = message.fileName ?: "Document",
                            subtitle = formatBytes(message.fileSizeBytes),
                            sourceUri = message.localUri ?: message.mediaUrl,
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
                        MessageStatusIcon(status = message.status, tintOverride = mutedColor)
                    }
                }
            }
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
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(sourceUri), mimeType)
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
