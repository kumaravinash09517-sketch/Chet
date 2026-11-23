package com.example

import android.Manifest
import android.app.KeyguardManager
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.IncomingMessageAlert
import com.example.platform.NotificationHelper
import com.example.platform.ShayariManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PendingChatDeepLink(
    val peerId: String,
    val peerName: String
)

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val callViewModel: CallViewModel by viewModels()
    private val _pendingChatDeepLink = MutableStateFlow<PendingChatDeepLink?>(null)
    private val pendingChatDeepLink: StateFlow<PendingChatDeepLink?> = _pendingChatDeepLink.asStateFlow()

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Granted or denied handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShayariManager.scheduleDailyMorningNotification(this)
        requestNotificationPermissionIfNeeded()
        handleDeepLinkIntent(intent)

        setContent {
            HelloSirTheme {
                HelloSirRoot(
                    mainViewModel = mainViewModel,
                    callViewModel = callViewModel,
                    pendingChatDeepLinkFlow = pendingChatDeepLink,
                    onConsumeChatDeepLink = { _pendingChatDeepLink.value = null },
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

    override fun onStart() {
        super.onStart()
        val app = application as HelloSirApplication
        app.isUiInForeground = true
        app.signalingClient.setAppForeground(true)
        app.chatRepository.markLocalSelfPresence(true)
    }

    override fun onResume() {
        super.onResume()
        val app = application as HelloSirApplication
        app.isUiInForeground = true
        app.signalingClient.setAppForeground(true)
        app.chatRepository.markLocalSelfPresence(true)
        callViewModel.onAppResumed()
        lifecycleScope.launch {
            app.chatRepository.syncUserDirectory()
            app.chatRepository.pollIncomingCallsOnce(includeActiveCalls = true)
        }
    }

    override fun onPause() {
        (application as HelloSirApplication).isUiInForeground = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            val app = application as HelloSirApplication
            app.isUiInForeground = false
            app.signalingClient.setAppForeground(false)
            app.chatRepository.markLocalSelfPresence(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun enableCallLockscreenDisplay(dismissKeyguard: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            if (dismissKeyguard) {
                val km = getSystemService(KeyguardManager::class.java)
                if (km != null && km.isKeyguardLocked) {
                    runCatching { km.requestDismissKeyguard(this, null) }
                }
            }
        }
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_CALL, false)) {
            val autoAccept = intent.getBooleanExtra(NotificationHelper.EXTRA_AUTO_ACCEPT, false)
            if (autoAccept) {
                NotificationHelper.stopRingtoneAndVibrate(this)
            }
            enableCallLockscreenDisplay(dismissKeyguard = autoAccept)
            val callId = intent.getStringExtra(NotificationHelper.EXTRA_CALL_ID) ?: return
            val callerId = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_ID) ?: return
            val callerName = intent.getStringExtra(NotificationHelper.EXTRA_CALLER_NAME) ?: callerId
            val isVideo = intent.getBooleanExtra(NotificationHelper.EXTRA_IS_VIDEO, false)
            val sdp = intent.getStringExtra(NotificationHelper.EXTRA_OFFER_SDP) ?: return
            if (autoAccept) {
                (application as HelloSirApplication).chatRepository.markCallAnswered(callId)
            }
            callViewModel.injectIncomingCallFromIntent(callId, callerId, callerName, isVideo, sdp)
            if (autoAccept) {
                callViewModel.acceptIncomingCall()
            }
            return
        }

        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_CHAT, false)) {
            val peerId = intent.getStringExtra(NotificationHelper.EXTRA_PEER_ID)?.takeIf { it.isNotBlank() } ?: return
            val peerName = intent.getStringExtra(NotificationHelper.EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() } ?: peerId
            _pendingChatDeepLink.value = PendingChatDeepLink(peerId = peerId, peerName = peerName)
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
    pendingChatDeepLinkFlow: StateFlow<PendingChatDeepLink?>,
    onConsumeChatDeepLink: () -> Unit,
    onShowBlockedToast: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? HelloSirApplication
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
    val isSearchingUsers by mainViewModel.isSearchingUsers.collectAsStateWithLifecycle()
    val serverStatus by mainViewModel.serverStatus.collectAsStateWithLifecycle()
    val wsConnected by mainViewModel.wsConnected.collectAsStateWithLifecycle()
    val loginFlowState by mainViewModel.loginFlowState.collectAsStateWithLifecycle()
    val isBlocked by mainViewModel.isBlockedByAdmin.collectAsStateWithLifecycle()
    val currentShayari by mainViewModel.currentShayari.collectAsStateWithLifecycle()
    val callState by callViewModel.callState.collectAsStateWithLifecycle()
    val pendingChatDeepLink by pendingChatDeepLinkFlow.collectAsStateWithLifecycle()

    var activeInAppBanner by remember { mutableStateOf<IncomingMessageAlert?>(null) }

    LaunchedEffect(Unit) {
        mainViewModel.incomingMessageAlerts.collect { alert ->
            if (app?.activeChatPeerId != alert.peerId && session != null) {
                activeInAppBanner = alert
            }
        }
    }

    LaunchedEffect(activeInAppBanner?.messageId, activeInAppBanner?.timestamp) {
        if (activeInAppBanner != null) {
            delay(4_500L)
            activeInAppBanner = null
        }
    }

    LaunchedEffect(isBlocked) {
        if (isBlocked) {
            onShowBlockedToast()
        }
    }

    LaunchedEffect(session, pendingChatDeepLink, callState) {
        val target = pendingChatDeepLink
        if (session != null && target != null && callState is CallState.Idle) {
            mainViewModel.openConversation(target.peerId, target.peerName)
            navController.navigate(ChatRoute(target.peerId, target.peerName)) {
                launchSingleTop = true
            }
            onConsumeChatDeepLink()
        }
    }

    if (!authResolved) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Active / Incoming Call Overlay takes precedence over navigation stack
        if (callState !is CallState.Idle) {
            val isMuted by callViewModel.isMuted.collectAsStateWithLifecycle()
            val isSpeaker by callViewModel.isSpeakerOn.collectAsStateWithLifecycle()
            val isVideoEnabled by callViewModel.isVideoEnabled.collectAsStateWithLifecycle()
            val isFrontCamera by callViewModel.isFrontCamera.collectAsStateWithLifecycle()
            val hasRemoteVideo by callViewModel.hasRemoteVideo.collectAsStateWithLifecycle()
            val duration by callViewModel.callDurationSeconds.collectAsStateWithLifecycle()

            CallScreen(
                callState = callState,
                eglBaseContext = callViewModel.eglBaseContext,
                isMuted = isMuted,
                isSpeakerOn = isSpeaker,
                isVideoEnabled = isVideoEnabled,
                isFrontCamera = isFrontCamera,
                hasRemoteVideo = hasRemoteVideo,
                durationSeconds = duration,
                onAttachRenderers = { local, remote, forceRebind ->
                    callViewModel.attachRenderers(local, remote, forceRebind)
                },
                onAudioPermissionGranted = { callViewModel.onAudioPermissionGranted() },
                onCameraPermissionGranted = { callViewModel.onCameraPermissionGranted() },
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
                        onPickAvatarPhoto = { uri -> mainViewModel.uploadProfilePhoto(uri) },
                        onSkipAvatarPhoto = { mainViewModel.clearProfilePhoto() },
                        onSubmitDirectLogin = { username, userId, avatarUrl ->
                            mainViewModel.loginDirect(username, userId, avatarUrl)
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
                            isSearchingUsers = isSearchingUsers,
                            serverStatus = serverStatus,
                            wsConnected = wsConnected,
                            currentShayari = currentShayari,
                            onNextShayari = { mainViewModel.rotateDailyShayari() },
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
                    val peerUser by chatViewModel.peerUser.collectAsStateWithLifecycle()
                    val myUserId by chatViewModel.myUserId.collectAsStateWithLifecycle()
                    val isPeerTyping by chatViewModel.isPeerTyping.collectAsStateWithLifecycle()
                    val liveLocId by chatViewModel.liveLocationMessageId.collectAsStateWithLifecycle()
                    val statusBanner by chatViewModel.statusBanner.collectAsStateWithLifecycle()

                    ChatScreen(
                        peerId = route.peerId,
                        peerName = route.peerName,
                        peerIsOnline = peerUser?.isOnline == true,
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
                        onEnsureVideoDownloaded = { msg -> chatViewModel.ensureVideoDownloaded(msg) },
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

        // In-App Message Notification Banner with Sender Name + Headline
        AnimatedVisibility(
            visible = activeInAppBanner != null && session != null && callState is CallState.Idle,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            activeInAppBanner?.let { banner ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            activeInAppBanner = null
                            mainViewModel.openConversation(banner.peerId, banner.senderName)
                            navController.navigate(ChatRoute(banner.peerId, banner.senderName)) {
                                launchSingleTop = true
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = banner.senderName.take(1).uppercase().ifBlank { "?" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = NotificationHelper.formatSenderTitle(banner.senderName, banner.peerId),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = banner.headline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { activeInAppBanner = null }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss notification",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
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
