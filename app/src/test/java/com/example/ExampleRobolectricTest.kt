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
    fun `searchUsers finds user by phone number or display name`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val userDao = db.userDao()
            userDao.upsertUsers(
                listOf(
                    com.example.data.local.UserEntity(
                        userId = "u_9876543210",
                        displayName = "Avinash Kumar",
                        phone = "+919876543210",
                        lastLoginMillis = 2000L,
                        lastLoginFormatted = "19 Nov 2026, 06:50 AM"
                    ),
                    com.example.data.local.UserEntity(
                        userId = "u_1122334455",
                        displayName = "Riya Sharma",
                        phone = "+911122334455",
                        lastLoginMillis = 1000L,
                        lastLoginFormatted = "19 Nov 2026, 06:40 AM"
                    )
                )
            )
            val byPhone = userDao.searchUsers("987654")
            assertEquals(1, byPhone.size)
            assertEquals("Avinash Kumar", byPhone.first().displayName)

            val byName = userDao.searchUsers("Riya")
            assertEquals(1, byName.size)
            assertEquals("u_1122334455", byName.first().userId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `phone normalization standardizes 10-digit and E164 numbers consistently`() {
        assertEquals("+919876543210", com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber("9876543210"))
        assertEquals("+919876543210", com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber(" 098765-43210 "))
        assertEquals("+919876543210", com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber("919876543210"))
        assertEquals("+919876543210", com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber("+91 98765 43210"))
        assertEquals("+14155550199", com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber("+1 (415) 555-0199"))
    }

    @Test
    fun `parseApiErrorMessage extracts detail and message from HTTP 400 JSON`() {
        val detailMsg = com.example.data.repository.PhoneAuthUtils.parseApiErrorMessage(
            rawErrorBody = """{"detail":"Invalid or expired OTP"}""",
            statusCode = 400,
            fallback = "OTP verification failed"
        )
        assertEquals("Invalid or expired OTP", detailMsg)

        val nullDetailMsg = com.example.data.repository.PhoneAuthUtils.parseApiErrorMessage(
            rawErrorBody = """{"detail":null,"message":"OTP expired"}""",
            statusCode = 400,
            fallback = "OTP verification failed"
        )
        assertEquals("OTP expired", nullDetailMsg)

        val arrayDetailMsg = com.example.data.repository.PhoneAuthUtils.parseApiErrorMessage(
            rawErrorBody = """{"detail":[{"msg":"Phone format invalid"}]}""",
            statusCode = 400,
            fallback = "OTP request failed"
        )
        assertEquals("Phone format invalid", arrayDetailMsg)

        val fallbackMsg = com.example.data.repository.PhoneAuthUtils.parseApiErrorMessage(
            rawErrorBody = null,
            statusCode = 400,
            fallback = "OTP verification failed"
        )
        assertEquals("OTP verification failed (400)", fallbackMsg)
    }

    @Test
    fun `sendOtpResponse resolves telegram_bot_url and bot_username`() {
        val direct = com.example.data.remote.SendOtpResponse(
            telegramBotUrl = "https://t.me/HelloSirOtpBot?start=123"
        )
        assertEquals("https://t.me/HelloSirOtpBot?start=123", direct.resolvedTelegramBotUrl())

        val fromUsername = com.example.data.remote.SendOtpResponse(
            botUsername = "@HelloSirOtpBot"
        )
        assertEquals("https://t.me/HelloSirOtpBot", fromUsername.resolvedTelegramBotUrl())
    }

    @Test
    fun `verifyOtpResponse advances to profile setup on HTTP 200 even when name is requested`() {
        val standardOk = com.example.data.remote.VerifyOtpResponse(success = true)
        assertTrue(standardOk.isOtpVerified())

        val verifiedWithProfileFlag = com.example.data.remote.VerifyOtpResponse(
            success = false,
            verified = true,
            requiresProfile = true,
            message = "Please enter your name"
        )
        assertTrue(verifiedWithProfileFlag.isOtpVerified())

        val promptForNameOnly = com.example.data.remote.VerifyOtpResponse(
            success = false,
            message = "Display name is required for registration"
        )
        assertTrue(promptForNameOnly.isOtpVerified())

        val invalidOtp = com.example.data.remote.VerifyOtpResponse(
            success = false,
            verified = false,
            message = "Invalid OTP code"
        )
        org.junit.Assert.assertFalse(invalidOtp.isOtpVerified())
    }

    @Test
    fun `403 blocklist event sets blocked state`() {
        GlobalSecurityEventBus.clearBlocked()
        GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
        assertTrue(GlobalSecurityEventBus.isBlocked.value)
        GlobalSecurityEventBus.clearBlocked()
    }
}
