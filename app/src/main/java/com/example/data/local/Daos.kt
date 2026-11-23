package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY lastLoginMillis DESC")
    fun observeAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getUser(userId: String): UserEntity?

    @Query(
        "SELECT * FROM users " +
            "WHERE LOWER(displayName) LIKE '%' || LOWER(:query) || '%' " +
            "OR LOWER(phone) LIKE '%' || LOWER(:query) || '%' " +
            "OR LOWER(userId) LIKE '%' || LOWER(:query) || '%' " +
            "ORDER BY lastLoginMillis DESC"
    )
    suspend fun searchUsers(query: String): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(users: List<UserEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Query("UPDATE users SET isOnline = :isOnline WHERE userId = :userId")
    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean)

    @Query("UPDATE users SET isOnline = 0")
    suspend fun clearAllOnlineStatuses()

    @Query("DELETE FROM users WHERE LOWER(userId) NOT IN (:activeLowerIds)")
    suspend fun deleteUsersNotIn(activeLowerIds: List<String>)
}

@Dao
interface ConversationDao {
    /**
     * WhatsApp-style top sorting: conversations with the latest incoming/outgoing
     * message or media jump immediately to the top of the list.
     */
    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun observeTotalUnreadCount(): Flow<Int?>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId LIMIT 1")
    suspend fun getConversation(peerId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE peerId = :peerId")
    suspend fun clearUnreadCount(peerId: String)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE peerId = :peerId")
    suspend fun incrementUnreadCount(peerId: String)

    @Query("UPDATE conversations SET isPeerTyping = :isTyping WHERE peerId = :peerId")
    suspend fun updatePeerTyping(peerId: String, isTyping: Boolean)

    @Query(
        "UPDATE conversations SET lastMessageStatus = :status " +
            "WHERE peerId = :peerId AND lastMessageId = :messageId"
    )
    suspend fun updateLastMessageStatus(peerId: String, messageId: String, status: MessageStatus)

    @Query(
        "UPDATE conversations SET lastMessageStatus = 'DELIVERED' " +
            "WHERE peerId = :peerId AND lastMessageIsMine = 1 AND lastMessageStatus IN ('PENDING', 'SENT')"
    )
    suspend fun markLastMessageDeliveredIfMine(peerId: String)

    @Query(
        "UPDATE conversations SET lastMessageStatus = 'READ' " +
            "WHERE peerId = :peerId AND lastMessageIsMine = 1"
    )
    suspend fun markLastMessageReadIfMine(peerId: String)

    @Query("DELETE FROM conversations WHERE lastMessageId IS NULL AND LOWER(peerId) NOT IN (:activeLowerIds)")
    suspend fun deleteEmptyConversationsNotIn(activeLowerIds: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND isDeleted = 0 " +
            "ORDER BY timestamp ASC"
    )
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND isDeleted = 0 " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestActiveMessage(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = 'PENDING' AND isDeleted = 0 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query(
        "UPDATE messages SET status = :status " +
            "WHERE conversationId = :conversationId AND senderId != :myUserId AND status != :status"
    )
    suspend fun markIncomingAsRead(
        conversationId: String,
        myUserId: String,
        status: MessageStatus = MessageStatus.READ
    )

    @Query(
        "UPDATE messages SET status = 'DELIVERED' " +
            "WHERE conversationId = :conversationId AND senderId = :myUserId AND status IN ('PENDING', 'SENT')"
    )
    suspend fun markMyMessagesDelivered(
        conversationId: String,
        myUserId: String
    )

    @Query(
        "UPDATE messages SET status = :status " +
            "WHERE conversationId = :conversationId AND senderId = :myUserId AND status != 'READ'"
    )
    suspend fun markMyMessagesRead(
        conversationId: String,
        myUserId: String,
        status: MessageStatus = MessageStatus.READ
    )

    @Query("UPDATE messages SET localUri = :localUri WHERE id = :messageId")
    suspend fun updateLocalUri(messageId: String, localUri: String)

    @Query(
        "UPDATE messages SET latitude = :latitude, longitude = :longitude, liveLocationActive = :active " +
            "WHERE id = :messageId"
    )
    suspend fun updateLiveLocation(
        messageId: String,
        latitude: Double,
        longitude: Double,
        active: Boolean
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageHard(messageId: String)

    @Query("UPDATE messages SET isDeleted = 1, content = '', mediaUrl = NULL, localUri = NULL WHERE id = :messageId")
    suspend fun markDeleted(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
