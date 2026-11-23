package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.ServerStatus
import com.example.core.security.UserSession
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageStatus
import com.example.data.local.MessageType
import com.example.data.local.StatusType
import com.example.data.local.StatusUpdateEntity
import com.example.data.local.UserEntity
import com.example.data.remote.BackendJsonParsers
import com.example.data.remote.SecurityAuthInterceptor
import com.example.data.remote.SignalingEvent
import com.example.platform.SignalingForegroundService
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.HelloSirTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PostLoginCrashRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `room MIGRATION_2_3 migrates v2 schema cleanly without schema validation crash`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_v2_v3_test.db"
        context.deleteDatabase(dbName)

        // Create an authentic v2 database matching schema before bio + status_updates were added
        val v2Helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `users` (`userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `phone` TEXT NOT NULL, `avatarUrl` TEXT, `lastLoginMillis` INTEGER NOT NULL, `lastLoginFormatted` TEXT NOT NULL, `isOnline` INTEGER NOT NULL, `isBlocked` INTEGER NOT NULL, PRIMARY KEY(`userId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations` (`peerId` TEXT NOT NULL, `peerName` TEXT NOT NULL, `peerPhone` TEXT NOT NULL, `peerAvatarUrl` TEXT, `lastMessageId` TEXT, `lastMessagePreview` TEXT NOT NULL, `lastMessageType` TEXT NOT NULL, `lastMessageTimestamp` INTEGER NOT NULL, `lastMessageStatus` TEXT NOT NULL, `lastMessageIsMine` INTEGER NOT NULL, `unreadCount` INTEGER NOT NULL, `isPeerTyping` INTEGER NOT NULL, PRIMARY KEY(`peerId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversations_lastMessageTimestamp` ON `conversations` (`lastMessageTimestamp`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `receiverId` TEXT NOT NULL, `content` TEXT NOT NULL, `type` TEXT NOT NULL, `mediaUrl` TEXT, `localUri` TEXT, `fileName` TEXT, `fileSizeBytes` INTEGER, `latitude` REAL, `longitude` REAL, `liveLocationActive` INTEGER NOT NULL, `status` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_timestamp` ON `messages` (`conversationId`, `timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_senderId` ON `messages` (`senderId`)"
                )
                db.execSQL(
                    "INSERT INTO `users` (`userId`, `displayName`, `phone`, `avatarUrl`, `lastLoginMillis`, `lastLoginFormatted`, `isOnline`, `isBlocked`) VALUES ('avinash_1', 'Avinash', '+919999999999', NULL, 1763900000000, '22 Nov', 0, 0)"
                )
                db.execSQL(
                    "INSERT INTO `conversations` (`peerId`, `peerName`, `peerPhone`, `peerAvatarUrl`, `lastMessageId`, `lastMessagePreview`, `lastMessageType`, `lastMessageTimestamp`, `lastMessageStatus`, `lastMessageIsMine`, `unreadCount`, `isPeerTyping`) VALUES ('sandeep_2', 'Sandeep', '', NULL, 'm_1', 'Namaste', 'TEXT', 1763900001000, 'READ', 0, 1, 0)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v2Helper.writableDatabase.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            // Triggers onOpen -> onValidateSchema for all entities including users.bio & status_updates
            val user = roomDb.userDao().getUser("avinash_1")
            assertNotNull(user)
            assertEquals("Avinash", user?.displayName)
            assertEquals("", user?.bio)

            val conv = roomDb.conversationDao().getConversation("sandeep_2")
            assertNotNull(conv)
            assertEquals("Namaste", conv?.lastMessagePreview)

            val now = System.currentTimeMillis()
            roomDb.statusDao().upsertStatus(
                StatusUpdateEntity(
                    id = "st_migrated_1",
                    userId = "avinash_1",
                    userName = "Avinash",
                    type = StatusType.TEXT,
                    textContent = "Migrated safely",
                    createdAt = now,
                    expiresAt = now + 60_000L
                )
            )
            val loadedStatuses = roomDb.statusDao().observeActiveStatuses(now).first()
            assertEquals(1, loadedStatuses.size)
            assertEquals("Migrated safely", loadedStatuses.first().textContent)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `SignalingForegroundService starts, handles commands, and destroys without crash`() {
        val controller = Robolectric.buildService(SignalingForegroundService::class.java)
        val service = controller.create().startCommand(0, 0).get()
        assertNotNull(service)
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
    }

    @Test
    fun `MainActivity launches and reopens cleanly with persisted UserSession`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelloSirApplication>()
        app.sessionManager.clearSession()
        app.sessionManager.saveSession(
            userId = "avinash_post_login",
            displayName = "Avinash",
            phone = "+919455877902",
            avatarUrl = null,
            serverToken = "tok_post_login_1\r\nभारत",
            registeredAtMillis = System.currentTimeMillis(),
            registeredAtFormatted = "23 Nov 2026",
            bio = "Hey there!"
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        assertNotNull(activity)
        shadowOf(Looper.getMainLooper()).idle()

        // Simulate leaving and reopening while session remains persisted
        controller.pause().stop().destroy()

        val reopenController = Robolectric.buildActivity(MainActivity::class.java)
        val reopened = reopenController.create().start().resume().get()
        assertNotNull(reopened)
        shadowOf(Looper.getMainLooper()).idle()
        reopenController.pause().stop().destroy()

        app.sessionManager.clearSession()
    }

    @Test
    fun `HomeScreen renders authenticated post-login state safely`() {
        val now = System.currentTimeMillis()
        val session = UserSession(
            userId = "avinash_99",
            displayName = "Avinash Kumar",
            phone = "+919455877902",
            bio = "Ready to connect",
            avatarUrl = null,
            sessionToken = "tok_home_1",
            deviceLockId = "dev_1",
            registeredAtMillis = now,
            registeredAtFormatted = "23 Nov 2026"
        )
        val conversations = listOf(
            ConversationEntity(
                peerId = "sandeep_88",
                peerName = "Sandeep",
                lastMessagePreview = "Hello Sir!",
                lastMessageType = MessageType.TEXT,
                lastMessageTimestamp = now,
                lastMessageStatus = MessageStatus.READ,
                unreadCount = 2
            )
        )
        val users = listOf(
            UserEntity(
                userId = "sandeep_88",
                displayName = "Sandeep",
                bio = "Online now",
                lastLoginMillis = now,
                lastLoginFormatted = "23 Nov 2026",
                isOnline = true
            )
        )

        composeTestRule.setContent {
            HelloSirTheme {
                HomeScreen(
                    session = session,
                    conversations = conversations,
                    directoryUsers = users,
                    remoteSearchResults = emptyList(),
                    pickerContacts = emptyList(),
                    totalUnreadCount = 2,
                    searchQuery = "",
                    isDirectorySyncing = false,
                    serverStatus = ServerStatus.READY,
                    wsConnected = true,
                    statuses = emptyList(),
                    hasContactPermission = { true },
                    onSearchQueryChange = {},
                    onRefreshDirectory = {},
                    onLoadContactPicker = {},
                    onOpenChat = { _, _ -> },
                    onOpenProfileSettings = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("profile_identity_trigger").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sandeep").assertIsDisplayed()
    }

    @Test
    fun `header sanitizer and JSON parsers handle malformed inputs gracefully`() {
        assertEquals("tok_123", SecurityAuthInterceptor.sanitizeHeaderValue("  tok\r\nभारत_123  "))
        assertTrue(BackendJsonParsers.parseStatusesFromBody("not-json").isEmpty())
        assertTrue(BackendJsonParsers.parseStatusesFromBody(null).isEmpty())
        assertTrue(BackendJsonParsers.parseUsersFromBody("{broken").isEmpty())

        val app = ApplicationProvider.getApplicationContext<HelloSirApplication>()
        app.signalingClient.emitExternalEvent(SignalingEvent.DirectoryUpdated)
    }
}
