package com.example

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.core.security.SessionManager
import com.example.data.local.AppDatabase
import com.example.data.remote.NetworkModule
import com.example.data.remote.SignalingClient
import com.example.data.repository.ChatRepository
import com.example.platform.ContactSyncHelper
import com.example.platform.LocationHelper
import com.example.platform.NotificationHelper
import com.example.platform.SignalingForegroundService
import com.example.rtc.CallSessionController

class HelloSirApplication : Application(), ImageLoaderFactory {

    lateinit var sessionManager: SessionManager
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var signalingClient: SignalingClient
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var callController: CallSessionController
        private set
    lateinit var contactSyncHelper: ContactSyncHelper
        private set
    lateinit var locationHelper: LocationHelper
        private set

    @Volatile
    var isUiInForeground: Boolean = false

    @Volatile
    var activeChatPeerId: String? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isUiInForeground = false
                    val hasOngoingCall = ::callController.isInitialized && callController.hasOngoingCall()
                    signalingClient.setAppForeground(false, keepSocketForCall = hasOngoingCall)
                    chatRepository.markLocalSelfPresence(false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)

        sessionManager = SessionManager(this)
        database = AppDatabase.getInstance(this)

        val moshi = NetworkModule.provideMoshi()
        val okHttpClient = NetworkModule.provideOkHttpClient(sessionManager)
        val mediaHttpClient = NetworkModule.provideImageOkHttpClient(sessionManager)
        val apiService = NetworkModule.provideApiService(okHttpClient, moshi)

        signalingClient = SignalingClient(okHttpClient, sessionManager)
        chatRepository = ChatRepository(
            context = this,
            database = database,
            apiService = apiService,
            signalingClient = signalingClient,
            sessionManager = sessionManager,
            mediaHttpClient = mediaHttpClient
        )
        callController = CallSessionController(this)
        contactSyncHelper = ContactSyncHelper(this)
        locationHelper = LocationHelper(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, filter)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { NetworkModule.provideImageOkHttpClient(sessionManager) }
            .crossfade(true)
            .build()
    }

    fun startBackgroundSignalingService() {
        val intent = Intent(this, SignalingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { startForegroundService(intent) }
        } else {
            runCatching { startService(intent) }
        }
    }

    fun stopBackgroundSignalingService() {
        runCatching { stopService(Intent(this, SignalingForegroundService::class.java)) }
    }
}
