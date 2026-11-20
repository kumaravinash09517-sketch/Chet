package com.example.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {
    const val CHANNEL_CALLS = "hello_sir_calls"
    const val CHANNEL_MESSAGES = "hello_sir_messages"
    const val CHANNEL_SYNC = "hello_sir_sync"

    const val NOTIFICATION_SYNC_ID = 1001
    const val NOTIFICATION_CALL_ID = 2001
    const val NOTIFICATION_ACTIVE_CALL_ID = 2002

    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CALLER_ID = "extra_caller_id"
    const val EXTRA_CALLER_NAME = "extra_caller_name"
    const val EXTRA_IS_VIDEO = "extra_is_video"
    const val EXTRA_OFFER_SDP = "extra_offer_sdp"
    const val EXTRA_AUTO_ACCEPT = "extra_auto_accept"
    const val EXTRA_PEER_ID = "extra_peer_id"
    const val EXTRA_PEER_NAME = "extra_peer_name"
    const val EXTRA_OPEN_CALL = "extra_open_call"
    const val EXTRA_OPEN_CHAT = "extra_open_chat"
    const val KEY_TEXT_REPLY = "key_text_reply"

    private var activeRingtone: Ringtone? = null
    private var outgoingToneGenerator: ToneGenerator? = null

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val callAudio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen incoming audio & video call alerts"
                setSound(ringtoneUri, callAudio)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time incoming messages with inline reply"
                enableVibration(true)
            }

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps encrypted WebRTC & messaging connection active"
                setShowBadge(false)
            }

            manager.createNotificationChannel(callChannel)
            manager.createNotificationChannel(messageChannel)
            manager.createNotificationChannel(syncChannel)
        }
    }

    fun buildSyncServiceNotification(context: Context): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Hello Sir")
            .setContentText("Encrypted call & message relay active")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
    }

    fun buildActiveCallNotification(context: Context, peerName: String, isVideo: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val typeLabel = if (isVideo) "Video call" else "Voice call"
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("$typeLabel with $peerName")
            .setContentText("Tap to return to call")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openIntent)
            .build()
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean,
        offerSdp: String
    ) {
        if (!canPostNotifications(context)) return
        startRingtoneAndVibrate(context)

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CALL, true)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
            putExtra(EXTRA_OFFER_SDP, offerSdp)
        }

        val fullScreenPending = PendingIntent.getActivity(
            context,
            201,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CALL, true)
            putExtra(EXTRA_AUTO_ACCEPT, true)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
            putExtra(EXTRA_OFFER_SDP, offerSdp)
        }
        val answerPending = PendingIntent.getActivity(
            context,
            203,
            answerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val declineIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = CallNotificationReceiver.ACTION_DECLINE_CALL
            putExtra(EXTRA_CALLER_ID, callerId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context,
            202,
            declineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callTypeLabel = if (isVideo) "Incoming Video Call" else "Incoming Audio Call"

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName)
            .setContentText(callTypeLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePending)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_CALL_ID, notification)
    }

    fun showIncomingMessageNotification(
        context: Context,
        senderId: String,
        senderName: String,
        preview: String
    ) {
        if (!canPostNotifications(context)) return

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT, true)
            putExtra(EXTRA_PEER_ID, senderId)
            putExtra(EXTRA_PEER_NAME, senderName)
        }
        val openPending = PendingIntent.getActivity(
            context,
            senderId.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $senderName")
            .build()

        val replyIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = CallNotificationReceiver.ACTION_REPLY_MESSAGE
            putExtra(EXTRA_PEER_ID, senderId)
            putExtra(EXTRA_PEER_NAME, senderName)
        }
        val replyPending = PendingIntent.getBroadcast(
            context,
            senderId.hashCode() + 1,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPending
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(replyAction)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(senderId.hashCode(), notification)
    }

    fun startRingtoneAndVibrate(context: Context) {
        try {
            stopRingtoneAndVibrate(context)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
            val pattern = longArrayOf(0, 600, 400, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
            // Audio device may be unavailable on headless/emulator audio stacks
        }
    }

    fun stopRingtoneAndVibrate(context: Context) {
        stopOutgoingRingback()
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_CALL_ID)
    }

    fun startOutgoingRingback() {
        runCatching {
            stopOutgoingRingback()
            outgoingToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    fun stopOutgoingRingback() {
        runCatching {
            outgoingToneGenerator?.stopTone()
            outgoingToneGenerator?.release()
        }
        outgoingToneGenerator = null
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
