package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.security.GlobalSecurityEventBus
import com.example.platform.NotificationHelper
import com.example.rtc.CallState
import com.example.ui.navigation.CallRoute
import com.example.ui.navigation.ChatRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.LoginRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.theme.HelloSirTheme
import com.example.ui.viewmodel.CallViewModel
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val callViewModel: CallViewModel by viewModels()

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Granted or denied handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleDeepLinkIntent(intent)

        setContent {
            HelloSirTheme {
                HelloSirRoot(
                    mainViewModel = mainViewModel,
                    callViewModel = callViewModel,
                    onShowBlockedToast = {
                        Toast.makeText(
                            this,
                            getString(R.string.blocked_message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as HelloSirApplication).isUiInForeground = true
    }

    override fun onPause() {
        (application as HelloSirApplication).isUiInForeground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_CALL, false)) {
            val callId = intent.getStringExtra(NotificationHelper.EXTRA_CALL_ID) ?: return
            val callerId = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_ID) ?: return
            val callerName = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_NAME) ?: callerId
            val isVideo = intent.getBooleanExtra(NotificationHelper.EXTRA_IS_VIDEO, false)
            val sdp = intent.getStringExtra(NotificationHelper.EXTRA_OFFER_SDP) ?: return
            callViewModel.injectIncomingCallFromIntent(callId, callerId, callerName, isVideo, sdp)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun HelloSirRoot(
    mainViewModel: MainViewModel,
    callViewModel: CallViewModel,
    onShowBlockedToast: () -> Unit
) {
    val navController = rememberNavController()
    val authResolved by mainViewModel.authResolved.collectAsStateWithLifecycle()
    val session by mainViewModel.sessionState.collectAsStateWithLifecycle()
    val conversations by mainViewModel.conversations.collectAsStateWithLifecycle()
    val directoryUsers by mainViewModel.directoryUsers.collectAsStateWithLifecycle()
    val pickerContacts by mainViewModel.pickerContacts.collectAsStateWithLifecycle()
    val remoteSearchResults by mainViewModel.remoteSearchResults.collectAsStateWithLifecycle()
    val totalUnread by mainViewModel.totalUnreadCount.collectAsStateWithLifecycle()
    val searchQuery by mainViewModel.searchQuery.collectAsStateWithLifecycle()
    val isSyncing by mainViewModel.directorySyncing.collectAsStateWithLifecycle()
    val serverStatus by mainViewModel.serverStatus.collectAsStateWithLifecycle()
    val wsConnected by mainViewModel.wsConnected.collectAsStateWithLifecycle()
    val loginFlowState by mainViewModel.loginFlowState.collectAsStateWithLifecycle()
    val isBlocked by mainViewModel.isBlockedByAdmin.collectAsStateWithLifecycle()
    val callState by callViewModel.callState.collectAsStateWithLifecycle()

    LaunchedEffect(isBlocked) {
        if (isBlocked) {
            onShowBlockedToast()
        }
    }

    if (!authResolved) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Active / Incoming Call Overlay takes precedence over navigation stack
    if (callState !is CallState.Idle) {
        val isMuted by callViewModel.isMuted.collectAsStateWithLifecycle()
        val isSpeaker by callViewModel.isSpeakerOn.collectAsStateWithLifecycle()
        val isVideoEnabled by callViewModel.isVideoEnabled.collectAsStateWithLifecycle()
        val duration by callViewModel.callDurationSeconds.collectAsStateWithLifecycle()

        CallScreen(
            callState = callState,
            isMuted = isMuted,
            isSpeakerOn = isSpeaker,
            isVideoEnabled = isVideoEnabled,
            durationSeconds = duration,
            onAttachRenderers = { local, remote -> callViewModel.attachRenderers(local, remote) },
            onAcceptIncoming = { callViewModel.acceptIncomingCall() },
            onDeclineIncoming = { callViewModel.declineIncomingCall() },
            onHangUp = { callViewModel.hangUp() },
            onToggleMute = { callViewModel.toggleMute() },
            onToggleSpeaker = { callViewModel.toggleSpeaker() },
            onToggleVideo = { callViewModel.toggleVideo() },
            onSwitchCamera = { callViewModel.switchCamera() }
        )
    } else {
        val startDest: Any = if (session != null) HomeRoute else LoginRoute

        NavHost(
            navController = navController,
            startDestination = startDest
        ) {
            composable<LoginRoute> {
                LaunchedEffect(session) {
                    if (session != null) {
                        navController.navigate(HomeRoute) {
                            popUpTo(LoginRoute) { inclusive = true }
                        }
                    }
                }
                LoginScreen(
                    flowState = loginFlowState,
                    serverStatus = serverStatus,
                    onSendOtp = { phone -> mainViewModel.sendOtp(phone) },
                    onVerifyOtp = { otp -> mainViewModel.verifyOtp(otp) },
                    onChangePhone = { mainViewModel.resetLoginToPhoneStep() },
                    onPickAvatarPhoto = { uri -> mainViewModel.uploadProfilePhoto(uri) },
                    onSkipAvatarPhoto = { mainViewModel.clearProfilePhoto() },
                    onCompleteRegistration = { userId, displayName, phone, deviceId, avatarUrl ->
                        mainViewModel.registerAndLogin(userId, displayName, phone, deviceId, avatarUrl)
                    },
                    onClearError = { mainViewModel.clearLoginError() }
                )
            }

            composable<HomeRoute> {
                val activeSession = session
                if (activeSession == null) {
                    LaunchedEffect(Unit) {
                        navController.navigate(LoginRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                } else {
                    HomeScreen(
                        session = activeSession,
                        conversations = conversations,
                        directoryUsers = directoryUsers,
                        remoteSearchResults = remoteSearchResults,
                        pickerContacts = pickerContacts,
                        totalUnreadCount = totalUnread ?: 0,
                        searchQuery = searchQuery,
                        isDirectorySyncing = isSyncing,
                        serverStatus = serverStatus,
                        wsConnected = wsConnected,
                        hasContactPermission = { mainViewModel.hasContactPermission() },
                        onSearchQueryChange = { mainViewModel.updateSearchQuery(it) },
                        onRefreshDirectory = { mainViewModel.refreshDirectory() },
                        onLoadContactPicker = { mainViewModel.loadInAppContactPicker() },
                        onOpenChat = { peerId, peerName ->
                            mainViewModel.openConversation(peerId, peerName)
                            navController.navigate(ChatRoute(peerId, peerName))
                        },
                        onOpenProfileSettings = {
                            navController.navigate(SettingsRoute)
                        }
                    )
                }
            }

            composable<ChatRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoute>()
                val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

                LaunchedEffect(route.peerId) {
                    chatViewModel.bindConversation(route.peerId, route.peerName)
                }

                val messages by chatViewModel.messages.collectAsStateWithLifecycle()
                val myUserId by chatViewModel.myUserId.collectAsStateWithLifecycle()
                val isPeerTyping by chatViewModel.isPeerTyping.collectAsStateWithLifecycle()
                val liveLocId by chatViewModel.liveLocationMessageId.collectAsStateWithLifecycle()
                val statusBanner by chatViewModel.statusBanner.collectAsStateWithLifecycle()

                ChatScreen(
                    peerId = route.peerId,
                    peerName = route.peerName,
                    myUserId = myUserId,
                    messages = messages,
                    isPeerTyping = isPeerTyping,
                    liveLocationMessageId = liveLocId,
                    statusBanner = statusBanner,
                    onBack = { navController.popBackStack() },
                    onMarkRead = { chatViewModel.markRead() },
                    onComposerTextChanged = { chatViewModel.onComposerTextChanged(it) },
                    onSendText = { chatViewModel.sendText(it) },
                    onSendMedia = { uri, type -> chatViewModel.sendMedia(uri, type) },
                    onShareCurrentLocation = { chatViewModel.shareCurrentLocation() },
                    onStartLiveLocation = { chatViewModel.startLiveLocationSharing() },
                    onStopLiveLocation = { chatViewModel.stopLiveLocationSharing() },
                    onDeleteMessage = { chatViewModel.deleteMessage(it) },
                    onStartAudioCall = {
                        callViewModel.startOutgoingCall(route.peerId, route.peerName, isVideo = false)
                    },
                    onStartVideoCall = {
                        callViewModel.startOutgoingCall(route.peerId, route.peerName, isVideo = true)
                    }
                )
            }

            composable<SettingsRoute> {
                val activeSession = session
                if (activeSession != null) {
                    ProfileSettingsScreen(
                        session = activeSession,
                        isHardwareKeystoreActive = mainViewModel.isHardwareKeystoreActive(),
                        wsConnected = wsConnected,
                        onBack = { navController.popBackStack() },
                        onLogoutConfirmed = {
                            mainViewModel.logout {
                                navController.navigate(LoginRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    )
                }
            }

            composable<CallRoute> {
                // Direct call state handled by CallState overlay above
            }
        }
    }

    // Global HTTP 403 Forbidden Blocklist AlertDialog
    if (isBlocked) {
        AlertDialog(
            onDismissRequest = { GlobalSecurityEventBus.clearBlocked() },
            title = { Text("Access Restricted") },
            text = { Text("You are blocked by the administrator.") },
            confirmButton = {
                TextButton(onClick = { GlobalSecurityEventBus.clearBlocked() }) {
                    Text("OK")
                }
            }
        )
    }
}
