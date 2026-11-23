package com.example.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MorningShayariReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MORNING_SHAYARI = "com.example.ACTION_MORNING_SHAYARI"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ShayariManager.scheduleDailyMorningNotification(context)
            }
            ACTION_MORNING_SHAYARI -> {
                if (ShayariManager.shouldSendMorningNotificationToday(context)) {
                    val shayari = ShayariManager.shayariForCalendarDay()
                    NotificationHelper.createChannels(context)
                    NotificationHelper.showDailyShayariNotification(context, shayari)
                    ShayariManager.markMorningNotificationSent(context)
                }
            }
        }
    }
}
