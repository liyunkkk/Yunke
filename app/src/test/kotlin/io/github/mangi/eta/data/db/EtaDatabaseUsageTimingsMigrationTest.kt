package io.github.mangi.eta.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v35 → v36：消息表新增可空列 usage_timings_json。
 * 旧行保持原值、新列为 NULL、迁移可重复执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaDatabaseUsageTimingsMigrationTest {
    private val databaseName = "migration-usage-timings-${UUID.randomUUID()}.db"

    @Test
    fun migrationAddsNullableColumnAndKeepsOldRowsReadable() {
        val helper = openVersion35()
        try {
            val database = helper.writableDatabase
            assertFalse(
                "v35 不应该已经有 usage_timings_json",
                columns(database, "conversation_messages").contains(COLUMN),
            )

            EtaDatabase.MIGRATION_35_36.migrate(database)

            assertTrue(columns(database, "conversation_messages").contains(COLUMN))
            database.query(
                "SELECT content, input_tokens, output_tokens, generated_at_millis, `$COLUMN` " +
                    "FROM conversation_messages WHERE id = 'old-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧回答", cursor.getString(0))
                assertEquals(1_500, cursor.getInt(1))
                assertEquals(120, cursor.getInt(2))
                // 毫秒时间戳本身不能被迁移动过。
                assertEquals(1_700_000_000_000L, cursor.getLong(3))
                // 旧记录的新列必须是 NULL：读回 null，胶囊不画。
                assertTrue(cursor.isNull(4))
            }
        } finally {
            helper.close()
            deleteDatabase()
        }
    }

    @Test
    fun migrationIsRepeatableOnTheSameDatabase() {
        val helper = openVersion35()
        try {
            val database = helper.writableDatabase
            EtaDatabase.MIGRATION_35_36.migrate(database)
            // 列已存在时再跑一次不能因 "duplicate column name" 失败。
            EtaDatabase.MIGRATION_35_36.migrate(database)
            assertEquals(1, columns(database, "conversation_messages").count { it == COLUMN })
        } finally {
            helper.close()
            deleteDatabase()
        }
    }

    @Test
    fun newRowsCanStoreAndReadTimingsJson() {
        val helper = openVersion35()
        try {
            val database = helper.writableDatabase
            EtaDatabase.MIGRATION_35_36.migrate(database)
            val payload = """{"s":3,"l":8400,"t":2100,"f":1200,"n":3,"d":6000,"k":120}"""
            database.execSQL(
                "INSERT INTO conversation_messages " +
                    "(id, conversation_id, sort_index, type, content, input_tokens, `$COLUMN`) " +
                    "VALUES ('new-1', 'c1', 1, 'assistant', '新回答', 2000, ?)",
                arrayOf(payload),
            )
            database.query(
                "SELECT `$COLUMN` FROM conversation_messages WHERE id = 'new-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(payload, cursor.getString(0))
            }
        } finally {
            helper.close()
            deleteDatabase()
        }
    }

    private fun openVersion35(): SupportSQLiteOpenHelper {
        val context = RuntimeEnvironment.getApplication() as Context
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(35) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_35_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO conversations (id, title) VALUES ('c1', '旧对话')",
                        )
                        db.execSQL(
                            "INSERT INTO conversation_messages " +
                                "(id, conversation_id, sort_index, type, content, context_tokens, " +
                                "input_tokens, output_tokens, generated_at_millis) VALUES " +
                                "('old-1', 'c1', 0, 'assistant', '旧回答', 32000, 1500, 120, 1700000000000)",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private fun deleteDatabase() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase(databaseName)
    }

    private fun columns(database: SupportSQLiteDatabase, table: String): List<String> =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private companion object {
        const val COLUMN = "usage_timings_json"

        /** v35 的 conversation_messages：没有 usage_timings_json，其余列形状与实体一致。 */
        val VERSION_35_SCHEMA = listOf(
            "CREATE TABLE conversations (" +
                "id TEXT NOT NULL, title TEXT NOT NULL, thinking_enabled INTEGER NOT NULL DEFAULT 0, " +
                "reasoning_effort TEXT NOT NULL DEFAULT 'default', history_json TEXT NOT NULL DEFAULT '[]', " +
                "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL DEFAULT 0, folder_id TEXT NOT NULL DEFAULT '', " +
                "is_pinned INTEGER NOT NULL DEFAULT 0, has_completion_marker INTEGER NOT NULL DEFAULT 0, " +
                "provider_id TEXT NOT NULL DEFAULT '', model_id TEXT NOT NULL DEFAULT '', " +
                "assistant_id TEXT NOT NULL DEFAULT '', PRIMARY KEY(id))",
            "CREATE TABLE conversation_messages (" +
                "id TEXT NOT NULL, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, " +
                "type TEXT NOT NULL, content TEXT NOT NULL, images_json TEXT NOT NULL DEFAULT '[]', " +
                "is_edited INTEGER NOT NULL DEFAULT 0, render_markdown INTEGER, context_tokens INTEGER, " +
                "input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, " +
                "elapsed_seconds INTEGER, tool_name TEXT, tool_status TEXT, arguments_summary TEXT, " +
                "result_summary TEXT, image_count INTEGER NOT NULL DEFAULT 0, tools_json TEXT NOT NULL DEFAULT '[]', " +
                "generated_at_millis INTEGER, PRIMARY KEY(id), " +
                "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}
