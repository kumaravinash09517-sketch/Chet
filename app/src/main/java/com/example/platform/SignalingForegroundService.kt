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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground relay service polling incoming calls and messages via HTTP
 * whenever the UI is in background (so WebSocket can stay closed for strict offline presence)
 * and raising incoming call notifications immediately.
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

        // Listen for incoming call offers dispatched from either WS (if connected) or background HTTP poll
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

        // Background HTTP poll loop when UI is NOT in foreground (so user stays strictly Offline while still getting alerts)
        serviceScope.launch {
            var tick = 0
            while (isActive) {
                val session = app.sessionManager.getCurrentSession()
                if (session != null && !app.isUiInForeground) {
                    tick++
                    if (tick == 1 || tick % 3 == 0) {
                        runCatching { app.chatRepository.syncUserDirectory() }
                    }
                    runCatching { app.chatRepository.pollBackgroundMessagesForNotifications(session.userId) }
                }
                delay(2_500L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HelloSirApplication
        if (app.isUiInForeground) {
            app.signalingClient.setAppForeground(true)
        } else if (app.callController.hasOngoingCall()) {
            app.signalingClient.setAppForeground(false, keepSocketForCall = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
