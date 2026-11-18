package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterUserRequest(
    @Json(name = "userId") val userId: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "deviceLockId") val deviceLockId: String,
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
