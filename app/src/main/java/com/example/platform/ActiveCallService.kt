package com.example.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.HelloSirApplication
import com.example.rtc.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service active during an ongoing WebRTC voice/video call
 * to preserve microphone, camera capture, and notification controls
 * even when MainActivity is minimized or swiped from Recents.
 */
class ActiveCallService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var lastPeerName: String = "Hello Sir Call"

    @Volatile
    private var lastIsVideo: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val app = application as? HelloSirApplication ?: return
        val controller = app.callController

        serviceScope.launch {
            combine(controller.callState, controller.isMuted) { state, muted ->
                state to muted
            }.collect { (state, muted) ->
                when (state) {
                    is CallState.Idle -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    is CallState.Ended -> {
                        // CallSessionController handles final teardown after short delay
                    }
                    is CallState.OutgoingRinging -> {
                        lastPeerName = state.peerName
                        lastIsVideo = state.isVideo
                        notifyActiveCallUpdate(
                            peerName = state.peerName,
                            isVideo = state.isVideo,
                            isConnected = false,
                            connectedSinceMs = System.currentTimeMillis(),
                            isMuted = muted
                        )
                    }
                    is CallState.IncomingRinging -> {
                        lastPeerName = state.callerName
                        lastIsVideo = state.isVideo
                        notifyActiveCallUpdate(
                            peerName = state.callerName,
                            isVideo = state.isVideo,
                            isConnected = false,
                            connectedSinceMs = System.currentTimeMillis(),
                            isMuted = muted
                        )
                    }
                    is CallState.Connecting -> {
                        lastPeerName = state.peerName
                        lastIsVideo = state.isVideo
                        notifyActiveCallUpdate(
                            peerName = state.peerName,
                            isVideo = state.isVideo,
                            isConnected = false,
                            connectedSinceMs = System.currentTimeMillis(),
                            isMuted = muted
                        )
                    }
                    is CallState.Connected -> {
                        lastPeerName = state.peerName
                        lastIsVideo = state.isVideo
                        notifyActiveCallUpdate(
                            peerName = state.peerName,
                            isVideo = state.isVideo,
                            isConnected = true,
                            connectedSinceMs = controller.getConnectedSinceMs(),
                            isMuted = muted
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(NotificationHelper.EXTRA_PEER_NAME)
            ?.takeIf { it.isNotBlank() } ?: lastPeerName
        val isVideo = intent?.getBooleanExtra(NotificationHelper.EXTRA_IS_VIDEO, lastIsVideo)
            ?: lastIsVideo
        val isConnected = intent?.getBooleanExtra(NotificationHelper.EXTRA_IS_CONNECTED, false) ?: false
        val connectedSinceMs = intent?.getLongExtra(
            NotificationHelper.EXTRA_CONNECTED_SINCE_MS,
            System.currentTimeMillis()
        ) ?: System.currentTimeMillis()
        val isMuted = intent?.getBooleanExtra(NotificationHelper.EXTRA_IS_MUTED, false) ?: false

        lastPeerName = peerName
        lastIsVideo = isVideo

        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildActiveCallNotification(
            context = this,
            peerName = peerName,
            isVideo = isVideo,
            isConnected = isConnected,
            connectedSinceMs = connectedSinceMs,
            isMuted = isMuted
        )

        startForegroundSafely(notification, isVideo)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as? HelloSirApplication
        if (app != null && app.callController.hasOngoingCall()) {
            app.isUiInForeground = false
            app.signalingClient.setAppForeground(false, keepSocketForCall = true)
            val state = app.callController.callState.value
            val isConnected = state is CallState.Connected
            val notification = NotificationHelper.buildActiveCallNotification(
                context = this,
                peerName = lastPeerName,
                isVideo = lastIsVideo,
                isConnected = isConnected,
                connectedSinceMs = app.callController.getConnectedSinceMs(),
                isMuted = app.callController.isMuted.value
            )
            startForegroundSafely(notification, lastIsVideo)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notifyActiveCallUpdate(
        peerName: String,
        isVideo: Boolean,
        isConnected: Boolean,
        connectedSinceMs: Long,
        isMuted: Boolean
    ) {
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildActiveCallNotification(
            context = this,
            peerName = peerName,
            isVideo = isVideo,
            isConnected = isConnected,
            connectedSinceMs = connectedSinceMs,
            isMuted = isMuted
        )
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification)
        }
    }

    private fun startForegroundSafely(notification: Notification, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            var type = 0
            if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isVideo && hasCam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (type == 0) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            val startedPrimary = runCatching {
                startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification, type)
            }.isSuccess
            if (!startedPrimary) {
                val fallbackType = when {
                    hasMic -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                val startedFallback = runCatching {
                    startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification, fallbackType)
                }.isSuccess
                if (!startedFallback) {
                    runCatching {
                        startForeground(
                            NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    }
                }
            }
        } else {
            runCatching {
                startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
