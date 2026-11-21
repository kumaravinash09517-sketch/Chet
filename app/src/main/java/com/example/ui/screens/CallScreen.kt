package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.core.content.ContextCompat
import com.example.rtc.CallState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.ObsidianBackground
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

@Composable
fun CallScreen(
    callState: CallState,
    eglBaseContext: EglBase.Context,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    isFrontCamera: Boolean,
    hasRemoteVideo: Boolean,
    durationSeconds: Long,
    onAttachRenderers: (local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) -> Unit,
    onCameraPermissionGranted: () -> Unit,
    onAcceptIncoming: () -> Unit,
    onDeclineIncoming: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val context = LocalContext.current
    var pendingAcceptAfterPermission by remember { mutableStateOf(false) }

    val remoteView = remember(eglBaseContext) {
        SurfaceViewRenderer(context).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setMirror(false)
            init(eglBaseContext, null)
        }
    }
    val localView = remember(eglBaseContext) {
        SurfaceViewRenderer(context).apply {
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setMirror(true)
            init(eglBaseContext, null)
        }
    }

    val isVideoCall = when (callState) {
        is CallState.OutgoingRinging -> callState.isVideo
        is CallState.IncomingRinging -> callState.isVideo
        is CallState.Connecting -> callState.isVideo
        is CallState.Connected -> callState.isVideo
        else -> false
    }

    val isActiveCall = callState !is CallState.Idle && callState !is CallState.Ended

    val showRemoteVideo = isVideoCall && callState is CallState.Connected && hasRemoteVideo
    val showLocalPreview = isVideoCall && isVideoEnabled && (
        callState is CallState.OutgoingRinging ||
            callState is CallState.Connecting ||
            callState is CallState.Connected
        )

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (isVideoCall && camGranted) {
            onCameraPermissionGranted()
        }
        if (pendingAcceptAfterPermission) {
            pendingAcceptAfterPermission = false
            if (micGranted) {
                onAcceptIncoming()
            }
        }
    }

    // Request CAMERA / RECORD_AUDIO automatically if an active video call lacks runtime permissions
    LaunchedEffect(isVideoCall, callState) {
        if (isVideoCall &&
            (callState is CallState.OutgoingRinging ||
                callState is CallState.Connecting ||
                callState is CallState.Connected)
        ) {
            val missing = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.CAMERA)
                }
            }
            if (missing.isNotEmpty()) {
                callPermissionLauncher.launch(missing.toTypedArray())
            } else {
                onCameraPermissionGranted()
            }
        }
    }

    val peerName = when (callState) {
        is CallState.OutgoingRinging -> callState.peerName
        is CallState.IncomingRinging -> callState.callerName
        is CallState.Connecting -> callState.peerName
        is CallState.Connected -> callState.peerName
        else -> "Hello Sir Call"
    }

    val statusText = when (callState) {
        is CallState.OutgoingRinging -> when {
            callState.isRingingOnRemote -> "Ringing…"
            callState.isVideo -> "Video calling…"
            else -> "Calling…"
        }
        is CallState.IncomingRinging -> if (callState.isVideo) "Incoming Video Call" else "Incoming Voice Call"
        is CallState.Connecting -> "Connecting…"
        is CallState.Connected -> formatDuration(durationSeconds)
        is CallState.Ended -> callState.reason
        CallState.Idle -> ""
    }

    DisposableEffect(isVideoCall, isActiveCall) {
        if (isVideoCall && isActiveCall) {
            onAttachRenderers(localView, remoteView)
        } else {
            onAttachRenderers(null, null)
        }
        onDispose {
            onAttachRenderers(null, null)
        }
    }

    LaunchedEffect(showLocalPreview, showRemoteVideo) {
        if (isVideoCall && isActiveCall) {
            onAttachRenderers(localView, remoteView)
        }
    }

    DisposableEffect(remoteView, localView) {
        onDispose {
            runCatching { localView.release() }
            runCatching { remoteView.release() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianBackground, Color(0xFF0A241C), ObsidianBackground)
                )
            )
    ) {
        // Keep remoteView mounted throughout any active video call so SurfaceHolder exists before remote frames arrive
        if (isVideoCall && isActiveCall) {
            AndroidView(
                factory = {
                    remoteView.apply {
                        setZOrderMediaOverlay(false)
                    }
                },
                update = { view ->
                    view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    view.visibility = if (showRemoteVideo) View.VISIBLE else View.INVISIBLE
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Local Self-Camera Preview:
        // Single persistent AndroidView transitions smoothly from full-screen preview to top-right PiP without tearing down SurfaceView.
        if (showLocalPreview) {
            val isPipMode = showRemoteVideo
            val localContainerModifier = if (isPipMode) {
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .size(width = 116.dp, height = 164.dp)
                    .border(1.5.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .testTag("local_camera_preview")
            } else {
                Modifier
                    .fillMaxSize()
                    .testTag("local_camera_preview")
            }

            Box(modifier = localContainerModifier) {
                AndroidView(
                    factory = {
                        localView.apply {
                            setZOrderMediaOverlay(true)
                        }
                    },
                    update = { view ->
                        view.setZOrderMediaOverlay(true)
                        view.setMirror(isFrontCamera)
                        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        if (isPipMode) {
                            view.clipToOutline = true
                            view.outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(v: View, outline: Outline) {
                                    val radius = 16f * v.resources.displayMetrics.density
                                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                                }
                            }
                        } else {
                            view.clipToOutline = false
                            view.outlineProvider = ViewOutlineProvider.BACKGROUND
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Subtle scrim for readability over full-screen camera preview
        if (showLocalPreview || showRemoteVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
            )
        }

        // Top Call Identity Header
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!showRemoteVideo && !showLocalPreview) {
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
                color = Color.White.copy(alpha = 0.88f)
            )
        }

        // Bottom Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
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
                            IconButton(
                                onClick = {
                                    val missing = buildList {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            add(Manifest.permission.RECORD_AUDIO)
                                        }
                                        if (isVideoCall && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                            add(Manifest.permission.CAMERA)
                                        }
                                    }
                                    if (missing.isEmpty()) {
                                        onAcceptIncoming()
                                    } else {
                                        pendingAcceptAfterPermission = true
                                        callPermissionLauncher.launch(missing.toTypedArray())
                                    }
                                }
                            ) {
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
