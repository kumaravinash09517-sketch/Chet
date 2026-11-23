package com.example.platform

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import com.example.HelloSirApplication
import com.example.core.security.CallNotificationAction
import com.example.core.security.GlobalSecurityEventBus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DECLINE_CALL = "com.example.ACTION_DECLINE_CALL"
        const val ACTION_REPLY_MESSAGE = "com.example.ACTION_REPLY_MESSAGE"
        const val ACTION_HANGUP_CALL = "com.example.ACTION_HANGUP_CALL"
        const val ACTION_TOGGLE_MUTE = "com.example.ACTION_TOGGLE_MUTE"
    }

    private val receiverExceptionHandler = CoroutineExceptionHandler { _, _ -> }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? HelloSirApplication ?: return
        when (intent.action) {
            ACTION_DECLINE_CALL -> {
                NotificationHelper.stopRingtoneAndVibrate(context)
                val callerId = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_ID) ?: return
                val callId = intent.getStringExtra(NotificationHelper.EXTRA_CALL_ID).orEmpty()
                if (callId.isNotBlank()) {
                    app.chatRepository.markCallDismissed(callId)
                }
                GlobalSecurityEventBus.emitCallAction(CallNotificationAction.Decline(callerId))
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO + receiverExceptionHandler).launch {
                    try {
                        val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                        runCatching { app.signalingClient.sendCallEnd(myId, callerId, isReject = true, callId = callId) }
                        runCatching {
                            app.chatRepository.sendCallSignal(
                                type = "decline",
                                callId = callId.ifBlank { "decline_${System.currentTimeMillis()}" },
                                receiverId = callerId
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_HANGUP_CALL -> {
                NotificationHelper.stopRingtoneAndVibrate(context)
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        if (app.callController.hasOngoingCall()) {
                            app.callController.hangUp()
                        } else {
                            context.stopService(Intent(context, ActiveCallService::class.java))
                        }
                    }
                }
            }
            ACTION_TOGGLE_MUTE -> {
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        if (app.callController.hasOngoingCall()) {
                            app.callController.toggleMute()
                        }
                    }
                }
            }
            ACTION_REPLY_MESSAGE -> {
                val results = RemoteInput.getResultsFromIntent(intent) ?: return
                val replyText = results.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString()
                    ?.trim()
                    .orEmpty()
                val peerId = intent.getStringExtra(NotificationHelper.EXTRA_PEER_ID) ?: return
                val peerName = intent.getStringExtra(NotificationHelper.EXTRA_PEER_NAME) ?: peerId
                if (replyText.isNotBlank()) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO + receiverExceptionHandler).launch {
                        try {
                            runCatching { app.chatRepository.sendTextMessage(peerId, peerName, replyText) }
                            runCatching {
                                val nm = context.getSystemService(NotificationManager::class.java)
                                nm?.cancel(NotificationHelper.messageNotificationIdFor(peerId))
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }
}
