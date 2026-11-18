package com.example.rtc

import android.content.Context
import android.media.AudioManager
import com.example.data.remote.IceServerDto
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRTCClient(
    private val context: Context,
    private val onLocalSdpCreated: (SessionDescription) -> Unit,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit
) {
    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        if (local != null && localRenderer != local) {
            localRenderer = local
            local.init(eglBase.eglBaseContext, null)
            local.setMirror(true)
            local.setEnableHardwareScaler(true)
            localVideoTrack?.addSink(local)
        }
        if (remote != null && remoteRenderer != remote) {
            remoteRenderer = remote
            remote.init(eglBase.eglBaseContext, null)
            remote.setMirror(false)
            remote.setEnableHardwareScaler(true)
        }
    }

    fun startPeerConnection(
        isVideo: Boolean,
        customIceServers: List<IceServerDto> = emptyList()
    ) {
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        customIceServers.forEach { dto ->
            if (dto.urls.isNotEmpty()) {
                val builder = PeerConnection.IceServer.builder(dto.urls)
                dto.username?.let { builder.setUsername(it) }
                dto.credential?.let { builder.setPassword(it) }
                iceServers.add(builder.createIceServer())
            }
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    newState?.let(onConnectionStateChanged)
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let(onIceCandidateGenerated)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) {
                    stream?.videoTracks?.firstOrNull()?.let { track ->
                        remoteRenderer?.let { track.addSink(it) }
                        onRemoteVideoTrack(track)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteRenderer?.let { track.addSink(it) }
                        onRemoteVideoTrack(track)
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        remoteRenderer?.let { track.addSink(it) }
                        onRemoteVideoTrack(track)
                    }
                }
            }
        )

        setupLocalTracks(isVideo)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = isVideo
    }

    private fun setupLocalTracks(isVideo: Boolean) {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("HELLO_SIR_AUDIO", audioSource)
        localAudioTrack?.setEnabled(true)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM")) }

        if (isVideo) {
            val capturer = createCameraCapturer()
            if (capturer != null) {
                videoCapturer = capturer
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                runCatching { capturer.startCapture(720, 1280, 30) }
                localVideoTrack = peerConnectionFactory.createVideoTrack("HELLO_SIR_VIDEO", videoSource)
                localVideoTrack?.setEnabled(true)
                localRenderer?.let { localVideoTrack?.addSink(it) }
                localVideoTrack?.let { peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM")) }
            }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val front = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        if (front != null) {
            return enumerator.createCapturer(front, null)
        }
        val back = deviceNames.firstOrNull { !enumerator.isFrontFacing(it) }
        return back?.let { enumerator.createCapturer(it, null) }
    }

    fun createOffer(isVideo: Boolean) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo.toString()))
        }
        peerConnection?.createOffer(
            SimpleSdpObserver(
                onCreate = { sdp ->
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                    onLocalSdpCreated(sdp)
                }
            ),
            constraints
        )
    }

    fun handleRemoteOfferAndCreateAnswer(offerSdp: String, isVideo: Boolean) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(
                onSet = {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo.toString()))
                    }
                    peerConnection?.createAnswer(
                        SimpleSdpObserver(
                            onCreate = { answerSdp ->
                                peerConnection?.setLocalDescription(SimpleSdpObserver(), answerSdp)
                                onLocalSdpCreated(answerSdp)
                            }
                        ),
                        constraints
                    )
                }
            ),
            remoteDesc
        )
    }

    fun handleRemoteAnswer(answerSdp: String) {
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), remoteDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun release() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        localRenderer?.release()
        localRenderer = null
        remoteRenderer?.release()
        remoteRenderer = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private class SimpleSdpObserver(
        private val onCreate: ((SessionDescription) -> Unit)? = null,
        private val onSet: (() -> Unit)? = null
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let { onCreate?.invoke(it) }
        }

        override fun onSetSuccess() {
            onSet?.invoke()
        }

        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
