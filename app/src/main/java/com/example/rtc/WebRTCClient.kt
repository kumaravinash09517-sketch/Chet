package com.example.rtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.example.data.remote.IceServerDto
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
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
    private val eglBase: EglBase,
    private val onLocalSdpCreated: (SessionDescription) -> Unit,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit,
    private val onCameraFacingChanged: (Boolean) -> Unit = {}
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localAudioAdded = false
    private var localVideoAdded = false
    private var isRemoteDescriptionSet = false
    private val queuedRemoteIce = mutableListOf<IceCandidate>()

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
        if (localRenderer !== local) {
            localRenderer?.let { old -> runCatching { localVideoTrack?.removeSink(old) } }
            localRenderer = local
        }
        if (local != null) {
            runCatching { localVideoTrack?.removeSink(local) }
            runCatching { localVideoTrack?.addSink(local) }
        }

        if (remoteRenderer !== remote) {
            remoteRenderer?.let { old -> runCatching { remoteVideoTrack?.removeSink(old) } }
            remoteRenderer = remote
        }
        if (remote != null) {
            runCatching { remoteVideoTrack?.removeSink(remote) }
            runCatching { remoteVideoTrack?.addSink(remote) }
        }
    }

    fun prepareLocalMedia(isVideo: Boolean) {
        if (localAudioTrack == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("HELLO_SIR_AUDIO", audioSource).apply {
                setEnabled(true)
            }
        }
        if (isVideo) {
            ensureVideoCaptureStarted()
        }
    }

    fun ensureVideoCaptureStarted(): Boolean {
        val hasCameraPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) return false

        if (localVideoTrack == null) {
            val capturerSelection = createCameraCapturer() ?: return false
            val capturer = capturerSelection.capturer
            videoCapturer = capturer
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            startCaptureSafely(capturer, capturerSelection.width, capturerSelection.height, capturerSelection.fps)
            localVideoTrack = peerConnectionFactory.createVideoTrack("HELLO_SIR_VIDEO", videoSource).apply {
                setEnabled(true)
            }
            onCameraFacingChanged(capturerSelection.isFront)
        } else {
            localVideoTrack?.setEnabled(true)
        }

        localRenderer?.let { sink ->
            runCatching { localVideoTrack?.removeSink(sink) }
            runCatching { localVideoTrack?.addSink(sink) }
        }
        if (peerConnection != null && !localVideoAdded) {
            localVideoTrack?.let {
                peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM"))
                localVideoAdded = true
            }
        }
        return true
    }

    fun startPeerConnection(
        isVideo: Boolean,
        customIceServers: List<IceServerDto> = emptyList()
    ) {
        prepareLocalMedia(isVideo)

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

        isRemoteDescriptionSet = false
        localAudioAdded = false
        localVideoAdded = false

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
                        bindRemoteVideoTrack(track)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        bindRemoteVideoTrack(track)
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        bindRemoteVideoTrack(track)
                    }
                }
            }
        )

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM"))
            localAudioAdded = true
        }
        if (isVideo) {
            localVideoTrack?.let {
                peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM"))
                localVideoAdded = true
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = isVideo
    }

    private fun bindRemoteVideoTrack(track: VideoTrack) {
        remoteVideoTrack = track
        track.setEnabled(true)
        remoteRenderer?.let { sink ->
            runCatching { track.removeSink(sink) }
            runCatching { track.addSink(sink) }
        }
        onRemoteVideoTrack(track)
    }

    private data class CapturerSelection(
        val capturer: CameraVideoCapturer,
        val isFront: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private fun createCameraCapturer(): CapturerSelection? {
        if (Camera2Enumerator.isSupported(context)) {
            pickCapturerFromEnumerator(Camera2Enumerator(context))?.let { return it }
        }
        pickCapturerFromEnumerator(Camera1Enumerator(true))?.let { return it }
        return pickCapturerFromEnumerator(Camera1Enumerator(false))
    }

    private fun pickCapturerFromEnumerator(enumerator: CameraEnumerator): CapturerSelection? {
        val deviceNames = runCatching { enumerator.deviceNames }.getOrNull().orEmpty()
        val frontName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val targetName = frontName ?: deviceNames.firstOrNull() ?: return null
        val isFront = frontName != null
        val capturer = runCatching { enumerator.createCapturer(targetName, null) }.getOrNull() ?: return null

        val formats = runCatching { enumerator.getSupportedFormats(targetName) }.getOrNull().orEmpty()
        val preferredFormat = formats
            .filter { it.width in 480..1280 && it.height in 360..1280 }
            .minByOrNull { kotlin.math.abs((it.width * it.height) - (1280 * 720)) }
            ?: formats.firstOrNull()

        val width = preferredFormat?.width ?: 1280
        val height = preferredFormat?.height ?: 720
        val maxFps = preferredFormat?.framerate?.max?.let { raw ->
            val normalized = if (raw > 1000) raw / 1000 else raw
            normalized.coerceIn(15, 30)
        } ?: 30

        return CapturerSelection(
            capturer = capturer,
            isFront = isFront,
            width = width,
            height = height,
            fps = maxFps
        )
    }

    private fun startCaptureSafely(
        capturer: CameraVideoCapturer,
        width: Int,
        height: Int,
        fps: Int
    ) {
        val started = runCatching {
            capturer.startCapture(width, height, fps)
        }.isSuccess
        if (!started) {
            runCatching { capturer.startCapture(640, 480, 15) }
        }
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
                    isRemoteDescriptionSet = true
                    flushQueuedRemoteIce()
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
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(
                onSet = {
                    isRemoteDescriptionSet = true
                    flushQueuedRemoteIce()
                }
            ),
            remoteDesc
        )
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val pc = peerConnection
        if (pc != null && isRemoteDescriptionSet) {
            pc.addIceCandidate(ice)
        } else {
            synchronized(queuedRemoteIce) {
                queuedRemoteIce.add(ice)
            }
        }
    }

    private fun flushQueuedRemoteIce() {
        val pc = peerConnection ?: return
        val copy = synchronized(queuedRemoteIce) {
            val list = queuedRemoteIce.toList()
            queuedRemoteIce.clear()
            list
        }
        copy.forEach { pc.addIceCandidate(it) }
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (enabled && localVideoTrack == null) {
            ensureVideoCaptureStarted()
        }
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            localRenderer?.let { sink ->
                runCatching { localVideoTrack?.removeSink(sink) }
                runCatching { localVideoTrack?.addSink(sink) }
            }
        }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    onCameraFacingChanged(isFrontCamera)
                }

                override fun onCameraSwitchError(error: String?) = Unit
            }
        )
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun release() {
        localRenderer?.let { sink -> runCatching { localVideoTrack?.removeSink(sink) } }
        remoteRenderer?.let { sink -> runCatching { remoteVideoTrack?.removeSink(sink) } }
        localRenderer = null
        remoteRenderer = null
        remoteVideoTrack = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        synchronized(queuedRemoteIce) { queuedRemoteIce.clear() }
        isRemoteDescriptionSet = false
        localAudioAdded = false
        localVideoAdded = false
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
