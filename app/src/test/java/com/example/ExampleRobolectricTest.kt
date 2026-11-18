package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.GlobalSecurityEventBus
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageStatus
import com.example.data.local.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app_name matches Hello Sir`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Hello Sir", appName)
    }

    @Test
    fun `conversations sort newest message to top`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.conversationDao()
            dao.upsertConversation(
                ConversationEntity(
                    peerId = "user_a",
                    peerName = "User A",
                    lastMessagePreview = "Older message",
                    lastMessageType = MessageType.TEXT,
                    lastMessageTimestamp = 1000L,
                    lastMessageStatus = MessageStatus.READ
                )
            )
            dao.upsertConversation(
                ConversationEntity(
                    peerId = "user_b",
                    peerName = "User B",
                    lastMessagePreview = "Newest media",
                    lastMessageType = MessageType.IMAGE,
                    lastMessageTimestamp = 5000L,
                    lastMessageStatus = MessageStatus.DELIVERED
                )
            )
            val list = dao.observeConversations().first()
            assertEquals("user_b", list.first().peerId)
            assertEquals("user_a", list.last().peerId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `403 blocklist event sets blocked state`() {
        GlobalSecurityEventBus.clearBlocked()
        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
        assertTrue(GlobalSecurityEventBus.isBlocked.value)
        GlobalSecurityEventBus.clearBlocked()
    }
}
