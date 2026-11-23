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
