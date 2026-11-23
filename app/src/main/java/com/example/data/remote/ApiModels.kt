package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SendOtpRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class SendOtpResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "expiresInSeconds") val expiresInSeconds: Int? = null,
    @Json(name = "expires_in_seconds") val expiresInSecondsSnake: Int? = null,
    @Json(name = "expires_in") val expiresInSnake: Int? = null,
    @Json(name = "telegram_bot_url") val telegramBotUrl: String? = null,
    @Json(name = "telegramBotUrl") val telegramBotUrlCamel: String? = null,
    @Json(name = "bot_username") val botUsername: String? = null,
    @Json(name = "botUsername") val botUsernameCamel: String? = null,
    @Json(name = "is_blocked") val isBlockedSnake: Boolean? = null,
    @Json(name = "isBlocked") val isBlockedCamel: Boolean? = null
) {
    fun resolvedIsBlocked(): Boolean = (isBlockedSnake == true) || (isBlockedCamel == true)

    fun resolvedExpiresInSeconds(): Int =
        expiresInSeconds ?: expiresInSecondsSnake ?: expiresInSnake ?: 60

    fun resolvedTelegramBotUrl(): String? {
        val rawUrl = telegramBotUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: telegramBotUrlCamel?.trim()?.takeIf { it.isNotBlank() }
        if (rawUrl != null) {
            return if (
                rawUrl.startsWith("http://", ignoreCase = true) ||
                rawUrl.startsWith("https://", ignoreCase = true) ||
                rawUrl.startsWith("tg://", ignoreCase = true)
            ) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
        }
        val username = (botUsername ?: botUsernameCamel)
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
        return username?.let { "https://t.me/$it" }
    }
}

@JsonClass(generateAdapter = true)
data class VerifyOtpRequest(
    @Json(name = "phone") val phone: String,
    @Json(name = "otp") val otp: String,
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class VerifyOtpResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "verified") val verified: Boolean? = null,
    @Json(name = "otp_verified") val otpVerifiedSnake: Boolean? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "requires_profile") val requiresProfile: Boolean? = null,
    @Json(name = "requires_name") val requiresName: Boolean? = null,
    @Json(name = "needs_profile") val needsProfile: Boolean? = null,
    @Json(name = "needs_name") val needsName: Boolean? = null,
    @Json(name = "needs_registration") val needsRegistration: Boolean? = null,
    @Json(name = "is_new_user") val isNewUser: Boolean? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "user_id") val userIdSnake: String? = null,
    @Json(name = "id") val idTopLevel: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "display_name") val displayNameSnake: String? = null,
    @Json(name = "name") val nameTopLevel: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "avatar_url") val avatarUrlSnake: String? = null,
    @Json(name = "is_blocked") val isBlockedSnake: Boolean? = null,
    @Json(name = "isBlocked") val isBlockedCamel: Boolean? = null,
    @Json(name = "user") val user: RemoteUserDto? = null,
    @Json(name = "message") val message: String? = null
) {
    fun resolvedToken(): String? =
        token?.takeIf { it.isNotBlank() } ?: accessToken?.takeIf { it.isNotBlank() }

    fun resolvedUserId(): String? =
        userId?.takeIf { it.isNotBlank() }
            ?: userIdSnake?.takeIf { it.isNotBlank() }
            ?: idTopLevel?.takeIf { it.isNotBlank() }
            ?: user?.resolvedUserId()?.takeIf { it.isNotBlank() }

    fun resolvedDisplayName(): String? =
        displayName?.takeIf { it.isNotBlank() }
            ?: displayNameSnake?.takeIf { it.isNotBlank() }
            ?: nameTopLevel?.takeIf { it.isNotBlank() }
            ?: user?.resolvedDisplayName()?.takeIf { it.isNotBlank() }

    fun resolvedAvatarUrl(): String? =
        avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlSnake?.takeIf { it.isNotBlank() }
            ?: user?.resolvedAvatarUrl()?.takeIf { it.isNotBlank() }

    fun resolvedIsBlocked(): Boolean =
        (isBlockedSnake == true) || (isBlockedCamel == true) || (user?.resolvedIsBlocked() == true)

    fun isProfileSetupExplicitlyRequired(): Boolean {
        if (requiresProfile == true ||
            requiresName == true ||
            needsProfile == true ||
            needsName == true ||
            needsRegistration == true ||
            isNewUser == true
        ) {
            return true
        }
        val normalizedStatus = status?.trim()?.lowercase()
        return normalizedStatus in setOf(
            "otp_verified",
            "profile_required",
            "requires_profile",
            "requires_name",
            "needs_profile",
            "needs_name",
            "needs_registration",
            "new_user"
        )
    }

    fun canCompleteExistingLoginDirectly(): Boolean =
        !resolvedIsBlocked() &&
            !isProfileSetupExplicitlyRequired() &&
            !resolvedToken().isNullOrBlank() &&
            !resolvedDisplayName().isNullOrBlank()

    fun isOtpVerified(): Boolean {
        if (verified == true || otpVerifiedSnake == true) return true
        if (isProfileSetupExplicitlyRequired()) return true

        val normalizedStatus = status?.trim()?.lowercase()
        if (normalizedStatus in setOf("ok", "success", "verified", "otp_verified")) {
            return true
        }
        if (verified == false || otpVerifiedSnake == false) return false
        if (success) return true

        val rawMsg = message?.trim().orEmpty()
        val lowerMsg = rawMsg.lowercase()
        val isOtpFailure = listOf(
            "invalid",
            "expired",
            "incorrect",
            "wrong",
            "mismatch",
            "invalid otp",
            "wrong otp",
            "गलत",
            "अमान्य",
            "एक्सपायर",
            "समाप्त"
        ).any { lowerMsg.contains(it) }
        if (isOtpFailure) return false

        val asksForProfile = lowerMsg.contains("name") ||
            lowerMsg.contains("profile") ||
            lowerMsg.contains("register") ||
            lowerMsg.contains("naam") ||
            rawMsg.contains("नाम") ||
            rawMsg.contains("प्रोफ़ाइल") ||
            rawMsg.contains("प्रोफाइल")
        return asksForProfile
    }
}

@JsonClass(generateAdapter = true)
data class DirectLoginRequest(
    @Json(name = "userId") val userId: String,
    @Json(name = "user_id") val userIdSnake: String = userId,
    @Json(name = "username") val username: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "display_name") val displayNameSnake: String = displayName,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "deviceLockId") val deviceLockId: String,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "loginTimestamp") val loginTimestamp: Long,
    @Json(name = "loginDateFormatted") val loginDateFormatted: String
)

@JsonClass(generateAdapter = true)
data class DirectLoginResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "token") val token: String? = null,
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "user_id") val userIdSnake: String? = null,
    @Json(name = "id") val idTopLevel: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "display_name") val displayNameSnake: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val nameTopLevel: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "avatar_url") val avatarUrlSnake: String? = null,
    @Json(name = "is_blocked") val isBlockedSnake: Boolean? = null,
    @Json(name = "isBlocked") val isBlockedCamel: Boolean? = null,
    @Json(name = "user") val user: RemoteUserDto? = null,
    @Json(name = "message") val message: String? = null
) {
    fun resolvedToken(): String? =
        token?.takeIf { it.isNotBlank() } ?: accessToken?.takeIf { it.isNotBlank() }

    fun resolvedUserId(): String? =
        userId?.trim()?.takeIf { it.isNotBlank() }
            ?: userIdSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: idTopLevel?.trim()?.takeIf { it.isNotBlank() }
            ?: user?.resolvedUserId()?.takeIf { it.isNotBlank() }

    fun resolvedDisplayName(): String? =
        displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: displayNameSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: username?.trim()?.takeIf { it.isNotBlank() }
            ?: nameTopLevel?.trim()?.takeIf { it.isNotBlank() }
            ?: user?.resolvedDisplayName()?.takeIf { it.isNotBlank() }

    fun resolvedPhone(): String =
        phone?.trim()?.takeIf { it.isNotBlank() } ?: user?.resolvedPhone().orEmpty()

    fun resolvedAvatarUrl(): String? =
        BackendJsonParsers.normalizeMediaUrl(
            avatarUrl?.takeIf { it.isNotBlank() }
                ?: avatarUrlSnake?.takeIf { it.isNotBlank() }
                ?: user?.resolvedAvatarUrl()?.takeIf { it.isNotBlank() }
        )

    fun resolvedIsBlocked(): Boolean =
        (isBlockedSnake == true) || (isBlockedCamel == true) || (user?.resolvedIsBlocked() == true)
}

@JsonClass(generateAdapter = true)
data class RegisterUserRequest(
    @Json(name = "userId") val userId: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "phone") val phone: String = "",
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "deviceLockId") val deviceLockId: String,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "loginTimestamp") val loginTimestamp: Long,
    @Json(name = "loginDateFormatted") val loginDateFormatted: String
)

@JsonClass(generateAdapter = true)
data class RegisterUserResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "token") val token: String? = null,
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "user_id") val userIdSnake: String? = null,
    @Json(name = "id") val idTopLevel: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "display_name") val displayNameSnake: String? = null,
    @Json(name = "name") val nameTopLevel: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "avatar_url") val avatarUrlSnake: String? = null,
    @Json(name = "is_blocked") val isBlockedSnake: Boolean? = null,
    @Json(name = "isBlocked") val isBlockedCamel: Boolean? = null,
    @Json(name = "user") val user: RemoteUserDto? = null,
    @Json(name = "message") val message: String? = null
) {
    fun resolvedToken(): String? =
        token?.takeIf { it.isNotBlank() } ?: accessToken?.takeIf { it.isNotBlank() }

    fun resolvedUserId(): String? =
        userId?.trim()?.takeIf { it.isNotBlank() }
            ?: userIdSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: idTopLevel?.trim()?.takeIf { it.isNotBlank() }
            ?: user?.resolvedUserId()?.takeIf { it.isNotBlank() }

    fun resolvedDisplayName(): String? =
        displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: displayNameSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: nameTopLevel?.trim()?.takeIf { it.isNotBlank() }
            ?: user?.resolvedDisplayName()?.takeIf { it.isNotBlank() }

    fun resolvedAvatarUrl(): String? =
        avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlSnake?.takeIf { it.isNotBlank() }
            ?: user?.resolvedAvatarUrl()?.takeIf { it.isNotBlank() }

    fun resolvedIsBlocked(): Boolean =
        (isBlockedSnake == true) || (isBlockedCamel == true) || (user?.resolvedIsBlocked() == true)
}

@JsonClass(generateAdapter = true)
data class RemoteUserDto(
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "user_id") val userIdSnake: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "display_name") val displayNameSnake: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "avatar_url") val avatarUrlSnake: String? = null,
    @Json(name = "lastLoginMillis") val lastLoginMillis: Long? = null,
    @Json(name = "last_login_millis") val lastLoginMillisSnake: Long? = null,
    @Json(name = "lastLoginFormatted") val lastLoginFormatted: String? = null,
    @Json(name = "last_login_formatted") val lastLoginFormattedSnake: String? = null,
    @Json(name = "isOnline") val isOnline: Boolean? = null,
    @Json(name = "is_online") val isOnlineSnake: Boolean? = null,
    @Json(name = "isBlocked") val isBlocked: Boolean? = null,
    @Json(name = "is_blocked") val isBlockedSnake: Boolean? = null,
    @Json(name = "device_id") val deviceIdSnake: String? = null
) {
    fun resolvedUserId(): String =
        userId?.trim()?.takeIf { it.isNotBlank() }
            ?: userIdSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: id?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

    fun resolvedDisplayName(): String =
        displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: displayNameSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: username?.trim()?.takeIf { it.isNotBlank() }
            ?: name?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

    fun resolvedPhone(): String = phone?.trim().orEmpty()

    fun resolvedAvatarUrl(): String? =
        BackendJsonParsers.normalizeMediaUrl(
            avatarUrl?.trim()?.takeIf { it.isNotBlank() }
                ?: avatarUrlSnake?.trim()?.takeIf { it.isNotBlank() }
        )

    fun resolvedLastLoginMillis(): Long =
        lastLoginMillis ?: lastLoginMillisSnake ?: System.currentTimeMillis()

    fun resolvedLastLoginFormatted(): String =
        lastLoginFormatted?.trim()?.takeIf { it.isNotBlank() }
            ?: lastLoginFormattedSnake?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

    fun resolvedIsOnline(): Boolean = (isOnline == true) || (isOnlineSnake == true)

    fun resolvedIsBlocked(): Boolean = (isBlocked == true) || (isBlockedSnake == true)
}

@JsonClass(generateAdapter = true)
data class UserDirectoryResponse(
    @Json(name = "users") val users: List<RemoteUserDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AdminWebhookEventRequest(
    @Json(name = "eventType") val eventType: String,
    @Json(name = "userId") val userId: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "timestamp") val timestamp: Long,
    @Json(name = "summary") val summary: String
)

@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    @Json(name = "id") val id: String,
    @Json(name = "senderId") val senderId: String,
    @Json(name = "receiverId") val receiverId: String,
    @Json(name = "content") val content: String,
    @Json(name = "type") val type: String,
    @Json(name = "mediaUrl") val mediaUrl: String? = null,
    @Json(name = "fileName") val fileName: String? = null,
    @Json(name = "fileSizeBytes") val fileSizeBytes: Long? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "liveLocationActive") val liveLocationActive: Boolean = false,
    @Json(name = "timestamp") val timestamp: Long
) {
    fun toSnakeCase(): SendMessageSnakeCaseRequest = SendMessageSnakeCaseRequest(
        id = id,
        senderId = senderId,
        receiverId = receiverId,
        content = content,
        type = type,
        mediaUrl = mediaUrl,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        latitude = latitude,
        longitude = longitude,
        liveLocationActive = liveLocationActive,
        timestamp = timestamp
    )
}

@JsonClass(generateAdapter = true)
data class SendMessageSnakeCaseRequest(
    @Json(name = "id") val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "receiver_id") val receiverId: String,
    @Json(name = "content") val content: String,
    @Json(name = "type") val type: String,
    @Json(name = "media_url") val mediaUrl: String? = null,
    @Json(name = "file_name") val fileName: String? = null,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "live_location_active") val liveLocationActive: Boolean = false,
    @Json(name = "timestamp") val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class RemoteMessageDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "senderId") val senderId: String = "",
    @Json(name = "senderName") val senderName: String? = null,
    @Json(name = "receiverId") val receiverId: String = "",
    @Json(name = "content") val content: String = "",
    @Json(name = "type") val type: String = "TEXT",
    @Json(name = "mediaUrl") val mediaUrl: String? = null,
    @Json(name = "fileName") val fileName: String? = null,
    @Json(name = "fileSizeBytes") val fileSizeBytes: Long? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "liveLocationActive") val liveLocationActive: Boolean = false,
    @Json(name = "status") val status: String = "SENT",
    @Json(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class MessagesListResponse(
    @Json(name = "messages") val messages: List<RemoteMessageDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class MediaUploadResponse(
    @Json(name = "url") val url: String? = null,
    @Json(name = "mediaUrl") val mediaUrl: String? = null,
    @Json(name = "media_url") val mediaUrlSnake: String? = null,
    @Json(name = "fileUrl") val fileUrl: String? = null,
    @Json(name = "file_url") val fileUrlSnake: String? = null,
    @Json(name = "path") val path: String? = null,
    @Json(name = "fileName") val fileName: String? = null,
    @Json(name = "sizeBytes") val sizeBytes: Long? = null
) {
    fun resolvedUrl(): String? =
        BackendJsonParsers.normalizeMediaUrl(
            url ?: mediaUrl ?: mediaUrlSnake ?: fileUrl ?: fileUrlSnake ?: path
        )
}

@JsonClass(generateAdapter = true)
data class IceServerDto(
    @Json(name = "urls") val urls: List<String> = emptyList(),
    @Json(name = "username") val username: String? = null,
    @Json(name = "credential") val credential: String? = null
)

@JsonClass(generateAdapter = true)
data class IceServersResponse(
    @Json(name = "iceServers") val iceServers: List<IceServerDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BasicSuccessResponse(
    @Json(name = "success") val success: Boolean = true,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class CallSignalRequest(
    @Json(name = "type") val type: String,
    @Json(name = "callId") val callId: String,
    @Json(name = "call_id") val callIdSnake: String = callId,
    @Json(name = "senderId") val senderId: String,
    @Json(name = "sender_id") val senderIdSnake: String = senderId,
    @Json(name = "callerId") val callerId: String? = null,
    @Json(name = "caller_id") val callerIdSnake: String? = callerId,
    @Json(name = "senderName") val senderName: String? = null,
    @Json(name = "sender_name") val senderNameSnake: String? = senderName,
    @Json(name = "callerName") val callerName: String? = null,
    @Json(name = "caller_name") val callerNameSnake: String? = callerName,
    @Json(name = "receiverId") val receiverId: String,
    @Json(name = "receiver_id") val receiverIdSnake: String = receiverId,
    @Json(name = "toUserId") val toUserId: String = receiverId,
    @Json(name = "to") val to: String = receiverId,
    @Json(name = "isVideo") val isVideo: Boolean? = null,
    @Json(name = "is_video") val isVideoSnake: Boolean? = isVideo,
    @Json(name = "sdp") val sdp: String? = null,
    @Json(name = "candidate") val candidate: String? = null,
    @Json(name = "sdpMid") val sdpMid: String? = null,
    @Json(name = "sdp_mid") val sdpMidSnake: String? = sdpMid,
    @Json(name = "sdpMLineIndex") val sdpMLineIndex: Int? = null,
    @Json(name = "sdp_m_line_index") val sdpMLineIndexSnake: Int? = sdpMLineIndex,
    @Json(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class RemoteCallSignalDto(
    val signalId: String? = null,
    val type: String,
    val callId: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String = "",
    val isVideo: Boolean = false,
    val sdp: String = "",
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

object BackendJsonParsers {

    fun normalizeMediaUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = NetworkModule.BASE_URL.trimEnd('/')
        return "$base/${trimmed.trimStart('/')}"
    }

    fun parseMediaUploadUrl(rawJson: String?): String? {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("/uploads/", ignoreCase = true)
        ) {
            return normalizeMediaUrl(trimmed)
        }
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val root = org.json.JSONObject(trimmed)
            val candidates = listOfNotNull(
                root,
                root.optJSONObject("data"),
                root.optJSONObject("file"),
                root.optJSONObject("media"),
                root.optJSONObject("result"),
                root.optJSONObject("upload")
            )
            for (obj in candidates) {
                val direct = firstNonBlankString(
                    obj,
                    "url",
                    "mediaUrl",
                    "media_url",
                    "fileUrl",
                    "file_url",
                    "downloadUrl",
                    "download_url",
                    "path",
                    "filePath",
                    "file_path",
                    "location"
                )
                if (direct != null) {
                    return@runCatching normalizeMediaUrl(direct)
                }
                val fileName = firstNonBlankString(
                    obj,
                    "storedFilename",
                    "stored_filename",
                    "filename",
                    "fileName",
                    "file_name"
                )
                if (fileName != null) {
                    val path = if (fileName.startsWith("/") || fileName.contains("uploads/")) {
                        fileName
                    } else {
                        "/uploads/$fileName"
                    }
                    return@runCatching normalizeMediaUrl(path)
                }
            }
            null
        }.getOrNull()
    }

    fun parseCallSignalsFromBody(rawJson: String?): List<RemoteCallSignalDto> {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("[") -> {
                    parseCallSignalArray(org.json.JSONArray(trimmed))
                }
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    val arrayKeys = listOf("signals", "events", "calls", "activeCalls", "active_calls", "data", "results", "items")
                    val array = arrayKeys.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                    if (array != null) {
                        parseCallSignalArray(array)
                    } else {
                        val singleObj = obj.optJSONObject("activeCall")
                            ?: obj.optJSONObject("active_call")
                            ?: obj.optJSONObject("call")
                            ?: obj.optJSONObject("signal")
                            ?: obj.optJSONObject("data")
                            ?: obj
                        listOfNotNull(parseSingleCallSignal(singleObj))
                    }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseCallSignalArray(arr: org.json.JSONArray): List<RemoteCallSignalDto> {
        val result = mutableListOf<RemoteCallSignalDto>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseSingleCallSignal(item)?.let { result.add(it) }
        }
        return result
    }

    fun parseSingleCallSignal(root: org.json.JSONObject): RemoteCallSignalDto? {
        val data = root.optJSONObject("data") ?: root.optJSONObject("payload") ?: root
        val rawType = firstNonBlankString(root, "type", "event", "signalType", "signal_type", "action", "status")
            ?: firstNonBlankString(data, "type", "event", "signalType", "signal_type", "action", "status")
            ?: return null

        val sdpObj = data.optJSONObject("offer")
            ?: data.optJSONObject("sdp")
            ?: root.optJSONObject("offer")
            ?: root.optJSONObject("sdp")
        val answerObj = data.optJSONObject("answer")
            ?: root.optJSONObject("answer")
        val offerSdp = firstNonBlankString(data, "offerSdp", "offer_sdp", "offer")
            ?: firstNonBlankString(root, "offerSdp", "offer_sdp", "offer")
            ?: sdpObj?.let { firstNonBlankString(it, "sdp", "description") }
            ?: ""
        val answerSdp = firstNonBlankString(data, "answerSdp", "answer_sdp", "answer")
            ?: firstNonBlankString(root, "answerSdp", "answer_sdp", "answer")
            ?: answerObj?.let { firstNonBlankString(it, "sdp", "description") }
            ?: ""
        val genericSdp = firstNonBlankString(data, "sdp")
            ?: firstNonBlankString(root, "sdp")
            ?: ""

        val initialNorm = normalizeCallSignalType(rawType)
        val rawLower = rawType.trim().lowercase()
        val normalizedType = when {
            // /api/calls/active often returns an active call row with status="ringing" + offer SDP or status="answered"/"connected" + answer SDP
            (rawLower in setOf("ringing", "call_ringing", "active", "pending", "waiting")) &&
                answerSdp.isNotBlank() -> "answer"
            (rawLower in setOf("ringing", "call_ringing", "active", "pending", "waiting")) &&
                (offerSdp.isNotBlank() || genericSdp.isNotBlank()) -> "offer"
            (rawLower in setOf("active", "answered", "connected", "call_connected", "accepted", "call_accepted")) &&
                (answerSdp.isNotBlank() || genericSdp.isNotBlank()) -> "answer"
            initialNorm != null -> initialNorm
            else -> return null
        }

        val resolvedSdp = when (normalizedType) {
            "offer" -> offerSdp.ifBlank { genericSdp }
            "answer" -> answerSdp.ifBlank { genericSdp }
            "connected" -> answerSdp.ifBlank { genericSdp }
            else -> genericSdp
        }

        // Prioritize explicit signal-actor keys ("senderId"/"from") BEFORE "callerId"
        // because backend call records may keep "callerId" as the call initiator even on "answer"/"decline"/"ice".
        val senderId = if (normalizedType == "offer") {
            firstNonBlankString(
                data,
                "senderId",
                "sender_id",
                "callerId",
                "caller_id",
                "from",
                "fromUserId",
                "from_user_id"
            ) ?: firstNonBlankString(root, "senderId", "sender_id", "callerId", "caller_id", "from")
        } else {
            firstNonBlankString(
                data,
                "senderId",
                "sender_id",
                "from",
                "fromUserId",
                "from_user_id",
                "calleeId",
                "callee_id",
                "answererId",
                "answerer_id",
                "callerId",
                "caller_id"
            ) ?: firstNonBlankString(root, "senderId", "sender_id", "from", "calleeId", "callee_id", "callerId", "caller_id")
        } ?: return null

        val senderName = if (normalizedType == "offer") {
            firstNonBlankString(
                data,
                "senderName",
                "sender_name",
                "callerName",
                "caller_name",
                "fromName",
                "from_name"
            ) ?: firstNonBlankString(root, "senderName", "sender_name", "callerName", "caller_name")
        } else {
            firstNonBlankString(
                data,
                "senderName",
                "sender_name",
                "fromName",
                "from_name",
                "calleeName",
                "callee_name",
                "callerName",
                "caller_name"
            ) ?: firstNonBlankString(root, "senderName", "sender_name", "callerName", "caller_name")
        } ?: senderId

        val receiverId = firstNonBlankString(
            data,
            "receiverId",
            "receiver_id",
            "toUserId",
            "to_user_id",
            "to",
            "calleeId",
            "callee_id"
        ) ?: firstNonBlankString(root, "receiverId", "receiver_id", "toUserId", "to")
            ?: ""

        val callId = firstNonBlankString(data, "callId", "call_id", "sessionId", "session_id", "roomId", "room_id")
            ?: firstNonBlankString(root, "callId", "call_id", "sessionId", "session_id")
            ?: ""

        val signalId = firstNonBlankString(data, "signalId", "signal_id", "id", "_id")
            ?: firstNonBlankString(root, "signalId", "signal_id", "id", "_id")

        val isVideo = when {
            data.has("isVideo") && !data.isNull("isVideo") -> data.optBoolean("isVideo", false)
            data.has("is_video") && !data.isNull("is_video") -> data.optBoolean("is_video", false)
            root.has("isVideo") && !root.isNull("isVideo") -> root.optBoolean("isVideo", false)
            root.has("is_video") && !root.isNull("is_video") -> root.optBoolean("is_video", false)
            else -> {
                val callKind = firstNonBlankString(data, "callType", "call_type", "mediaType", "media_type")
                    ?: firstNonBlankString(root, "callType", "call_type")
                callKind?.equals("video", ignoreCase = true) == true
            }
        }

        val iceObj = data.optJSONObject("candidate")
            ?: data.optJSONObject("ice")
            ?: data.optJSONObject("iceCandidate")
            ?: data.optJSONObject("ice_candidate")
            ?: root.optJSONObject("candidate")
            ?: root.optJSONObject("ice")
            ?: root.optJSONObject("iceCandidate")
            ?: root.optJSONObject("ice_candidate")
        val candidate = firstNonBlankString(data, "candidate", "iceCandidate", "ice_candidate")
            ?: firstNonBlankString(root, "candidate", "iceCandidate", "ice_candidate")
            ?: iceObj?.let { firstNonBlankString(it, "candidate", "sdp") }
            ?: ""
        val sdpMid = firstNonBlankString(data, "sdpMid", "sdp_mid")
            ?: firstNonBlankString(root, "sdpMid", "sdp_mid")
            ?: iceObj?.let { firstNonBlankString(it, "sdpMid", "sdp_mid") }
            ?: ""
        val sdpMLineIndex = when {
            data.has("sdpMLineIndex") && !data.isNull("sdpMLineIndex") -> data.optInt("sdpMLineIndex", 0)
            data.has("sdp_m_line_index") && !data.isNull("sdp_m_line_index") -> data.optInt("sdp_m_line_index", 0)
            root.has("sdpMLineIndex") && !root.isNull("sdpMLineIndex") -> root.optInt("sdpMLineIndex", 0)
            root.has("sdp_m_line_index") && !root.isNull("sdp_m_line_index") -> root.optInt("sdp_m_line_index", 0)
            iceObj != null && iceObj.has("sdpMLineIndex") && !iceObj.isNull("sdpMLineIndex") -> iceObj.optInt("sdpMLineIndex", 0)
            iceObj != null && iceObj.has("sdp_m_line_index") && !iceObj.isNull("sdp_m_line_index") -> iceObj.optInt("sdp_m_line_index", 0)
            else -> 0
        }

        val timestamp = parseTimestampMillis(data, "timestamp", "createdAt", "created_at")
            ?: parseTimestampMillis(root, "timestamp", "createdAt", "created_at")
            ?: System.currentTimeMillis()

        // Validate required payload fields for offer/answer/ice
        if ((normalizedType == "offer" || normalizedType == "answer") && resolvedSdp.isBlank()) return null
        if (normalizedType == "ice" && candidate.isBlank()) return null

        return RemoteCallSignalDto(
            signalId = signalId,
            type = normalizedType,
            callId = callId.ifBlank { "call_${senderId}_$timestamp" },
            senderId = senderId,
            senderName = senderName,
            receiverId = receiverId,
            isVideo = isVideo,
            sdp = resolvedSdp,
            candidate = candidate,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            timestamp = timestamp
        )
    }

    fun normalizeCallSignalType(rawType: String?): String? {
        return when (rawType?.trim()?.lowercase()) {
            "offer", "call_offer", "offering", "incoming_call" -> "offer"
            "ringing", "call_ringing" -> "ringing"
            "answer", "call_answer", "answered", "accept", "call_accept" -> "answer"
            "connected", "call_connected", "accepted", "call_accepted" -> "connected"
            "ice", "ice_candidate", "candidate" -> "ice"
            "hangup", "call_hangup", "end", "call_end", "ended" -> "hangup"
            "decline", "call_decline", "reject", "call_reject", "declined", "rejected" -> "decline"
            else -> null
        }
    }

    fun parseIceServersFromBody(rawJson: String?): List<IceServerDto> {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            val array = when {
                trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    listOf("iceServers", "ice_servers", "servers", "data")
                        .firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                }
                else -> null
            } ?: return@runCatching emptyList()

            val result = mutableListOf<IceServerDto>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val urls = mutableListOf<String>()
                val urlsArr = item.optJSONArray("urls")
                if (urlsArr != null) {
                    for (j in 0 until urlsArr.length()) {
                        urlsArr.optString(j).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                            ?.let { urls.add(it) }
                    }
                } else {
                    val singleUrl = firstNonBlankString(item, "urls", "url")
                    if (singleUrl != null) urls.add(singleUrl)
                }
                if (urls.isNotEmpty()) {
                    result.add(
                        IceServerDto(
                            urls = urls,
                            username = firstNonBlankString(item, "username", "user"),
                            credential = firstNonBlankString(item, "credential", "password", "secret")
                        )
                    )
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    fun parseUsersFromBody(rawJson: String?): List<RemoteUserDto> {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("[") -> {
                    val arr = org.json.JSONArray(trimmed)
                    parseUserArray(arr)
                }
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    val arrayKeys = listOf("users", "data", "results", "items", "directory")
                    val array = arrayKeys.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                    if (array != null) {
                        parseUserArray(array)
                    } else {
                        val singleObj = obj.optJSONObject("user") ?: obj
                        listOfNotNull(parseSingleUser(singleObj))
                    }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseUserArray(arr: org.json.JSONArray): List<RemoteUserDto> {
        val result = mutableListOf<RemoteUserDto>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseSingleUser(item)?.let { result.add(it) }
        }
        return result
    }

    private fun parseSingleUser(obj: org.json.JSONObject): RemoteUserDto? {
        val isDeleted = obj.optBoolean("isDeleted", false) ||
            obj.optBoolean("is_deleted", false) ||
            obj.optBoolean("deleted", false)
        if (isDeleted) return null

        val uid = firstNonBlankString(obj, "userId", "user_id", "id") ?: return null
        val displayName = firstNonBlankString(obj, "displayName", "display_name", "username", "name")
        val phone = firstNonBlankString(obj, "phone", "phone_number", "phoneNumber", "mobile")
        if (displayName.isNullOrBlank() && phone.isNullOrBlank()) return null

        val avatarUrl = normalizeMediaUrl(
            firstNonBlankString(obj, "avatarUrl", "avatar_url", "avatar", "photoUrl", "photo_url")
        )
        val millis = parseTimestampMillis(
            obj,
            "lastLoginMillis",
            "last_login_millis",
            "loginTimestamp",
            "login_timestamp",
            "lastLogin",
            "last_login",
            "lastSeen",
            "last_seen",
            "updatedAt",
            "updated_at"
        )
        val formatted = firstNonBlankString(
            obj,
            "lastLoginFormatted",
            "last_login_formatted",
            "loginDateFormatted",
            "login_date_formatted"
        )
        val rawStatus = firstNonBlankString(
            obj,
            "status",
            "presence",
            "presenceStatus",
            "presence_status",
            "appState",
            "app_state"
        )?.lowercase()
        val foregroundExplicitFalse = hasExplicitFalse(
            obj,
            "inForeground",
            "isForeground",
            "in_foreground",
            "is_foreground",
            "foreground"
        )
        val isOnline = when {
            foregroundExplicitFalse -> false
            rawStatus in setOf(
                "offline",
                "disconnected",
                "logged_out",
                "inactive",
                "away",
                "background",
                "paused",
                "stopped",
                "locked"
            ) -> false
            obj.has("isOnline") && !obj.isNull("isOnline") -> obj.optBoolean("isOnline", false)
            obj.has("is_online") && !obj.isNull("is_online") -> obj.optBoolean("is_online", false)
            obj.has("online") && !obj.isNull("online") -> obj.optBoolean("online", false)
            rawStatus in setOf("online", "active", "connected", "foreground") -> true
            else -> false
        }
        val isBlocked = obj.optBoolean("isBlocked", obj.optBoolean("is_blocked", false))
        val deviceId = firstNonBlankString(obj, "device_id", "deviceId", "deviceLockId")
        return RemoteUserDto(
            userId = uid,
            displayName = displayName,
            phone = phone,
            avatarUrl = avatarUrl,
            lastLoginMillis = millis,
            lastLoginFormatted = formatted,
            isOnline = isOnline,
            isBlocked = isBlocked,
            deviceIdSnake = deviceId
        )
    }

    fun parseMessagesFromBody(rawJson: String?): List<RemoteMessageDto> {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("[") -> {
                    parseMessageArray(org.json.JSONArray(trimmed))
                }
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    val arrayKeys = listOf("messages", "data", "results", "items")
                    val array = arrayKeys.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                    if (array != null) {
                        parseMessageArray(array)
                    } else {
                        val single = obj.optJSONObject("message") ?: obj.optJSONObject("data") ?: obj
                        listOfNotNull(parseSingleMessage(single))
                    }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseMessageArray(arr: org.json.JSONArray): List<RemoteMessageDto> {
        val result = mutableListOf<RemoteMessageDto>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseSingleMessage(item)?.let { result.add(it) }
        }
        return result
    }

    fun parseSingleMessage(obj: org.json.JSONObject): RemoteMessageDto? {
        val senderObj = obj.optJSONObject("sender")
        val receiverObj = obj.optJSONObject("receiver")
        val senderId = firstNonBlankString(obj, "senderId", "sender_id", "from", "fromUserId", "from_user_id")
            ?: senderObj?.let { firstNonBlankString(it, "userId", "user_id", "id") }
            ?: firstNonBlankString(obj, "sender")
            ?: return null
        val receiverId = firstNonBlankString(obj, "receiverId", "receiver_id", "to", "toUserId", "to_user_id")
            ?: receiverObj?.let { firstNonBlankString(it, "userId", "user_id", "id") }
            ?: firstNonBlankString(obj, "receiver")
            ?: ""
        val senderName = firstNonBlankString(obj, "senderName", "sender_name", "fromName", "from_name")
            ?: senderObj?.let { firstNonBlankString(it, "displayName", "display_name", "username", "name") }

        val nestedPayload = obj.optJSONObject("payload") ?: obj.optJSONObject("meta") ?: obj.optJSONObject("metadata")
        val content = firstNonBlankString(
            obj,
            "content",
            "text",
            "body",
            "caption",
            "msg",
            "messageText",
            "message_text"
        )
            ?: obj.optString("message").takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.trim().startsWith("{")
            }
            ?: nestedPayload?.let { firstNonBlankString(it, "content", "text", "caption", "body") }
            ?: ""

        val mediaUrl = normalizeMediaUrl(
            firstNonBlankString(
                obj,
                "mediaUrl",
                "media_url",
                "fileUrl",
                "file_url",
                "imageUrl",
                "image_url",
                "photoUrl",
                "photo_url",
                "videoUrl",
                "video_url",
                "attachmentUrl",
                "attachment_url",
                "url"
            ) ?: nestedPayload?.let {
                firstNonBlankString(it, "mediaUrl", "media_url", "fileUrl", "file_url", "url")
            }
        )
        val fileName = firstNonBlankString(obj, "fileName", "file_name", "filename", "originalName", "original_name")
            ?: nestedPayload?.let { firstNonBlankString(it, "fileName", "file_name") }
        val fileSizeBytes = listOf("fileSizeBytes", "file_size_bytes", "sizeBytes", "size_bytes", "size")
            .firstOrNull { obj.has(it) && !obj.isNull(it) }
            ?.let { obj.optLong(it) }

        val locObj = obj.optJSONObject("location") ?: obj.optJSONObject("coords") ?: nestedPayload
        val latitude = parseDoubleOpt(obj, "latitude", "lat")
            ?: locObj?.let { parseDoubleOpt(it, "latitude", "lat") }
        val longitude = parseDoubleOpt(obj, "longitude", "lng", "lon", "long")
            ?: locObj?.let { parseDoubleOpt(it, "longitude", "lng", "lon", "long") }
        val liveLocationActive = obj.optBoolean(
            "liveLocationActive",
            obj.optBoolean("live_location_active", locObj?.optBoolean("active", false) ?: false)
        )

        val timestamp = parseTimestampMillis(
            obj,
            "timestamp",
            "createdAt",
            "created_at",
            "sentAt",
            "sent_at"
        ) ?: System.currentTimeMillis()

        val id = firstNonBlankString(obj, "id", "messageId", "message_id", "_id", "msgId", "msg_id")
            ?: "srv_${senderId}_${receiverId}_${timestamp}_${content.hashCode()}"

        val rawType = firstNonBlankString(obj, "messageType", "message_type", "type", "mediaType", "media_type", "kind")
            ?: "TEXT"
        val normalizedType = resolveMessageTypeName(
            rawType = rawType,
            mediaUrl = mediaUrl,
            fileName = fileName,
            latitude = latitude,
            longitude = longitude,
            liveLocationActive = liveLocationActive
        )

        val status = resolveMessageStatusFromJson(obj, fallback = "SENT")

        return RemoteMessageDto(
            id = id,
            senderId = senderId,
            senderName = senderName,
            receiverId = receiverId,
            content = content,
            type = normalizedType,
            mediaUrl = mediaUrl,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            latitude = latitude,
            longitude = longitude,
            liveLocationActive = liveLocationActive,
            status = status,
            timestamp = timestamp
        )
    }

    private fun resolveMessageTypeName(
        rawType: String,
        mediaUrl: String?,
        fileName: String?,
        latitude: Double?,
        longitude: Double?,
        liveLocationActive: Boolean
    ): String {
        val upper = rawType.trim().uppercase()
        val mapped = when (upper) {
            "CHAT_MESSAGE", "MESSAGE", "TXT", "TEXT" -> "TEXT"
            "IMAGE", "PHOTO", "PIC", "PICTURE", "IMG" -> "IMAGE"
            "VIDEO", "VID", "MP4" -> "VIDEO"
            "AUDIO", "VOICE", "SOUND", "MP3", "M4A" -> "AUDIO"
            "DOCUMENT", "DOC", "FILE", "PDF", "ATTACHMENT" -> "DOCUMENT"
            "LOCATION", "LOC", "GPS", "PIN" -> "LOCATION"
            "LIVE_LOCATION", "LIVE_GPS", "LIVELOCATION" -> "LIVE_LOCATION"
            "CONTACT", "VCARD" -> "CONTACT"
            else -> upper
        }

        if (mapped == "TEXT") {
            if (latitude != null && longitude != null) {
                return if (liveLocationActive) "LIVE_LOCATION" else "LOCATION"
            }
            if (!mediaUrl.isNullOrBlank()) {
                val hint = (fileName ?: mediaUrl).lowercase()
                return when {
                    hint.endsWith(".mp4") || hint.endsWith(".mov") || hint.endsWith(".mkv") || hint.endsWith(".webm") -> "VIDEO"
                    hint.endsWith(".mp3") || hint.endsWith(".wav") || hint.endsWith(".m4a") || hint.endsWith(".aac") || hint.endsWith(".ogg") -> "AUDIO"
                    hint.endsWith(".pdf") || hint.endsWith(".doc") || hint.endsWith(".docx") || hint.endsWith(".txt") || hint.endsWith(".zip") -> "DOCUMENT"
                    else -> "IMAGE"
                }
            }
        }
        return mapped
    }

    fun parseSendMessageStatus(rawJson: String?): String? {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching {
            val obj = org.json.JSONObject(trimmed)
            val nested = obj.optJSONObject("message") ?: obj.optJSONObject("data")
            val fromObj = resolveMessageStatusFromJson(obj, fallback = "")
            val fromNested = nested?.let { resolveMessageStatusFromJson(it, fallback = "") } ?: ""
            val resolved = when {
                fromObj == "READ" || fromNested == "READ" -> "READ"
                fromObj == "DELIVERED" || fromNested == "DELIVERED" -> "DELIVERED"
                fromObj == "SENT" || fromNested == "SENT" -> "SENT"
                else -> ""
            }
            resolved.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun resolveMessageStatusFromJson(obj: org.json.JSONObject, fallback: String): String {
        val rawStatus = (firstNonBlankString(obj, "status", "receiptStatus", "receipt_status") ?: "")
            .trim()
            .uppercase()
        val isReadFlag = hasExplicitTrue(obj, "isRead", "is_read", "read", "isSeen", "is_seen", "seen") ||
            firstNonBlankString(obj, "readAt", "read_at", "seenAt", "seen_at") != null
        val isDeliveredFlag = hasExplicitTrue(obj, "isDelivered", "is_delivered", "delivered") ||
            firstNonBlankString(obj, "deliveredAt", "delivered_at") != null
        return when {
            isReadFlag || rawStatus in setOf("READ", "SEEN", "READ_BY_PEER") -> "READ"
            isDeliveredFlag || rawStatus in setOf("DELIVERED", "RECEIVED") -> "DELIVERED"
            rawStatus in setOf("SENT", "OK", "SUCCESS") -> "SENT"
            else -> fallback
        }
    }

    private fun hasExplicitTrue(obj: org.json.JSONObject, vararg keys: String): Boolean {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            if (obj.optBoolean(key, false)) return true
        }
        return false
    }

    private fun hasExplicitFalse(obj: org.json.JSONObject, vararg keys: String): Boolean {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            if (!obj.optBoolean(key, true)) return true
        }
        return false
    }

    private fun parseDoubleOpt(obj: org.json.JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val rawVal = obj.opt(key)
            val num = when (rawVal) {
                is Number -> rawVal.toDouble()
                is String -> rawVal.trim().toDoubleOrNull()
                else -> null
            }
            if (num != null && !num.isNaN() && !num.isInfinite()) {
                return num
            }
        }
        return null
    }

    private fun firstNonBlankString(obj: org.json.JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true) && !value.startsWith("{")) {
                return value
            }
        }
        return null
    }

    private fun parseTimestampMillis(obj: org.json.JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val rawLong = obj.optLong(key, -1L)
            if (rawLong > 0L) {
                return if (rawLong < 10_000_000_000L) rawLong * 1000L else rawLong
            }
            val rawStr = obj.optString(key).trim()
            if (rawStr.isNotEmpty() && !rawStr.equals("null", ignoreCase = true)) {
                rawStr.toLongOrNull()?.let { parsed ->
                    if (parsed > 0L) return if (parsed < 10_000_000_000L) parsed * 1000L else parsed
                }
                parseIso8601Millis(rawStr)?.let { parsedIso ->
                    if (parsedIso > 0L) return parsedIso
                }
            }
        }
        return null
    }

    private fun parseIso8601Millis(raw: String): Long? {
        val cleaned = raw.trim()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(cleaned)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return null
    }
}

