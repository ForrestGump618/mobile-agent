package com.mobileagent.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        CheckpointEntity::class,
        EventEntity::class,
        ProviderEntity::class,
        McpServerEntity::class,
        SkillEntity::class,
        MemoryEntity::class,
        ArtifactEntity::class,
        ScheduleEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun eventDao(): EventDao
    abstract fun providerDao(): ProviderDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun skillDao(): SkillDao
    abstract fun memoryDao(): MemoryDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        /**
         * v2 adds plain-chat mode, explicit API path/provider presets, and last test/error state.
         * The migration is additive; existing tasks and providers are preserved.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN mode TEXT")
                db.execSQL("ALTER TABLE providers ADD COLUMN presetId TEXT")
                db.execSQL("ALTER TABLE providers ADD COLUMN apiPath TEXT")
                db.execSQL("ALTER TABLE providers ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE providers ADD COLUMN lastTestedAt INTEGER")
                db.execSQL("ALTER TABLE providers ADD COLUMN verifiedCapsJson TEXT")
            }
        }

        fun create(context: Context): AgentDatabase =
            Room.databaseBuilder(context, AgentDatabase::class.java, "mobile-agent.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
