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
        val uid = firstNonBlankString(obj, "userId", "user_id", "id") ?: return null
        val displayName = firstNonBlankString(obj, "displayName", "display_name", "username", "name")
        val phone = firstNonBlankString(obj, "phone", "phone_number", "phoneNumber", "mobile")
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
        val isOnline = obj.optBoolean("isOnline", obj.optBoolean("is_online", false))
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

        val status = (firstNonBlankString(obj, "status") ?: "SENT").uppercase()

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
            val status = firstNonBlankString(obj, "status")
                ?: nested?.let { firstNonBlankString(it, "status") }
            status?.uppercase()?.takeIf { it in setOf("SENT", "DELIVERED", "READ") }
        }.getOrNull()
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
            }
        }
        return null
    }
}

