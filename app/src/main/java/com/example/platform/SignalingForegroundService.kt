package com.example.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.HelloSirApplication
import com.example.data.remote.SignalingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent foreground relay service keeping WebSocket signaling active
 * in the background so incoming calls and messages trigger ringtones and alerts.
 */
class SignalingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        startForeground(
            NotificationHelper.NOTIFICATION_SYNC_ID,
            NotificationHelper.buildSyncServiceNotification(this)
        )

        val app = application as HelloSirApplication
        app.signalingClient.connect()

        serviceScope.launch {
            app.signalingClient.events.collect { event ->
                val myId = app.sessionManager.getCurrentSession()?.userId ?: return@collect
                when (event) {
                    is SignalingEvent.IncomingCallOffer -> {
                        if (!app.isUiInForeground) {
                            NotificationHelper.showIncomingCallNotification(
                                context = this@SignalingForegroundService,
                                callId = event.callId,
                                callerId = event.callerId,
                                callerName = event.callerName,
                                isVideo = event.isVideo,
                                offerSdp = event.sdp
                            )
                        }
                    }
                    is SignalingEvent.ChatMessageReceived -> {
                        if (event.dto.senderId != myId && !app.isUiInForeground) {
                            val senderUser = app.database.userDao().getUser(event.dto.senderId)
                            val preview = when (event.dto.type.uppercase()) {
                                "IMAGE" -> "Sent a photo"
                                "VIDEO" -> "Sent a video"
                                "AUDIO" -> "Sent an audio clip"
                                "DOCUMENT" -> event.dto.fileName ?: "Sent a document"
                                "LOCATION", "LIVE_LOCATION" -> "Shared a GPS location"
                                "CONTACT" -> "Shared a contact"
                                else -> event.dto.content
                            }
                            NotificationHelper.showIncomingMessageNotification(
                                context = this@SignalingForegroundService,
                                senderId = event.dto.senderId,
                                senderName = senderUser?.displayName ?: event.dto.senderId,
                                preview = preview
                            )
                        }
                    }
                    is SignalingEvent.CallEnded -> {
                        NotificationHelper.stopRingtoneAndVibrate(this@SignalingForegroundService)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HelloSirApplication
        app.signalingClient.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
