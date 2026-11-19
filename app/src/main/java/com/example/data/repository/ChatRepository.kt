package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.security.GlobalSecurityEventBus
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
import com.example.data.remote.IceServerDto
import com.example.data.remote.RegisterUserRequest
import com.example.data.remote.RemoteMessageDto
import com.example.data.remote.SendMessageRequest
import com.example.data.remote.SendOtpRequest
import com.example.data.remote.SendOtpResponse
import com.example.data.remote.SignalingClient
import com.example.data.remote.SignalingEvent
import com.example.data.remote.VerifyOtpRequest
import com.example.data.remote.VerifyOtpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    val sessionFlow: Flow<UserSession?> = sessionManager.sessionFlow
    val conversationsFlow: Flow<List<ConversationEntity>> = conversationDao.observeConversations()
    val usersFlow: Flow<List<UserEntity>> = userDao.observeAllUsers()
    val totalUnreadCountFlow: Flow<Int?> = conversationDao.observeTotalUnreadCount()

    init {
        observeSignalingEvents()
    }

    fun observeMessages(peerId: String): Flow<List<MessageEntity>> =
        messageDao.observeMessages(peerId)

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                val myId = sessionManager.getCurrentSession()?.userId ?: return@collect
                when (event) {
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
                        userDao.updateOnlineStatus(event.userId, event.isOnline)
                    }
                    is SignalingEvent.LiveLocationUpdated -> {
                        messageDao.updateLiveLocation(
                            messageId = event.messageId,
                            latitude = event.latitude,
                            longitude = event.longitude,
                            active = event.active
                        )
                    }
                    else -> Unit
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
                if (!body.isOtpVerified()) {
                    Result.failure(IllegalStateException(body.message ?: "Invalid OTP code."))
                } else {
                    Result.success(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun uploadAvatarPhoto(uri: Uri, senderHint: String): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val (fileName, _) = queryUriMetadata(uri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Unable to read selected image."))
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    fileName ?: "avatar_${System.currentTimeMillis()}.jpg",
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
                val resp = apiService.uploadMedia(
                    file = filePart,
                    type = "AVATAR".toRequestBody("text/plain".toMediaTypeOrNull()),
                    senderId = senderHint.toRequestBody("text/plain".toMediaTypeOrNull())
                )
                if (resp.isSuccessful) {
                    val url = resp.body()?.url
                    if (!url.isNullOrBlank()) {
                        Result.success(url)
                    } else {
                        Result.success(uri.toString())
                    }
                } else {
                    Result.failure(IllegalStateException("Photo upload failed (${resp.code()})"))
                }
            } catch (e: Exception) {
                Result.failure(e)
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
            val resolvedAvatar = body?.user?.avatarUrl ?: avatarUrl
            val session = sessionManager.saveSession(
                userId = cleanId,
                displayName = cleanName,
                phone = cleanPhone,
                avatarUrl = resolvedAvatar,
                serverToken = body?.token ?: verifiedToken,
                registeredAtMillis = now,
                registeredAtFormatted = formatted,
                deviceIdOverride = effectiveDeviceId
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

            // Notify backend admin webhook about verified registration/login
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncUserDirectory(): Result<List<UserEntity>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = apiService.getUserDirectory()
            if (response.isSuccessful) {
                val remoteUsers = response.body()?.users.orEmpty()
                val mapped = remoteUsers.map { dto ->
                    val millis = if (dto.lastLoginMillis > 0L) dto.lastLoginMillis else System.currentTimeMillis()
                    UserEntity(
                        userId = dto.userId,
                        displayName = dto.displayName.ifBlank { dto.userId },
                        phone = dto.phone,
                        avatarUrl = dto.avatarUrl,
                        lastLoginMillis = millis,
                        lastLoginFormatted = dto.lastLoginFormatted.ifBlank { formatDateTime(millis) },
                        isOnline = dto.isOnline,
                        isBlocked = dto.isBlocked
                    )
                }
                if (mapped.isNotEmpty()) {
                    userDao.upsertUsers(mapped)
                    mapped.forEach { user ->
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
                Result.success(mapped)
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
        try {
            val response = apiService.searchUsers(clean)
            if (response.isSuccessful) {
                val remoteUsers = response.body()?.users.orEmpty()
                val mapped = remoteUsers.map { dto ->
                    val millis = if (dto.lastLoginMillis > 0L) dto.lastLoginMillis else System.currentTimeMillis()
                    UserEntity(
                        userId = dto.userId,
                        displayName = dto.displayName.ifBlank { dto.userId },
                        phone = dto.phone,
                        avatarUrl = dto.avatarUrl,
                        lastLoginMillis = millis,
                        lastLoginFormatted = dto.lastLoginFormatted.ifBlank { formatDateTime(millis) },
                        isOnline = dto.isOnline,
                        isBlocked = dto.isBlocked
                    )
                }
                if (mapped.isNotEmpty()) {
                    userDao.upsertUsers(mapped)
                }
            }
        } catch (_: Exception) {
            // Fall back cleanly to local Room search
        }
        return@withContext userDao.searchUsers(clean)
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
        syncMessagesWithServer(peerId)
    }

    suspend fun syncMessagesWithServer(peerId: String) = withContext(Dispatchers.IO) {
        val myId = sessionManager.getCurrentSession()?.userId ?: return@withContext
        try {
            val response = apiService.getMessages(peerId, myId)
            if (response.isSuccessful) {
                val remoteMessages = response.body()?.messages.orEmpty()
                if (remoteMessages.isNotEmpty()) {
                    val entities = remoteMessages.map { dto ->
                        dto.toEntity(conversationId = peerId)
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
                                lastMessageId = latest.id,
                                lastMessagePreview = previewForMessage(latest),
                                lastMessageType = latest.type,
                                lastMessageTimestamp = latest.timestamp,
                                lastMessageStatus = latest.status,
                                lastMessageIsMine = latest.senderId == myId,
                                unreadCount = existing?.unreadCount ?: 0,
                                isPeerTyping = existing?.isPeerTyping ?: false
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
        val (fileName, fileSize) = queryUriMetadata(uri)
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val pendingEntity = MessageEntity(
            id = messageId,
            conversationId = peerId,
            senderId = myId,
            receiverId = peerId,
            content = caption,
            type = type,
            localUri = uri.toString(),
            fileName = fileName,
            fileSizeBytes = fileSize,
            status = MessageStatus.PENDING,
            timestamp = now
        )
        persistOutgoingLocally(peerId, peerName, pendingEntity)

        var uploadedUrl: String? = null
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    fileName ?: "media_${now}",
                    bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
                val uploadResp = apiService.uploadMedia(
                    file = filePart,
                    type = type.name.toRequestBody("text/plain".toMediaTypeOrNull()),
                    senderId = myId.toRequestBody("text/plain".toMediaTypeOrNull())
                )
                if (uploadResp.isSuccessful) {
                    uploadedUrl = uploadResp.body()?.url
                }
            }
        } catch (_: Exception) {
            // Retain localUri if upload fails
        }

        val updatedEntity = pendingEntity.copy(mediaUrl = uploadedUrl)
        dispatchOutgoingMessage(updatedEntity, peerName)
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
        conversationDao.upsertConversation(
            ConversationEntity(
                peerId = peerId,
                peerName = existing?.peerName ?: peerName,
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
        var finalStatus = if (wsSent) MessageStatus.SENT else MessageStatus.PENDING

        try {
            val response = apiService.sendMessage(req)
            if (response.isSuccessful) {
                finalStatus = MessageStatus.SENT
            }
        } catch (_: Exception) {
            // Keep WS status or PENDING
        }

        messageDao.upsertMessage(entity.copy(status = finalStatus))
        conversationDao.updateLastMessageStatus(entity.conversationId, entity.id, finalStatus)
        val existing = conversationDao.getConversation(entity.conversationId)
        if (existing == null) {
            conversationDao.upsertConversation(
                ConversationEntity(
                    peerId = entity.conversationId,
                    peerName = peerName,
                    lastMessageId = entity.id,
                    lastMessagePreview = previewForMessage(entity),
                    lastMessageType = entity.type,
                    lastMessageTimestamp = entity.timestamp,
                    lastMessageStatus = finalStatus,
                    lastMessageIsMine = true
                )
            )
        }
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
        val peerId = if (dto.senderId == myId) dto.receiverId else dto.senderId
        val isMine = dto.senderId == myId
        val entity = dto.toEntity(conversationId = peerId)
        messageDao.upsertMessage(entity)

        val peerUser = userDao.getUser(peerId)
        val existingConv = conversationDao.getConversation(peerId)
        val newUnread = if (isMine) 0 else (existingConv?.unreadCount ?: 0) + 1

        conversationDao.upsertConversation(
            ConversationEntity(
                peerId = peerId,
                peerName = existingConv?.peerName ?: peerUser?.displayName ?: peerId,
                lastMessageId = entity.id,
                lastMessagePreview = previewForMessage(entity),
                lastMessageType = entity.type,
                lastMessageTimestamp = entity.timestamp,
                lastMessageStatus = entity.status,
                lastMessageIsMine = isMine,
                unreadCount = newUnread,
                isPeerTyping = false
            )
        )

        if (!isMine) {
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

    suspend fun fetchIceServers(): List<IceServerDto> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = apiService.getIceServers()
            if (response.isSuccessful) {
                response.body()?.iceServers.orEmpty()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        signalingClient.disconnect()
        sessionManager.clearSession()
    }

    private fun RemoteMessageDto.toEntity(conversationId: String): MessageEntity {
        val parsedType = runCatching { MessageType.valueOf(type.uppercase()) }
            .getOrDefault(MessageType.TEXT)
        val parsedStatus = runCatching { MessageStatus.valueOf(status.uppercase()) }
            .getOrDefault(MessageStatus.SENT)
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            type = parsedType,
            mediaUrl = mediaUrl,
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
            MessageType.IMAGE -> "Photo"
            MessageType.VIDEO -> "Video"
            MessageType.AUDIO -> "Audio clip"
            MessageType.DOCUMENT -> message.fileName ?: "Document"
            MessageType.LOCATION -> "Location pin"
            MessageType.LIVE_LOCATION -> "Live GPS Location"
            MessageType.CONTACT -> {
                val name = message.content.substringBefore("|")
                "Contact: $name"
            }
        }
    }

    private fun queryUriMetadata(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private fun formatDateTime(millis: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}
