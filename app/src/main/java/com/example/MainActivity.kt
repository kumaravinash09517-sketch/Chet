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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
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
        runCatching { ShayariManager.scheduleDailyMorningNotification(this) }
        runCatching { requestNotificationPermissionIfNeeded() }
        runCatching { handleDeepLinkIntent(intent) }

        setContent {
            HelloSirTheme {
                HelloSirRoot(
                    mainViewModel = mainViewModel,
                    callViewModel = callViewModel,
                    pendingChatDeepLinkFlow = pendingChatDeepLink,
                    onConsumeChatDeepLink = { _pendingChatDeepLink.value = null },
                    onShowBlockedToast = {
                        runCatching {
                            Toast.makeText(
                                this,
                                getString(R.string.blocked_message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as? HelloSirApplication ?: return
        app.isUiInForeground = true
        runCatching { app.chatRepository.markLocalSelfPresence(true) }
        runCatching { app.signalingClient.setAppForeground(true) }
    }

    override fun onResume() {
        super.onResume()
        val app = application as? HelloSirApplication ?: return
        app.isUiInForeground = true
        runCatching { app.chatRepository.markLocalSelfPresence(true) }
        runCatching { app.signalingClient.setAppForeground(true) }
        runCatching { callViewModel.onAppResumed() }
        lifecycleScope.launch {
            runCatching { app.chatRepository.syncUserDirectory() }
            runCatching { app.chatRepository.pollIncomingCallsOnce(includeActiveCalls = true) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            val app = application as? HelloSirApplication ?: return
            app.isUiInForeground = false
            val keepSocket = runCatching { app.callController.hasOngoingCall() }.getOrDefault(false)
            runCatching { app.signalingClient.setAppForeground(false, keepSocketForCall = keepSocket) }
            runCatching { app.chatRepository.markLocalSelfPresence(false) }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && isFinishing) {
            val app = application as? HelloSirApplication
            if (app != null) {
                app.isUiInForeground = false
                val keepSocket = runCatching { app.callController.hasOngoingCall() }.getOrDefault(false)
                runCatching { app.signalingClient.setAppForeground(false, keepSocketForCall = keepSocket) }
                runCatching { app.chatRepository.markLocalSelfPresence(false) }
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { handleDeepLinkIntent(intent) }
    }

    private fun enableCallLockscreenDisplay(dismissKeyguard: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
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
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_REOPEN_CALL, false)) {
            enableCallLockscreenDisplay(dismissKeyguard = false)
            callViewModel.expandCall()
            return
        }
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
                (application as? HelloSirApplication)?.chatRepository?.markCallAnswered(callId)
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
    val activeStatuses by mainViewModel.activeStatuses.collectAsStateWithLifecycle()
    val isPostingStatus by mainViewModel.isPostingStatus.collectAsStateWithLifecycle()
    val isSavingProfile by mainViewModel.isSavingProfile.collectAsStateWithLifecycle()
    val profileFeedback by mainViewModel.profileFeedbackMessage.collectAsStateWithLifecycle()
    val callState by callViewModel.callState.collectAsStateWithLifecycle()
    val isCallMinimized by callViewModel.isCallMinimized.collectAsStateWithLifecycle()
    val callDurationSeconds by callViewModel.callDurationSeconds.collectAsStateWithLifecycle()
    val pendingChatDeepLink by pendingChatDeepLinkFlow.collectAsStateWithLifecycle()
    val showFullCallOverlay = callState !is CallState.Idle && !isCallMinimized

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

    if (!authResolved) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(authResolved, session, pendingChatDeepLink, showFullCallOverlay) {
        val target = pendingChatDeepLink
        if (authResolved && session != null && target != null && !showFullCallOverlay) {
            mainViewModel.openConversation(target.peerId, target.peerName)
            runCatching {
                navController.navigate(ChatRoute(target.peerId, target.peerName)) {
                    launchSingleTop = true
                }
            }
            onConsumeChatDeepLink()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Active / Incoming Call Overlay takes precedence over navigation stack unless minimized
        if (showFullCallOverlay) {
            val isMuted by callViewModel.isMuted.collectAsStateWithLifecycle()
            val isSpeaker by callViewModel.isSpeakerOn.collectAsStateWithLifecycle()
            val isVideoEnabled by callViewModel.isVideoEnabled.collectAsStateWithLifecycle()
            val isFrontCamera by callViewModel.isFrontCamera.collectAsStateWithLifecycle()
            val hasRemoteVideo by callViewModel.hasRemoteVideo.collectAsStateWithLifecycle()

            CallScreen(
                callState = callState,
                eglBaseContext = callViewModel.eglBaseContext,
                isMuted = isMuted,
                isSpeakerOn = isSpeaker,
                isVideoEnabled = isVideoEnabled,
                isFrontCamera = isFrontCamera,
                hasRemoteVideo = hasRemoteVideo,
                durationSeconds = callDurationSeconds,
                onAttachRenderers = { local, remote, forceRebind ->
                    callViewModel.attachRenderers(local, remote, forceRebind)
                },
                onAudioPermissionGranted = { callViewModel.onAudioPermissionGranted() },
                onCameraPermissionGranted = { callViewModel.onCameraPermissionGranted() },
                onAcceptIncoming = { callViewModel.acceptIncomingCall() },
                onDeclineIncoming = { callViewModel.declineIncomingCall() },
                onHangUp = { callViewModel.hangUp() },
                onMinimizeCall = { callViewModel.minimizeCall() },
                onToggleMute = { callViewModel.toggleMute() },
                onToggleSpeaker = { callViewModel.toggleSpeaker() },
                onToggleVideo = { callViewModel.toggleVideo() },
                onSwitchCamera = { callViewModel.switchCamera() }
            )
        } else {
            val startDest: Any = remember(authResolved) { if (session != null) HomeRoute else LoginRoute }

            Column(modifier = Modifier.fillMaxSize()) {
                if (callState !is CallState.Idle && isCallMinimized) {
                    ActiveCallStatusBar(
                        callState = callState,
                        durationSeconds = callDurationSeconds,
                        onReopenCall = { callViewModel.expandCall() },
                        onHangUp = { callViewModel.hangUp() }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (callState !is CallState.Idle && isCallMinimized) {
                                Modifier.consumeWindowInsets(WindowInsets.statusBars)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = startDest
                    ) {
                composable<LoginRoute> {
                    LaunchedEffect(session) {
                        if (session != null) {
                            runCatching {
                                navController.navigate(HomeRoute) {
                                    popUpTo(LoginRoute) { inclusive = true }
                                    launchSingleTop = true
                                }
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
                            runCatching {
                                navController.navigate(LoginRoute) {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
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
                            statuses = activeStatuses,
                            isPostingStatus = isPostingStatus,
                            onPostTextStatus = { text, colorHex ->
                                mainViewModel.postTextStatus(text, colorHex)
                            },
                            onPostMediaStatus = { uri, isVideo, caption ->
                                mainViewModel.postMediaStatus(uri, isVideo, caption)
                            },
                            onMarkStatusViewed = { mainViewModel.markStatusViewed(it) },
                            onDeleteStatus = { mainViewModel.deleteStatus(it) },
                            onRefreshStatuses = { mainViewModel.refreshStatuses() },
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
                    val transferProgress by chatViewModel.mediaTransferProgress.collectAsStateWithLifecycle()
                    val peerUser by chatViewModel.peerUser.collectAsStateWithLifecycle()
                    val myUserId by chatViewModel.myUserId.collectAsStateWithLifecycle()
                    val isPeerTyping by chatViewModel.isPeerTyping.collectAsStateWithLifecycle()
                    val liveLocId by chatViewModel.liveLocationMessageId.collectAsStateWithLifecycle()
                    val statusBanner by chatViewModel.statusBanner.collectAsStateWithLifecycle()

                    ChatScreen(
                        peerId = route.peerId,
                        peerName = peerUser?.displayName?.ifBlank { route.peerName } ?: route.peerName,
                        peerIsOnline = peerUser?.isOnline == true,
                        peerPhone = peerUser?.phone.orEmpty(),
                        peerBio = peerUser?.bio.orEmpty(),
                        peerAvatarUrl = peerUser?.avatarUrl,
                        peerLastSeen = peerUser?.lastLoginFormatted.orEmpty(),
                        myUserId = myUserId,
                        messages = messages,
                        mediaTransferProgress = transferProgress,
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
                            isSavingProfile = isSavingProfile,
                            profileFeedback = profileFeedback,
                            onBack = { navController.popBackStack() },
                            onSaveProfile = { displayName, bio, pickedUri, removeAvatar ->
                                mainViewModel.updateUserProfile(displayName, bio, pickedUri, removeAvatar)
                            },
                            onClearProfileFeedback = { mainViewModel.clearProfileFeedback() },
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
            }
        }

        // In-App Message Notification Banner with Sender Name + Headline
        AnimatedVisibility(
            visible = activeInAppBanner != null && session != null && !showFullCallOverlay,
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
                            runCatching {
                                navController.navigate(ChatRoute(banner.peerId, banner.senderName)) {
                                    launchSingleTop = true
                                }
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

@Composable
private fun ActiveCallStatusBar(
    callState: CallState,
    durationSeconds: Long,
    onReopenCall: () -> Unit,
    onHangUp: () -> Unit
) {
    val peerName = when (callState) {
        is CallState.OutgoingRinging -> callState.peerName
        is CallState.IncomingRinging -> callState.callerName
        is CallState.Connecting -> callState.peerName
        is CallState.Connected -> callState.peerName
        else -> "Call"
    }
    val isVideo = when (callState) {
        is CallState.OutgoingRinging -> callState.isVideo
        is CallState.IncomingRinging -> callState.isVideo
        is CallState.Connecting -> callState.isVideo
        is CallState.Connected -> callState.isVideo
        else -> false
    }
    val statusSuffix = when (callState) {
        is CallState.Connected -> {
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
        is CallState.OutgoingRinging -> if (callState.isRingingOnRemote) "Ringing" else "Calling"
        is CallState.IncomingRinging -> "Incoming"
        is CallState.Connecting -> "Connecting"
        else -> ""
    }

    Surface(
        color = com.example.ui.theme.EmeraldPrimary,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReopenCall)
            .testTag("active_call_bar_reopen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                    contentDescription = "Tap to return to call",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$peerName • $statusSuffix",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Tap to return to call",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = com.example.ui.theme.CrimsonAlert,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("active_call_bar_hangup")
            ) {
                IconButton(onClick = onHangUp) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
