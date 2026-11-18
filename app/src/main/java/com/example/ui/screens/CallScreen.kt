package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rtc.CallState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.ObsidianBackground
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

@Composable
fun CallScreen(
    callState: CallState,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    durationSeconds: Long,
    onAttachRenderers: (local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) -> Unit,
    onAcceptIncoming: () -> Unit,
    onDeclineIncoming: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val context = LocalContext.current
    val remoteView = remember { SurfaceViewRenderer(context) }
    val localView = remember { SurfaceViewRenderer(context) }

    val isVideoCall = when (callState) {
        is CallState.OutgoingRinging -> callState.isVideo
        is CallState.IncomingRinging -> callState.isVideo
        is CallState.Connected -> callState.isVideo
        else -> false
    }

    val peerName = when (callState) {
        is CallState.OutgoingRinging -> callState.peerName
        is CallState.IncomingRinging -> callState.callerName
        is CallState.Connected -> callState.peerName
        else -> "Hello Sir Call"
    }

    val statusText = when (callState) {
        is CallState.OutgoingRinging -> if (callState.isVideo) "Video calling…" else "Calling…"
        is CallState.IncomingRinging -> if (callState.isVideo) "Incoming Video Call" else "Incoming Voice Call"
        is CallState.Connected -> formatDuration(durationSeconds)
        is CallState.Ended -> callState.reason
        CallState.Idle -> ""
    }

    DisposableEffect(callState) {
        if (isVideoCall && callState is CallState.Connected) {
            onAttachRenderers(localView, remoteView)
        }
        onDispose {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianBackground, Color(0xFF0A241C), ObsidianBackground)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Remote Video Surface
        if (isVideoCall && callState is CallState.Connected) {
            AndroidView(
                factory = { remoteView },
                modifier = Modifier.fillMaxSize()
            )
            // Local PiP Video Surface
            Surface(
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 16.dp)
                    .size(width = 110.dp, height = 156.dp)
            ) {
                AndroidView(
                    factory = { localView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Top Call Identity Header
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isVideoCall || callState !is CallState.Connected) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(EmeraldPrimary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peerName.take(1).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Bottom Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            if (callState is CallState.IncomingRinging) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = CrimsonAlert,
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("decline_call_button")
                        ) {
                            IconButton(onClick = onDeclineIncoming) {
                                Icon(
                                    imageVector = Icons.Rounded.CallEnd,
                                    contentDescription = "Decline Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Decline", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = EmeraldPrimary,
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("accept_call_button")
                        ) {
                            IconButton(onClick = onAcceptIncoming) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = "Accept Call",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Accept", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallToggleButton(
                            icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            active = isMuted,
                            contentDescription = "Toggle Microphone",
                            onClick = onToggleMute
                        )
                        CallToggleButton(
                            icon = Icons.Rounded.VolumeUp,
                            active = isSpeakerOn,
                            contentDescription = "Toggle Speakerphone",
                            onClick = onToggleSpeaker
                        )
                        if (isVideoCall) {
                            CallToggleButton(
                                icon = if (isVideoEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                active = !isVideoEnabled,
                                contentDescription = "Toggle Camera",
                                onClick = onToggleVideo
                            )
                            CallToggleButton(
                                icon = Icons.Rounded.Cameraswitch,
                                active = false,
                                contentDescription = "Switch Camera",
                                onClick = onSwitchCamera
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = CrimsonAlert,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("hangup_call_button")
                        ) {
                            IconButton(onClick = onHangUp) {
                                Icon(
                                    imageVector = Icons.Rounded.CallEnd,
                                    contentDescription = "End Call",
                                    tint = Color.White
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
private fun CallToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (active) Color.White else Color.White.copy(alpha = 0.16f),
        modifier = Modifier.size(50.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) ObsidianBackground else Color.White
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
