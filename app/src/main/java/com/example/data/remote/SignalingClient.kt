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
        val status: String
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
    private val shouldStayConnected = AtomicBoolean(false)
    private var reconnectAttempt = 0

    fun connect() {
        shouldStayConnected.set(true)
        if (_isConnected.value && webSocket != null) return
        openSocket()
    }

    fun disconnect() {
        shouldStayConnected.set(false)
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _isConnected.value = false
    }

    fun emitExternalEvent(event: SignalingEvent) {
        _events.tryEmit(event)
    }

    private fun openSocket() {
        scope.launch {
            val session = sessionManager.getCurrentSession() ?: return@launch
            val wsUrl = "${NetworkModule.WS_URL}?userId=${Uri.encode(session.userId)}&deviceLockId=${Uri.encode(session.deviceLockId)}"
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${session.sessionToken}")
                .header("X-Device-Lock", session.deviceLockId)
                .build()

            webSocket?.cancel()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _isConnected.value = true
                    reconnectAttempt = 0
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.READY)
                    sendRaw(
                        JSONObject().apply {
                            put("type", "auth")
                            put("userId", session.userId)
                            put("displayName", session.displayName)
                            put("deviceLockId", session.deviceLockId)
                        }
                    )
                    _events.tryEmit(SignalingEvent.SocketConnected)
                    startHeartbeat(session.userId)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseAndDispatch(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    _isConnected.value = false
                    if (code == 4003 || reason.contains("403") || reason.contains("blocked", ignoreCase = true)) {
                        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _isConnected.value = false
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _isConnected.value = false
                    if (response?.code == 403) {
                        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                        shouldStayConnected.set(false)
                        return
                    }
                    scheduleReconnect()
                }
            })
        }
    }

    private fun startHeartbeat(userId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (shouldStayConnected.get() && _isConnected.value) {
                delay(20_000L)
                sendRaw(
                    JSONObject().apply {
                        put("type", "ping")
                        put("userId", userId)
                        put("timestamp", System.currentTimeMillis())
                    }
                )
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val backoffMs = (1500L * reconnectAttempt).coerceAtMost(15_000L)
            delay(backoffMs)
            if (shouldStayConnected.get()) {
                openSocket()
            }
        }
    }

    private fun parseAndDispatch(raw: String) {
        try {
            val json = JSONObject(raw)
            val data = json.optJSONObject("data") ?: json.optJSONObject("payload") ?: json
            when (json.optString("type").trim().lowercase()) {
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
                "receipt", "read_receipt", "delivered_receipt" -> {
                    val messageId = data.optString("messageId", data.optString("message_id"))
                        .takeIf { it.isNotBlank() && it != "null" }
                    val convId = data.optString(
                        "peerId",
                        data.optString("senderId", data.optString("sender_id"))
                    )
                    val status = data.optString("status", "READ")
                    if (convId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.ReceiptUpdated(messageId, convId, status))
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
                "presence" -> {
                    val userId = data.optString("userId", data.optString("user_id"))
                    val online = data.optBoolean("isOnline", data.optBoolean("is_online", false))
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
                    val peerId = firstNonBlank(data, "senderId", "sender_id", "from", "calleeId", "callee_id", "receiverId", "callerId").orEmpty()
                    val callId = firstNonBlank(data, "callId", "call_id").orEmpty()
                    _events.tryEmit(SignalingEvent.CallRinging(callId = callId, peerId = peerId))
                }
                "call_answer", "answer" -> {
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
                        val sdp = data.optString("sdp")
                        if (!peerId.isNullOrBlank() && sdp.isNotBlank()) {
                            _events.tryEmit(
                                SignalingEvent.CallAnswerReceived(
                                    callerId = peerId,
                                    sdp = sdp,
                                    callId = firstNonBlank(data, "callId", "call_id").orEmpty()
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
                    val peerId = firstNonBlank(data, "senderId", "sender_id", "from", "peerId", "callerId").orEmpty()
                    val callId = firstNonBlank(data, "callId", "call_id").orEmpty()
                    _events.tryEmit(
                        SignalingEvent.CallEnded(
                            peerId = peerId,
                            reason = json.optString("type"),
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
                put("callerName", senderName)
                put("receiverId", receiverId)
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
                put("sdp", sdp)
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
            }
        )
    }

    private fun sendRaw(json: JSONObject): Boolean {
        return webSocket?.send(json.toString()) == true
    }
}
