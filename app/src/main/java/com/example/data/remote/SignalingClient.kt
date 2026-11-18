package com.example.data.remote

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

    data class CallAnswerReceived(val callerId: String, val sdp: String) : SignalingEvent
    data class IceCandidateReceived(
        val senderId: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    ) : SignalingEvent

    data class CallEnded(val peerId: String, val reason: String) : SignalingEvent
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

    private fun openSocket() {
        scope.launch {
            val session = sessionManager.getCurrentSession() ?: return@launch
            val wsUrl = "${NetworkModule.WS_URL}?userId=${session.userId}&deviceLockId=${session.deviceLockId}"
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
            when (json.optString("type")) {
                "blocked", "error_403" -> {
                    GlobalSecurityEventBus.emitBlocked(
                        json.optString("message").ifBlank { "You are blocked by the administrator." }
                    )
                }
                "chat_message", "message" -> {
                    val data = json.optJSONObject("data") ?: json
                    val dto = RemoteMessageDto(
                        id = data.optString("id"),
                        senderId = data.optString("senderId"),
                        receiverId = data.optString("receiverId"),
                        content = data.optString("content"),
                        type = data.optString("messageType", data.optString("type", "TEXT")),
                        mediaUrl = data.optString("mediaUrl").takeIf { it.isNotBlank() && it != "null" },
                        fileName = data.optString("fileName").takeIf { it.isNotBlank() && it != "null" },
                        fileSizeBytes = if (data.has("fileSizeBytes") && !data.isNull("fileSizeBytes")) data.optLong("fileSizeBytes") else null,
                        latitude = if (data.has("latitude") && !data.isNull("latitude")) data.optDouble("latitude") else null,
                        longitude = if (data.has("longitude") && !data.isNull("longitude")) data.optDouble("longitude") else null,
                        liveLocationActive = data.optBoolean("liveLocationActive", false),
                        status = data.optString("status", "DELIVERED"),
                        timestamp = data.optLong("timestamp", System.currentTimeMillis())
                    )
                    if (dto.id.isNotBlank() && dto.senderId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.ChatMessageReceived(dto))
                    }
                }
                "delete_message" -> {
                    val messageId = json.optString("messageId")
                    val convId = json.optString("conversationId", json.optString("senderId"))
                    if (messageId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.MessageDeleted(messageId, convId))
                    }
                }
                "receipt", "read_receipt", "delivered_receipt" -> {
                    val messageId = json.optString("messageId").takeIf { it.isNotBlank() }
                    val convId = json.optString("peerId", json.optString("senderId"))
                    val status = json.optString("status", "READ")
                    if (convId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.ReceiptUpdated(messageId, convId, status))
                    }
                }
                "typing" -> {
                    val userId = json.optString("senderId", json.optString("userId"))
                    val isTyping = json.optBoolean("isTyping", false)
                    if (userId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.TypingStatusChanged(userId, isTyping))
                    }
                }
                "presence" -> {
                    val userId = json.optString("userId")
                    val online = json.optBoolean("isOnline", false)
                    if (userId.isNotBlank()) {
                        _events.tryEmit(SignalingEvent.PresenceChanged(userId, online))
                    }
                }
                "live_location" -> {
                    _events.tryEmit(
                        SignalingEvent.LiveLocationUpdated(
                            messageId = json.optString("messageId"),
                            senderId = json.optString("senderId"),
                            latitude = json.optDouble("latitude"),
                            longitude = json.optDouble("longitude"),
                            active = json.optBoolean("active", true)
                        )
                    )
                }
                "call_offer", "offer" -> {
                    _events.tryEmit(
                        SignalingEvent.IncomingCallOffer(
                            callId = json.optString("callId", System.currentTimeMillis().toString()),
                            callerId = json.optString("callerId", json.optString("senderId")),
                            callerName = json.optString("callerName", json.optString("callerId")),
                            isVideo = json.optBoolean("isVideo", false),
                            sdp = json.optString("sdp")
                        )
                    )
                }
                "call_answer", "answer" -> {
                    _events.tryEmit(
                        SignalingEvent.CallAnswerReceived(
                            callerId = json.optString("senderId", json.optString("callerId")),
                            sdp = json.optString("sdp")
                        )
                    )
                }
                "ice_candidate" -> {
                    _events.tryEmit(
                        SignalingEvent.IceCandidateReceived(
                            senderId = json.optString("senderId"),
                            sdpMid = json.optString("sdpMid"),
                            sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                            candidate = json.optString("candidate")
                        )
                    )
                }
                "call_end", "call_reject" -> {
                    _events.tryEmit(
                        SignalingEvent.CallEnded(
                            peerId = json.optString("senderId", json.optString("peerId")),
                            reason = json.optString("type")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore malformed non-JSON heartbeat frames
        }
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
                put("callerName", senderName)
                put("receiverId", receiverId)
                put("isVideo", isVideo)
                put("sdp", sdp)
            }
        )
    }

    fun sendCallAnswer(senderId: String, receiverId: String, sdp: String) {
        sendRaw(
            JSONObject().apply {
                put("type", "call_answer")
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
        candidate: String
    ) {
        sendRaw(
            JSONObject().apply {
                put("type", "ice_candidate")
                put("senderId", senderId)
                put("receiverId", receiverId)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }
        )
    }

    fun sendCallEnd(senderId: String, receiverId: String, isReject: Boolean = false) {
        sendRaw(
            JSONObject().apply {
                put("type", if (isReject) "call_reject" else "call_end")
                put("senderId", senderId)
                put("receiverId", receiverId)
            }
        )
    }

    private fun sendRaw(json: JSONObject): Boolean {
        return webSocket?.send(json.toString()) == true
    }
}
