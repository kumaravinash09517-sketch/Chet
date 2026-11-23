package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType =
        runCatching { MessageType.valueOf(value) }.getOrDefault(MessageType.TEXT)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus =
        runCatching { MessageStatus.valueOf(value) }.getOrDefault(MessageStatus.SENT)

    @TypeConverter
    fun fromStatusType(value: StatusType): String = value.name

    @TypeConverter
    fun toStatusType(value: String): StatusType =
        runCatching { StatusType.valueOf(value) }.getOrDefault(StatusType.TEXT)
}

@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        StatusUpdateEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun statusDao(): StatusDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN bio TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `status_updates` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `userName` TEXT NOT NULL,
                        `userAvatarUrl` TEXT,
                        `type` TEXT NOT NULL DEFAULT 'TEXT',
                        `textContent` TEXT NOT NULL DEFAULT '',
                        `mediaUrl` TEXT,
                        `localUri` TEXT,
                        `backgroundColorHex` TEXT NOT NULL DEFAULT '#1B5E20',
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `isViewedByMe` INTEGER NOT NULL DEFAULT 0,
                        `viewCount` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_status_updates_userId` ON `status_updates` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_status_updates_expiresAt` ON `status_updates` (`expiresAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_status_updates_createdAt` ON `status_updates` (`createdAt`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hello_sir_cache.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
