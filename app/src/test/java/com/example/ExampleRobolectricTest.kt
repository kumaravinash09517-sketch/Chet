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

        val hindiNamePrompt = com.example.data.remote.VerifyOtpResponse(
            success = false,
            message = "कृपया अपना नाम लिखें"
        )
        assertTrue(hindiNamePrompt.isOtpVerified())
    }

    @Test
    fun `isProfileSetupRequiredError detects 400 name prompts but rejects wrong OTP errors`() {
        assertTrue(
            com.example.data.repository.PhoneAuthUtils.isProfileSetupRequiredError(
                rawErrorBody = """{"detail":"कृपया अपना नाम लिखें..."}""",
                parsedMessage = "कृपया अपना नाम लिखें..."
            )
        )
        assertTrue(
            com.example.data.repository.PhoneAuthUtils.isProfileSetupRequiredError(
                rawErrorBody = """{"verified":true,"requires_profile":true,"message":"Display name required"}""",
                parsedMessage = "Display name required"
            )
        )
        org.junit.Assert.assertFalse(
            com.example.data.repository.PhoneAuthUtils.isProfileSetupRequiredError(
                rawErrorBody = """{"detail":"Invalid or expired OTP"}""",
                parsedMessage = "Invalid or expired OTP"
            )
        )
        org.junit.Assert.assertFalse(
            com.example.data.repository.PhoneAuthUtils.isProfileSetupRequiredError(
                rawErrorBody = """{"detail":"OTP गलत है"}""",
                parsedMessage = "OTP गलत है"
            )
        )
    }

    @Test
    fun `moshi parses verify-otp responses without userId missing crash at user`() {
        val moshi = com.example.data.remote.NetworkModule.provideMoshi()
        val adapter = moshi.adapter(com.example.data.remote.VerifyOtpResponse::class.java)

        val otpVerifiedOnly = adapter.fromJson("""{"status":"otp_verified","verified":true}""")!!
        assertTrue(otpVerifiedOnly.isOtpVerified())
        assertTrue(otpVerifiedOnly.isProfileSetupExplicitlyRequired())

        val emptyUserObj = adapter.fromJson("""{"success":true,"status":"otp_verified","user":{}}""")!!
        assertTrue(emptyUserObj.isOtpVerified())
        assertEquals(null, emptyUserObj.resolvedUserId())

        val snakeCaseNestedUser = adapter.fromJson(
            """{"success":true,"token":"tok_123","user":{"user_id":"u_919876543210","display_name":"Avinash","phone":null,"is_blocked":false}}"""
        )!!
        assertEquals("u_919876543210", snakeCaseNestedUser.resolvedUserId())
        assertEquals("Avinash", snakeCaseNestedUser.resolvedDisplayName())
        assertTrue(snakeCaseNestedUser.canCompleteExistingLoginDirectly())

        val idBlockedUser = adapter.fromJson(
            """{"success":true,"token":"tok_456","user":{"id":"usr_77","name":"Riya","is_blocked":true}}"""
        )!!
        assertEquals("usr_77", idBlockedUser.resolvedUserId())
        assertEquals("Riya", idBlockedUser.resolvedDisplayName())
        assertTrue(idBlockedUser.resolvedIsBlocked())
        org.junit.Assert.assertFalse(idBlockedUser.canCompleteExistingLoginDirectly())
    }

    @Test
    fun `sessionManager allows same-phone re-login and blocks other active accounts`() {
        assertTrue(
            com.example.core.security.SessionManager.isAllowedForActiveLock(
                existingUserId = "u_919876543210",
                existingPhone = "+919876543210",
                candidateUserId = "u_919876543210",
                candidatePhone = "9876543210"
            )
        )
        assertTrue(
            com.example.core.security.SessionManager.isAllowedForActiveLock(
                existingUserId = "u_919876543210",
                existingPhone = "+919876543210",
                candidateUserId = "different_id_same_phone",
                candidatePhone = "09876543210"
            )
        )
        org.junit.Assert.assertFalse(
            com.example.core.security.SessionManager.isAllowedForActiveLock(
                existingUserId = "u_919876543210",
                existingPhone = "+919876543210",
                candidateUserId = "u_other",
                candidatePhone = "+911122334455"
            )
        )
    }

    @Test
    fun `backendJsonParsers parses directory users, single find user, and raw arrays`() {
        val wrapped = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """{"users":[{"userId":"u_919455877902","displayName":"avinash","phone":"+919455877902"},{"user_id":"u_919250903007","display_name":"sandeep","phone":"+919250903007"}]}"""
        )
        assertEquals(2, wrapped.size)
        assertEquals("u_919455877902", wrapped[0].resolvedUserId())
        assertEquals("avinash", wrapped[0].resolvedDisplayName())
        assertEquals("u_919250903007", wrapped[1].resolvedUserId())
        assertEquals("sandeep", wrapped[1].resolvedDisplayName())

        val singleFind = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """{"user":{"id":"u_919455877902","name":"avinash","phone":"9455877902"}}"""
        )
        assertEquals(1, singleFind.size)
        assertEquals("u_919455877902", singleFind.first().resolvedUserId())

        val topLevelArray = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """[{"user_id":"u_919250903007","name":"sandeep","phone":"+919250903007"}]"""
        )
        assertEquals(1, topLevelArray.size)
        assertEquals("sandeep", topLevelArray.first().resolvedDisplayName())
    }

    @Test
    fun `backendJsonParsers parses messages and send ack statuses across JSON shapes`() {
        val parsedMsgs = com.example.data.remote.BackendJsonParsers.parseMessagesFromBody(
            """{"messages":[{"id":"m1","sender_id":"u_919455877902","receiver_id":"u_919250903007","text":"hi","status":"sent","timestamp":1763636348}]}"""
        )
        assertEquals(1, parsedMsgs.size)
        assertEquals("m1", parsedMsgs.first().id)
        assertEquals("u_919455877902", parsedMsgs.first().senderId)
        assertEquals("u_919250903007", parsedMsgs.first().receiverId)
        assertEquals("hi", parsedMsgs.first().content)
        assertEquals("SENT", parsedMsgs.first().status)
        assertEquals(1763636348000L, parsedMsgs.first().timestamp)

        assertEquals(
            "DELIVERED",
            com.example.data.remote.BackendJsonParsers.parseSendMessageStatus("""{"success":true,"message":{"status":"delivered"}}""")
        )
        assertEquals(
            null,
            com.example.data.remote.BackendJsonParsers.parseSendMessageStatus("""{"success":true}""")
        )
    }

    @Test
    fun `getPendingMessages returns unsent messages for retry`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.messageDao()
            dao.upsertMessage(
                com.example.data.local.MessageEntity(
                    id = "m_pending",
                    conversationId = "u_919250903007",
                    senderId = "u_919455877902",
                    receiverId = "u_919250903007",
                    content = "hi",
                    status = MessageStatus.PENDING,
                    timestamp = 1000L
                )
            )
            dao.upsertMessage(
                com.example.data.local.MessageEntity(
                    id = "m_sent",
                    conversationId = "u_919250903007",
                    senderId = "u_919455877902",
                    receiverId = "u_919250903007",
                    content = "hello",
                    status = MessageStatus.SENT,
                    timestamp = 2000L
                )
            )
            val pending = dao.getPendingMessages()
            assertEquals(1, pending.size)
            assertEquals("m_pending", pending.first().id)
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

    @Test
    fun `directLoginResponse resolves username, userId, normalized avatarUrl, and isBlocked`() {
        val moshi = com.example.data.remote.NetworkModule.provideMoshi()
        val adapter = moshi.adapter(com.example.data.remote.DirectLoginResponse::class.java)

        val parsed = adapter.fromJson(
            """{"success":true,"token":"jwt_99","user":{"user_id":"avinash_01","username":"Avinash","avatar_url":"/uploads/av.jpg","is_blocked":false}}"""
        )!!
        assertEquals("jwt_99", parsed.resolvedToken())
        assertEquals("avinash_01", parsed.resolvedUserId())
        assertEquals("Avinash", parsed.resolvedDisplayName())
        assertEquals("https://rendar-ud3i.onrender.com/uploads/av.jpg", parsed.resolvedAvatarUrl())
        org.junit.Assert.assertFalse(parsed.resolvedIsBlocked())
    }

    @Test
    fun `backendJsonParsers infers IMAGE, LOCATION, and TEXT with relative media URL normalization`() {
        val raw = """
            {
              "messages": [
                {"id":"m_img","sender_id":"sandeep","receiver_id":"avinash","type":"PHOTO","media_url":"/uploads/photo1.jpg","caption":"Look here","timestamp":1763636400},
                {"id":"m_loc","sender_id":"sandeep","receiver_id":"avinash","type":"TEXT","location":{"lat":28.6139,"lng":77.2090},"text":"Connaught Place","timestamp":1763636450},
                {"sender_id":"sandeep","receiver_id":"avinash","msg":"Hello Sir!","timestamp":1763636500}
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.remote.BackendJsonParsers.parseMessagesFromBody(raw)
        assertEquals(3, parsed.size)

        val img = parsed[0]
        assertEquals("IMAGE", img.type)
        assertEquals("https://rendar-ud3i.onrender.com/uploads/photo1.jpg", img.mediaUrl)
        assertEquals("Look here", img.content)

        val loc = parsed[1]
        assertEquals("LOCATION", loc.type)
        assertEquals(28.6139, loc.latitude!!, 0.0001)
        assertEquals(77.2090, loc.longitude!!, 0.0001)
        assertEquals("Connaught Place", loc.content)

        val txt = parsed[2]
        assertTrue(txt.id.isNotBlank())
        assertEquals("TEXT", txt.type)
        assertEquals("Hello Sir!", txt.content)
    }

    @Test
    fun `incoming message alert carries senderName and message headline`() = runTest {
        val alert = com.example.core.security.IncomingMessageAlert(
            messageId = "msg_alert_1",
            peerId = "u_919250903007",
            senderName = "Sandeep",
            headline = "Hello Sir, calling now"
        )
        assertEquals("Sandeep", alert.senderName)
        assertEquals("Hello Sir, calling now", alert.headline)
    }
}
