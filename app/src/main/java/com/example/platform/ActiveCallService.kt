package com.example.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service active during an ongoing WebRTC voice/video call
 * to preserve microphone and camera capture when switching apps.
 */
class ActiveCallService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(NotificationHelper.EXTRA_PEER_NAME) ?: "Peer"
        val isVideo = intent?.getBooleanExtra(NotificationHelper.EXTRA_IS_VIDEO, false) ?: false

        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.buildActiveCallNotification(this, peerName, isVideo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification, type)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
