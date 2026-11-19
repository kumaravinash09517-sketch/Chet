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
    @Json(name = "botUsername") val botUsernameCamel: String? = null
) {
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
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "display_name") val displayNameSnake: String? = null,
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "avatar_url") val avatarUrlSnake: String? = null,
    @Json(name = "user") val user: RemoteUserDto? = null,
    @Json(name = "message") val message: String? = null
) {
    fun resolvedToken(): String? =
        token?.takeIf { it.isNotBlank() } ?: accessToken?.takeIf { it.isNotBlank() }

    fun resolvedUserId(): String? =
        userId?.takeIf { it.isNotBlank() }
            ?: userIdSnake?.takeIf { it.isNotBlank() }
            ?: user?.userId?.takeIf { it.isNotBlank() }

    fun resolvedDisplayName(): String? =
        displayName?.takeIf { it.isNotBlank() }
            ?: displayNameSnake?.takeIf { it.isNotBlank() }
            ?: user?.displayName?.takeIf { it.isNotBlank() }

    fun resolvedAvatarUrl(): String? =
        avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlSnake?.takeIf { it.isNotBlank() }
            ?: user?.avatarUrl?.takeIf { it.isNotBlank() }

    fun isOtpVerified(): Boolean {
        if (verified == true) return true
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
        if (normalizedStatus in setOf(
                "ok",
                "success",
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
        if (verified == false) return false
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
    @Json(name = "user") val user: RemoteUserDto? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class RemoteUserDto(
    @Json(name = "userId") val userId: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "phone") val phone: String = "",
    @Json(name = "avatarUrl") val avatarUrl: String? = null,
    @Json(name = "lastLoginMillis") val lastLoginMillis: Long = System.currentTimeMillis(),
    @Json(name = "lastLoginFormatted") val lastLoginFormatted: String = "",
    @Json(name = "isOnline") val isOnline: Boolean = false,
    @Json(name = "isBlocked") val isBlocked: Boolean = false
)

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
)

@JsonClass(generateAdapter = true)
data class RemoteMessageDto(
    @Json(name = "id") val id: String,
    @Json(name = "senderId") val senderId: String,
    @Json(name = "receiverId") val receiverId: String,
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
    @Json(name = "url") val url: String,
    @Json(name = "fileName") val fileName: String? = null,
    @Json(name = "sizeBytes") val sizeBytes: Long? = null
)

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
