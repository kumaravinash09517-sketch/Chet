package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.IncomingMessageAlert
import com.example.core.security.ServerStatus
import com.example.core.security.SessionManager
import com.example.core.security.UserSession
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageStatus
import com.example.data.local.MessageType
import com.example.data.local.UserEntity
import com.example.data.remote.AdminWebhookEventRequest
import com.example.data.remote.ApiService
import com.example.data.remote.BackendJsonParsers
import com.example.data.remote.CallSignalRequest
import com.example.data.remote.DirectLoginRequest
import com.example.data.remote.IceServerDto
import com.example.data.remote.RegisterUserRequest
import com.example.data.remote.RemoteCallSignalDto
import com.example.data.remote.RemoteMessageDto
import com.example.data.remote.RemoteUserDto
import com.example.data.remote.SendMessageRequest
import com.example.data.remote.SendOtpRequest
import com.example.data.remote.SendOtpResponse
import com.example.data.remote.SignalingClient
import com.example.data.remote.SignalingEvent
import com.example.data.remote.VerifyOtpRequest
import com.example.data.remote.VerifyOtpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object PhoneAuthUtils {
    fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            hasPlus -> "+$digits"
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> "+$digits"
        }
    }

    fun parseApiErrorMessage(rawErrorBody: String?, statusCode: Int, fallback: String): String {
        val body = rawErrorBody?.trim().orEmpty()
        if (body.isNotEmpty()) {
            try {
                val json = JSONObject(body)
                val detail = json.opt("detail")
                when (detail) {
                    is String -> if (detail.isNotBlank()) return detail
                    is JSONArray -> {
                        val firstObj = detail.optJSONObject(0)
                        val objMsg = if (firstObj != null && !firstObj.isNull("msg")) {
                            firstObj.optString("msg")
                        } else {
                            ""
                        }
                        if (objMsg.isNotBlank()) return objMsg
                        val strItem = if (!detail.isNull(0)) detail.optString(0) else ""
                        if (strItem.isNotBlank()) return strItem
                    }
                }
                val message = if (!json.isNull("message")) json.optString("message") else ""
                if (message.isNotBlank()) return message
                val error = if (!json.isNull("error")) json.optString("error") else ""
                if (error.isNotBlank()) return error
            } catch (_: Exception) {
                if (body.length <= 160 && !body.startsWith("<")) {
                    return body
                }
            }
        }
        return "$fallback ($statusCode)"
    }

    fun isProfileSetupRequiredError(rawErrorBody: String?, parsedMessage: String?): Boolean {
        val raw = rawErrorBody?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            try {
                val json = JSONObject(raw)
                val trueFlags = listOf(
                    "verified",
                    "otp_verified",
                    "otpVerified",
                    "requires_profile",
                    "requiresProfile",
                    "requires_name",
                    "requiresName",
                    "needs_profile",
                    "needsProfile",
                    "needs_name",
                    "needsName",
                    "needs_registration",
                    "needsRegistration",
                    "is_new_user",
                    "isNewUser"
                )
                if (trueFlags.any { key -> json.optBoolean(key, false) }) {
                    return true
                }
                val status = if (!json.isNull("status")) json.optString("status").trim().lowercase() else ""
                if (status in setOf(
                        "verified",
                        "profile_required",
                        "requires_profile",
                        "requires_name",
                        "needs_profile",
                        "needs_name",
                        "needs_registration",
                        "new_user"
                    )
                ) {
                    return true
                }
            } catch (_: Exception) {
                // Non-JSON body handled by text heuristic below
            }
        }

        val combined = listOfNotNull(parsedMessage, raw)
            .joinToString(" ")
            .trim()
        if (combined.isBlank()) return false

        val lower = combined.lowercase(Locale.ROOT)
        val otpFailureTokens = listOf(
            "invalid otp",
            "wrong otp",
            "incorrect otp",
            "expired",
            "otp not found",
            "otp mismatch",
            "invalid code",
            "wrong code",
            "incorrect code",
            "too many attempts",
            "blocked",
            "banned",
            "गलत",
            "अमान्य",
            "एक्सपायर",
            "समाप्त"
        )
        if (otpFailureTokens.any { lower.contains(it) }) {
            return false
        }

        val hindiOrHinglishIndicators = listOf(
            "नाम",
            "प्रोफ़ाइल",
            "प्रोफाइल",
            "naam"
        )
        if (hindiOrHinglishIndicators.any { combined.contains(it, ignoreCase = true) }) {
            return true
        }

        val englishProfilePhrases = listOf(
            "display name",
            "displayname",
            "display_name",
            "full name",
            "your name",
            "enter name",
            "name required",
            "name is required",
            "missing name",
            "profile setup",
            "complete profile",
            "complete registration",
            "new user"
        )
        if (englishProfilePhrases.any { lower.contains(it) }) {
            return true
        }

        return Regex("\\bname\\b").containsMatchIn(lower)
    }

    fun buildProfileSetupResponseFromError(rawErrorBody: String?, parsedMessage: String?): VerifyOtpResponse {
        var token: String? = null
        var userId: String? = null
        var displayName: String? = null
        var avatarUrl: String? = null
        if (!rawErrorBody.isNullOrBlank()) {
            try {
                val json = JSONObject(rawErrorBody)
                token = listOf("token", "access_token")
                    .firstNotNullOfOrNull { k -> if (!json.isNull(k)) json.optString(k).takeIf { it.isNotBlank() } else null }
                userId = listOf("userId", "user_id")
                    .firstNotNullOfOrNull { k -> if (!json.isNull(k)) json.optString(k).takeIf { it.isNotBlank() } else null }
                displayName = listOf("displayName", "display_name")
                    .firstNotNullOfOrNull { k -> if (!json.isNull(k)) json.optString(k).takeIf { it.isNotBlank() } else null }
                avatarUrl = listOf("avatarUrl", "avatar_url")
                    .firstNotNullOfOrNull { k -> if (!json.isNull(k)) json.optString(k).takeIf { it.isNotBlank() } else null }
            } catch (_: Exception) {
                // Ignore JSON parse fallback
            }
        }
        return VerifyOtpResponse(
            success = true,
            verified = true,
            requiresProfile = true,
            token = token,
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            message = parsedMessage
        )
    }
}

object CallSignalDeduplicator {
    private const val MAX_KEYS = 400
    private val seenKeys = LinkedHashSet<String>()
    private val dismissedCallIds = LinkedHashSet<String>()

    fun buildKey(
        type: String,
        callId: String,
        senderId: String,
        payload: String = "",
        signalId: String? = null
    ): String {
        val cleanId = signalId?.trim()?.takeIf { it.isNotBlank() }
        if (cleanId != null) {
            return "${type.lowercase()}|id:$cleanId|${senderId.lowercase()}|${payload.hashCode()}"
        }
        return "${type.lowercase()}|$callId|${senderId.lowercase()}|${payload.hashCode()}"
    }

    @Synchronized
    fun markIfNew(key: String): Boolean {
        if (seenKeys.contains(key)) return false
        seenKeys.add(key)
        while (seenKeys.size > MAX_KEYS) {
            val first = seenKeys.firstOrNull() ?: break
            seenKeys.remove(first)
        }
        return true
    }

    @Synchronized
    fun markDismissed(callId: String) {
        val clean = callId.trim()
        if (clean.isEmpty()) return
        dismissedCallIds.add(clean)
        while (dismissedCallIds.size > 100) {
            val first = dismissedCallIds.firstOrNull() ?: break
            dismissedCallIds.remove(first)
        }
    }

    @Synchronized
    fun isDismissed(callId: String): Boolean {
        val clean = callId.trim()
        return clean.isNotEmpty() && dismissedCallIds.contains(clean)
    }

    @Synchronized
    fun clear() {
        seenKeys.clear()
        dismissedCallIds.clear()
    }
}

class ChatRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val apiService: ApiService,
    private val signalingClient: SignalingClient,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userDao = database.userDao()
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()

    private var callPollingJob: Job? = null

    @Volatile
    private var fastCallPolling: Boolean = false

    @Volatile
    private var cachedIceServers: List<IceServerDto> = emptyList()

    val sessionFlow: Flow<UserSession?> = sessionManager.sessionFlow
    val conversationsFlow: Flow<List<ConversationEntity>> = conversationDao.observeConversations()
    val usersFlow: Flow<List<UserEntity>> = userDao.observeAllUsers()
    val totalUnreadCountFlow: Flow<Int?> = conversationDao.observeTotalUnreadCount()

    init {
        observeSignalingEvents()
        observeSessionForCallPolling()
    }

    fun observeMessages(peerId: String): Flow<List<MessageEntity>> =
        messageDao.observeMessages(peerId)

    fun setFastCallPolling(active: Boolean) {
        fastCallPolling = active
    }

    fun markCallDismissed(callId: String) {
        CallSignalDeduplicator.markDismissed(callId)
    }

    fun isCallDismissed(callId: String): Boolean = CallSignalDeduplicator.isDismissed(callId)

    private fun observeSessionForCallPolling() {
        scope.launch {
            sessionFlow
                .distinctUntilChangedBy { it?.userId }
                .collect { session ->
                    callPollingJob?.cancel()
                    if (session != null) {
                        startCallPollingLoop()
                    }
                }
        }
    }

    private fun startCallPollingLoop() {
        callPollingJob?.cancel()
        callPollingJob = scope.launch {
            var includeActive = true
            while (isActive) {
                pollIncomingCallsOnce(includeActiveCalls = includeActive || fastCallPolling)
                includeActive = false
                delay(if (fastCallPolling) 850L else 2_000L)
            }
        }
    }

    suspend fun pollIncomingCallsOnce(includeActiveCalls: Boolean = false) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        val collected = mutableListOf<RemoteCallSignalDto>()

        runCatching {
            val pollResp = apiService.pollCallSignals(myId)
            if (pollResp.isSuccessful) {
                collected.addAll(BackendJsonParsers.parseCallSignalsFromBody(pollResp.body()?.string()))
            }
        }

        if (includeActiveCalls) {
            runCatching {
                val activeResp = apiService.getActiveCalls(myId)
                if (activeResp.isSuccessful) {
                    collected.addAll(BackendJsonParsers.parseCallSignalsFromBody(activeResp.body()?.string()))
                }
            }
        }

        for (rawSignal in collected) {
            // If backend active/poll row keeps callerId == myId without senderId on response states (answer/ringing/connected/decline),
            // infer peer from receiverId so caller does not drop callee's response as a self-echo.
            val signal = if (
                rawSignal.type in setOf("answer", "ringing", "connected", "decline") &&
                rawSignal.senderId.equals(myId, ignoreCase = true) &&
                rawSignal.receiverId.isNotBlank() &&
                !rawSignal.receiverId.equals(myId, ignoreCase = true)
            ) {
                rawSignal.copy(
                    senderId = rawSignal.receiverId,
                    receiverId = myId
                )
            } else {
                rawSignal
            }

            if (signal.senderId.equals(myId, ignoreCase = true)) continue
            if (signal.receiverId.isNotBlank() && !signal.receiverId.equals(myId, ignoreCase = true)) continue
            if (signal.type == "offer" && CallSignalDeduplicator.isDismissed(signal.callId)) continue

            val payloadHint = when (signal.type) {
                "offer", "answer", "connected" -> signal.sdp
                "ice" -> "${signal.candidate}|${signal.sdpMid}|${signal.sdpMLineIndex}"
                else -> ""
            }
            val key = CallSignalDeduplicator.buildKey(
                type = signal.type,
                callId = signal.callId,
                senderId = signal.senderId,
                payload = payloadHint,
                signalId = signal.signalId
            )
            if (!CallSignalDeduplicator.markIfNew(key)) continue

            when (signal.type) {
                "offer" -> {
                    if (signal.sdp.isNotBlank()) {
                        signalingClient.emitExternalEvent(
                            SignalingEvent.IncomingCallOffer(
                                callId = signal.callId,
                                callerId = signal.senderId,
                                callerName = signal.senderName,
                                isVideo = signal.isVideo,
                                sdp = signal.sdp
                            )
                        )
                    }
                }
                "ringing" -> {
                    signalingClient.emitExternalEvent(
                        SignalingEvent.CallRinging(
                            callId = signal.callId,
                            peerId = signal.senderId
                        )
                    )
                }
                "answer" -> {
                    if (signal.sdp.isNotBlank()) {
                        signalingClient.emitExternalEvent(
                            SignalingEvent.CallAnswerReceived(
                                callerId = signal.senderId,
                                sdp = signal.sdp,
                                callId = signal.callId
                            )
                        )
                    }
                }
                "connected" -> {
                    if (signal.sdp.isNotBlank()) {
                        signalingClient.emitExternalEvent(
                            SignalingEvent.CallAnswerReceived(
                                callerId = signal.senderId,
                                sdp = signal.sdp,
                                callId = signal.callId
                            )
                        )
                    }
                    signalingClient.emitExternalEvent(
                        SignalingEvent.CallConnected(
                            callId = signal.callId,
                            peerId = signal.senderId,
                            sdp = signal.sdp
                        )
                    )
                }
                "ice" -> {
                    if (signal.candidate.isNotBlank()) {
                        signalingClient.emitExternalEvent(
                            SignalingEvent.IceCandidateReceived(
                                senderId = signal.senderId,
                                sdpMid = signal.sdpMid,
                                sdpMLineIndex = signal.sdpMLineIndex,
                                candidate = signal.candidate,
                                callId = signal.callId
                            )
                        )
                    }
                }
                "hangup", "decline" -> {
                    CallSignalDeduplicator.markDismissed(signal.callId)
                    signalingClient.emitExternalEvent(
                        SignalingEvent.CallEnded(
                            peerId = signal.senderId,
                            reason = signal.type,
                            callId = signal.callId
                        )
                    )
                }
            }
        }
    }

    suspend fun sendCallSignal(
        type: String,
        callId: String,
        receiverId: String,
        isVideo: Boolean? = null,
        sdp: String? = null,
        candidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = sessionManager.getCurrentSession() ?: return@withContext false
        if (receiverId.isBlank() || callId.isBlank()) return@withContext false
        val isOffer = type.equals("offer", ignoreCase = true)
        return@withContext runCatching {
            val response = apiService.sendCallSignal(
                CallSignalRequest(
                    type = type,
                    callId = callId,
                    senderId = session.userId,
                    callerId = if (isOffer) session.userId else null,
                    senderName = session.displayName,
                    callerName = if (isOffer) session.displayName else null,
                    receiverId = receiverId,
                    isVideo = isVideo,
                    sdp = sdp,
                    candidate = candidate,
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex
                )
            )
            response.isSuccessful
        }.getOrDefault(false)
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                val myId = sessionManager.getCurrentSession()?.userId ?: return@collect
                when (event) {
                    is SignalingEvent.SocketConnected -> {
                        syncUserDirectory()
                        retryPendingMessages()
                        pollIncomingCallsOnce(includeActiveCalls = true)
                        launch { fetchIceServers() }
                    }
                    is SignalingEvent.DirectoryUpdated -> {
                        syncUserDirectory()
                    }
                    is SignalingEvent.ChatMessageReceived -> {
                        handleIncomingMessage(event.dto, myId)
                    }
                    is SignalingEvent.MessageDeleted -> {
                        handleRemoteDelete(event.messageId, event.conversationId)
                    }
                    is SignalingEvent.ReceiptUpdated -> {
                        val status = runCatching { MessageStatus.valueOf(event.status) }
                            .getOrDefault(MessageStatus.DELIVERED)
                        if (event.messageId != null) {
                            messageDao.updateMessageStatus(event.messageId, status)
                            conversationDao.updateLastMessageStatus(
                                event.conversationId,
                                event.messageId,
                                status
                            )
                        } else if (status == MessageStatus.READ) {
                            messageDao.markMyMessagesRead(event.conversationId, myId)
                        }
                    }
                    is SignalingEvent.TypingStatusChanged -> {
                        conversationDao.updatePeerTyping(event.userId, event.isTyping)
                    }
                    is SignalingEvent.PresenceChanged -> {
                        val existingUser = userDao.getUser(event.userId)
                        if (existingUser != null) {
                            userDao.updateOnlineStatus(event.userId, event.isOnline)
                        } else {
                            syncUserDirectory()
                        }
                    }
                    is SignalingEvent.LiveLocationUpdated -> {
                        messageDao.updateLiveLocation(
                            messageId = event.messageId,
                            latitude = event.latitude,
                            longitude = event.longitude,
                            active = event.active
                        )
                    }
                    is SignalingEvent.IncomingCallOffer -> {
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey("offer", event.callId, event.callerId, event.sdp)
                        )
                    }
                    is SignalingEvent.CallRinging -> {
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey("ringing", event.callId, event.peerId)
                        )
                    }
                    is SignalingEvent.CallAnswerReceived -> {
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey("answer", event.callId, event.callerId, event.sdp)
                        )
                    }
                    is SignalingEvent.CallConnected -> {
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey("connected", event.callId, event.peerId, event.sdp)
                        )
                    }
                    is SignalingEvent.IceCandidateReceived -> {
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey(
                                "ice",
                                event.callId,
                                event.senderId,
                                "${event.candidate}|${event.sdpMid}|${event.sdpMLineIndex}"
                            )
                        )
                    }
                    is SignalingEvent.CallEnded -> {
                        val normType = BackendJsonParsers.normalizeCallSignalType(event.reason) ?: "hangup"
                        CallSignalDeduplicator.markIfNew(
                            CallSignalDeduplicator.buildKey(normType, event.callId, event.peerId)
                        )
                        if (event.callId.isNotBlank()) {
                            CallSignalDeduplicator.markDismissed(event.callId)
                        }
                    }
                }
            }
        }
    }

    suspend fun warmUpServer() = withContext(Dispatchers.IO) {
        try {
            GlobalSecurityEventBus.updateServerStatus(ServerStatus.WAKING_UP)
            val response = apiService.pingHealth()
            if (response.isSuccessful) {
                GlobalSecurityEventBus.updateServerStatus(ServerStatus.READY)
            }
        } catch (_: Exception) {
            GlobalSecurityEventBus.updateServerStatus(ServerStatus.OFFLINE)
        }
    }

    suspend fun getDeviceLockId(): String = sessionManager.getOrCreateDeviceLockId()

    suspend fun sendOtp(phone: String): Result<SendOtpResponse> = withContext(Dispatchers.IO) {
        val normalizedPhone = PhoneAuthUtils.normalizePhoneNumber(phone)
        if (normalizedPhone.filter { it.isDigit() }.length < 8) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid phone number."))
        }
        val deviceId = sessionManager.getOrCreateDeviceLockId()
        return@withContext try {
            val response = apiService.sendOtp(SendOtpRequest(phone = normalizedPhone, deviceId = deviceId))
            if (response.code() == 403) {
                GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
            }
            if (!response.isSuccessful) {
                val errorMsg = PhoneAuthUtils.parseApiErrorMessage(
                    rawErrorBody = response.errorBody()?.string(),
                    statusCode = response.code(),
                    fallback = "OTP request failed"
                )
                return@withContext Result.failure(IllegalStateException(errorMsg))
            }
            val body = response.body() ?: SendOtpResponse(success = true)
            if (body.resolvedIsBlocked()) {
                GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
            }
            if (!body.success) {
                Result.failure(IllegalStateException(body.message ?: "Unable to send OTP."))
            } else {
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(phone: String, otp: String): Result<VerifyOtpResponse> =
        withContext(Dispatchers.IO) {
            val normalizedPhone = PhoneAuthUtils.normalizePhoneNumber(phone)
            val cleanOtp = otp.trim()
            if (normalizedPhone.filter { it.isDigit() }.length < 8) {
                return@withContext Result.failure(IllegalArgumentException("Enter a valid phone number."))
            }
            if (cleanOtp.length < 4) {
                return@withContext Result.failure(IllegalArgumentException("Enter the verification code."))
            }
            val deviceId = sessionManager.getOrCreateDeviceLockId()
            return@withContext try {
                val response = apiService.verifyOtp(
                    VerifyOtpRequest(phone = normalizedPhone, otp = cleanOtp, deviceId = deviceId)
                )
                if (response.code() == 403) {
                    GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                    return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
                }
                if (!response.isSuccessful) {
                    val rawError = response.errorBody()?.string()
                    val errorMsg = PhoneAuthUtils.parseApiErrorMessage(
                        rawErrorBody = rawError,
                        statusCode = response.code(),
                        fallback = "OTP verification failed"
                    )
                    if ((response.code() == 400 || response.code() == 422) &&
                        PhoneAuthUtils.isProfileSetupRequiredError(rawError, errorMsg)
                    ) {
                        return@withContext Result.success(
                            PhoneAuthUtils.buildProfileSetupResponseFromError(rawError, errorMsg)
                        )
                    }
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }
                val body = response.body() ?: VerifyOtpResponse(success = true)
                if (body.resolvedIsBlocked()) {
                    GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                    return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
                }
                if (!body.isOtpVerified()) {
                    Result.failure(IllegalStateException(body.message ?: "Invalid OTP code."))
                } else {
                    Result.success(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun findCachedUserProfile(phone: String, userId: String?): UserEntity? =
        withContext(Dispatchers.IO) {
            val cleanId = userId?.trim().orEmpty()
            if (cleanId.isNotEmpty()) {
                userDao.getUser(cleanId)?.let { return@withContext it }
            }
            val cleanPhone = PhoneAuthUtils.normalizePhoneNumber(phone)
            val tail10 = cleanPhone.filter { it.isDigit() }.takeLast(10)
            if (tail10.isNotEmpty()) {
                val matches = userDao.searchUsers(tail10)
                matches.firstOrNull { !it.isBlocked }?.let { return@withContext it }
            }
            null
        }

    suspend fun completeVerifiedLogin(
        phone: String,
        resp: VerifyOtpResponse
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        if (resp.resolvedIsBlocked()) {
            GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
            return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
        }
        val cleanPhone = PhoneAuthUtils.normalizePhoneNumber(phone).ifBlank { phone.trim() }
        val fallbackId = cleanPhone.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { "u_$it" } ?: ""
        val cleanId = resp.resolvedUserId()?.trim()?.ifBlank { fallbackId } ?: fallbackId
        val cleanName = resp.resolvedDisplayName()?.trim().orEmpty()
        if (cleanId.isBlank() || cleanName.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Profile details missing."))
        }
        if (!sessionManager.canRegisterUser(cleanId, cleanPhone)) {
            return@withContext Result.failure(
                IllegalStateException("This device is locked to another registered account.")
            )
        }

        val now = System.currentTimeMillis()
        val formatted = formatDateTime(now)
        val hardwareDeviceId = sessionManager.getOrCreateDeviceLockId()
        val resolvedAvatar = resp.resolvedAvatarUrl()

        val session = sessionManager.saveSession(
            userId = cleanId,
            displayName = cleanName,
            phone = cleanPhone,
            avatarUrl = resolvedAvatar,
            serverToken = resp.resolvedToken(),
            registeredAtMillis = now,
            registeredAtFormatted = formatted,
            deviceIdOverride = hardwareDeviceId
        )

        userDao.upsertUser(
            UserEntity(
                userId = cleanId,
                displayName = cleanName,
                phone = cleanPhone,
                avatarUrl = resolvedAvatar,
                lastLoginMillis = now,
                lastLoginFormatted = formatted,
                isOnline = true
            )
        )

        runCatching {
            apiService.sendAdminWebhook(
                AdminWebhookEventRequest(
                    eventType = "USER_LOGIN",
                    userId = cleanId,
                    displayName = cleanName,
                    timestamp = now,
                    summary = "User $cleanName (@$cleanId, $cleanPhone) logged in at $formatted"
                )
            )
        }

        signalingClient.connect()
        syncUserDirectory()
        Result.success(session)
    }

    suspend fun uploadAvatarPhoto(uri: Uri, senderHint: String): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val (rawName, _) = queryUriMetadata(uri)
                val safeName = ensureFileNameWithExtension(
                    rawName = rawName,
                    mimeType = mimeType,
                    fallbackPrefix = "avatar_${System.currentTimeMillis()}",
                    defaultExt = "jpg"
                )
                val bytes = readBytesFromUri(uri)
                    ?: return@withContext Result.failure(IllegalStateException("Unable to read selected image."))
                val url = uploadMediaBytesToBackend(
                    bytes = bytes,
                    fileName = safeName,
                    mimeType = mimeType,
                    mediaType = "AVATAR",
                    senderId = senderHint
                )
                if (!url.isNullOrBlank()) {
                    Result.success(url)
                } else {
                    Result.failure(IllegalStateException("Photo upload failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun loginDirect(
        userId: String,
        displayName: String,
        avatarUrl: String? = null,
        deviceId: String = ""
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        val cleanName = displayName.trim()
        val cleanId = userId.trim().replace(" ", "_")
        if (cleanName.isBlank() || cleanId.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Username and User ID are required.")
            )
        }
        if (!sessionManager.canRegisterUser(cleanId, "")) {
            return@withContext Result.failure(
                IllegalStateException("This device is locked to another registered account.")
            )
        }

        val now = System.currentTimeMillis()
        val formatted = formatDateTime(now)
        val hardwareDeviceId = sessionManager.getOrCreateDeviceLockId()
        val effectiveDeviceId = deviceId.trim().ifBlank { hardwareDeviceId }

        return@withContext try {
            val response = apiService.loginDirect(
                DirectLoginRequest(
                    userId = cleanId,
                    username = cleanName,
                    displayName = cleanName,
                    deviceId = effectiveDeviceId,
                    deviceLockId = hardwareDeviceId,
                    avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
                    loginTimestamp = now,
                    loginDateFormatted = formatted
                )
            )

            if (response.code() == 403) {
                GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
            }

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.resolvedIsBlocked() == true) {
                    GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                    return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
                }

                val finalUserId = body?.resolvedUserId()?.takeIf { it.isNotBlank() } ?: cleanId
                val finalDisplayName = body?.resolvedDisplayName()?.takeIf { it.isNotBlank() } ?: cleanName
                val finalPhone = body?.resolvedPhone().orEmpty()
                if (!sessionManager.canRegisterUser(finalUserId, finalPhone)) {
                    return@withContext Result.failure(
                        IllegalStateException("This device is locked to another registered account.")
                    )
                }

                val resolvedAvatar = body?.resolvedAvatarUrl() ?: BackendJsonParsers.normalizeMediaUrl(avatarUrl)
                val session = sessionManager.saveSession(
                    userId = finalUserId,
                    displayName = finalDisplayName,
                    phone = finalPhone,
                    avatarUrl = resolvedAvatar,
                    serverToken = body?.resolvedToken(),
                    registeredAtMillis = now,
                    registeredAtFormatted = formatted,
                    deviceIdOverride = effectiveDeviceId
                )

                userDao.upsertUser(
                    UserEntity(
                        userId = finalUserId,
                        displayName = finalDisplayName,
                        phone = finalPhone,
                        avatarUrl = resolvedAvatar,
                        lastLoginMillis = now,
                        lastLoginFormatted = formatted,
                        isOnline = true
                    )
                )

                runCatching {
                    apiService.sendAdminWebhook(
                        AdminWebhookEventRequest(
                            eventType = "USER_LOGIN",
                            userId = finalUserId,
                            displayName = finalDisplayName,
                            timestamp = now,
                            summary = "User $finalDisplayName (@$finalUserId) logged in at $formatted"
                        )
                    )
                }

                signalingClient.connect()
                syncUserDirectory()
                return@withContext Result.success(session)
            }

            val code = response.code()
            val rawError = response.errorBody()?.string()
            // Fall back to /api/users/register if /api/auth/login is unavailable or user is new
            if (code in setOf(400, 404, 405, 422, 501)) {
                return@withContext registerOrLogin(
                    userId = cleanId,
                    displayName = cleanName,
                    phone = "",
                    deviceId = effectiveDeviceId,
                    avatarUrl = avatarUrl
                )
            }

            val errorMsg = PhoneAuthUtils.parseApiErrorMessage(
                rawErrorBody = rawError,
                statusCode = code,
                fallback = "Login failed"
            )
            Result.failure(IllegalStateException(errorMsg))
        } catch (e: Exception) {
            // Attempt /api/users/register fallback on transient serialization/path mismatch if needed
            registerOrLogin(
                userId = cleanId,
                displayName = cleanName,
                phone = "",
                deviceId = effectiveDeviceId,
                avatarUrl = avatarUrl
            )
        }
    }

    suspend fun registerOrLogin(
        userId: String,
        displayName: String,
        phone: String = "",
        deviceId: String = "",
        avatarUrl: String? = null,
        verifiedToken: String? = null
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        val cleanPhone = PhoneAuthUtils.normalizePhoneNumber(phone).ifBlank { phone.trim() }
        val fallbackId = cleanPhone.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { "u_$it" } ?: ""
        val cleanId = userId.trim().ifBlank { fallbackId }
        val cleanName = displayName.trim()
        if (cleanId.isBlank() || cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID and Display Name are required."))
        }

        if (!sessionManager.canRegisterUser(cleanId, cleanPhone)) {
            return@withContext Result.failure(
                IllegalStateException("This device is locked to another registered account.")
            )
        }

        val now = System.currentTimeMillis()
        val formatted = formatDateTime(now)
        val hardwareDeviceId = sessionManager.getOrCreateDeviceLockId()
        val effectiveDeviceId = deviceId.trim().ifBlank { hardwareDeviceId }

        return@withContext try {
            val response = apiService.registerUser(
                RegisterUserRequest(
                    userId = cleanId,
                    displayName = cleanName,
                    phone = cleanPhone,
                    deviceId = effectiveDeviceId,
                    deviceLockId = hardwareDeviceId,
                    avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
                    loginTimestamp = now,
                    loginDateFormatted = formatted
                )
            )

            if (response.code() == 403) {
                GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
            }

            if (!response.isSuccessful) {
                val errorMsg = PhoneAuthUtils.parseApiErrorMessage(
                    rawErrorBody = response.errorBody()?.string(),
                    statusCode = response.code(),
                    fallback = "Server registration error"
                )
                return@withContext Result.failure(IllegalStateException(errorMsg))
            }

            val body = response.body()
            if (body?.resolvedIsBlocked() == true) {
                GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                return@withContext Result.failure(SecurityException("You are blocked by the administrator."))
            }

            val finalUserId = body?.resolvedUserId()?.takeIf { it.isNotBlank() } ?: cleanId
            val finalDisplayName = body?.resolvedDisplayName()?.takeIf { it.isNotBlank() } ?: cleanName
            if (!sessionManager.canRegisterUser(finalUserId, cleanPhone)) {
                return@withContext Result.failure(
                    IllegalStateException("This device is locked to another registered account.")
                )
            }

            val resolvedAvatar = body?.resolvedAvatarUrl() ?: avatarUrl
            val session = sessionManager.saveSession(
                userId = finalUserId,
                displayName = finalDisplayName,
                phone = cleanPhone,
                avatarUrl = resolvedAvatar,
                serverToken = body?.resolvedToken() ?: verifiedToken,
                registeredAtMillis = now,
                registeredAtFormatted = formatted,
                deviceIdOverride = effectiveDeviceId
            )

            userDao.upsertUser(
                UserEntity(
                    userId = finalUserId,
                    displayName = finalDisplayName,
                    phone = cleanPhone,
                    avatarUrl = resolvedAvatar,
                    lastLoginMillis = now,
                    lastLoginFormatted = formatted,
                    isOnline = true
                )
            )

            // Notify backend admin webhook about verified registration/login
            runCatching {
                apiService.sendAdminWebhook(
                    AdminWebhookEventRequest(
                        eventType = "USER_LOGIN",
                        userId = finalUserId,
                        displayName = finalDisplayName,
                        timestamp = now,
                        summary = "User $finalDisplayName (@$finalUserId, $cleanPhone) logged in at $formatted"
                    )
                )
            }

            signalingClient.connect()
            syncUserDirectory()
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun RemoteUserDto.toUserEntity(): UserEntity? {
        val uid = resolvedUserId()
        if (uid.isBlank()) return null
        val rawMillis = resolvedLastLoginMillis()
        val millis = if (rawMillis > 0L) rawMillis else System.currentTimeMillis()
        val rawPhone = resolvedPhone()
        val formattedPhone = if (rawPhone.isNotBlank()) {
            PhoneAuthUtils.normalizePhoneNumber(rawPhone).ifBlank { rawPhone }
        } else {
            ""
        }
        return UserEntity(
            userId = uid,
            displayName = resolvedDisplayName().ifBlank { uid },
            phone = formattedPhone,
            avatarUrl = resolvedAvatarUrl(),
            lastLoginMillis = millis,
            lastLoginFormatted = resolvedLastLoginFormatted().ifBlank { formatDateTime(millis) },
            isOnline = resolvedIsOnline(),
            isBlocked = resolvedIsBlocked()
        )
    }

    suspend fun syncUserDirectory(): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = apiService.getUserDirectory()
            if (response.isSuccessful) {
                val rawBody = response.body()?.string()
                var remoteUsers = BackendJsonParsers.parseUsersFromBody(rawBody)
                if (remoteUsers.isEmpty()) {
                    runCatching {
                        val fallbackResp = apiService.findUsers("")
                        if (fallbackResp.isSuccessful) {
                            remoteUsers = BackendJsonParsers.parseUsersFromBody(fallbackResp.body()?.string())
                        }
                    }
                }
                val mapped = remoteUsers.mapNotNull { it.toUserEntity() }
                val currentSession = sessionManager.getCurrentSession()
                if (currentSession != null &&
                    mapped.any { it.userId.equals(currentSession.userId, ignoreCase = true) && it.isBlocked }
                ) {
                    GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
                }

                val mergedMap = linkedMapOf<String, UserEntity>()
                mapped.forEach { user ->
                    mergedMap[user.userId.lowercase(Locale.ROOT)] = user
                }
                if (currentSession != null) {
                    val selfKey = currentSession.userId.lowercase(Locale.ROOT)
                    val existingSelf = mergedMap[selfKey]
                    mergedMap[selfKey] = UserEntity(
                        userId = existingSelf?.userId ?: currentSession.userId,
                        displayName = currentSession.displayName.ifBlank { existingSelf?.displayName ?: currentSession.userId },
                        phone = currentSession.phone.ifBlank { existingSelf?.phone.orEmpty() },
                        avatarUrl = currentSession.avatarUrl ?: existingSelf?.avatarUrl,
                        lastLoginMillis = maxOf(currentSession.registeredAtMillis, existingSelf?.lastLoginMillis ?: 0L),
                        lastLoginFormatted = currentSession.registeredAtFormatted.ifBlank {
                            existingSelf?.lastLoginFormatted ?: formatDateTime(System.currentTimeMillis())
                        },
                        isOnline = true,
                        isBlocked = existingSelf?.isBlocked ?: false
                    )
                }

                val mergedUsers = mergedMap.values.toList()
                if (mergedUsers.isNotEmpty()) {
                    userDao.upsertUsers(mergedUsers)
                    mergedUsers.forEach { user ->
                        val existing = conversationDao.getConversation(user.userId)
                        if (existing != null &&
                            (existing.peerName != user.displayName ||
                                existing.peerPhone != user.phone ||
                                existing.peerAvatarUrl != user.avatarUrl)
                        ) {
                            conversationDao.upsertConversation(
                                existing.copy(
                                    peerName = user.displayName,
                                    peerPhone = user.phone,
                                    peerAvatarUrl = user.avatarUrl
                                )
                            )
                        }
                    }
                }
                Result.success(mergedUsers)
            } else {
                Result.failure(IllegalStateException("Directory sync failed: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchUsers(query: String): List<UserEntity> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()

        val digits = clean.filter { it.isDigit() }
        val normalizedPhone = if (digits.length >= 10) PhoneAuthUtils.normalizePhoneNumber(clean) else null

        val remoteFound = linkedMapOf<String, UserEntity>()
        suspend fun collectFromCall(call: suspend () -> retrofit2.Response<okhttp3.ResponseBody>) {
            runCatching {
                val resp = call()
                if (resp.isSuccessful) {
                    val parsed = BackendJsonParsers.parseUsersFromBody(resp.body()?.string())
                    parsed.mapNotNull { it.toUserEntity() }.forEach { user ->
                        remoteFound[user.userId] = user
                    }
                }
            }
        }

        // Primary: /api/users/find
        collectFromCall { apiService.findUsers(clean) }

        if (remoteFound.isEmpty() && normalizedPhone != null && normalizedPhone != clean) {
            collectFromCall { apiService.findUsers(normalizedPhone) }
        }
        if (remoteFound.isEmpty() && normalizedPhone != null) {
            collectFromCall { apiService.findUsersByPhone(normalizedPhone) }
        }
        if (remoteFound.isEmpty() && (clean.startsWith("u_", ignoreCase = true) || clean.startsWith("@"))) {
            collectFromCall { apiService.findUsersByUserId(clean.removePrefix("@")) }
        }
        if (remoteFound.isEmpty()) {
            collectFromCall { apiService.findUsersByQuery(clean) }
        }
        // Fallback: /api/users/search
        if (remoteFound.isEmpty()) {
            collectFromCall { apiService.searchUsers(clean) }
        }

        if (remoteFound.isNotEmpty()) {
            userDao.upsertUsers(remoteFound.values.toList())
        } else {
            // Ensure local directory has latest registered users from /api/users
            syncUserDirectory()
        }

        val localMerged = linkedMapOf<String, UserEntity>()
        remoteFound.values.forEach { localMerged[it.userId] = it }
        userDao.searchUsers(clean).forEach { localMerged.putIfAbsent(it.userId, it) }
        if (digits.length >= 4) {
            userDao.searchUsers(digits).forEach { localMerged.putIfAbsent(it.userId, it) }
            val tail10 = digits.takeLast(10)
            if (tail10 != digits) {
                userDao.searchUsers(tail10).forEach { localMerged.putIfAbsent(it.userId, it) }
            }
        }
        return@withContext localMerged.values.toList()
    }

    suspend fun startOrOpenConversation(peerId: String, peerName: String) = withContext(Dispatchers.IO) {
        val peerUser = userDao.getUser(peerId)
        val existing = conversationDao.getConversation(peerId)
        if (existing == null) {
            conversationDao.upsertConversation(
                ConversationEntity(
                    peerId = peerId,
                    peerName = peerUser?.displayName ?: peerName,
                    peerPhone = peerUser?.phone.orEmpty(),
                    peerAvatarUrl = peerUser?.avatarUrl,
                    lastMessagePreview = "Tap to start secure chat",
                    lastMessageTimestamp = System.currentTimeMillis()
                )
            )
        }
        markConversationAsRead(peerId)
        retryPendingMessages(peerId)
        syncMessagesWithServer(peerId)
    }

    suspend fun syncMessagesWithServer(peerId: String) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        try {
            val response = apiService.getMessages(peerId, myId)
            if (response.isSuccessful) {
                val remoteMessages = BackendJsonParsers.parseMessagesFromBody(response.body()?.string())
                if (remoteMessages.isNotEmpty()) {
                    val prevConv = conversationDao.getConversation(peerId)
                    val prevLastTs = prevConv?.lastMessageTimestamp ?: 0L
                    val newlyReceivedFromPeer = mutableListOf<MessageEntity>()

                    val entities = remoteMessages.map { dto ->
                        val incoming = dto.toEntity(conversationId = peerId)
                        val existing = messageDao.getMessageById(incoming.id)
                        if (existing != null) {
                            mergeMessageEntities(existing, incoming)
                        } else {
                            if (prevConv != null &&
                                !incoming.senderId.equals(myId, ignoreCase = true) &&
                                incoming.timestamp > prevLastTs
                            ) {
                                newlyReceivedFromPeer.add(incoming)
                            }
                            incoming
                        }
                    }
                    messageDao.upsertMessages(entities)
                    val latest = entities.maxByOrNull { it.timestamp }
                    if (latest != null) {
                        val existing = conversationDao.getConversation(peerId)
                        val peerUser = userDao.getUser(peerId)
                        conversationDao.upsertConversation(
                            ConversationEntity(
                                peerId = peerId,
                                peerName = existing?.peerName ?: peerUser?.displayName ?: peerId,
                                peerPhone = existing?.peerPhone?.ifBlank { peerUser?.phone.orEmpty() }
                                    ?: peerUser?.phone.orEmpty(),
                                peerAvatarUrl = existing?.peerAvatarUrl ?: peerUser?.avatarUrl,
                                lastMessageId = latest.id,
                                lastMessagePreview = previewForMessage(latest),
                                lastMessageType = latest.type,
                                lastMessageTimestamp = latest.timestamp,
                                lastMessageStatus = latest.status,
                                lastMessageIsMine = latest.senderId.equals(myId, ignoreCase = true),
                                unreadCount = existing?.unreadCount ?: 0,
                                isPeerTyping = existing?.isPeerTyping ?: false
                            )
                        )
                    }

                    newlyReceivedFromPeer.maxByOrNull { it.timestamp }?.let { newest ->
                        val peerUser = userDao.getUser(peerId)
                        val resolvedName = peerUser?.displayName ?: prevConv?.peerName ?: peerId
                        GlobalSecurityEventBus.emitIncomingMessageAlert(
                            IncomingMessageAlert(
                                messageId = newest.id,
                                peerId = peerId,
                                senderName = resolvedName,
                                headline = previewForMessage(newest),
                                timestamp = newest.timestamp
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Preserve local Room cache if network unavailable
        }
    }

    suspend fun sendTextMessage(peerId: String, peerName: String, text: String) {
        sendStructuredMessage(
            peerId = peerId,
            peerName = peerName,
            type = MessageType.TEXT,
            content = text.trim()
        )
    }

    suspend fun sendMediaMessage(
        peerId: String,
        peerName: String,
        uri: Uri,
        type: MessageType,
        caption: String = ""
    ) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val now = System.currentTimeMillis()
        val mimeType = context.contentResolver.getType(uri) ?: defaultMimeTypeFor(type)
        val (rawName, rawSize) = queryUriMetadata(uri)
        val safeFileName = ensureFileNameWithExtension(
            rawName = rawName,
            mimeType = mimeType,
            fallbackPrefix = "${type.name.lowercase(Locale.ROOT)}_$now",
            defaultExt = defaultExtensionFor(type)
        )
        val bytes = readBytesFromUri(uri)
        val cachedUriString = if (bytes != null) {
            cacheMediaFileLocally(bytes, safeFileName)?.let { Uri.fromFile(it).toString() } ?: uri.toString()
        } else {
            uri.toString()
        }
        val effectiveSize = rawSize ?: bytes?.size?.toLong()
        val messageId = UUID.randomUUID().toString()
        val cleanContent = caption.trim()

        val pendingEntity = MessageEntity(
            id = messageId,
            conversationId = peerId,
            senderId = myId,
            receiverId = peerId,
            content = cleanContent,
            type = type,
            localUri = cachedUriString,
            fileName = safeFileName,
            fileSizeBytes = effectiveSize,
            status = MessageStatus.PENDING,
            timestamp = now
        )
        persistOutgoingLocally(peerId, peerName, pendingEntity)

        val uploadedUrl = if (bytes != null) {
            uploadMediaBytesToBackend(
                bytes = bytes,
                fileName = safeFileName,
                mimeType = mimeType,
                mediaType = type.name,
                senderId = myId
            )
        } else {
            null
        }

        if (!uploadedUrl.isNullOrBlank()) {
            val updatedEntity = pendingEntity.copy(mediaUrl = uploadedUrl)
            messageDao.upsertMessage(updatedEntity)
            dispatchOutgoingMessage(updatedEntity, peerName)
        }
    }

    suspend fun sendLocationMessage(
        peerId: String,
        peerName: String,
        latitude: Double,
        longitude: Double,
        isLive: Boolean,
        label: String = "Shared Location"
    ): String? = withContext(Dispatchers.IO) {
        val type = if (isLive) MessageType.LIVE_LOCATION else MessageType.LOCATION
        sendStructuredMessage(
            peerId = peerId,
            peerName = peerName,
            type = type,
            content = label,
            latitude = latitude,
            longitude = longitude,
            liveLocationActive = isLive
        )
    }

    suspend fun updateLiveLocation(
        messageId: String,
        peerId: String,
        latitude: Double,
        longitude: Double,
        active: Boolean
    ) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        messageDao.updateLiveLocation(messageId, latitude, longitude, active)
        signalingClient.sendLiveLocationUpdate(
            messageId = messageId,
            senderId = myId,
            receiverId = peerId,
            latitude = latitude,
            longitude = longitude,
            active = active
        )
    }

    suspend fun sendContactCard(peerId: String, peerName: String, contactName: String, contactPhone: String) {
        sendStructuredMessage(
            peerId = peerId,
            peerName = peerName,
            type = MessageType.CONTACT,
            content = "$contactName|$contactPhone"
        )
    }

    private suspend fun sendStructuredMessage(
        peerId: String,
        peerName: String,
        type: MessageType,
        content: String,
        latitude: Double? = null,
        longitude: Double? = null,
        liveLocationActive: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext null
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = messageId,
            conversationId = peerId,
            senderId = myId,
            receiverId = peerId,
            content = content,
            type = type,
            latitude = latitude,
            longitude = longitude,
            liveLocationActive = liveLocationActive,
            status = MessageStatus.PENDING,
            timestamp = now
        )
        persistOutgoingLocally(peerId, peerName, entity)
        dispatchOutgoingMessage(entity, peerName)
        messageId
    }

    private suspend fun persistOutgoingLocally(
        peerId: String,
        peerName: String,
        entity: MessageEntity
    ) {
        messageDao.upsertMessage(entity)
        val existing = conversationDao.getConversation(peerId)
        val peerUser = userDao.getUser(peerId)
        conversationDao.upsertConversation(
            ConversationEntity(
                peerId = peerId,
                peerName = existing?.peerName ?: peerUser?.displayName ?: peerName,
                peerPhone = existing?.peerPhone?.ifBlank { peerUser?.phone.orEmpty() }
                    ?: peerUser?.phone.orEmpty(),
                peerAvatarUrl = existing?.peerAvatarUrl ?: peerUser?.avatarUrl,
                lastMessageId = entity.id,
                lastMessagePreview = previewForMessage(entity),
                lastMessageType = entity.type,
                lastMessageTimestamp = entity.timestamp,
                lastMessageStatus = entity.status,
                lastMessageIsMine = true,
                unreadCount = 0,
                isPeerTyping = existing?.isPeerTyping ?: false
            )
        )
    }

    private suspend fun dispatchOutgoingMessage(entity: MessageEntity, peerName: String) {
        val req = SendMessageRequest(
            id = entity.id,
            senderId = entity.senderId,
            receiverId = entity.receiverId,
            content = entity.content,
            type = entity.type.name,
            mediaUrl = entity.mediaUrl,
            fileName = entity.fileName,
            fileSizeBytes = entity.fileSizeBytes,
            latitude = entity.latitude,
            longitude = entity.longitude,
            liveLocationActive = entity.liveLocationActive,
            timestamp = entity.timestamp
        )

        val wsSent = signalingClient.sendChatMessage(req)
        if (!wsSent) {
            signalingClient.connect()
        }
        var finalStatus = if (wsSent) MessageStatus.SENT else MessageStatus.PENDING

        try {
            var response = apiService.sendMessage(req)
            if (response.code() == 400 || response.code() == 422) {
                response.errorBody()?.close()
                response = apiService.sendMessageSnakeCase(req.toSnakeCase())
            }
            if (response.isSuccessful) {
                val ackStatus = BackendJsonParsers.parseSendMessageStatus(response.body()?.string())
                val parsed = ackStatus?.let {
                    runCatching { MessageStatus.valueOf(it) }.getOrDefault(MessageStatus.SENT)
                } ?: MessageStatus.SENT
                finalStatus = maxStatus(finalStatus, parsed)
            }
        } catch (_: Exception) {
            // Keep WS status or PENDING for retry
        }

        val currentStored = messageDao.getMessageById(entity.id)
        val mergedStatus = maxStatus(currentStored?.status ?: entity.status, finalStatus)
        messageDao.upsertMessage(entity.copy(status = mergedStatus))
        conversationDao.updateLastMessageStatus(entity.conversationId, entity.id, mergedStatus)
        val existing = conversationDao.getConversation(entity.conversationId)
        if (existing == null) {
            val peerUser = userDao.getUser(entity.conversationId)
            conversationDao.upsertConversation(
                ConversationEntity(
                    peerId = entity.conversationId,
                    peerName = peerUser?.displayName ?: peerName,
                    peerPhone = peerUser?.phone.orEmpty(),
                    peerAvatarUrl = peerUser?.avatarUrl,
                    lastMessageId = entity.id,
                    lastMessagePreview = previewForMessage(entity),
                    lastMessageType = entity.type,
                    lastMessageTimestamp = entity.timestamp,
                    lastMessageStatus = mergedStatus,
                    lastMessageIsMine = true
                )
            )
        }
    }

    suspend fun retryPendingMessages(onlyConversationId: String? = null) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        val pending = messageDao.getPendingMessages()
            .filter { onlyConversationId == null || it.conversationId == onlyConversationId }
        for (rawMsg in pending) {
            val peerName = conversationDao.getConversation(rawMsg.conversationId)?.peerName
                ?: userDao.getUser(rawMsg.conversationId)?.displayName
                ?: rawMsg.conversationId

            var msg = rawMsg
            val needsUpload = msg.type in setOf(
                MessageType.IMAGE,
                MessageType.VIDEO,
                MessageType.AUDIO,
                MessageType.DOCUMENT
            ) && msg.mediaUrl.isNullOrBlank()

            if (needsUpload) {
                val localUri = msg.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: continue
                val bytes = readBytesFromUri(localUri) ?: continue
                val mimeType = context.contentResolver.getType(localUri) ?: defaultMimeTypeFor(msg.type)
                val safeName = ensureFileNameWithExtension(
                    rawName = msg.fileName,
                    mimeType = mimeType,
                    fallbackPrefix = "${msg.type.name.lowercase(Locale.ROOT)}_${msg.timestamp}",
                    defaultExt = defaultExtensionFor(msg.type)
                )
                val uploadedUrl = uploadMediaBytesToBackend(
                    bytes = bytes,
                    fileName = safeName,
                    mimeType = mimeType,
                    mediaType = msg.type.name,
                    senderId = myId
                ) ?: continue
                msg = msg.copy(mediaUrl = uploadedUrl, fileName = safeName)
                messageDao.upsertMessage(msg)
            }

            dispatchOutgoingMessage(msg, peerName)
        }
    }

    private fun maxStatus(a: MessageStatus, b: MessageStatus): MessageStatus {
        return if (a.ordinal >= b.ordinal) a else b
    }

    suspend fun deleteMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        messageDao.markDeleted(message.id)
        refreshConversationPreviewAfterDelete(message.conversationId, myId)

        signalingClient.sendDeleteMessage(message.id, myId, message.conversationId)
        runCatching {
            apiService.deleteMessage(message.id, myId)
        }
    }

    private suspend fun handleRemoteDelete(messageId: String, conversationId: String) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return
        val existing = messageDao.getMessageById(messageId)
        val convId = existing?.conversationId ?: conversationId
        messageDao.markDeleted(messageId)
        refreshConversationPreviewAfterDelete(convId, myId)
    }

    private suspend fun refreshConversationPreviewAfterDelete(conversationId: String, myId: String) {
        val latest = messageDao.getLatestActiveMessage(conversationId)
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (latest != null) {
            conversationDao.upsertConversation(
                conv.copy(
                    lastMessageId = latest.id,
                    lastMessagePreview = previewForMessage(latest),
                    lastMessageType = latest.type,
                    lastMessageTimestamp = latest.timestamp,
                    lastMessageStatus = latest.status,
                    lastMessageIsMine = latest.senderId == myId
                )
            )
        } else {
            conversationDao.upsertConversation(
                conv.copy(
                    lastMessageId = null,
                    lastMessagePreview = "Message deleted",
                    lastMessageType = MessageType.TEXT
                )
            )
        }
    }

    private suspend fun handleIncomingMessage(dto: RemoteMessageDto, myId: String) {
        val isMine = dto.senderId.equals(myId, ignoreCase = true)
        val peerId = if (isMine) dto.receiverId else dto.senderId
        if (peerId.isBlank()) return

        val incoming = dto.toEntity(conversationId = peerId)
        val existingMsg = messageDao.getMessageById(incoming.id)
        val entity = if (existingMsg != null) mergeMessageEntities(existingMsg, incoming) else incoming
        messageDao.upsertMessage(entity)

        var peerUser = userDao.getUser(peerId)
        if (peerUser == null && !isMine) {
            // Immediately ensure unknown senders appear in Directory too
            syncUserDirectory()
            peerUser = userDao.getUser(peerId)
        }
        val existingConv = conversationDao.getConversation(peerId)
        val resolvedPeerName = existingConv?.peerName
            ?.takeIf { it.isNotBlank() && it != peerId }
            ?: peerUser?.displayName?.takeIf { it.isNotBlank() }
            ?: dto.senderName?.takeIf { it.isNotBlank() }
            ?: peerId
        val newUnread = if (isMine) 0 else (existingConv?.unreadCount ?: 0) + 1
        val headline = previewForMessage(entity)

        conversationDao.upsertConversation(
            ConversationEntity(
                peerId = peerId,
                peerName = resolvedPeerName,
                peerPhone = existingConv?.peerPhone?.ifBlank { peerUser?.phone.orEmpty() }
                    ?: peerUser?.phone.orEmpty(),
                peerAvatarUrl = existingConv?.peerAvatarUrl ?: peerUser?.avatarUrl,
                lastMessageId = entity.id,
                lastMessagePreview = headline,
                lastMessageType = entity.type,
                lastMessageTimestamp = entity.timestamp,
                lastMessageStatus = entity.status,
                lastMessageIsMine = isMine,
                unreadCount = newUnread,
                isPeerTyping = false
            )
        )

        if (!isMine && existingMsg == null) {
            GlobalSecurityEventBus.emitIncomingMessageAlert(
                IncomingMessageAlert(
                    messageId = entity.id,
                    peerId = peerId,
                    senderName = resolvedPeerName,
                    headline = headline,
                    timestamp = entity.timestamp
                )
            )
            signalingClient.sendReceipt(
                senderId = myId,
                receiverId = peerId,
                messageId = entity.id,
                status = MessageStatus.DELIVERED.name
            )
        }
    }

    suspend fun markConversationAsRead(peerId: String) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        conversationDao.clearUnreadCount(peerId)
        messageDao.markIncomingAsRead(peerId, myId, MessageStatus.READ)
        signalingClient.sendReceipt(myId, peerId, null, MessageStatus.READ.name)
        runCatching {
            apiService.markConversationRead(peerId, myId)
        }
    }

    suspend fun sendTypingIndicator(peerId: String, isTyping: Boolean) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return
        signalingClient.sendTyping(myId, peerId, isTyping)
    }

    fun getCachedIceServers(): List<IceServerDto> = cachedIceServers

    suspend fun fetchIceServers(): List<IceServerDto> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = apiService.getIceServers()
            if (response.isSuccessful) {
                val parsed = BackendJsonParsers.parseIceServersFromBody(response.body()?.string())
                if (parsed.isNotEmpty()) {
                    cachedIceServers = parsed
                }
                parsed
            } else {
                cachedIceServers
            }
        } catch (_: Exception) {
            cachedIceServers
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        signalingClient.disconnect()
        sessionManager.clearSession()
    }

    private fun mergeMessageEntities(existing: MessageEntity, incoming: MessageEntity): MessageEntity {
        val resolvedType = if (incoming.type == MessageType.TEXT && existing.type != MessageType.TEXT) {
            existing.type
        } else {
            incoming.type
        }
        return incoming.copy(
            content = incoming.content.ifBlank { existing.content },
            type = resolvedType,
            mediaUrl = BackendJsonParsers.normalizeMediaUrl(incoming.mediaUrl)
                ?: BackendJsonParsers.normalizeMediaUrl(existing.mediaUrl),
            localUri = existing.localUri ?: incoming.localUri,
            fileName = incoming.fileName ?: existing.fileName,
            fileSizeBytes = incoming.fileSizeBytes ?: existing.fileSizeBytes,
            latitude = incoming.latitude ?: existing.latitude,
            longitude = incoming.longitude ?: existing.longitude,
            liveLocationActive = existing.liveLocationActive || incoming.liveLocationActive,
            status = maxStatus(existing.status, incoming.status),
            timestamp = if (incoming.timestamp > 0L) incoming.timestamp else existing.timestamp
        )
    }

    private fun RemoteMessageDto.toEntity(conversationId: String): MessageEntity {
        val normalizedUrl = BackendJsonParsers.normalizeMediaUrl(mediaUrl)
        val parsedType = runCatching { MessageType.valueOf(type.uppercase()) }
            .getOrElse {
                when {
                    latitude != null && longitude != null ->
                        if (liveLocationActive) MessageType.LIVE_LOCATION else MessageType.LOCATION
                    !normalizedUrl.isNullOrBlank() -> MessageType.IMAGE
                    else -> MessageType.TEXT
                }
            }
        val finalType = if (parsedType == MessageType.TEXT) {
            when {
                latitude != null && longitude != null ->
                    if (liveLocationActive) MessageType.LIVE_LOCATION else MessageType.LOCATION
                !normalizedUrl.isNullOrBlank() -> MessageType.IMAGE
                else -> MessageType.TEXT
            }
        } else {
            parsedType
        }
        val parsedStatus = runCatching { MessageStatus.valueOf(status.uppercase()) }
            .getOrDefault(MessageStatus.SENT)
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            type = finalType,
            mediaUrl = normalizedUrl,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            latitude = latitude,
            longitude = longitude,
            liveLocationActive = liveLocationActive,
            status = parsedStatus,
            timestamp = timestamp
        )
    }

    private fun previewForMessage(message: MessageEntity): String {
        return when (message.type) {
            MessageType.TEXT -> message.content.ifBlank { "Message" }
            MessageType.IMAGE -> if (message.content.isNotBlank()) "Photo: ${message.content}" else "Photo"
            MessageType.VIDEO -> message.fileName?.let { "Video: $it" } ?: "Video"
            MessageType.AUDIO -> message.fileName?.let { "Audio: $it" } ?: "Audio clip"
            MessageType.DOCUMENT -> message.fileName ?: "Document"
            MessageType.LOCATION -> message.content.ifBlank { "Shared Location" }
            MessageType.LIVE_LOCATION -> "Live GPS Location"
            MessageType.CONTACT -> {
                val name = message.content.substringBefore("|").ifBlank { "Contact" }
                "Contact: $name"
            }
        }
    }

    private suspend fun uploadMediaBytesToBackend(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        mediaType: String,
        senderId: String
    ): String? {
        return runCatching {
            val filePart = MultipartBody.Part.createFormData(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            )
            val uploadResp = apiService.uploadMedia(
                file = filePart,
                type = mediaType.toRequestBody("text/plain".toMediaTypeOrNull()),
                senderId = senderId.toRequestBody("text/plain".toMediaTypeOrNull())
            )
            if (uploadResp.isSuccessful) {
                BackendJsonParsers.parseMediaUploadUrl(uploadResp.body()?.string())
            } else {
                null
            }
        }.getOrNull()
    }

    private fun readBytesFromUri(uri: Uri): ByteArray? {
        return runCatching {
            if (uri.scheme == "file" && uri.path != null) {
                val file = File(uri.path!!)
                if (file.exists()) return@runCatching file.readBytes()
            }
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun cacheMediaFileLocally(bytes: ByteArray, fileName: String): File? {
        return runCatching {
            val dir = File(context.filesDir, "media_cache").apply { mkdirs() }
            val safeBase = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(dir, "${UUID.randomUUID()}_$safeBase")
            dest.writeBytes(bytes)
            dest
        }.getOrNull()
    }

    private fun ensureFileNameWithExtension(
        rawName: String?,
        mimeType: String,
        fallbackPrefix: String,
        defaultExt: String
    ): String {
        val clean = rawName?.trim()?.takeIf { it.isNotBlank() }
        val mimeExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.lowercase(Locale.ROOT)
        val ext = mimeExt?.takeIf { it.isNotBlank() } ?: defaultExt
        if (clean == null) return "$fallbackPrefix.$ext"
        return if (clean.contains('.') && clean.substringAfterLast('.').length in 2..5) {
            clean
        } else {
            "$clean.$ext"
        }
    }

    private fun defaultMimeTypeFor(type: MessageType): String = when (type) {
        MessageType.IMAGE -> "image/jpeg"
        MessageType.VIDEO -> "video/mp4"
        MessageType.AUDIO -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun defaultExtensionFor(type: MessageType): String = when (type) {
        MessageType.IMAGE -> "jpg"
        MessageType.VIDEO -> "mp4"
        MessageType.AUDIO -> "m4a"
        else -> "bin"
    }

    private fun queryUriMetadata(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun formatDateTime(millis: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}
