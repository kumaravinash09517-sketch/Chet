package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.HelloSirApplication
import com.example.rtc.CallState
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HelloSirApplication
    private val controller = app.callController

    val eglBaseContext: EglBase.Context get() = controller.eglBaseContext

    val callState: StateFlow<CallState> = controller.callState
    val isMuted: StateFlow<Boolean> = controller.isMuted
    val isSpeakerOn: StateFlow<Boolean> = controller.isSpeakerOn
    val isVideoEnabled: StateFlow<Boolean> = controller.isVideoEnabled
    val isFrontCamera: StateFlow<Boolean> = controller.isFrontCamera
    val hasRemoteVideo: StateFlow<Boolean> = controller.hasRemoteVideo
    val callDurationSeconds: StateFlow<Long> = controller.callDurationSeconds
    val isCallMinimized: StateFlow<Boolean> = controller.isCallMinimized

    fun startOutgoingCall(peerId: String, peerName: String, isVideo: Boolean) {
        controller.startOutgoingCall(peerId, peerName, isVideo)
    }

    fun acceptIncomingCall() {
        controller.acceptIncomingCall()
    }

    fun onAudioPermissionGranted() {
        controller.onAudioPermissionGranted()
    }

    fun onCameraPermissionGranted() {
        controller.onCameraPermissionGranted()
    }

    fun onAppResumed() {
        controller.onAppResumed()
    }

    fun injectIncomingCallFromIntent(
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean,
        offerSdp: String
    ) {
        controller.injectIncomingCallFromIntent(callId, callerId, callerName, isVideo, offerSdp)
    }

    fun declineIncomingCall() {
        controller.declineIncomingCall()
    }

    fun hangUp() {
        controller.hangUp()
    }

    fun minimizeCall() {
        controller.minimizeCallScreen()
    }

    fun expandCall() {
        controller.expandCallScreen()
    }

    fun attachRenderers(
        local: SurfaceViewRenderer?,
        remote: SurfaceViewRenderer?,
        forceRebind: Boolean = false
    ) {
        controller.attachRenderers(local, remote, forceRebind)
    }

    fun toggleMute() {
        controller.toggleMute()
    }

    fun toggleSpeaker() {
        controller.toggleSpeaker()
    }

    fun toggleVideo() {
        controller.toggleVideo()
    }

    fun switchCamera() {
        controller.switchCamera()
    }

    override fun onCleared() {
        // Keep active WebRTC call alive in Application CallSessionController across Recents swipe;
        // only detach UI renderers bound to this destroyed Activity.
        controller.detachActivityRenderers()
        super.onCleared()
    }
}
