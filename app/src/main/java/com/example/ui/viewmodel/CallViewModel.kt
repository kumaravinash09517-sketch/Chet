package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.HelloSirApplication
import com.example.core.security.CallNotificationAction
import com.example.data.remote.SignalingEvent
import com.example.platform.ActiveCallService
import com.example.platform.NotificationHelper
import com.example.rtc.CallState
import com.example.rtc.WebRTCClient
import kotlinx.coroutines.Job
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

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HelloSirApplication
    private val repository = app.chatRepository
    private val signalingClient = app.signalingClient

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

    private var webRTCClient: WebRTCClient? = null
    private var activePeerId: String = ""
    private var activePeerName: String = ""
    private var activeIsVideo: Boolean = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var attachedLocalRenderer: SurfaceViewRenderer? = null
    private var attachedRemoteRenderer: SurfaceViewRenderer? = null
    private var timerJob: Job? = null

    init {
        observeCallSignaling()
        observeNotificationCallActions()
    }

    private fun observeNotificationCallActions() {
        viewModelScope.launch {
            com.example.core.security.GlobalSecurityEventBus.callActions.collect { action ->
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
                }
            }
        }
    }

    private fun observeCallSignaling() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.IncomingCallOffer -> {
                        if (_callState.value is CallState.Idle || _callState.value is CallState.Ended) {
                            val resolvedCallerName = if (event.callerName.isBlank() || event.callerName == event.callerId) {
                                app.database.userDao().getUser(event.callerId)?.displayName ?: event.callerId
                            } else {
                                event.callerName
                            }
                            activePeerId = event.callerId
                            activePeerName = resolvedCallerName
                            activeIsVideo = event.isVideo
                            _isVideoEnabled.value = event.isVideo
                            _hasRemoteVideo.value = false
                            pendingRemoteIce.clear()
                            NotificationHelper.startRingtoneAndVibrate(app)
                            _callState.value = CallState.IncomingRinging(
                                callId = event.callId,
                                callerId = event.callerId,
                                callerName = resolvedCallerName,
                                isVideo = event.isVideo,
                                offerSdp = event.sdp
                            )
                        }
                    }
                    is SignalingEvent.CallAnswerReceived -> {
                        val current = _callState.value
                        if (current is CallState.OutgoingRinging && event.callerId.equals(current.peerId, ignoreCase = true)) {
                            NotificationHelper.stopOutgoingRingback()
                            webRTCClient?.handleRemoteAnswer(event.sdp)
                            drainPendingIce()
                            syncRenderersToClient()
                            transitionToConnected(current.peerId, current.peerName, current.isVideo)
                        }
                    }
                    is SignalingEvent.IceCandidateReceived -> {
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
                        if (event.peerId.equals(activePeerId, ignoreCase = true) || activePeerId.isNotBlank()) {
                            endCallLocally(sendSignal = false, reason = "Call ended")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun startOutgoingCall(peerId: String, peerName: String, isVideo: Boolean) {
        viewModelScope.launch {
            app.sessionManager.getCurrentSession() ?: return@launch
            activePeerId = peerId
            activePeerName = peerName
            activeIsVideo = isVideo
            _isMuted.value = false
            _isSpeakerOn.value = isVideo
            _isVideoEnabled.value = isVideo
            _isFrontCamera.value = true
            _hasRemoteVideo.value = false
            pendingRemoteIce.clear()

            _callState.value = CallState.OutgoingRinging(peerId, peerName, isVideo)
            NotificationHelper.startOutgoingRingback()

            // Open camera and local preview immediately without waiting for network ICE fetch
            val client = ensureWebRtcClient()
            client.prepareLocalMedia(isVideo = isVideo)
            syncRenderersToClient()

            startActiveCallForegroundService(peerName, isVideo)

            val iceServers = withTimeoutOrNull(2_500L) { repository.fetchIceServers() }.orEmpty()
            if (_callState.value !is CallState.OutgoingRinging) return@launch
            client.startPeerConnection(isVideo = isVideo, customIceServers = iceServers)
            syncRenderersToClient()
            client.createOffer(isVideo)
        }
    }

    fun acceptIncomingCall() {
        val incoming = _callState.value as? CallState.IncomingRinging ?: return
        NotificationHelper.stopRingtoneAndVibrate(app)

        viewModelScope.launch {
            activePeerId = incoming.callerId
            activePeerName = incoming.callerName
            activeIsVideo = incoming.isVideo
            _isMuted.value = false
            _isSpeakerOn.value = incoming.isVideo
            _isVideoEnabled.value = incoming.isVideo
            _isFrontCamera.value = true
            _hasRemoteVideo.value = false

            // Transition immediately and open camera preview right away
            transitionToConnected(incoming.callerId, incoming.callerName, incoming.isVideo)

            val client = ensureWebRtcClient()
            client.prepareLocalMedia(isVideo = incoming.isVideo)
            syncRenderersToClient()

            startActiveCallForegroundService(incoming.callerName, incoming.isVideo)

            val iceServers = withTimeoutOrNull(2_500L) { repository.fetchIceServers() }.orEmpty()
            if (_callState.value !is CallState.Connected) return@launch
            client.startPeerConnection(isVideo = incoming.isVideo, customIceServers = iceServers)
            syncRenderersToClient()
            client.handleRemoteOfferAndCreateAnswer(incoming.offerSdp, incoming.isVideo)
            drainPendingIce()
        }
    }

    fun onCameraPermissionGranted() {
        if (!activeIsVideo) return
        val client = ensureWebRtcClient()
        if (client.ensureVideoCaptureStarted()) {
            _isVideoEnabled.value = true
            syncRenderersToClient()
            if (activePeerName.isNotBlank()) {
                startActiveCallForegroundService(activePeerName, isVideo = true)
            }
        }
    }

    fun injectIncomingCallFromIntent(
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean,
        offerSdp: String
    ) {
        activePeerId = callerId
        activePeerName = callerName
        activeIsVideo = isVideo
        _isVideoEnabled.value = isVideo
        _hasRemoteVideo.value = false
        _callState.value = CallState.IncomingRinging(
            callId = callId,
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
        viewModelScope.launch {
            val myId = app.sessionManager.getCurrentSession()?.userId
            if (myId != null && peerId.isNotBlank()) {
                signalingClient.sendCallEnd(myId, peerId, isReject = true)
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
        _callDurationSeconds.value = 0L
        _hasRemoteVideo.value = false
        _isFrontCamera.value = true

        if (sendSignal && activePeerId.isNotBlank()) {
            viewModelScope.launch {
                val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                signalingClient.sendCallEnd(myId, activePeerId, isReject = false)
            }
        }

        runCatching { webRTCClient?.attachRenderers(null, null) }
        webRTCClient?.release()
        webRTCClient = null
        attachedLocalRenderer = null
        attachedRemoteRenderer = null
        stopActiveCallForegroundService()
        activePeerId = ""
        activePeerName = ""
        activeIsVideo = false
        pendingRemoteIce.clear()
        _callState.value = CallState.Ended(reason)

        viewModelScope.launch {
            delay(1_200L)
            if (_callState.value is CallState.Ended) {
                _callState.value = CallState.Idle
            }
        }
    }

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        attachedLocalRenderer = local
        attachedRemoteRenderer = remote
        syncRenderersToClient()
    }

    private fun syncRenderersToClient() {
        val client = webRTCClient ?: return
        if (activeIsVideo) {
            client.attachRenderers(attachedLocalRenderer, attachedRemoteRenderer)
        } else {
            client.attachRenderers(null, null)
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        webRTCClient?.setMicMuted(next)
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
            syncRenderersToClient()
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
                viewModelScope.launch {
                    val session = app.sessionManager.getCurrentSession() ?: return@launch
                    if (sdp.type == SessionDescription.Type.OFFER) {
                        signalingClient.sendCallOffer(
                            callId = UUID.randomUUID().toString(),
                            senderId = session.userId,
                            senderName = session.displayName,
                            receiverId = activePeerId,
                            isVideo = activeIsVideo,
                            sdp = sdp.description
                        )
                    } else if (sdp.type == SessionDescription.Type.ANSWER) {
                        signalingClient.sendCallAnswer(
                            senderId = session.userId,
                            receiverId = activePeerId,
                            sdp = sdp.description
                        )
                    }
                }
            },
            onIceCandidateGenerated = { candidate ->
                viewModelScope.launch {
                    val myId = app.sessionManager.getCurrentSession()?.userId ?: return@launch
                    if (activePeerId.isNotBlank()) {
                        signalingClient.sendIceCandidate(
                            senderId = myId,
                            receiverId = activePeerId,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.sdp
                        )
                    }
                }
            },
            onConnectionStateChanged = { iceState ->
                if (iceState == PeerConnection.IceConnectionState.CONNECTED) {
                    syncRenderersToClient()
                    transitionToConnected(activePeerId, activePeerName, activeIsVideo)
                } else if (iceState == PeerConnection.IceConnectionState.FAILED ||
                    iceState == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    endCallLocally(sendSignal = false, reason = "Connection lost")
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

    private fun transitionToConnected(peerId: String, peerName: String, isVideo: Boolean) {
        NotificationHelper.stopOutgoingRingback()
        if (_callState.value is CallState.Connected) return
        _callState.value = CallState.Connected(peerId, peerName, isVideo)
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            _callDurationSeconds.value = 0L
            while (true) {
                delay(1_000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun startActiveCallForegroundService(peerName: String, isVideo: Boolean) {
        val intent = Intent(app, ActiveCallService::class.java).apply {
            putExtra(NotificationHelper.EXTRA_PEER_NAME, peerName)
            putExtra(NotificationHelper.EXTRA_IS_VIDEO, isVideo)
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

    override fun onCleared() {
        runCatching { webRTCClient?.attachRenderers(null, null) }
        webRTCClient?.release()
        runCatching { eglBase.release() }
        NotificationHelper.stopRingtoneAndVibrate(app)
        super.onCleared()
    }
}
