package com.example.rtc

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.HelloSirApplication
import com.example.core.security.CallNotificationAction
import com.example.core.security.GlobalSecurityEventBus
import com.example.data.remote.SignalingEvent
import com.example.platform.ActiveCallService
import com.example.platform.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID

/**
 * Application-scoped WebRTC call session controller.
 * Lives across Activity destruction / Recents swipe so ActiveCallService keeps voice/video calls alive
 * and tapping the Active Call notification re-opens CallScreen seamlessly.
 */
class CallSessionController(
    private val app: HelloSirApplication
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = app.chatRepository
    private val signalingClient get() = app.signalingClient

    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _hasRemoteVideo = MutableStateFlow(false)
    val hasRemoteVideo: StateFlow<Boolean> = _hasRemoteVideo.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _isCallMinimized = MutableStateFlow(false)
    val isCallMinimized: StateFlow<Boolean> = _isCallMinimized.asStateFlow()

    private var webRTCClient: WebRTCClient? = null
    private var activeCallId: String = ""
    private var activePeerId: String = ""
    private var activePeerName: String = ""
    private var activeIsVideo: Boolean = false
    private var connectedSinceMs: Long = 0L
    private var hasAppliedRemoteAnswer: Boolean = false
    private var hasSentConnectedSignal: Boolean = false
    private var lastIceRestartAttemptMs: Long = 0L
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var attachedLocalRenderer: SurfaceViewRenderer? = null
    private var attachedRemoteRenderer: SurfaceViewRenderer? = null
    private var timerJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var networkCallbackRegistered = false
    private val connectivityManager by lazy {
        app.getSystemService(ConnectivityManager::class.java)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkTransition()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            handleNetworkTransition()
        }
    }

    init {
        observeCallSignaling()
        observeNotificationCallActions()
    }

    fun hasOngoingCall(): Boolean {
        return when (_callState.value) {
            is CallState.OutgoingRinging,
            is CallState.IncomingRinging,
            is CallState.Connecting,
            is CallState.Connected -> true
            CallState.Idle,
            is CallState.Ended -> false
        }
    }

    fun getConnectedSinceMs(): Long = connectedSinceMs

    fun minimizeCallScreen() {
        if (hasOngoingCall()) {
            _isCallMinimized.value = true
        }
    }

    fun expandCallScreen() {
        if (_callState.value !is CallState.Idle) {
            _isCallMinimized.value = false
            onAppResumed()
        }
    }

    private fun handleNetworkTransition() {
        if (!hasOngoingCall()) return
        scope.launch {
            signalingClient.connect(forceDuringCall = true)
            launch { repository.fetchIceServers() }
            val state = _callState.value
            val now = System.currentTimeMillis()
            if ((state is CallState.Connected || state is CallState.Connecting) &&
                (now - lastIceRestartAttemptMs) > 4_000L
            ) {
                lastIceRestartAttemptMs = now
                webRTCClient?.restartIce(isVideo = activeIsVideo)
            }
        }
    }

    private fun registerNetworkMonitor() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkMonitor() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
    }

    private fun observeNotificationCallActions() {
        scope.launch {
            GlobalSecurityEventBus.callActions.collect { action ->
                when (action) {
                    is CallNotificationAction.Decline -> {
                        if (_callState.value is CallState.IncomingRinging) {
                            declineIncomingCall()
                        } else {
                            NotificationHelper.stopRingtoneAndVibrate(app)
                        }
                    }
                    is CallNotificationAction.Answer -> {
                        injectIncomingCallFromIntent(
                            callId = action.callId,
                            callerId = action.callerId,
                            callerName = action.callerName,
                            isVideo = action.isVideo,
                            offerSdp = action.offerSdp
                        )
                        acceptIncomingCall()
                    }
                    CallNotificationAction.Hangup -> {
                        if (hasOngoingCall()) {
                            hangUp()
                        }
                    }
                    CallNotificationAction.ToggleMute -> {
                        if (hasOngoingCall()) {
                            toggleMute()
                        }
                    }
                }
            }
        }
    }

    private fun observeCallSignaling() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.IncomingCallOffer -> {
                        val effectiveCallId = event.callId.ifBlank { UUID.randomUUID().toString() }
                        val current = _callState.value
                        // Handle mid-call ICE restart re-offer seamlessly without re-ringing
                        if ((current is CallState.Connected || current is CallState.Connecting) &&
                            effectiveCallId == activeCallId &&
                            event.callerId.equals(activePeerId, ignoreCase = true)
                        ) {
                            webRTCClient?.handleRemoteOfferAndCreateAnswer(event.sdp, activeIsVideo)
                            drainPendingIce()
                            return@collect
                        }

                        val alreadyHandled = repository.isCallHandled(effectiveCallId)
                        if (!alreadyHandled && (current is CallState.Idle || current is CallState.Ended)) {
                            val resolvedCallerName = if (event.callerName.isBlank() || event.callerName == event.callerId) {
                                app.database.userDao().getUser(event.callerId)?.displayName ?: event.callerId
                            } else {
                                event.callerName
                            }
                            activeCallId = effectiveCallId
                            activePeerId = event.callerId
                            activePeerName = resolvedCallerName
                            activeIsVideo = event.isVideo
                            connectedSinceMs = 0L
                            _isCallMinimized.value = false
                            _isVideoEnabled.value = event.isVideo
                            _hasRemoteVideo.value = false
                            pendingRemoteIce.clear()
                            repository.setFastCallPolling(true)
                            NotificationHelper.startRingtoneAndVibrate(app)
                            _callState.value = CallState.IncomingRinging(
                                callId = effectiveCallId,
                                callerId = event.callerId,
                                callerName = resolvedCallerName,
                                isVideo = event.isVideo,
                                offerSdp = event.sdp
                            )
                            val myId = app.sessionManager.getCurrentSession()?.userId
                            if (myId != null) {
                                signalingClient.sendCallRinging(effectiveCallId, myId, event.callerId)
                                launch {
                                    repository.sendCallSignal(
                                        type = "ringing",
                                        callId = effectiveCallId,
                                        receiverId = event.callerId,
                                        isVideo = event.isVideo
                                    )
                                }
                            }
                        }
                    }
                    is SignalingEvent.CallRinging -> {
                        val current = _callState.value
                        val matchesCall = event.callId.isBlank() || activeCallId.isBlank() || event.callId == activeCallId
                        if (current is CallState.OutgoingRinging && matchesCall && !current.isRingingOnRemote) {
                            _callState.value = current.copy(isRingingOnRemote = true)
                        }
                    }
                    is SignalingEvent.CallAnswerReceived -> {
                        val current = _callState.value
                        val hasExplicitCallMatch = event.callId.isNotBlank() &&
                            activeCallId.isNotBlank() &&
                            event.callId == activeCallId
                        val currentPeerId = when (current) {
                            is CallState.OutgoingRinging -> current.peerId
                            is CallState.Connecting -> current.peerId
                            is CallState.Connected -> current.peerId
                            else -> activePeerId
                        }
                        val myId = app.sessionManager.getCurrentSession()?.userId
                        val isSelfSender = !myId.isNullOrBlank() && event.callerId.equals(myId, ignoreCase = true)
                        val matchesPeer = event.callerId.isBlank() ||
                            event.callerId.equals(currentPeerId, ignoreCase = true)
                        val matchesThisCall = hasExplicitCallMatch ||
                            ((event.callId.isBlank() || activeCallId.isBlank()) && matchesPeer && !isSelfSender)

                        if ((current is CallState.OutgoingRinging || current is CallState.Connecting || current is CallState.Connected) &&
                            matchesThisCall
                        ) {
                            NotificationHelper.stopRingtoneAndVibrate(app)
                            val peerId = if (current is CallState.OutgoingRinging) current.peerId else activePeerId
                            val peerName = if (current is CallState.OutgoingRinging) current.peerName else activePeerName
                            val isVid = if (current is CallState.OutgoingRinging) current.isVideo else activeIsVideo
                            if (current !is CallState.Connected) {
                                transitionToConnecting(peerId, peerName, isVid)
                            }
                            // Always accept fresh SDP answer (handles initial answer AND mid-call ICE restart answers)
                            if (event.sdp.isNotBlank() && (!hasAppliedRemoteAnswer || current is CallState.Connected)) {
                                hasAppliedRemoteAnswer = true
                                webRTCClient?.handleRemoteAnswer(event.sdp)
                            }
                            drainPendingIce()
                            syncRenderersToClient()
                        }
                    }
                    is SignalingEvent.CallConnected -> {
                        val current = _callState.value
                        val hasExplicitCallMatch = event.callId.isNotBlank() &&
                            activeCallId.isNotBlank() &&
                            event.callId == activeCallId
                        val currentPeerId = when (current) {
                            is CallState.OutgoingRinging -> current.peerId
                            is CallState.Connecting -> current.peerId
                            else -> activePeerId
                        }
                        val matchesPeer = event.peerId.isBlank() ||
                            event.peerId.equals(currentPeerId, ignoreCase = true)
                        val matchesThisCall = hasExplicitCallMatch ||
                            ((event.callId.isBlank() || activeCallId.isBlank()) && matchesPeer)

                        if ((current is CallState.OutgoingRinging || current is CallState.Connecting) &&
                            matchesThisCall
                        ) {
                            NotificationHelper.stopRingtoneAndVibrate(app)
                            val peerId = if (current is CallState.OutgoingRinging) current.peerId else activePeerId
                            val peerName = if (current is CallState.OutgoingRinging) current.peerName else activePeerName
                            val isVid = if (current is CallState.OutgoingRinging) current.isVideo else activeIsVideo
                            transitionToConnecting(peerId, peerName, isVid)
                            if (!hasAppliedRemoteAnswer && event.sdp.isNotBlank()) {
                                hasAppliedRemoteAnswer = true
                                webRTCClient?.handleRemoteAnswer(event.sdp)
                                drainPendingIce()
                                syncRenderersToClient()
                            } else if (!hasAppliedRemoteAnswer) {
                                launch { repository.pollIncomingCallsOnce(includeActiveCalls = true) }
                            }
                        }
                    }
                    is SignalingEvent.IceCandidateReceived -> {
                        val matchesCall = event.callId.isBlank() || activeCallId.isBlank() || event.callId == activeCallId
                        if (!matchesCall) return@collect
                        val candidate = IceCandidate(
                            event.sdpMid,
                            event.sdpMLineIndex,
                            event.candidate
                        )
                        if (webRTCClient != null) {
                            webRTCClient?.addRemoteIceCandidate(
                                event.sdpMid,
                                event.sdpMLineIndex,
                                event.candidate
                            )
                        } else {
                            pendingRemoteIce.add(candidate)
                        }
                    }
                    is SignalingEvent.CallEnded -> {
                        val matchesCall = event.callId.isBlank() || activeCallId.isBlank() || event.callId == activeCallId
                        val matchesPeer = event.peerId.isBlank() || event.peerId.equals(activePeerId, ignoreCase = true)
                        if (matchesCall && matchesPeer && _callState.value !is CallState.Idle) {
                            val label = if (event.reason.contains("decline", ignoreCase = true) ||
                                event.reason.contains("reject", ignoreCase = true)
                            ) {
                                "Call declined"
                            } else {
                                "Call ended"
                            }
                            endCallLocally(sendSignal = false, reason = label)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun startOutgoingCall(peerId: String, peerName: String, isVideo: Boolean) {
        scope.launch {
            app.sessionManager.getCurrentSession() ?: return@launch
            if (!signalingClient.isConnected.value) {
                signalingClient.connect(forceDuringCall = true)
            }
            activeCallId = UUID.randomUUID().toString()
            activePeerId = peerId
            activePeerName = peerName
            activeIsVideo = isVideo
            connectedSinceMs = 0L
            hasAppliedRemoteAnswer = false
            hasSentConnectedSignal = false
            lastIceRestartAttemptMs = 0L
            _isCallMinimized.value = false
            _isMuted.value = false
            _isSpeakerOn.value = isVideo
            _isVideoEnabled.value = isVideo
            _isFrontCamera.value = true
            _hasRemoteVideo.value = false
            pendingRemoteIce.clear()
            repository.setFastCallPolling(true)
            registerNetworkMonitor()

            _callState.value = CallState.OutgoingRinging(peerId, peerName, isVideo)
            NotificationHelper.startOutgoingRingback()

            // Open camera and local preview immediately without waiting for network ICE fetch
            val client = ensureWebRtcClient()
            client.prepareLocalMedia(isVideo = isVideo)
            syncRenderersToClient()

            startActiveCallForegroundService(peerName, isVideo, isConnected = false)

            val cachedIce = repository.getCachedIceServers()
            val iceServers = if (cachedIce.isNotEmpty()) {
                launch { repository.fetchIceServers() }
                cachedIce
            } else {
                withTimeoutOrNull(1_200L) { repository.fetchIceServers() }.orEmpty()
            }
            if (_callState.value !is CallState.OutgoingRinging) return@launch
            client.startPeerConnection(isVideo = isVideo, customIceServers = iceServers)
            syncRenderersToClient()
            client.createOffer(isVideo)
        }
    }

    fun acceptIncomingCall() {
        NotificationHelper.stopRingtoneAndVibrate(app)
        val incoming = _callState.value as? CallState.IncomingRinging ?: return

        scope.launch {
            if (!signalingClient.isConnected.value) {
                signalingClient.connect(forceDuringCall = true)
            }
            activeCallId = incoming.callId.ifBlank { activeCallId.ifBlank { UUID.randomUUID().toString() } }
            repository.markCallAnswered(activeCallId)
            activePeerId = incoming.callerId
            activePeerName = incoming.callerName
            activeIsVideo = incoming.isVideo
            connectedSinceMs = 0L
            hasAppliedRemoteAnswer = false
            hasSentConnectedSignal = false
            lastIceRestartAttemptMs = 0L
            _isCallMinimized.value = false
            _isMuted.value = false
            _isSpeakerOn.value = incoming.isVideo
            _isVideoEnabled.value = incoming.isVideo
            _isFrontCamera.value = true
            _hasRemoteVideo.value = false
            repository.setFastCallPolling(true)
            registerNetworkMonitor()

            // Transition to Connecting (NOT Connected) until WebRTC ICE actually establishes
            transitionToConnecting(incoming.callerId, incoming.callerName, incoming.isVideo)

            val client = ensureWebRtcClient()
            client.prepareLocalMedia(isVideo = incoming.isVideo)
            syncRenderersToClient()

            startActiveCallForegroundService(incoming.callerName, incoming.isVideo, isConnected = false)

            val cachedIce = repository.getCachedIceServers()
            val iceServers = if (cachedIce.isNotEmpty()) {
                launch { repository.fetchIceServers() }
                cachedIce
            } else {
                withTimeoutOrNull(1_200L) { repository.fetchIceServers() }.orEmpty()
            }
            if (_callState.value !is CallState.Connecting) return@launch
            client.startPeerConnection(isVideo = incoming.isVideo, customIceServers = iceServers)
            syncRenderersToClient()
            client.handleRemoteOfferAndCreateAnswer(incoming.offerSdp, incoming.isVideo)
            drainPendingIce()
        }
    }

    fun onAudioPermissionGranted() {
        webRTCClient?.ensureAudioActive(muted = _isMuted.value, speakerOn = _isSpeakerOn.value)
    }

    fun onCameraPermissionGranted() {
        if (!activeIsVideo) return
        val client = ensureWebRtcClient()
        if (client.ensureVideoCaptureStarted()) {
            _isVideoEnabled.value = true
            syncRenderersToClient()
            if (activePeerName.isNotBlank()) {
                startActiveCallForegroundService(
                    peerName = activePeerName,
                    isVideo = true,
                    isConnected = _callState.value is CallState.Connected
                )
            }
        }
    }

    fun onAppResumed() {
        if (activeIsVideo && _isVideoEnabled.value && _callState.value !is CallState.Idle && _callState.value !is CallState.Ended) {
            webRTCClient?.restartVideoCaptureIfActive()
            syncRenderersToClient()
        }
    }

    fun injectIncomingCallFromIntent(
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean,
        offerSdp: String
    ) {
        val cleanCallId = callId.ifBlank { UUID.randomUUID().toString() }
        val currentState = _callState.value
        if (activeCallId == cleanCallId &&
            (currentState is CallState.Connecting || currentState is CallState.Connected)
        ) {
            return
        }
        activeCallId = cleanCallId
        activePeerId = callerId
        activePeerName = callerName
        activeIsVideo = isVideo
        connectedSinceMs = 0L
        hasAppliedRemoteAnswer = false
        hasSentConnectedSignal = false
        _isCallMinimized.value = false
        _isVideoEnabled.value = isVideo
        _isSpeakerOn.value = isVideo
        _hasRemoteVideo.value = false
        repository.setFastCallPolling(true)
        _callState.value = CallState.IncomingRinging(
            callId = activeCallId,
            callerId = callerId,
            callerName = callerName,
            isVideo = isVideo,
            offerSdp = offerSdp
        )
    }

    fun declineIncomingCall() {
        val incoming = _callState.value as? CallState.IncomingRinging
        NotificationHelper.stopRingtoneAndVibrate(app)
        val peerId = incoming?.callerId ?: activePeerId
        val callIdToDecline = incoming?.callId?.ifBlank { activeCallId } ?: activeCallId
        if (callIdToDecline.isNotBlank()) {
            repository.markCallDismissed(callIdToDecline)
        }
        scope.launch {
            val myId = app.sessionManager.getCurrentSession()?.userId
            if (myId != null && peerId.isNotBlank()) {
                signalingClient.sendCallEnd(myId, peerId, isReject = true, callId = callIdToDecline)
                repository.sendCallSignal(
                    type = "decline",
                    callId = callIdToDecline.ifBlank { "decline_${System.currentTimeMillis()}" },
                    receiverId = peerId
                )
            }
        }
        endCallLocally(sendSignal = false, reason = "Declined")
    }

    fun hangUp() {
        endCallLocally(sendSignal = true, reason = "Call ended")
    }

    private fun endCallLocally(sendSignal: Boolean, reason: String) {
        NotificationHelper.stopRingtoneAndVibrate(app)
        timerJob?.cancel()
        timerJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
        unregisterNetworkMonitor()
        _callDurationSeconds.value = 0L
        _hasRemoteVideo.value = false
        _isFrontCamera.value = true
        _isCallMinimized.value = false
        connectedSinceMs = 0L
        repository.setFastCallPolling(false)

        val peerToNotify = activePeerId
        val callIdToNotify = activeCallId
        if (callIdToNotify.isNotBlank()) {
            repository.markCallDismissed(callIdToNotify)
        }

        if (sendSignal && peerToNotify.isNotBlank()) {
            scope.launch {
                val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                signalingClient.sendCallEnd(myId, peerToNotify, isReject = false, callId = callIdToNotify)
                repository.sendCallSignal(
                    type = "hangup",
                    callId = callIdToNotify.ifBlank { "hangup_${System.currentTimeMillis()}" },
                    receiverId = peerToNotify
                )
            }
        }

        runCatching { webRTCClient?.attachRenderers(null, null) }
        webRTCClient?.release()
        webRTCClient = null
        attachedLocalRenderer = null
        attachedRemoteRenderer = null
        stopActiveCallForegroundService()
        activeCallId = ""
        activePeerId = ""
        activePeerName = ""
        activeIsVideo = false
        hasAppliedRemoteAnswer = false
        hasSentConnectedSignal = false
        pendingRemoteIce.clear()
        _callState.value = CallState.Ended(reason)

        if (!app.isUiInForeground) {
            signalingClient.setAppForeground(false, keepSocketForCall = false)
        }

        scope.launch {
            delay(1_200L)
            if (_callState.value is CallState.Ended) {
                _callState.value = CallState.Idle
            }
        }
    }

    fun attachRenderers(
        local: SurfaceViewRenderer?,
        remote: SurfaceViewRenderer?,
        forceRebind: Boolean = false
    ) {
        attachedLocalRenderer = local
        attachedRemoteRenderer = remote
        syncRenderersToClient(forceRebind = forceRebind)
    }

    fun detachActivityRenderers() {
        attachedLocalRenderer = null
        attachedRemoteRenderer = null
        webRTCClient?.attachRenderers(null, null, forceRebind = false)
    }

    private fun syncRenderersToClient(forceRebind: Boolean = false) {
        val client = webRTCClient ?: return
        if (activeIsVideo) {
            client.attachRenderers(attachedLocalRenderer, attachedRemoteRenderer, forceRebind = forceRebind)
        } else {
            client.attachRenderers(null, null, forceRebind = forceRebind)
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        webRTCClient?.setMicMuted(next)
        if (activePeerName.isNotBlank() && hasOngoingCall()) {
            startActiveCallForegroundService(
                peerName = activePeerName,
                isVideo = activeIsVideo,
                isConnected = _callState.value is CallState.Connected
            )
        }
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        webRTCClient?.setSpeakerphoneOn(next)
    }

    fun toggleVideo() {
        val next = !_isVideoEnabled.value
        _isVideoEnabled.value = next
        webRTCClient?.setVideoEnabled(next)
        if (next) {
            syncRenderersToClient(forceRebind = true)
        }
    }

    fun switchCamera() {
        webRTCClient?.switchCamera()
    }

    private fun ensureWebRtcClient(): WebRTCClient {
        webRTCClient?.let { return it }
        val client = WebRTCClient(
            context = app,
            eglBase = eglBase,
            onLocalSdpCreated = { sdp ->
                scope.launch {
                    val session = app.sessionManager.getCurrentSession() ?: return@launch
                    val callIdToSend = activeCallId.ifBlank {
                        UUID.randomUUID().toString().also { activeCallId = it }
                    }
                    val targetPeerId = activePeerId
                    val isVideoCall = activeIsVideo
                    if (targetPeerId.isBlank()) return@launch

                    if (sdp.type == SessionDescription.Type.OFFER) {
                        signalingClient.sendCallOffer(
                            callId = callIdToSend,
                            senderId = session.userId,
                            senderName = session.displayName,
                            receiverId = targetPeerId,
                            isVideo = isVideoCall,
                            sdp = sdp.description
                        )
                        repository.sendCallSignal(
                            type = "offer",
                            callId = callIdToSend,
                            receiverId = targetPeerId,
                            isVideo = isVideoCall,
                            sdp = sdp.description
                        )
                    } else if (sdp.type == SessionDescription.Type.ANSWER) {
                        signalingClient.sendCallAnswer(
                            senderId = session.userId,
                            receiverId = targetPeerId,
                            sdp = sdp.description,
                            callId = callIdToSend
                        )
                        repository.sendCallSignal(
                            type = "answer",
                            callId = callIdToSend,
                            receiverId = targetPeerId,
                            isVideo = isVideoCall,
                            sdp = sdp.description
                        )
                    }
                }
            },
            onIceCandidateGenerated = { candidate ->
                scope.launch {
                    val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                    val targetPeerId = activePeerId
                    val callIdToSend = activeCallId
                    if (targetPeerId.isNotBlank()) {
                        signalingClient.sendIceCandidate(
                            senderId = myId,
                            receiverId = targetPeerId,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.sdp,
                            callId = callIdToSend
                        )
                        if (callIdToSend.isNotBlank()) {
                            repository.sendCallSignal(
                                type = "ice",
                                callId = callIdToSend,
                                receiverId = targetPeerId,
                                candidate = candidate.sdp,
                                sdpMid = candidate.sdpMid,
                                sdpMLineIndex = candidate.sdpMLineIndex
                            )
                        }
                    }
                }
            },
            onConnectionStateChanged = { iceState ->
                when (iceState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = null
                        connectTimeoutJob?.cancel()
                        connectTimeoutJob = null
                        syncRenderersToClient()
                        if (activePeerId.isNotBlank()) {
                            transitionToConnected(activePeerId, activePeerName, activeIsVideo)
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (hasOngoingCall()) {
                            val now = System.currentTimeMillis()
                            if ((now - lastIceRestartAttemptMs) > 3_000L) {
                                lastIceRestartAttemptMs = now
                                webRTCClient?.restartIce(activeIsVideo)
                            } else {
                                endCallLocally(sendSignal = false, reason = "Connection failed")
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        if (_callState.value !is CallState.Idle && _callState.value !is CallState.Ended) {
                            endCallLocally(sendSignal = false, reason = "Call ended")
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = scope.launch {
                            delay(2_000L)
                            if (hasOngoingCall()) {
                                val now = System.currentTimeMillis()
                                if ((now - lastIceRestartAttemptMs) > 4_000L) {
                                    lastIceRestartAttemptMs = now
                                    signalingClient.connect(forceDuringCall = true)
                                    webRTCClient?.restartIce(activeIsVideo)
                                }
                            }
                            delay(8_000L)
                            if (_callState.value is CallState.Connected || _callState.value is CallState.Connecting) {
                                endCallLocally(sendSignal = false, reason = "Connection lost")
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onPeerConnectionStateChanged = { pcState ->
                when (pcState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = null
                        connectTimeoutJob?.cancel()
                        connectTimeoutJob = null
                        syncRenderersToClient()
                        if (activePeerId.isNotBlank()) {
                            transitionToConnected(activePeerId, activePeerName, activeIsVideo)
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        if (hasOngoingCall()) {
                            val now = System.currentTimeMillis()
                            if ((now - lastIceRestartAttemptMs) > 3_000L) {
                                lastIceRestartAttemptMs = now
                                webRTCClient?.restartIce(activeIsVideo)
                            } else {
                                endCallLocally(sendSignal = false, reason = "Connection failed")
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onRemoteVideoTrack = {
                _hasRemoteVideo.value = true
                syncRenderersToClient()
            },
            onCameraFacingChanged = { isFront ->
                _isFrontCamera.value = isFront
            }
        )
        webRTCClient = client
        return client
    }

    private fun drainPendingIce() {
        val copy = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        copy.forEach { c ->
            webRTCClient?.addRemoteIceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
        }
    }

    private fun transitionToConnecting(peerId: String, peerName: String, isVideo: Boolean) {
        NotificationHelper.stopRingtoneAndVibrate(app)
        if (_callState.value is CallState.Connected || _callState.value is CallState.Connecting) return
        _callState.value = CallState.Connecting(peerId, peerName, isVideo)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(25_000L)
            if (_callState.value is CallState.Connecting) {
                endCallLocally(sendSignal = true, reason = "Connection timed out")
            }
        }
    }

    private fun transitionToConnected(peerId: String, peerName: String, isVideo: Boolean) {
        NotificationHelper.stopRingtoneAndVibrate(app)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        if (_callState.value is CallState.Connected) return
        connectedSinceMs = System.currentTimeMillis()
        _callState.value = CallState.Connected(peerId, peerName, isVideo)
        webRTCClient?.ensureAudioActive(muted = _isMuted.value, speakerOn = _isSpeakerOn.value)
        if (isVideo && _isVideoEnabled.value) {
            webRTCClient?.restartVideoCaptureIfActive()
        }
        startActiveCallForegroundService(peerName, isVideo, isConnected = true)
        if (!hasSentConnectedSignal && activeCallId.isNotBlank() && peerId.isNotBlank()) {
            hasSentConnectedSignal = true
            val callIdSnapshot = activeCallId
            scope.launch {
                val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                signalingClient.sendCallConnected(callIdSnapshot, myId, peerId)
                repository.sendCallSignal(
                    type = "connected",
                    callId = callIdSnapshot,
                    receiverId = peerId,
                    isVideo = isVideo
                )
            }
        }
        timerJob?.cancel()
        timerJob = scope.launch {
            _callDurationSeconds.value = 0L
            while (true) {
                delay(1_000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    fun refreshActiveCallNotificationIfOngoing() {
        if (hasOngoingCall() && activePeerName.isNotBlank()) {
            startActiveCallForegroundService(
                peerName = activePeerName,
                isVideo = activeIsVideo,
                isConnected = _callState.value is CallState.Connected
            )
        }
    }

    private fun startActiveCallForegroundService(
        peerName: String,
        isVideo: Boolean,
        isConnected: Boolean
    ) {
        val intent = Intent(app, ActiveCallService::class.java).apply {
            putExtra(NotificationHelper.EXTRA_PEER_NAME, peerName)
            putExtra(NotificationHelper.EXTRA_IS_VIDEO, isVideo)
            putExtra(NotificationHelper.EXTRA_IS_CONNECTED, isConnected)
            putExtra(
                NotificationHelper.EXTRA_CONNECTED_SINCE_MS,
                if (isConnected && connectedSinceMs > 0L) connectedSinceMs else System.currentTimeMillis()
            )
            putExtra(NotificationHelper.EXTRA_IS_MUTED, _isMuted.value)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { app.startForegroundService(intent) }
        } else {
            runCatching { app.startService(intent) }
        }
    }

    private fun stopActiveCallForegroundService() {
        runCatching { app.stopService(Intent(app, ActiveCallService::class.java)) }
    }
}
