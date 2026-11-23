package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    LOCATION,
    LIVE_LOCATION,
    CONTACT
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ
}

enum class StatusType {
    TEXT,
    IMAGE,
    VIDEO
}

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val phone: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val lastLoginMillis: Long,
    val lastLoginFormatted: String,
    val isOnline: Boolean = false,
    val isBlocked: Boolean = false
)

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["lastMessageTimestamp"])]
)
data class ConversationEntity(
    @PrimaryKey val peerId: String,
    val peerName: String,
    val peerPhone: String = "",
    val peerAvatarUrl: String? = null,
    val lastMessageId: String? = null,
    val lastMessagePreview: String = "",
    val lastMessageType: MessageType = MessageType.TEXT,
    val lastMessageTimestamp: Long = 0L,
    val lastMessageStatus: MessageStatus = MessageStatus.SENT,
    val lastMessageIsMine: Boolean = false,
    val unreadCount: Int = 0,
    val isPeerTyping: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["senderId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val mediaUrl: String? = null,
    val localUri: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val liveLocationActive: Boolean = false,
    val status: MessageStatus = MessageStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "status_updates",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["expiresAt"]),
        Index(value = ["createdAt"])
    ]
)
data class StatusUpdateEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val userName: String,
    val userAvatarUrl: String? = null,
    val type: StatusType = StatusType.TEXT,
    val textContent: String = "",
    val mediaUrl: String? = null,
    val localUri: String? = null,
    val backgroundColorHex: String = "#1B5E20",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + 24L * 60L * 60L * 1000L,
    val isViewedByMe: Boolean = false,
    val viewCount: Int = 0
)
