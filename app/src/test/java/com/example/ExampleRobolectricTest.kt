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

    @Test
    fun `parseCallSignalsFromBody parses offer, ringing, answer, ice, hangup, and decline across poll and active shapes`() {
        val pollJson = """
            {
              "signals": [
                {"id":"sig_1","type":"offer","call_id":"c_101","caller_id":"u_avinash","caller_name":"Avinash","receiver_id":"u_sandeep","is_video":true,"sdp":"v=0\r\noffer"},
                {"id":"sig_2","type":"ringing","callId":"c_101","senderId":"u_sandeep","receiverId":"u_avinash"},
                {"id":"sig_3","type":"ice","callId":"c_101","senderId":"u_avinash","receiverId":"u_sandeep","candidate":{"candidate":"candidate:1 1 UDP 2122252543 10.0.0.1 50000 typ host","sdpMid":"0","sdpMLineIndex":0}},
                {"id":"sig_4","type":"answer","callId":"c_101","senderId":"u_sandeep","receiverId":"u_avinash","answer":{"sdp":"v=0\r\nanswer"}},
                {"id":"sig_5","type":"decline","callId":"c_102","senderId":"u_sandeep","receiverId":"u_avinash"},
                {"id":"sig_6","type":"hangup","callId":"c_101","senderId":"u_avinash","receiverId":"u_sandeep"}
              ]
            }
        """.trimIndent()

        val signals = com.example.data.remote.BackendJsonParsers.parseCallSignalsFromBody(pollJson)
        assertEquals(6, signals.size)
        assertEquals("offer", signals[0].type)
        assertEquals("c_101", signals[0].callId)
        assertEquals("u_avinash", signals[0].senderId)
        assertEquals("Avinash", signals[0].senderName)
        assertTrue(signals[0].isVideo)
        assertEquals("v=0\r\noffer", signals[0].sdp)

        assertEquals("ringing", signals[1].type)
        assertEquals("ice", signals[2].type)
        assertEquals("0", signals[2].sdpMid)
        assertTrue(signals[2].candidate.startsWith("candidate:1"))

        assertEquals("answer", signals[3].type)
        assertEquals("v=0\r\nanswer", signals[3].sdp)
        assertEquals("decline", signals[4].type)
        assertEquals("hangup", signals[5].type)

        val activeJson = """
            {"activeCall":{"callId":"c_200","status":"ringing","callerId":"u_avinash","callerName":"Avinash","receiverId":"u_sandeep","callType":"video","sdp":"v=0\r\nactive"}}
        """.trimIndent()
        val activeSignals = com.example.data.remote.BackendJsonParsers.parseCallSignalsFromBody(activeJson)
        assertEquals(1, activeSignals.size)
        assertEquals("c_200", activeSignals[0].callId)
        assertTrue(activeSignals[0].isVideo)
        assertEquals("v=0\r\nactive", activeSignals[0].sdp)
    }

    @Test
    fun `parseMediaUploadUrl resolves relative uploads path, nested data, and filename`() {
        assertEquals(
            "https://rendar-ud3i.onrender.com/uploads/clip_123.mp4",
            com.example.data.remote.BackendJsonParsers.parseMediaUploadUrl("""{"url":"/uploads/clip_123.mp4"}""")
        )
        assertEquals(
            "https://rendar-ud3i.onrender.com/uploads/pic_99.jpg",
            com.example.data.remote.BackendJsonParsers.parseMediaUploadUrl("""{"data":{"fileUrl":"https://rendar-ud3i.onrender.com/uploads/pic_99.jpg"}}""")
        )
        assertEquals(
            "https://rendar-ud3i.onrender.com/uploads/photo_abc.jpg",
            com.example.data.remote.BackendJsonParsers.parseMediaUploadUrl("""{"success":true,"filename":"photo_abc.jpg"}""")
        )
    }

    @Test
    fun `callSignalDeduplicator prevents duplicate signal processing and tracks dismissed calls`() {
        com.example.data.repository.CallSignalDeduplicator.clear()
        val key = com.example.data.repository.CallSignalDeduplicator.buildKey(
            type = "offer",
            callId = "call_777",
            senderId = "u_avinash",
            payload = "sdp_data"
        )
        assertTrue(com.example.data.repository.CallSignalDeduplicator.markIfNew(key))
        org.junit.Assert.assertFalse(com.example.data.repository.CallSignalDeduplicator.markIfNew(key))

        org.junit.Assert.assertFalse(com.example.data.repository.CallSignalDeduplicator.isDismissed("call_777"))
        com.example.data.repository.CallSignalDeduplicator.markDismissed("call_777")
        assertTrue(com.example.data.repository.CallSignalDeduplicator.isDismissed("call_777"))
        com.example.data.repository.CallSignalDeduplicator.clear()
    }

    @Test
    fun `parseCallSignalsFromBody prioritizes senderId over callerId on answer ice decline and maps active ringing to offer`() {
        val mixedSignalsJson = """
            {
              "signals": [
                {"id":"s_ans","type":"answer","callId":"c_500","callerId":"u_avinash","senderId":"u_sandeep","receiverId":"u_avinash","sdp":"v=0\r\nans"},
                {"id":"s_ice","type":"ice","callId":"c_500","caller_id":"u_avinash","sender_id":"u_sandeep","candidate":"candidate:1 1 UDP 1 10.0.0.2 50001 typ host","sdpMid":"0","sdpMLineIndex":0},
                {"id":"s_dec","type":"decline","callId":"c_501","callerId":"u_avinash","senderId":"u_sandeep","receiverId":"u_avinash"}
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.remote.BackendJsonParsers.parseCallSignalsFromBody(mixedSignalsJson)
        assertEquals(3, parsed.size)
        assertEquals("answer", parsed[0].type)
        assertEquals("u_sandeep", parsed[0].senderId)
        assertEquals("ice", parsed[1].type)
        assertEquals("u_sandeep", parsed[1].senderId)
        assertEquals("decline", parsed[2].type)
        assertEquals("u_sandeep", parsed[2].senderId)

        val activeOfferJson = """
            {"activeCall":{"callId":"c_502","status":"ringing","callerId":"u_avinash","callerName":"Avinash","receiverId":"u_sandeep","callType":"video","sdp":"v=0\r\noffer_active"}}
        """.trimIndent()
        val activeSignals = com.example.data.remote.BackendJsonParsers.parseCallSignalsFromBody(activeOfferJson)
        assertEquals(1, activeSignals.size)
        assertEquals("offer", activeSignals[0].type)
        assertEquals("u_avinash", activeSignals[0].senderId)
        assertEquals("v=0\r\noffer_active", activeSignals[0].sdp)
    }

    @Test
    fun `parseIceServersFromBody parses single string urls, array urls, snake_case wrapper, and TURN credentials`() {
        val json = """
            {
              "ice_servers": [
                {"urls":"stun:stun.l.google.com:19302"},
                {"urls":["turn:relay.example.com:3478?transport=udp","turns:relay.example.com:5349"],"username":"u1","credential":"secret1"}
              ]
            }
        """.trimIndent()
        val servers = com.example.data.remote.BackendJsonParsers.parseIceServersFromBody(json)
        assertEquals(2, servers.size)
        assertEquals(listOf("stun:stun.l.google.com:19302"), servers[0].urls)
        assertEquals(null, servers[0].username)
        assertEquals(2, servers[1].urls.size)
        assertEquals("u1", servers[1].username)
        assertEquals("secret1", servers[1].credential)
    }

    @Test
    fun `callSignalDeduplicator keeps offer answer connected and hangup distinct even with shared record id`() {
        com.example.data.repository.CallSignalDeduplicator.clear()
        val offerKey = com.example.data.repository.CallSignalDeduplicator.buildKey(
            type = "offer",
            callId = "c_shared",
            senderId = "u_avinash",
            payload = "v=0\roffer",
            signalId = "row_999"
        )
        val answerKey = com.example.data.repository.CallSignalDeduplicator.buildKey(
            type = "answer",
            callId = "c_shared",
            senderId = "u_sandeep",
            payload = "v=0\ranswer",
            signalId = "row_999"
        )
        val connectedKey = com.example.data.repository.CallSignalDeduplicator.buildKey(
            type = "connected",
            callId = "c_shared",
            senderId = "u_sandeep",
            payload = "",
            signalId = "row_999"
        )
        assertTrue(com.example.data.repository.CallSignalDeduplicator.markIfNew(offerKey))
        assertTrue(com.example.data.repository.CallSignalDeduplicator.markIfNew(answerKey))
        assertTrue(com.example.data.repository.CallSignalDeduplicator.markIfNew(connectedKey))
        org.junit.Assert.assertFalse(com.example.data.repository.CallSignalDeduplicator.markIfNew(answerKey))
        com.example.data.repository.CallSignalDeduplicator.clear()
    }

    @Test
    fun `parseCallSignalsFromBody parses connected and accepted signals cleanly and CallSignalRequest preserves null callerId`() {
        val json = """
            {
              "signals": [
                {"id":"s_conn_sdp","status":"connected","callId":"c_600","senderId":"u_sandeep","receiverId":"u_avinash","answer":"v=0\r\nconn_ans"},
                {"id":"s_conn_only","type":"call_connected","callId":"c_600","senderId":"u_sandeep","receiverId":"u_avinash"}
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.remote.BackendJsonParsers.parseCallSignalsFromBody(json)
        assertEquals(2, parsed.size)
        assertEquals("answer", parsed[0].type)
        assertEquals("v=0\r\nconn_ans", parsed[0].sdp)
        assertEquals("connected", parsed[1].type)
        assertEquals("u_sandeep", parsed[1].senderId)

        val answerReq = com.example.data.remote.CallSignalRequest(
            type = "answer",
            callId = "c_600",
            senderId = "u_sandeep",
            receiverId = "u_avinash",
            sdp = "v=0\r\nans"
        )
        assertEquals(null, answerReq.callerId)
        assertEquals("u_sandeep", answerReq.senderId)
        assertEquals("u_avinash", answerReq.receiverId)
    }

    @Test
    fun `notificationHelper formats sender title with Name and User ID`() {
        assertEquals(
            "Sandeep (u_919250903007)",
            com.example.platform.NotificationHelper.formatSenderTitle("Sandeep", "u_919250903007")
        )
        assertEquals(
            "u_919250903007",
            com.example.platform.NotificationHelper.formatSenderTitle("  ", "u_919250903007")
        )
        assertEquals(
            "avinash",
            com.example.platform.NotificationHelper.formatSenderTitle("avinash", "Avinash")
        )
    }

    @Test
    fun `shayariManager rotates shayari on every open and deduplicates morning notifications`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(com.example.platform.ShayariManager.shayariCollection.size >= 20)

        val first = com.example.platform.ShayariManager.nextShayariForAppOpen(context, nowMillis = 1_763_700_000_000L)
        val second = com.example.platform.ShayariManager.nextShayariForAppOpen(context, nowMillis = 1_763_700_060_000L)
        val third = com.example.platform.ShayariManager.getShayariAfterIndex(second.id)
        assertTrue(first.id != second.id)
        assertTrue(second.id != third.id)
        assertTrue(first.lines.isNotBlank())

        val dayOne = 1_763_700_000_000L
        val nextDay = dayOne + 24L * 60L * 60L * 1000L
        assertTrue(com.example.platform.ShayariManager.shouldSendMorningNotificationToday(context, dayOne))
        com.example.platform.ShayariManager.markMorningNotificationSent(context, dayOne)
        org.junit.Assert.assertFalse(
            com.example.platform.ShayariManager.shouldSendMorningNotificationToday(context, dayOne + 3_600_000L)
        )
        assertTrue(com.example.platform.ShayariManager.shouldSendMorningNotificationToday(context, nextDay))
    }

    @Test
    fun `callSignalDeduplicator tracks answered calls so poll cannot re-ring them`() {
        com.example.data.repository.CallSignalDeduplicator.clear()
        org.junit.Assert.assertFalse(com.example.data.repository.CallSignalDeduplicator.isHandled("call_ans_1"))
        com.example.data.repository.CallSignalDeduplicator.markAnswered("call_ans_1")
        assertTrue(com.example.data.repository.CallSignalDeduplicator.isHandled("call_ans_1"))
        com.example.data.repository.CallSignalDeduplicator.clear()
    }

    @Test
    fun `authCredentials sealed hierarchy holds DirectUserId and GoogleIdToken payloads`() {
        val direct: com.example.core.security.AuthCredentials =
            com.example.core.security.AuthCredentials.DirectUserId(
                username = "Avinash",
                userId = "u_919455877902",
                avatarUrl = null,
                deviceId = "dev_1"
            )
        val google: com.example.core.security.AuthCredentials =
            com.example.core.security.AuthCredentials.GoogleIdToken(
                idToken = "id_tok_abc",
                email = "avinash@example.com",
                displayName = "Avinash"
            )
        assertTrue(direct is com.example.core.security.AuthCredentials.DirectUserId)
        assertTrue(google is com.example.core.security.AuthCredentials.GoogleIdToken)
    }

    @Test
    fun `backendJsonParsers handles presence offline status, SEEN to READ, and ISO-8601 timestamps`() {
        val users = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """
            {
              "users": [
                {"userId":"u_online","displayName":"OnlineUser","status":"online","isOnline":true},
                {"userId":"u_offline_status","displayName":"OfflineByStatus","status":"offline","isOnline":true},
                {"userId":"u_offline_flag","displayName":"OfflineByFlag","is_online":false}
              ]
            }
            """.trimIndent()
        )
        assertEquals(3, users.size)
        assertTrue(users[0].resolvedIsOnline())
        org.junit.Assert.assertFalse(users[1].resolvedIsOnline())
        org.junit.Assert.assertFalse(users[2].resolvedIsOnline())

        val msgs = com.example.data.remote.BackendJsonParsers.parseMessagesFromBody(
            """
            {
              "messages": [
                {"id":"m_seen","sender_id":"u_avinash","receiver_id":"u_sandeep","text":"Read me","status":"SEEN","createdAt":"2026-11-22T12:30:00Z"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, msgs.size)
        assertEquals("READ", msgs.first().status)
        assertTrue(msgs.first().timestamp > 1_700_000_000_000L)

        assertEquals(
            "READ",
            com.example.data.remote.BackendJsonParsers.parseSendMessageStatus("""{"message":{"status":"seen"}}""")
        )
    }

    @Test
    fun `unread incoming message moves conversation to top and markLastMessageReadIfMine updates status to READ`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val convDao = db.conversationDao()
            val userDao = db.userDao()

            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "u_first",
                    peerName = "First",
                    lastMessageId = "m1",
                    lastMessagePreview = "Hello",
                    lastMessageType = MessageType.TEXT,
                    lastMessageTimestamp = 2000L,
                    lastMessageStatus = MessageStatus.SENT,
                    lastMessageIsMine = true,
                    unreadCount = 0
                )
            )
            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "u_second",
                    peerName = "Second",
                    lastMessageId = "m2",
                    lastMessagePreview = "Older",
                    lastMessageType = MessageType.TEXT,
                    lastMessageTimestamp = 1000L,
                    lastMessageStatus = MessageStatus.DELIVERED,
                    lastMessageIsMine = false,
                    unreadCount = 0
                )
            )
            // Incoming newer unread message updates u_second and moves it to index 0
            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "u_second",
                    peerName = "Second",
                    lastMessageId = "m3",
                    lastMessagePreview = "New unread msg!",
                    lastMessageType = MessageType.TEXT,
                    lastMessageTimestamp = 5000L,
                    lastMessageStatus = MessageStatus.DELIVERED,
                    lastMessageIsMine = false,
                    unreadCount = 2
                )
            )
            val ordered = convDao.observeConversations().first()
            assertEquals("u_second", ordered.first().peerId)
            assertEquals(2, ordered.first().unreadCount)

            convDao.markLastMessageReadIfMine("u_first")
            assertEquals(MessageStatus.READ, convDao.getConversation("u_first")?.lastMessageStatus)

            userDao.upsertUsers(
                listOf(
                    com.example.data.local.UserEntity(
                        userId = "u_first",
                        displayName = "First",
                        isOnline = true,
                        lastLoginMillis = 1000L,
                        lastLoginFormatted = "now"
                    )
                )
            )
            userDao.clearAllOnlineStatuses()
            org.junit.Assert.assertFalse(userDao.getUser("u_first")?.isOnline == true)
        } finally {
            db.close()
        }
    }

    @Test
    fun `sessionManager persists userId and displayName across simulated update key rotation until explicit logout`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = com.example.core.security.SessionManager(context)
        sessionManager.clearSession()

        val saved = sessionManager.saveSession(
            userId = "avinash_9455",
            displayName = "Avinash",
            phone = "+919455877902",
            avatarUrl = null,
            serverToken = "tok_persist_1",
            registeredAtMillis = 1_763_800_000_000L,
            registeredAtFormatted = "22 Nov 2026, 10:00 AM"
        )
        assertEquals("avinash_9455", saved.userId)
        assertEquals("Avinash", saved.displayName)

        // Simulate app update / Keystore key rotation corrupting DataStore cipher blob
        sessionManager.simulateUpdateKeyRotationForTest()
        val recovered = sessionManager.getCurrentSession()
        assertEquals("avinash_9455", recovered?.userId)
        assertEquals("Avinash", recovered?.displayName)

        // Explicit logout must clear session completely
        sessionManager.clearSession()
        assertEquals(null, sessionManager.getCurrentSession())
    }

    @Test
    fun `deleteUsersNotIn and deleteEmptyConversationsNotIn prune stale dummy clutter`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val userDao = db.userDao()
            val convDao = db.conversationDao()

            userDao.upsertUsers(
                listOf(
                    com.example.data.local.UserEntity("u_active", "Avinash", lastLoginMillis = 1000L, lastLoginFormatted = "now"),
                    com.example.data.local.UserEntity("dummy_old", "Stale Dummy", lastLoginMillis = 500L, lastLoginFormatted = "old")
                )
            )
            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "dummy_old",
                    peerName = "Stale Dummy",
                    lastMessageId = null,
                    lastMessagePreview = "Tap to start secure chat",
                    lastMessageTimestamp = 500L
                )
            )
            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "real_history_peer",
                    peerName = "Real Peer",
                    lastMessageId = "m_hist_1",
                    lastMessagePreview = "Saved chat history",
                    lastMessageTimestamp = 900L
                )
            )

            val activeIds = listOf("u_active")
            userDao.deleteUsersNotIn(activeIds)
            convDao.deleteEmptyConversationsNotIn(activeIds)

            val remainingUsers = userDao.observeAllUsers().first()
            assertEquals(1, remainingUsers.size)
            assertEquals("u_active", remainingUsers.first().userId)

            val remainingConvs = convDao.observeConversations().first()
            assertEquals(1, remainingConvs.size)
            assertEquals("real_history_peer", remainingConvs.first().peerId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `backendJsonParsers enforces strict foreground presence and boolean receipt flags`() {
        val users = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """
            {
              "users": [
                {"userId":"u_bg","displayName":"BackgroundUser","isOnline":true,"inForeground":false},
                {"userId":"u_fg","displayName":"ForegroundUser","isOnline":true,"appState":"foreground"},
                {"userId":"u_paused","displayName":"PausedUser","online":true,"appState":"background"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(3, users.size)
        org.junit.Assert.assertFalse(users[0].resolvedIsOnline())
        assertTrue(users[1].resolvedIsOnline())
        org.junit.Assert.assertFalse(users[2].resolvedIsOnline())

        val msgs = com.example.data.remote.BackendJsonParsers.parseMessagesFromBody(
            """
            {
              "messages": [
                {"id":"m_deliv","senderId":"a","receiverId":"b","text":"Hi","isDelivered":true,"timestamp":1000},
                {"id":"m_read_flag","senderId":"a","receiverId":"b","text":"Hello","isRead":true,"deliveredAt":"2026-11-22T10:00:00Z","timestamp":2000}
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, msgs.size)
        assertEquals("DELIVERED", msgs[0].status)
        assertEquals("READ", msgs[1].status)
    }

    @Test
    fun `messageDao and conversationDao advance SENT to DELIVERED cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val msgDao = db.messageDao()
            val convDao = db.conversationDao()
            msgDao.upsertMessage(
                com.example.data.local.MessageEntity(
                    id = "m_1",
                    conversationId = "peer_sandeep",
                    senderId = "me_avinash",
                    receiverId = "peer_sandeep",
                    content = "Hello",
                    status = MessageStatus.SENT,
                    timestamp = 1000L
                )
            )
            convDao.upsertConversation(
                ConversationEntity(
                    peerId = "peer_sandeep",
                    peerName = "Sandeep",
                    lastMessageId = "m_1",
                    lastMessagePreview = "Hello",
                    lastMessageType = MessageType.TEXT,
                    lastMessageTimestamp = 1000L,
                    lastMessageStatus = MessageStatus.SENT,
                    lastMessageIsMine = true
                )
            )

            msgDao.markMyMessagesDelivered("peer_sandeep", "me_avinash")
            convDao.markLastMessageDeliveredIfMine("peer_sandeep")

            assertEquals(MessageStatus.DELIVERED, msgDao.getMessageById("m_1")?.status)
            assertEquals(MessageStatus.DELIVERED, convDao.getConversation("peer_sandeep")?.lastMessageStatus)

            msgDao.markMyMessagesRead("peer_sandeep", "me_avinash")
            convDao.markLastMessageReadIfMine("peer_sandeep")

            assertEquals(MessageStatus.READ, msgDao.getMessageById("m_1")?.status)
            assertEquals(MessageStatus.READ, convDao.getConversation("peer_sandeep")?.lastMessageStatus)
        } finally {
            db.close()
        }
    }

    @Test
    fun `backendJsonParsers marks loggedOut and inactive session users strictly offline and parses server message ids`() {
        val users = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """
            {
              "users": [
                {"userId":"u_logged_out","displayName":"LoggedOut","isOnline":true,"loggedOut":true},
                {"userId":"u_not_logged_in","displayName":"NotLoggedIn","isOnline":true,"isLoggedIn":false},
                {"userId":"u_inactive_sess","displayName":"InactiveSess","online":true,"activeSession":false},
                {"userId":"u_active_now","displayName":"ActiveNow","isOnline":true,"isLoggedIn":true,"inForeground":true}
              ]
            }
            """.trimIndent()
        )
        assertEquals(4, users.size)
        org.junit.Assert.assertFalse(users[0].resolvedIsOnline())
        org.junit.Assert.assertFalse(users[1].resolvedIsOnline())
        org.junit.Assert.assertFalse(users[2].resolvedIsOnline())
        assertTrue(users[3].resolvedIsOnline())

        assertEquals(
            "srv_msg_101",
            com.example.data.remote.BackendJsonParsers.parseSendMessageServerId(
                """{"success":true,"message":{"_id":"srv_msg_101","status":"sent"}}"""
            )
        )
        assertEquals(
            "srv_msg_202",
            com.example.data.remote.BackendJsonParsers.parseSendMessageServerId(
                """{"success":true,"message_id":"srv_msg_202"}"""
            )
        )
        assertEquals(
            null,
            com.example.data.remote.BackendJsonParsers.parseSendMessageServerId("""{"success":true}""")
        )
    }

    @Test
    fun `signalingClient tracks foreground state cleanly and sessionManager cache resets on logout`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = com.example.core.security.SessionManager(context)
        sessionManager.clearSession()

        sessionManager.saveSession(
            userId = "vk",
            displayName = "avinash",
            phone = "+919455877902",
            avatarUrl = null,
            serverToken = "tok_vk_1",
            registeredAtMillis = 1_763_900_000_000L,
            registeredAtFormatted = "22 Nov 2026, 11:00 PM"
        )
        assertEquals("vk", sessionManager.getCurrentSession()?.userId)

        sessionManager.simulateUpdateKeyRotationForTest()
        assertEquals("vk", sessionManager.getCurrentSession()?.userId)

        val client = com.example.data.remote.SignalingClient(
            okHttpClient = okhttp3.OkHttpClient(),
            sessionManager = sessionManager
        )
        org.junit.Assert.assertFalse(client.isConnectingOrConnected())
        client.setAppForeground(false, keepSocketForCall = true)
        org.junit.Assert.assertFalse(client.isAppInForeground)

        sessionManager.clearSession()
        assertEquals(null, sessionManager.getCurrentSession())
    }

    @Test
    fun `activeCallNotification provides reopen intent, ongoing CallStyle chronometer, and Mute + HangUp actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.platform.NotificationHelper.createChannels(context)

        val notification = com.example.platform.NotificationHelper.buildActiveCallNotification(
            context = context,
            peerName = "Sandeep",
            isVideo = true,
            isConnected = true,
            connectedSinceMs = 1_763_900_000_000L,
            isMuted = false
        )
        assertTrue((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        org.junit.Assert.assertNotNull(notification.contentIntent)
        assertTrue(notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
        val actionTitles = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
        assertTrue(actionTitles.any { it.contains("Mute", ignoreCase = true) })

        val mutedNotification = com.example.platform.NotificationHelper.buildActiveCallNotification(
            context = context,
            peerName = "Sandeep",
            isVideo = false,
            isConnected = true,
            connectedSinceMs = 1_763_900_000_000L,
            isMuted = true
        )
        val mutedTitles = mutedNotification.actions.orEmpty().mapNotNull { it.title?.toString() }
        assertTrue(mutedTitles.any { it.contains("Unmute", ignoreCase = true) })
    }

    @Test
    fun `userDao searchUsers finds matches case-insensitively by User ID and Name`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val userDao = db.userDao()
            userDao.upsertUsers(
                listOf(
                    com.example.data.local.UserEntity(
                        userId = "vk_01",
                        displayName = "Avinash Verma",
                        phone = "+919455877902",
                        lastLoginMillis = 3000L,
                        lastLoginFormatted = "now"
                    ),
                    com.example.data.local.UserEntity(
                        userId = "sandeep_92",
                        displayName = "Sandeep Kumar",
                        phone = "+919250903007",
                        lastLoginMillis = 2000L,
                        lastLoginFormatted = "earlier"
                    )
                )
            )
            val byUpperUserId = userDao.searchUsers("VK_01")
            assertEquals(1, byUpperUserId.size)
            assertEquals("Avinash Verma", byUpperUserId.first().displayName)

            val byAtPrefixNormalized = userDao.searchUsers("@sandeep_92".removePrefix("@"))
            assertEquals(1, byAtPrefixNormalized.size)
            assertEquals("sandeep_92", byAtPrefixNormalized.first().userId)

            val byLowerName = userDao.searchUsers("avinash")
            assertEquals(1, byLowerName.size)
            assertEquals("vk_01", byLowerName.first().userId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `statusDao stores active statuses, filters expired ones, and updates viewed state and deletion`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val statusDao = db.statusDao()
            val now = 2_000_000_000_000L
            statusDao.upsertStatuses(
                listOf(
                    com.example.data.local.StatusUpdateEntity(
                        id = "st_active_text",
                        userId = "sandeep_92",
                        userName = "Sandeep",
                        type = com.example.data.local.StatusType.TEXT,
                        textContent = "Good morning!",
                        backgroundColorHex = "#1B5E20",
                        createdAt = now - 5_000L,
                        expiresAt = now + 60_000L,
                        isViewedByMe = false
                    ),
                    com.example.data.local.StatusUpdateEntity(
                        id = "st_expired",
                        userId = "sandeep_92",
                        userName = "Sandeep",
                        type = com.example.data.local.StatusType.IMAGE,
                        mediaUrl = "https://rendar-ud3i.onrender.com/uploads/old.jpg",
                        createdAt = now - 100_000L,
                        expiresAt = now - 1_000L,
                        isViewedByMe = false
                    )
                )
            )

            val active = statusDao.observeActiveStatuses(now).first()
            assertEquals(1, active.size)
            assertEquals("st_active_text", active.first().id)
            org.junit.Assert.assertFalse(active.first().isViewedByMe)

            statusDao.markViewed("st_active_text")
            statusDao.updateViewCountIfHigher("st_active_text", 3)
            val viewedItem = statusDao.getStatusById("st_active_text")
            assertTrue(viewedItem?.isViewedByMe == true)
            assertEquals(3, viewedItem?.viewCount)

            statusDao.deleteExpiredStatuses(now)
            assertEquals(null, statusDao.getStatusById("st_expired"))

            statusDao.deleteStatus("st_active_text")
            assertTrue(statusDao.observeAllStatuses().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `sessionManager persists edited bio and displayName across key rotation recovery and clears cleanly on logout`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = com.example.core.security.SessionManager(context)
        sessionManager.clearSession()

        sessionManager.saveSession(
            userId = "u_919455877902",
            displayName = "Avinash",
            phone = "+919455877902",
            avatarUrl = null,
            serverToken = "tok_profile_1",
            registeredAtMillis = 1_763_900_000_000L,
            registeredAtFormatted = "22 Nov 2026"
        )

        val updated = sessionManager.updateLocalProfile(
            displayName = "Avinash Kumar",
            bio = "Building Hello Sir!",
            avatarUrl = "https://rendar-ud3i.onrender.com/uploads/avatar.png"
        )
        assertEquals("Avinash Kumar", updated?.displayName)
        assertEquals("Building Hello Sir!", updated?.bio)
        assertEquals("https://rendar-ud3i.onrender.com/uploads/avatar.png", updated?.avatarUrl)

        sessionManager.simulateUpdateKeyRotationForTest()
        val recovered = sessionManager.getCurrentSession()
        assertEquals("Avinash Kumar", recovered?.displayName)
        assertEquals("Building Hello Sir!", recovered?.bio)
        assertEquals("https://rendar-ud3i.onrender.com/uploads/avatar.png", recovered?.avatarUrl)

        sessionManager.clearSession()
        assertEquals(null, sessionManager.getCurrentSession())
    }

    @Test
    fun `backendJsonParsers parses text, image, and video statuses and user bio fields`() {
        val users = com.example.data.remote.BackendJsonParsers.parseUsersFromBody(
            """
            {
              "users": [
                {
                  "userId": "sandeep_01",
                  "displayName": "Sandeep",
                  "about": "At work",
                  "isOnline": true,
                  "inForeground": true
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, users.size)
        assertEquals("At work", users.first().bio)

        val statuses = com.example.data.remote.BackendJsonParsers.parseStatusesFromBody(
            """
            {
              "statuses": [
                {
                  "id": "st_1",
                  "userId": "sandeep_01",
                  "userName": "Sandeep",
                  "type": "TEXT",
                  "textContent": "Hello everyone!",
                  "backgroundColorHex": "#0D47A1",
                  "createdAt": 1763900000000,
                  "expiresAt": 1763986400000,
                  "viewCount": 2
                },
                {
                  "id": "st_2",
                  "userId": "avinash_01",
                  "userName": "Avinash",
                  "type": "VIDEO",
                  "mediaUrl": "/uploads/clip.mp4",
                  "caption": "Trip clip"
                },
                {
                  "id": "st_3",
                  "userId": "avinash_01",
                  "userName": "Avinash",
                  "mediaUrl": "/uploads/photo.jpg"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(3, statuses.size)
        assertEquals("TEXT", statuses[0].type)
        assertEquals("Hello everyone!", statuses[0].textContent)
        assertEquals("#0D47A1", statuses[0].backgroundColorHex)
        assertEquals(2, statuses[0].viewCount)
        assertEquals("VIDEO", statuses[1].type)
        assertEquals("https://rendar-ud3i.onrender.com/uploads/clip.mp4", statuses[1].mediaUrl)
        assertEquals("IMAGE", statuses[2].type)
        assertEquals("https://rendar-ud3i.onrender.com/uploads/photo.jpg", statuses[2].mediaUrl)
    }
}
