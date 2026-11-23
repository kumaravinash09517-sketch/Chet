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
        ShayariManager.scheduleDailyMorningNotification(this)
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
                        if (!app.isUiInForeground && !app.chatRepository.isCallHandled(event.callId)) {
                            val callerUser = app.database.userDao().getUser(event.callerId)
                            val resolvedCallerName = if (event.callerName.isBlank() || event.callerName == event.callerId) {
                                callerUser?.displayName ?: event.callerId
                            } else {
                                event.callerName
                            }
                            NotificationHelper.showIncomingCallNotification(
                                context = this@SignalingForegroundService,
                                callId = event.callId,
                                callerId = event.callerId,
                                callerName = resolvedCallerName,
                                isVideo = event.isVideo,
                                offerSdp = event.sdp
                            )
                            app.signalingClient.sendCallRinging(event.callId, myId, event.callerId)
                            app.chatRepository.sendCallSignal(
                                type = "ringing",
                                callId = event.callId,
                                receiverId = event.callerId,
                                isVideo = event.isVideo
                            )
                        }
                    }
                    is SignalingEvent.ChatMessageReceived -> {
                        if (!event.dto.senderId.equals(myId, ignoreCase = true) && !app.isUiInForeground) {
                            val senderUser = app.database.userDao().getUser(event.dto.senderId)
                            val senderName = senderUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: event.dto.senderName?.takeIf { it.isNotBlank() }
                                ?: event.dto.senderId
                            val preview = when (event.dto.type.uppercase()) {
                                "IMAGE" -> if (event.dto.content.isNotBlank()) "Photo: ${event.dto.content}" else "Sent a photo"
                                "VIDEO" -> event.dto.fileName?.let { "Video: $it" } ?: "Sent a video"
                                "AUDIO" -> event.dto.fileName?.let { "Audio: $it" } ?: "Sent an audio clip"
                                "DOCUMENT" -> event.dto.fileName ?: "Sent a document"
                                "LOCATION", "LIVE_LOCATION" -> event.dto.content.ifBlank { "Shared a GPS location" }
                                "CONTACT" -> "Shared a contact"
                                else -> event.dto.content.ifBlank { "New message" }
                            }
                            NotificationHelper.showIncomingMessageNotification(
                                context = this@SignalingForegroundService,
                                senderId = event.dto.senderId,
                                senderName = senderName,
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
