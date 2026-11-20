package com.example.platform

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

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
            runCatching {
                startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification, type)
            }
        } else {
            runCatching {
                startForeground(NotificationHelper.NOTIFICATION_ACTIVE_CALL_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
