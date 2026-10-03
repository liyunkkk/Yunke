package io.github.mangi.eta.agent.device

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
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
 * “每次询问 → 前台执行”一次性迁移。
 *
 * 只把旧的显式 "ask" 改成前台，并给每个安装写上固定标记；缺键、前台、后台都只记已检查，
 * 不写存储值，这样用户以后重新选择 ASK/后台不会被后续启动或升级改写。
 * 读取异常与 commit 失败都必须报告为未完成，保留可重试语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentTaskSurfaceAskMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** 用一份全新的本地配置替换 [Prefs] 的存储，避免测试之间互相污染。 */
    private fun withFreshPrefs(block: (SharedPreferences) -> Unit) {
        withLocalStorage(app.getSharedPreferences("surface-migration-${UUID.randomUUID()}", Context.MODE_PRIVATE), block)
    }

    private fun withLocalStorage(storage: SharedPreferences, block: (SharedPreferences) -> Unit) {
        val field = Prefs::class.java.getDeclaredField("localAgent").apply { isAccessible = true }
        val previous = field.get(Prefs)
        field.set(Prefs, storage)
        try {
            block(storage)
        } finally {
            field.set(Prefs, previous)
        }
    }

    private fun marked(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(AgentTaskSurface.ASK_MIGRATION_KEY, false)

    @Test
    fun `legacy explicit ask becomes foreground and records the marker`() = withFreshPrefs { prefs ->
        prefs.edit().putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.ASK.wire).commit()
        assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurface.stored())
        assertTrue(marked(prefs))
    }

    @Test
    fun `missing key is only marked as checked and keeps the foreground default`() = withFreshPrefs { prefs ->
        assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
        assertFalse(prefs.contains(AgentTaskSurface.PREF_KEY))
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurface.stored())
        assertTrue(marked(prefs))
    }

    @Test
    fun `unreadable stored value is not marked as checked and stays retryable`() = withFreshPrefs { prefs ->
        prefs.edit().putInt(AgentTaskSurface.PREF_KEY, 1).commit()
        assertFalse(AgentTaskSurface.migrateAskToForegroundOnce())
        assertEquals(1, prefs.getInt(AgentTaskSurface.PREF_KEY, 0))
        assertFalse(marked(prefs))
    }

    @Test
    fun `explicit foreground and background are preserved but marked`() = withFreshPrefs { prefs ->
        listOf(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurfaceMode.BACKGROUND).forEach { mode ->
            prefs.edit().clear().putString(AgentTaskSurface.PREF_KEY, mode.wire).commit()
            assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
            assertEquals(mode, AgentTaskSurface.stored())
            assertTrue(marked(prefs))
        }
    }

    @Test
    fun `ask chosen after migration is never rewritten by later launches`() = withFreshPrefs {
        // 先完成一次性迁移并写下标记。
        assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
        // 用户随后改选“每次询问”。
        AgentTaskSurface.save(AgentTaskSurfaceMode.ASK)
        // 后续每次启动都不得再迁移。
        assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
        assertEquals(AgentTaskSurfaceMode.ASK, AgentTaskSurface.stored())
    }

    @Test
    fun `repeated initialization and later upgrades keep the user's new choice`() = withFreshPrefs {
        // 首次启动完成迁移。
        assertTrue(AgentTaskSurface.migrateAskToForegroundOnce())
        repeat(3) { assertTrue(AgentTaskSurface.migrateAskToForegroundOnce()) }
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurface.stored())
        // 迁移后用户改选后台：后续每次启动/升级都不得覆盖。
        AgentTaskSurface.save(AgentTaskSurfaceMode.BACKGROUND)
        repeat(3) { assertTrue(AgentTaskSurface.migrateAskToForegroundOnce()) }
        assertEquals(AgentTaskSurfaceMode.BACKGROUND, AgentTaskSurface.stored())
    }

    @Test
    fun `legacy backup without marker migrates its explicit ask once`() = withFreshPrefs {
        Prefs.restoreAgentPreferences(
            mapOf(AgentTaskSurface.PREF_KEY to "s:${AgentTaskSurfaceMode.ASK.wire}"),
        )
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurface.stored())
        assertTrue(marked(requireNotNull(Prefs.localAgentPreferences())))
    }

    @Test
    fun `backup that already recorded the migration keeps the migrated ask`() = withFreshPrefs {
        Prefs.restoreAgentPreferences(
            mapOf(
                AgentTaskSurface.PREF_KEY to "s:${AgentTaskSurfaceMode.ASK.wire}",
                AgentTaskSurface.ASK_MIGRATION_KEY to "b:true",
            ),
        )
        assertEquals(AgentTaskSurfaceMode.ASK, AgentTaskSurface.stored())
    }

    @Test
    fun `failed commit is reported and the migration stays retryable`() {
        val storage = app.getSharedPreferences("surface-migration-fail-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        storage.edit().putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.ASK.wire).commit()
        withLocalStorage(RejectingCommit(storage)) {
            assertFalse(AgentTaskSurface.migrateAskToForegroundOnce())
        }
        // commit 报告失败：标记没有持久化为“已完成”，下次启动会重试。
        assertFalse(marked(storage))
        assertEquals(AgentTaskSurfaceMode.ASK, storage.getString(AgentTaskSurface.PREF_KEY, null))
    }

    @Test
    fun `initLocal migrates before any preference read`() {
        val field = Prefs::class.java.getDeclaredField("localAgent").apply { isAccessible = true }
        val previous = field.get(Prefs)
        try {
            field.set(Prefs, null)
            // 先拿到真实分组，再模拟“升级前显式存过 ask 且没有标记”的现场。
            Prefs.initLocal(app)
            val prefs = requireNotNull(Prefs.localAgentPreferences())
            prefs.edit().clear()
                .putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.ASK.wire)
                .commit()
            // 模拟一次新的进程启动：初始化必须早于首次读取完成迁移。
            field.set(Prefs, null)
            Prefs.initLocal(app)
            assertEquals(AgentTaskSurfaceMode.FOREGROUND, AgentTaskSurface.stored())
            assertTrue(marked(prefs))
        } finally {
            field.set(Prefs, previous)
        }
    }

    /** 提交被拒绝的存储：写入会更新内存，但 commit() 报告失败。 */
    private class RejectingCommit(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor {
            val delegate = real.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    delegate.putBoolean(key, value); return this
                }

                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    delegate.putString(key, value); return this
                }

                override fun commit(): Boolean = false
            }
        }
    }
}
