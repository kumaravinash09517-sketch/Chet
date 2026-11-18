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

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
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
