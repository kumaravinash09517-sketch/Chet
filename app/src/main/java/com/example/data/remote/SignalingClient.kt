package com.example.data.remote

import android.net.Uri
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.ServerStatus
import com.example.core.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SignalingEvent {
    data object SocketConnected : SignalingEvent
    data object DirectoryUpdated : SignalingEvent
    data class ChatMessageReceived(val dto: RemoteMessageDto) : SignalingEvent
    data class MessageDeleted(val messageId: String, val conversationId: String) : SignalingEvent
    data class ReceiptUpdated(
        val messageId: String?,
        val conversationId: String,
        val status: String,
        val peerId: String = "",
        val senderId: String = "",
        val receiverId: String = "",
        val readerId: String = ""
    ) : SignalingEvent

    data class TypingStatusChanged(val userId: String, val isTyping: Boolean) : SignalingEvent
    data class PresenceChanged(val userId: String, val isOnline: Boolean) : SignalingEvent
    data class LiveLocationUpdated(
        val messageId: String,
        val senderId: String,
        val latitude: Double,
        val longitude: Double,
        val active: Boolean
    ) : SignalingEvent

    data class IncomingCallOffer(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val isVideo: Boolean,
        val sdp: String
    ) : SignalingEvent

    data class CallRinging(
        val callId: String,
        val peerId: String
    ) : SignalingEvent

    data class CallAnswerReceived(
        val callerId: String,
        val sdp: String,
        val callId: String = ""
    ) : SignalingEvent

    data class CallConnected(
        val callId: String,
        val peerId: String,
        val sdp: String = ""
    ) : SignalingEvent

    data class IceCandidateReceived(
        val senderId: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String,
        val callId: String = ""
    ) : SignalingEvent

    data class CallEnded(
        val peerId: String,
        val reason: String,
        val callId: String = ""
    ) : SignalingEvent
}

class SignalingClient(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var backgroundDisconnectJob: Job? = null
    private val shouldStayConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var lastConnectAttemptMs = 0L

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    fun isConnectingOrConnected(): Boolean =
        (_isConnected.value || isConnecting.get()) && webSocket != null

    fun setAppForeground(inForeground: Boolean) {
        val changed = isAppInForeground != inForeground
        isAppInForeground = inForeground
        if (inForeground) {
            backgroundDisconnectJob?.cancel()
            backgroundDisconnectJob = null
            shouldStayConnected.set(true)
            if (_isConnected.value && webSocket != null) {
                if (changed) {
                    scope.launch {
                        val session = sessionManager.getCurrentSession() ?: return@launch
                        sendPresenceFrame(session.userId, true)
                    }
                }
            } else if (!isConnecting.get() || webSocket == null) {
                openSocket()
            }
        } else {
            shouldStayConnected.set(false)
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            backgroundDisconnectJob?.cancel()
            backgroundDisconnectJob = scope.launch {
                val userId = sessionManager.getCurrentSession()?.userId.orEmpty()
                if (userId.isNotBlank() && webSocket != null) {
                    sendPresenceFrame(userId, false)
                    delay(140L)
                }
                if (!isAppInForeground) {
                    closeSocketCleanly("App moved to background")
                    if (userId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.PresenceChanged(userId, false))
                    }
                }
            }
        }
    }

    private fun sendPresenceFrame(userId: String, online: Boolean) {
        sendRaw(
            JSONObject().apply {
                put("type", "presence")
                put("userId", userId)
                put("isOnline", online)
                put("is_online", online)
                put("online", online)
                put("inForeground", online)
                put("isForeground", online)
                put("foreground", online)
                put("appState", if (online) "foreground" else "background")
                put("status", if (online) "online" else "offline")
                put("timestamp", System.currentTimeMillis())
            }
        )
    }

    fun connect(forceDuringCall: Boolean = false) {
        if (!forceDuringCall && !isAppInForeground) {
            return
        }
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = null
        shouldStayConnected.set(true)
        if ((_isConnected.value || isConnecting.get()) && webSocket != null) return
        openSocket(forceDuringCall = forceDuringCall)
    }

    suspend fun sendPresenceAndDisconnect(userId: String) {
        isAppInForeground = false
        backgroundDisconnectJob?.cancel()
        if (userId.isNotBlank() && webSocket != null) {
            sendPresenceFrame(userId, false)
            sendRaw(
                JSONObject().apply {
                    put("type", "logout")
                    put("userId", userId)
                    put("user_id", userId)
                    put("isOnline", false)
                    put("inForeground", false)
                    put("status", "offline")
                }
            )
            delay(140L)
        }
        disconnect()
    }

    fun disconnect() {
        shouldStayConnected.set(false)
        backgroundDisconnectJob?.cancel()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        closeSocketCleanly("User disconnected")
    }

    private fun closeSocketCleanly(reason: String) {
        isConnecting.set(false)
        webSocket?.close(1000, reason)
        webSocket = null
        _isConnected.value = false
    }

    fun emitExternalEvent(event: SignalingEvent) {
        _events.tryEmit(event)
    }

    private fun openSocket(forceDuringCall: Boolean = false) {
        scope.launch {
            if (!shouldStayConnected.get()) return@launch
            if (!forceDuringCall && !isAppInForeground) return@launch
            val now = System.currentTimeMillis()
            if ((_isConnected.value || isConnecting.get()) &&
                webSocket != null &&
                (now - lastConnectAttemptMs) < 12_000L
            ) {
                return@launch
            }
            val session = sessionManager.getCurrentSession() ?: return@launch
            if (!isConnecting.compareAndSet(false, true)) {
                if ((now - lastConnectAttemptMs) < 12_000L) return@launch
            }
            lastConnectAttemptMs = now

            val wsUrl = "${NetworkModule.WS_URL}?userId=${Uri.encode(session.userId)}&deviceLockId=${Uri.encode(session.deviceLockId)}"
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${session.sessionToken}")
                .header("X-Device-Lock", session.deviceLockId)
                .build()

            val oldSocket = webSocket
            if (oldSocket != null && !_isConnected.value) {
                oldSocket.cancel()
            } else if (oldSocket != null) {
                oldSocket.close(1000, "Reconnecting")
            }

            lateinit var currentSocket: WebSocket
            currentSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    isConnecting.set(false)
                    _isConnected.value = true
                    reconnectAttempt = 0
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.READY)
                    val onlineNow = isAppInForeground
                    sendRaw(
                        JSONObject().apply {
                            put("type", "auth")
                            put("userId", session.userId)
                            put("displayName", session.displayName)
                            put("deviceLockId", session.deviceLockId)
                            put("isOnline", onlineNow)
                            put("inForeground", onlineNow)
                            put("status", if (onlineNow) "online" else "offline")
                        }
                    )
                    sendPresenceFrame(session.userId, onlineNow)
                    _events.tryEmit(SignalingEvent.SocketConnected)
                    startHeartbeat(session.userId)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    parseAndDispatch(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    isConnecting.set(false)
                    _isConnected.value = false
                    if (code == 4003 || reason.contains("403") || reason.contains("blocked", ignoreCase = true)) {
                        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    isConnecting.set(false)
                    _isConnected.value = false
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    isConnecting.set(false)
                    _isConnected.value = false
                    if (response?.code == 403) {
                        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                        shouldStayConnected.set(false)
                        return
                    }
                    scheduleReconnect()
                }
            })
            webSocket = currentSocket
        }
    }

    private fun startHeartbeat(userId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (shouldStayConnected.get() && _isConnected.value) {
                delay(15_000L)
                val onlineNow = isAppInForeground
                sendRaw(
                    JSONObject().apply {
                        put("type", "ping")
                        put("userId", userId)
                        put("isOnline", onlineNow)
                        put("inForeground", onlineNow)
                        put("status", if (onlineNow) "online" else "offline")
                        put("timestamp", System.currentTimeMillis())
                    }
                )
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected.get() || !isAppInForeground) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val backoffMs = when (reconnectAttempt) {
                1 -> 350L
                2 -> 900L
                else -> (1_500L * (reconnectAttempt - 1)).coerceAtMost(10_000L)
            }
            delay(backoffMs)
            if (shouldStayConnected.get() && isAppInForeground) {
                openSocket()
            }
        }
    }

    private fun parseAndDispatch(raw: String) {
        try {
            val json = JSONObject(raw)
            val data = json.optJSONObject("data") ?: json.optJSONObject("payload") ?: json
            val rawEventType = firstNonBlank(json, "type", "event", "signalType", "signal_type")
                ?: firstNonBlank(data, "type", "event", "signalType", "signal_type")
                ?: ""
            when (rawEventType.trim().lowercase()) {
                "blocked", "error_403" -> {
                    GlobalSecurityEventBus.emitBlocked(
                        json.optString("message").ifBlank { "You are blocked by the administrator." }
                    )
                }
                "user_registered", "directory_updated" -> {
                    _events.tryEmit(SignalingEvent.DirectoryUpdated)
                }
                "chat_message", "message" -> {
                    val msgObj = json.optJSONObject("data") ?: json.optJSONObject("message") ?: json
                    val dto = BackendJsonParsers.parseSingleMessage(msgObj)
                    if (dto != null) {
                        _events.tryEmit(SignalingEvent.ChatMessageReceived(dto))
                    }
                }
                "delete_message" -> {
                    val messageId = data.optString("messageId", data.optString("message_id"))
                    val convId = data.optString(
                        "conversationId",
                        data.optString("senderId", data.optString("sender_id"))
                    )
                    if (messageId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.MessageDeleted(messageId, convId))
                    }
                }
                "receipt", "read_receipt", "delivered_receipt", "seen" -> {
                    val messageId = firstNonBlank(data, "messageId", "message_id", "msgId", "msg_id")
                        ?: firstNonBlank(json, "messageId", "message_id")
                    val peerId = firstNonBlank(data, "peerId", "peer_id", "conversationId", "conversation_id")
                        ?: firstNonBlank(json, "peerId", "peer_id", "conversationId", "conversation_id")
                        ?: ""
                    val senderId = firstNonBlank(data, "senderId", "sender_id", "from")
                        ?: firstNonBlank(json, "senderId", "sender_id", "from")
                        ?: ""
                    val receiverId = firstNonBlank(data, "receiverId", "receiver_id", "to")
                        ?: firstNonBlank(json, "receiverId", "receiver_id", "to")
                        ?: ""
                    val readerId = firstNonBlank(data, "readerId", "reader_id", "userId", "user_id")
                        ?: firstNonBlank(json, "readerId", "reader_id", "userId", "user_id")
                        ?: ""
                    val convId = peerId.ifBlank { readerId }.ifBlank { senderId }.ifBlank { receiverId }
                    val defaultStatus = when (rawEventType.trim().lowercase()) {
                        "delivered_receipt" -> "DELIVERED"
                        else -> "READ"
                    }
                    val rawStatus = (firstNonBlank(data, "status") ?: firstNonBlank(json, "status") ?: defaultStatus)
                        .uppercase()
                    val status = if (rawStatus == "SEEN") "READ" else rawStatus
                    if (convId.isNotBlank()) {
                        _events.tryEmit(
                            SignalingEvent.ReceiptUpdated(
                                messageId = messageId,
                                conversationId = convId,
                                status = status,
                                peerId = peerId,
                                senderId = senderId,
                                receiverId = receiverId,
                                readerId = readerId
                            )
                        )
                    }
                }
                "typing" -> {
                    val userId = data.optString(
                        "senderId",
                        data.optString("sender_id", data.optString("userId", data.optString("user_id")))
                    )
                    val isTyping = data.optBoolean("isTyping", data.optBoolean("is_typing", false))
                    if (userId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.TypingStatusChanged(userId, isTyping))
                    }
                }
                "presence", "user_online", "user_offline", "user_disconnected" -> {
                    val eventKey = rawEventType.trim().lowercase()
                    val userId = firstNonBlank(data, "userId", "user_id", "id")
                        ?: firstNonBlank(json, "userId", "user_id", "id")
                        ?: ""
                    val statusStr = firstNonBlank(data, "status", "presence", "state", "appState", "app_state")
                        ?.lowercase()
                    val foregroundExplicitFalse = (data.has("inForeground") && !data.isNull("inForeground") && !data.optBoolean("inForeground", true)) ||
                        (data.has("isForeground") && !data.isNull("isForeground") && !data.optBoolean("isForeground", true)) ||
                        (data.has("in_foreground") && !data.isNull("in_foreground") && !data.optBoolean("in_foreground", true)) ||
                        (data.has("foreground") && !data.isNull("foreground") && !data.optBoolean("foreground", true))
                    val online = when {
                        eventKey == "user_offline" || eventKey == "user_disconnected" -> false
                        foregroundExplicitFalse -> false
                        statusStr in setOf("offline", "disconnected", "logged_out", "inactive", "away", "background", "paused", "stopped", "locked") -> false
                        eventKey == "user_online" -> true
                        data.has("isOnline") && !data.isNull("isOnline") -> data.optBoolean("isOnline", false)
                        data.has("is_online") && !data.isNull("is_online") -> data.optBoolean("is_online", false)
                        data.has("online") && !data.isNull("online") -> data.optBoolean("online", false)
                        statusStr in setOf("online", "active", "connected", "foreground") -> true
                        else -> false
                    }
                    if (userId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.PresenceChanged(userId, online))
                    }
                }
                "live_location" -> {
                    _events.tryEmit(
                        SignalingEvent.LiveLocationUpdated(
                            messageId = data.optString("messageId", data.optString("message_id")),
                            senderId = data.optString("senderId", data.optString("sender_id")),
                            latitude = data.optDouble("latitude"),
                            longitude = data.optDouble("longitude"),
                            active = data.optBoolean("active", true)
                        )
                    )
                }
                "call_offer", "offer", "incoming_call" -> {
                    val signal = BackendJsonParsers.parseSingleCallSignal(json)
                    if (signal != null && signal.sdp.isNotBlank()) {
                        _events.tryEmit(
                            SignalingEvent.IncomingCallOffer(
                                callId = signal.callId,
                                callerId = signal.senderId,
                                callerName = signal.senderName,
                                isVideo = signal.isVideo,
                                sdp = signal.sdp
                            )
                        )
                    } else {
                        val callerId = firstNonBlank(data, "callerId", "caller_id", "senderId", "sender_id", "from")
                        val callerName = firstNonBlank(data, "callerName", "caller_name", "senderName", "sender_name") ?: callerId.orEmpty()
                        val sdp = data.optString("sdp")
                        if (!callerId.isNullOrBlank() && sdp.isNotBlank()) {
                            _events.tryEmit(
                                SignalingEvent.IncomingCallOffer(
                                    callId = firstNonBlank(data, "callId", "call_id") ?: System.currentTimeMillis().toString(),
                                    callerId = callerId,
                                    callerName = callerName,
                                    isVideo = data.optBoolean("isVideo", data.optBoolean("is_video", false)),
                                    sdp = sdp
                                )
                            )
                        }
                    }
                }
                "ringing", "call_ringing" -> {
                    val peerId = firstNonBlank(data, "senderId", "sender_id", "from", "calleeId", "callee_id", "receiverId", "callerId")
                        ?: firstNonBlank(json, "senderId", "sender_id", "from", "calleeId", "receiverId", "callerId")
                        ?: ""
                    val callId = firstNonBlank(data, "callId", "call_id")
                        ?: firstNonBlank(json, "callId", "call_id")
                        ?: ""
                    _events.tryEmit(SignalingEvent.CallRinging(callId = callId, peerId = peerId))
                }
                "call_answer", "answer", "answered", "accept", "call_accept" -> {
                    val signal = BackendJsonParsers.parseSingleCallSignal(json)
                    if (signal != null && signal.sdp.isNotBlank()) {
                        _events.tryEmit(
                            SignalingEvent.CallAnswerReceived(
                                callerId = signal.senderId,
                                sdp = signal.sdp,
                                callId = signal.callId
                            )
                        )
                    } else {
                        val peerId = firstNonBlank(data, "senderId", "sender_id", "from", "calleeId", "callee_id", "peerId", "callerId")
                            ?: firstNonBlank(json, "senderId", "sender_id", "from", "calleeId", "peerId", "callerId")
                        val sdp = firstNonBlank(data, "sdp", "answerSdp", "answer_sdp")
                            ?: firstNonBlank(json, "sdp", "answerSdp", "answer_sdp")
                            ?: ""
                        if (!peerId.isNullOrBlank() && sdp.isNotBlank()) {
                            _events.tryEmit(
                                SignalingEvent.CallAnswerReceived(
                                    callerId = peerId,
                                    sdp = sdp,
                                    callId = firstNonBlank(data, "callId", "call_id")
                                        ?: firstNonBlank(json, "callId", "call_id")
                                        ?: ""
                                )
                            )
                        }
                    }
                }
                "connected", "call_connected", "accepted", "call_accepted" -> {
                    val signal = BackendJsonParsers.parseSingleCallSignal(json)
                    val peerId = signal?.senderId
                        ?: firstNonBlank(data, "senderId", "sender_id", "from", "calleeId", "callee_id", "peerId", "callerId")
                        ?: firstNonBlank(json, "senderId", "sender_id", "from", "calleeId", "peerId", "callerId")
                        ?: ""
                    val callId = signal?.callId?.takeIf { it.isNotBlank() }
                        ?: firstNonBlank(data, "callId", "call_id")
                        ?: firstNonBlank(json, "callId", "call_id")
                        ?: ""
                    val sdp = signal?.sdp?.takeIf { it.isNotBlank() }
                        ?: firstNonBlank(data, "sdp", "answerSdp", "answer_sdp")
                        ?: firstNonBlank(json, "sdp", "answerSdp", "answer_sdp")
                        ?: ""
                    if (sdp.isNotBlank()) {
                        _events.tryEmit(
                            SignalingEvent.CallAnswerReceived(
                                callerId = peerId,
                                sdp = sdp,
                                callId = callId
                            )
                        )
                    }
                    _events.tryEmit(
                        SignalingEvent.CallConnected(
                            callId = callId,
                            peerId = peerId,
                            sdp = sdp
                        )
                    )
                }
                "call_signal", "signal" -> {
                    val signal = BackendJsonParsers.parseSingleCallSignal(json)
                    if (signal != null) {
                        when (signal.type) {
                            "offer" -> if (signal.sdp.isNotBlank()) {
                                _events.tryEmit(
                                    SignalingEvent.IncomingCallOffer(
                                        callId = signal.callId,
                                        callerId = signal.senderId,
                                        callerName = signal.senderName,
                                        isVideo = signal.isVideo,
                                        sdp = signal.sdp
                                    )
                                )
                            }
                            "ringing" -> _events.tryEmit(SignalingEvent.CallRinging(signal.callId, signal.senderId))
                            "answer" -> if (signal.sdp.isNotBlank()) {
                                _events.tryEmit(
                                    SignalingEvent.CallAnswerReceived(
                                        callerId = signal.senderId,
                                        sdp = signal.sdp,
                                        callId = signal.callId
                                    )
                                )
                            }
                            "connected" -> {
                                if (signal.sdp.isNotBlank()) {
                                    _events.tryEmit(
                                        SignalingEvent.CallAnswerReceived(
                                            callerId = signal.senderId,
                                            sdp = signal.sdp,
                                            callId = signal.callId
                                        )
                                    )
                                }
                                _events.tryEmit(
                                    SignalingEvent.CallConnected(
                                        callId = signal.callId,
                                        peerId = signal.senderId,
                                        sdp = signal.sdp
                                    )
                                )
                            }
                            "ice" -> if (signal.candidate.isNotBlank()) {
                                _events.tryEmit(
                                    SignalingEvent.IceCandidateReceived(
                                        senderId = signal.senderId,
                                        sdpMid = signal.sdpMid,
                                        sdpMLineIndex = signal.sdpMLineIndex,
                                        candidate = signal.candidate,
                                        callId = signal.callId
                                    )
                                )
                            }
                            "hangup", "decline" -> _events.tryEmit(
                                SignalingEvent.CallEnded(
                                    peerId = signal.senderId,
                                    reason = signal.type,
                                    callId = signal.callId
                                )
                            )
                        }
                    }
                }
                "ice_candidate", "ice" -> {
                    val signal = BackendJsonParsers.parseSingleCallSignal(json)
                    if (signal != null && signal.candidate.isNotBlank()) {
                        _events.tryEmit(
                            SignalingEvent.IceCandidateReceived(
                                senderId = signal.senderId,
                                sdpMid = signal.sdpMid,
                                sdpMLineIndex = signal.sdpMLineIndex,
                                candidate = signal.candidate,
                                callId = signal.callId
                            )
                        )
                    } else {
                        val senderId = firstNonBlank(data, "senderId", "sender_id", "from").orEmpty()
                        val candidate = data.optString("candidate")
                        if (candidate.isNotBlank()) {
                            _events.tryEmit(
                                SignalingEvent.IceCandidateReceived(
                                    senderId = senderId,
                                    sdpMid = data.optString("sdpMid", data.optString("sdp_mid")),
                                    sdpMLineIndex = data.optInt("sdpMLineIndex", data.optInt("sdp_m_line_index", 0)),
                                    candidate = candidate,
                                    callId = firstNonBlank(data, "callId", "call_id").orEmpty()
                                )
                            )
                        }
                    }
                }
                "call_end", "call_reject", "hangup", "call_hangup", "decline", "call_decline", "end", "reject" -> {
                    val peerId = firstNonBlank(data, "senderId", "sender_id", "from", "peerId", "callerId")
                        ?: firstNonBlank(json, "senderId", "sender_id", "from", "peerId", "callerId")
                        ?: ""
                    val callId = firstNonBlank(data, "callId", "call_id")
                        ?: firstNonBlank(json, "callId", "call_id")
                        ?: ""
                    _events.tryEmit(
                        SignalingEvent.CallEnded(
                            peerId = peerId,
                            reason = rawEventType,
                            callId = callId
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore malformed non-JSON heartbeat frames
        }
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    fun sendChatMessage(request: SendMessageRequest): Boolean {
        val json = JSONObject().apply {
            put("type", "chat_message")
            put("id", request.id)
            put("senderId", request.senderId)
            put("receiverId", request.receiverId)
            put("content", request.content)
            put("messageType", request.type)
            request.mediaUrl?.let { put("mediaUrl", it) }
            request.fileName?.let { put("fileName", it) }
            request.fileSizeBytes?.let { put("fileSizeBytes", it) }
            request.latitude?.let { put("latitude", it) }
            request.longitude?.let { put("longitude", it) }
            put("liveLocationActive", request.liveLocationActive)
            put("timestamp", request.timestamp)
        }
        return sendRaw(json)
    }

    fun sendDeleteMessage(messageId: String, senderId: String, receiverId: String) {
        sendRaw(
            JSONObject().apply {
                put("type", "delete_message")
                put("messageId", messageId)
                put("senderId", senderId)
                put("receiverId", receiverId)
            }
        )
    }

    fun sendTyping(senderId: String, receiverId: String, isTyping: Boolean) {
        sendRaw(
            JSONObject().apply {
                put("type", "typing")
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("isTyping", isTyping)
            }
        )
    }

    fun sendReceipt(senderId: String, receiverId: String, messageId: String?, status: String) {
        sendRaw(
            JSONObject().apply {
                put("type", "receipt")
                put("senderId", senderId)
                put("receiverId", receiverId)
                messageId?.let { put("messageId", it) }
                put("status", status)
            }
        )
    }

    fun sendLiveLocationUpdate(
        messageId: String,
        senderId: String,
        receiverId: String,
        latitude: Double,
        longitude: Double,
        active: Boolean
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "live_location")
                put("messageId", messageId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("active", active)
            }
        )
    }

    fun sendCallOffer(
        callId: String,
        senderId: String,
        senderName: String,
        receiverId: String,
        isVideo: Boolean,
        sdp: String
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "call_offer")
                put("callId", callId)
                put("callerId", senderId)
                put("senderId", senderId)
                put("senderName", senderName)
                put("callerName", senderName)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
                put("isVideo", isVideo)
                put("sdp", sdp)
            }
        )
    }

    fun sendCallRinging(callId: String, senderId: String, receiverId: String) {
        sendRaw(
            JSONObject().apply {
                put("type", "ringing")
                if (callId.isNotBlank()) put("callId", callId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
            }
        )
    }

    fun sendCallAnswer(
        senderId: String,
        receiverId: String,
        sdp: String,
        callId: String = ""
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "call_answer")
                if (callId.isNotBlank()) put("callId", callId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
                put("sdp", sdp)
            }
        )
    }

    fun sendCallConnected(
        callId: String,
        senderId: String,
        receiverId: String
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "call_connected")
                if (callId.isNotBlank()) put("callId", callId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
            }
        )
    }

    fun sendIceCandidate(
        senderId: String,
        receiverId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String,
        callId: String = ""
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "ice_candidate")
                if (callId.isNotBlank()) put("callId", callId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }
        )
    }

    fun sendCallEnd(
        senderId: String,
        receiverId: String,
        isReject: Boolean = false,
        callId: String = ""
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", if (isReject) "call_reject" else "call_end")
                if (callId.isNotBlank()) put("callId", callId)
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("toUserId", receiverId)
                put("to", receiverId)
            }
        )
    }

    private fun sendRaw(json: JSONObject): Boolean {
        return webSocket?.send(json.toString()) == true
    }
}
