package com.example.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.HelloSirApplication
import com.example.data.remote.SignalingEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground relay service polling incoming calls and messages via HTTP
 * whenever the UI is in background (so WebSocket can stay closed for strict offline presence)
 * and raising incoming call notifications immediately.
 */
class SignalingForegroundService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        // Prevent uncaught background relay exceptions from crashing app process
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    @Volatile
    private var isForegroundActive = false

    private fun ensureForegroundStarted(): Boolean {
        if (isForegroundActive) return true
        NotificationHelper.createChannels(this)
        val notification = runCatching {
            NotificationHelper.buildSyncServiceNotification(this)
        }.getOrNull() ?: return false

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_SYNC_ID,
                notification,
                fgsType
            )
            true
        }.getOrDefault(false)
        isForegroundActive = started
        return started
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { NotificationHelper.createChannels(this) }
        runCatching { ShayariManager.scheduleDailyMorningNotification(this) }
        if (!ensureForegroundStarted()) {
            runCatching { stopSelf() }
            return
        }

        val app = application as? HelloSirApplication ?: run {
            runCatching { stopSelf() }
            return
        }

        // Listen for incoming call offers dispatched from either WS (if connected) or background HTTP poll
        serviceScope.launch {
            app.signalingClient.events
                .catch { /* keep service alive */ }
                .collect { event ->
                    runCatching {
                        val myId = app.sessionManager.getCurrentSession()?.userId ?: return@runCatching
                        when (event) {
                            is SignalingEvent.IncomingCallOffer -> {
                                if (!app.isUiInForeground && !app.chatRepository.isCallHandled(event.callId)) {
                                    val callerUser = runCatching {
                                        app.database.userDao().getUser(event.callerId)
                                    }.getOrNull()
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

        // Background HTTP poll loop when UI is NOT in foreground (so user stays strictly Offline while still getting alerts)
        serviceScope.launch {
            var tick = 0
            while (isActive) {
                runCatching {
                    val session = app.sessionManager.getCurrentSession()
                    if (session != null && !app.isUiInForeground) {
                        tick++
                        if (tick == 1 || tick % 3 == 0) {
                            runCatching { app.chatRepository.syncUserDirectory() }
                        }
                        runCatching { app.chatRepository.pollBackgroundMessagesForNotifications(session.userId) }
                    }
                }
                delay(2_500L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureForegroundStarted()) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        val app = application as? HelloSirApplication ?: return START_NOT_STICKY
        runCatching {
            if (app.isUiInForeground) {
                app.signalingClient.setAppForeground(true)
            } else if (app.callController.hasOngoingCall()) {
                app.signalingClient.setAppForeground(false, keepSocketForCall = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isForegroundActive = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
