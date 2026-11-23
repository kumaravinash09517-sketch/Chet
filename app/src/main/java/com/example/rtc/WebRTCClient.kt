package com.example.rtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.remote.IceServerDto
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val onLocalSdpCreated: (SessionDescription) -> Unit,
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onPeerConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onRemoteVideoTrack: (VideoTrack) -> Unit,
    private val onCameraFacingChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val audioDeviceModule: JavaAudioDeviceModule
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localAudioAdded = false
    private var localVideoAdded = false
    private var captureWidth = 640
    private var captureHeight = 480
    private var captureFps = 24
    @Volatile
    private var isReleased = false
    private var shouldBeCapturing = false
    @Volatile
    private var isCapturing = false
    @Volatile
    private var hasReceivedFirstFrame = false
    @Volatile
    private var lastCapturedFrameMs = 0L
    private var lastFreezeRecoveryMs = 0L
    private var freezeRecoveryCount = 0
    private var isUsingFrontCamera = true
    private var hasAudioFocus = false

    private val firstFrameWatchdogRunnable = Runnable {
        if (!isReleased && shouldBeCapturing && !hasReceivedFirstFrame) {
            recoverFrozenOrStoppedCamera(escalateToCamera1 = true)
        }
    }

    private val cameraKeepAliveRunnable = object : Runnable {
        override fun run() {
            if (isReleased || !shouldBeCapturing) return
            val now = System.currentTimeMillis()
            val frameStalled = hasReceivedFirstFrame &&
                lastCapturedFrameMs > 0L &&
                (now - lastCapturedFrameMs) > 3_500L
            if (!isCapturing || videoCapturer == null || frameStalled) {
                recoverFrozenOrStoppedCamera(escalateToCamera1 = true)
            }
            mainHandler.postDelayed(this, 3_000L)
        }
    }

    @Volatile
    private var isRemoteDescriptionSet = false
    private val queuedRemoteIce = mutableListOf<IceCandidate>()

    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()
        audioDeviceModule.setMicrophoneMute(false)
        audioDeviceModule.setSpeakerMute(false)

        // Constrained baseline (enableH264HighProfile = false) prevents hardware encoder stalls/freezes on mobile SoCs
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun attachRenderers(
        local: SurfaceViewRenderer?,
        remote: SurfaceViewRenderer?,
        forceRebind: Boolean = false
    ) {
        runOnMain {
            if (localRenderer !== local || forceRebind) {
                localRenderer?.let { old -> runCatching { localVideoTrack?.removeSink(old) } }
                localRenderer = local
                if (local != null) {
                    runCatching { localVideoTrack?.removeSink(local) }
                    runCatching { localVideoTrack?.addSink(local) }
                }
            }

            if (remoteRenderer !== remote || forceRebind) {
                remoteRenderer?.let { old -> runCatching { remoteVideoTrack?.removeSink(old) } }
                remoteRenderer = remote
                if (remote != null) {
                    runCatching { remoteVideoTrack?.removeSink(remote) }
                    runCatching { remoteVideoTrack?.addSink(remote) }
                }
            }
        }
    }

    fun prepareLocalMedia(isVideo: Boolean) {
        if (localAudioTrack == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
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
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPerm) return false

        if (localVideoTrack == null) {
            val capturerSelection = createCameraCapturer(preferCamera1 = false) ?: return false
            val capturer = capturerSelection.capturer
            videoCapturer = capturer
            captureWidth = capturerSelection.width
            captureHeight = capturerSelection.height
            captureFps = capturerSelection.fps
            isUsingFrontCamera = capturerSelection.isFront
            shouldBeCapturing = true

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, appContext, createMonitoredCapturerObserver())
            cameraExecutor.execute {
                if (!isReleased && shouldBeCapturing) {
                    startCaptureSafely(capturer, captureWidth, captureHeight, captureFps)
                }
            }
            localVideoTrack = peerConnectionFactory.createVideoTrack("HELLO_SIR_VIDEO", videoSource).apply {
                setEnabled(true)
            }
            scheduleCameraKeepAlive()
            onCameraFacingChanged(capturerSelection.isFront)
            runOnMain {
                localRenderer?.let { sink ->
                    runCatching { localVideoTrack?.removeSink(sink) }
                    runCatching { localVideoTrack?.addSink(sink) }
                }
            }
        } else {
            shouldBeCapturing = true
            localVideoTrack?.setEnabled(true)
            scheduleCameraKeepAlive()
            if (!isCapturing) {
                val capturer = videoCapturer
                if (capturer != null) {
                    cameraExecutor.execute {
                        if (!isReleased && shouldBeCapturing && !isCapturing) {
                            startCaptureSafely(capturer, captureWidth, captureHeight, captureFps)
                        }
                    }
                }
            }
        }

        val pc = peerConnection
        val track = localVideoTrack
        if (pc != null && track != null && !localVideoAdded) {
            val existingVideoTransceiver = pc.transceivers
                .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            if (existingVideoTransceiver != null && existingVideoTransceiver.sender.track() == null) {
                runCatching {
                    existingVideoTransceiver.sender.setTrack(track, false)
                    existingVideoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    localVideoAdded = true
                }
            } else {
                pc.addTrack(track, listOf("HELLO_SIR_STREAM"))
                localVideoAdded = true
            }
            configureVideoSenderParameters()
        }
        return true
    }

    fun startPeerConnection(
        isVideo: Boolean,
        customIceServers: List<IceServerDto> = emptyList()
    ) {
        prepareLocalMedia(isVideo)
        configureAudioForCall(speakerOn = isVideo)

        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
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
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            audioJitterBufferMaxPackets = 80
            audioJitterBufferFastAccelerate = true
            iceCandidatePoolSize = 4
        }

        isRemoteDescriptionSet = false
        localAudioAdded = false
        localVideoAdded = false

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    newState?.let { state ->
                        runOnMain { onConnectionStateChanged(state) }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    newState?.let { state ->
                        runOnMain { onPeerConnectionStateChanged(state) }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let(onIceCandidateGenerated)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                override fun onAddStream(stream: MediaStream?) {
                    stream?.audioTracks?.forEach { track -> bindRemoteAudioTrack(track) }
                    stream?.videoTracks?.firstOrNull()?.let { track -> bindRemoteVideoTrack(track) }
                }

                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    when (val track = receiver?.track()) {
                        is AudioTrack -> bindRemoteAudioTrack(track)
                        is VideoTrack -> bindRemoteVideoTrack(track)
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    when (val track = transceiver?.receiver?.track()) {
                        is AudioTrack -> bindRemoteAudioTrack(track)
                        is VideoTrack -> bindRemoteVideoTrack(track)
                    }
                }
            }
        )

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("HELLO_SIR_STREAM"))
            localAudioAdded = true
            configureAudioSenderParameters()
        }
        if (isVideo) {
            val videoTrack = localVideoTrack
            if (videoTrack != null) {
                peerConnection?.addTrack(videoTrack, listOf("HELLO_SIR_STREAM"))
                localVideoAdded = true
                configureVideoSenderParameters()
            } else {
                // Ensure m=video SEND_RECV transceiver exists even before late CAMERA grant
                peerConnection?.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        listOf("HELLO_SIR_STREAM")
                    )
                )
            }
        }
    }

    private fun configureAudioSenderParameters() {
        val pc = peerConnection ?: return
        runCatching {
            val audioSender = pc.senders.firstOrNull {
                it.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND
            } ?: return
            val params = audioSender.parameters ?: return
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = 40_000
                enc.networkPriority = 3
                enc.bitratePriority = 4.0
            }
            audioSender.parameters = params
        }
    }

    private fun configureVideoSenderParameters() {
        val pc = peerConnection ?: return
        runCatching {
            val videoSender = pc.senders.firstOrNull {
                it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
            } ?: return
            val params = videoSender.parameters ?: return
            // BALANCED degrades gracefully under weak signal without freezing frames or starving voice
            params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = 900_000
                enc.minBitrateBps = 120_000
                enc.maxFramerate = 24
                enc.networkPriority = 1
            }
            videoSender.parameters = params
        }
    }

    private fun bindRemoteAudioTrack(track: AudioTrack) {
        remoteAudioTrack = track
        runCatching {
            track.setEnabled(true)
            track.setVolume(1.0)
        }
    }

    private fun bindRemoteVideoTrack(track: VideoTrack) {
        remoteVideoTrack = track
        track.setEnabled(true)
        runOnMain {
            remoteRenderer?.let { sink ->
                runCatching { track.removeSink(sink) }
                runCatching { track.addSink(sink) }
            }
            onRemoteVideoTrack(track)
        }
    }

    private data class CapturerSelection(
        val capturer: CameraVideoCapturer,
        val isFront: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            isCapturing = false
            hasReceivedFirstFrame = false
            recoverFrozenOrStoppedCamera()
        }

        override fun onCameraDisconnected() {
            isCapturing = false
            hasReceivedFirstFrame = false
            recoverFrozenOrStoppedCamera()
        }

        override fun onCameraFreezed(errorDescription: String?) {
            isCapturing = false
            hasReceivedFirstFrame = false
            recoverFrozenOrStoppedCamera()
        }

        override fun onCameraOpening(cameraName: String?) = Unit

        override fun onFirstFrameAvailable() {
            isCapturing = true
            hasReceivedFirstFrame = true
            freezeRecoveryCount = 0
            mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        }

        override fun onCameraClosed() {
            isCapturing = false
        }
    }

    private fun createMonitoredCapturerObserver(): CapturerObserver {
        return object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                isCapturing = success
                runCatching { videoSource?.capturerObserver?.onCapturerStarted(success) }
            }

            override fun onCapturerStopped() {
                isCapturing = false
                runCatching { videoSource?.capturerObserver?.onCapturerStopped() }
            }

            override fun onFrameCaptured(frame: VideoFrame?) {
                if (frame != null) {
                    lastCapturedFrameMs = System.currentTimeMillis()
                    if (!hasReceivedFirstFrame) {
                        hasReceivedFirstFrame = true
                        isCapturing = true
                        freezeRecoveryCount = 0
                        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
                    }
                    runCatching { videoSource?.capturerObserver?.onFrameCaptured(frame) }
                }
            }
        }
    }

    private fun scheduleCameraKeepAlive() {
        mainHandler.removeCallbacks(cameraKeepAliveRunnable)
        if (!isReleased && shouldBeCapturing) {
            mainHandler.postDelayed(cameraKeepAliveRunnable, 3_000L)
        }
    }

    private fun recoverFrozenOrStoppedCamera(escalateToCamera1: Boolean = false) {
        val now = System.currentTimeMillis()
        if (isReleased || !shouldBeCapturing) return
        if (!escalateToCamera1 && (now - lastFreezeRecoveryMs) < 1_800L) return
        lastFreezeRecoveryMs = now
        freezeRecoveryCount++
        mainHandler.postDelayed({
            if (isReleased || !shouldBeCapturing) return@postDelayed
            cameraExecutor.execute {
                if (isReleased || !shouldBeCapturing) return@execute
                if (!escalateToCamera1 && freezeRecoveryCount <= 1) {
                    val capturer = videoCapturer ?: return@execute
                    runCatching { capturer.stopCapture() }
                    captureWidth = 640
                    captureHeight = 480
                    captureFps = 15
                    startCaptureSafely(capturer, captureWidth, captureHeight, captureFps)
                } else {
                    // Escalated recovery: tear down stalled capturer and rebuild off main thread (prefer Camera1 if Camera2 stalled/froze)
                    runCatching { videoCapturer?.stopCapture() }
                    runCatching { videoCapturer?.dispose() }
                    videoCapturer = null
                    runCatching { surfaceTextureHelper?.dispose() }
                    surfaceTextureHelper = null
                    if (isReleased || !shouldBeCapturing) return@execute

                    val selection = createCameraCapturer(preferCamera1 = true) ?: return@execute
                    val freshCapturer = selection.capturer
                    videoCapturer = freshCapturer
                    captureWidth = 640
                    captureHeight = 480
                    captureFps = 15
                    isUsingFrontCamera = selection.isFront
                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                    freshCapturer.initialize(surfaceTextureHelper, appContext, createMonitoredCapturerObserver())
                    startCaptureSafely(freshCapturer, captureWidth, captureHeight, captureFps)
                    runOnMain {
                        if (isReleased) return@runOnMain
                        localVideoTrack?.setEnabled(true)
                        localRenderer?.let { sink ->
                            runCatching { localVideoTrack?.removeSink(sink) }
                            runCatching { localVideoTrack?.addSink(sink) }
                        }
                        onCameraFacingChanged(selection.isFront)
                    }
                }
            }
        }, 220L)
    }

    fun restartVideoCaptureIfActive() {
        if (isReleased || !shouldBeCapturing) return
        scheduleCameraKeepAlive()
        val now = System.currentTimeMillis()
        val frameFresh = lastCapturedFrameMs > 0L && (now - lastCapturedFrameMs) < 2_500L
        // Do not tear down or interrupt a healthy streaming camera
        if (isCapturing && hasReceivedFirstFrame && frameFresh) {
            localVideoTrack?.setEnabled(true)
            return
        }
        val capturer = videoCapturer ?: run {
            ensureVideoCaptureStarted()
            return
        }
        cameraExecutor.execute {
            if (isReleased || !shouldBeCapturing) return@execute
            runCatching { capturer.stopCapture() }
            startCaptureSafely(capturer, captureWidth, captureHeight, captureFps)
            runOnMain {
                if (!isReleased) {
                    localVideoTrack?.setEnabled(true)
                }
            }
        }
    }

    private fun createCameraCapturer(preferCamera1: Boolean = false): CapturerSelection? {
        if (!preferCamera1 && Camera2Enumerator.isSupported(appContext)) {
            pickCapturerFromEnumerator(Camera2Enumerator(appContext))?.let { return it }
        }
        pickCapturerFromEnumerator(Camera1Enumerator(true))?.let { return it }
        return pickCapturerFromEnumerator(Camera1Enumerator(false))
    }

    private fun pickCapturerFromEnumerator(enumerator: CameraEnumerator): CapturerSelection? {
        val deviceNames = runCatching { enumerator.deviceNames }.getOrNull().orEmpty()
        val frontName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backName = deviceNames.firstOrNull { !enumerator.isFrontFacing(it) }
        val targetName = if (isUsingFrontCamera) {
            frontName ?: backName ?: deviceNames.firstOrNull()
        } else {
            backName ?: frontName ?: deviceNames.firstOrNull()
        } ?: return null
        val isFront = enumerator.isFrontFacing(targetName)
        val capturer = runCatching {
            enumerator.createCapturer(targetName, cameraEventsHandler)
        }.getOrNull() ?: return null

        val formats = runCatching { enumerator.getSupportedFormats(targetName) }.getOrNull().orEmpty()
        val preferredFormat = formats
            .filter { it.width in 480..960 && it.height in 360..720 }
            .minByOrNull { kotlin.math.abs((it.width * it.height) - (640 * 480)) }
            ?: formats.firstOrNull()

        val width = preferredFormat?.width ?: 640
        val height = preferredFormat?.height ?: 480
        val maxFps = preferredFormat?.framerate?.max?.let { raw ->
            val normalized = if (raw > 1000) raw / 1000 else raw
            normalized.coerceIn(15, 24)
        } ?: 24

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
        hasReceivedFirstFrame = false
        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        val started = runCatching {
            capturer.startCapture(width, height, fps)
        }.isSuccess
        if (started) {
            isCapturing = true
        } else {
            val fallbackStarted = runCatching { capturer.startCapture(640, 480, 15) }.isSuccess
            isCapturing = fallbackStarted
        }
        if (isCapturing && shouldBeCapturing) {
            mainHandler.postDelayed(firstFrameWatchdogRunnable, 2_800L)
        }
    }

    fun createOffer(isVideo: Boolean, iceRestart: Boolean = false) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo.toString()))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        peerConnection?.createOffer(
            SimpleSdpObserver(
                onCreate = { sdp ->
                    peerConnection?.setLocalDescription(
                        SimpleSdpObserver(
                            onSet = {
                                configureAudioSenderParameters()
                                if (isVideo) configureVideoSenderParameters()
                                onLocalSdpCreated(sdp)
                            }
                        ),
                        sdp
                    )
                }
            ),
            constraints
        )
    }

    fun restartIce(isVideo: Boolean) {
        if (isReleased || peerConnection == null) return
        createOffer(isVideo = isVideo, iceRestart = true)
    }

    fun handleRemoteOfferAndCreateAnswer(offerSdp: String, isVideo: Boolean) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, normalizeSdp(offerSdp))
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
                                peerConnection?.setLocalDescription(
                                    SimpleSdpObserver(
                                        onSet = {
                                            configureAudioSenderParameters()
                                            if (isVideo) configureVideoSenderParameters()
                                            onLocalSdpCreated(answerSdp)
                                        }
                                    ),
                                    answerSdp
                                )
                            }
                        ),
                        constraints
                    )
                    flushQueuedRemoteIceAndMarkSet()
                }
            ),
            remoteDesc
        )
    }

    fun handleRemoteAnswer(answerSdp: String) {
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, normalizeSdp(answerSdp))
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(
                onSet = {
                    flushQueuedRemoteIceAndMarkSet()
                }
            ),
            remoteDesc
        )
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val cleanCandidate = candidate.trim()
        if (cleanCandidate.isEmpty()) return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, cleanCandidate)
        val pc = peerConnection
        val addImmediately = synchronized(queuedRemoteIce) {
            if (pc != null && isRemoteDescriptionSet) {
                true
            } else {
                queuedRemoteIce.add(ice)
                false
            }
        }
        if (addImmediately) {
            pc?.addIceCandidate(ice)
        }
    }

    private fun flushQueuedRemoteIceAndMarkSet() {
        val pc = peerConnection
        val copy = synchronized(queuedRemoteIce) {
            isRemoteDescriptionSet = true
            val list = queuedRemoteIce.toList()
            queuedRemoteIce.clear()
            list
        }
        if (pc != null) {
            copy.forEach { pc.addIceCandidate(it) }
        }
    }

    fun ensureAudioActive(muted: Boolean, speakerOn: Boolean) {
        runCatching {
            localAudioTrack?.setEnabled(!muted)
            remoteAudioTrack?.setEnabled(true)
            remoteAudioTrack?.setVolume(1.0)
            audioDeviceModule.setMicrophoneMute(muted)
            audioDeviceModule.setSpeakerMute(false)
            configureAudioForCall(speakerOn = speakerOn)
            audioManager.isMicrophoneMute = muted
        }
    }

    private fun normalizeSdp(raw: String): String {
        val unescaped = raw.replace("\\r\\n", "\n").replace("\\n", "\n")
        val lfNormalized = unescaped
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        return if (lfNormalized.isEmpty()) "" else lfNormalized.replace("\n", "\r\n") + "\r\n"
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        runCatching { audioDeviceModule.setMicrophoneMute(muted) }
        runCatching { audioManager.isMicrophoneMute = muted }
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (isReleased) return
        shouldBeCapturing = enabled
        if (enabled && localVideoTrack == null) {
            ensureVideoCaptureStarted()
            return
        }
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            scheduleCameraKeepAlive()
            val capturer = videoCapturer
            if (capturer != null && !isCapturing) {
                cameraExecutor.execute {
                    if (!isReleased && shouldBeCapturing && !isCapturing) {
                        startCaptureSafely(capturer, captureWidth, captureHeight, captureFps)
                    }
                }
            }
            runOnMain {
                localRenderer?.let { sink ->
                    runCatching { localVideoTrack?.removeSink(sink) }
                    runCatching { localVideoTrack?.addSink(sink) }
                }
            }
        } else {
            isCapturing = false
            hasReceivedFirstFrame = false
            lastCapturedFrameMs = 0L
            mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
            mainHandler.removeCallbacks(cameraKeepAliveRunnable)
            val capturer = videoCapturer
            if (capturer != null) {
                cameraExecutor.execute {
                    runCatching { capturer.stopCapture() }
                }
            }
        }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    isUsingFrontCamera = isFrontCamera
                    runOnMain { onCameraFacingChanged(isFrontCamera) }
                }

                override fun onCameraSwitchError(error: String?) = Unit
            }
        )
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        applyAudioRouting(enabled)
    }

    private fun configureAudioForCall(speakerOn: Boolean) {
        runCatching {
            if (!hasAudioFocus) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener { }
                        .build()
                    audioFocusRequest = req
                    audioManager.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        null,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }
                hasAudioFocus = true
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            applyAudioRouting(speakerOn)
        }
    }

    private fun applyAudioRouting(speakerOn: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val target = if (speakerOn) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                val routed = if (target != null) {
                    audioManager.setCommunicationDevice(target)
                } else {
                    false
                }
                if (!routed) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = speakerOn
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerOn
            }
        }
    }

    fun release() {
        isReleased = true
        shouldBeCapturing = false
        isCapturing = false
        hasReceivedFirstFrame = false
        lastCapturedFrameMs = 0L
        hasAudioFocus = false
        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        mainHandler.removeCallbacks(cameraKeepAliveRunnable)
        runOnMain {
            localRenderer?.let { sink -> runCatching { localVideoTrack?.removeSink(sink) } }
            remoteRenderer?.let { sink -> runCatching { remoteVideoTrack?.removeSink(sink) } }
            localRenderer = null
            remoteRenderer = null
        }
        remoteVideoTrack = null
        remoteAudioTrack = null

        val capturerSnapshot = videoCapturer
        val helperSnapshot = surfaceTextureHelper
        val vTrackSnapshot = localVideoTrack
        val vSourceSnapshot = videoSource
        val aTrackSnapshot = localAudioTrack
        val aSourceSnapshot = audioSource
        val pcSnapshot = peerConnection

        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        videoSource = null
        localAudioTrack = null
        audioSource = null
        peerConnection = null

        synchronized(queuedRemoteIce) { queuedRemoteIce.clear() }
        isRemoteDescriptionSet = false
        localAudioAdded = false
        localVideoAdded = false

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioManager.isMicrophoneMute = false
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }

        // Run stopCapture/dispose off the main thread so a slow Camera2 HAL never freezes the UI on hangup
        runCatching {
            cameraExecutor.execute {
                runCatching { capturerSnapshot?.stopCapture() }
                runCatching { capturerSnapshot?.dispose() }
                runCatching { pcSnapshot?.close() }
                runCatching { pcSnapshot?.dispose() }
                runCatching { vTrackSnapshot?.dispose() }
                runCatching { vSourceSnapshot?.dispose() }
                runCatching { helperSnapshot?.dispose() }
                runCatching { aTrackSnapshot?.dispose() }
                runCatching { aSourceSnapshot?.dispose() }
                runCatching { audioDeviceModule.release() }
            }
            cameraExecutor.shutdown()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
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
