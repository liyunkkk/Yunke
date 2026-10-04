package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.FileLogSink
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentDiagnosticLoggingTest {
    /** Use the real settings flow -> file logger binding used by EtaApp, without starting runtime work. */
    private suspend fun withLogging(block: suspend (suspend (Boolean) -> Unit) -> Unit) = coroutineScope {
        val context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
        AppFileLogger.install(context)
        val before = SettingsDataStore.settings()
        val applied = Channel<Boolean>(Channel.UNLIMITED)
        val binding = launch(start = CoroutineStart.UNDISPATCHED) {
            SettingsDataStore.fileLoggingEnabledFlow().collect { enabled ->
                AppFileLogger.setEnabled(enabled)
                applied.send(enabled)
            }
        }
        val setLogging: suspend (Boolean) -> Unit = { value ->
            SettingsDataStore.setFileLoggingEnabled(value)
            withTimeout(5_000) {
                while (applied.receive() != value) { /* Await settings delivery, never sleep or poll files. */ }
            }
            assertEquals(value, AppFileLogger.isEnabled())
        }
        try {
            setLogging(false)
            AppFileLogger.clear()
            ShadowLog.clear()
            block(setLogging)
        } finally {
            binding.cancelAndJoin()
            AppFileLogger.setEnabled(false)
            AppFileLogger.clear()
            SettingsDataStore.updateSettings { before }
            applied.close()
        }
    }

    @Test fun globalSwitchAloneControlsActualFileAndLogcatOutputForAllLegacyKeyValues() = runBlocking {
        withLogging { setLogging ->
            val context = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("diagnostic-legacy-${UUID.randomUUID()}", Context.MODE_PRIVATE)
            val repo = ConversationSubAgentPreferences(preferences)
            listOf<Any?>(null, false, true, "obsolete", JSONObject.NULL).forEachIndexed { index, legacy ->
                val archive = JSONObject().put("version", 1).put("enabled", true)
                    .put("agents", JSONArray()).put("parallel_limits", JSONArray())
                if (legacy != null) archive.put("diagnostics_enabled", legacy)
                val owner = SubAgentConfigKey.Conversation("legacy-$index")
                assertTrue(repo.importOwner(owner, archive.toString()))
                assertTrue(repo.snapshot(owner).enabled)
                assertFalse(JSONObject(repo.export(owner)).has("diagnostics_enabled"))
                // Create while disabled, then keep the same object through all toggles, as retained tasks do.
                val diagnostics = SubAgentDiagnostics("private-run-$index")
                val disabled = "disabled_$index"
                val enabled = "enabled_$index"
                val stopped = "stopped_$index"
                val resumed = "resumed_$index"
                val existing = appLogText()
                diagnostics.mark(disabled)
                assertEquals(existing, appLogText())
                assertFalse(logcatDiagnostics().contains(disabled))

                setLogging(true)
                diagnostics.mark(enabled, providerId = "private-provider", model = "private-model",
                    failure = IllegalStateException("HTTP 429 private-key private-body"))
                assertTrue(appLogText().contains("\"stage\":\"$enabled\""))
                assertTrue(logcatDiagnostics().contains("\"stage\":\"$enabled\""))
                val recorded = appLogText()
                setLogging(false)
                diagnostics.mark(stopped)
                assertEquals(recorded, appLogText())
                assertFalse(logcatDiagnostics().contains(stopped))
                assertTrue(AppFileLogger.hasLogs()) // Disabled retains previously recorded diagnostics.

                setLogging(true)
                diagnostics.mark(resumed)
                assertTrue(appLogText().contains("\"stage\":\"$resumed\""))
                setLogging(false)
                for (secret in listOf("private-run-$index", "private-provider", "private-model", "private-key", "private-body")) {
                    assertFalse(appLogText().contains(secret))
                    assertFalse(logcatDiagnostics().contains(secret))
                }
            }
        }
    }

    @Test fun childDiagnosticsUseExistingRotationExportAndClearPaths() = runBlocking {
        withLogging { setLogging ->
            val diagnostics = SubAgentDiagnostics("private-parent")
            setLogging(true)
            diagnostics.mark("before_clear", agentId = "private-agent")
            // Force the shared app file to rotate; no separate child file or export/clear path exists.
            AppFileLogger.info("x".repeat(FileLogSink.DEFAULT_MAX_BYTES.toInt()))
            diagnostics.mark("after_rotation")
            AppFileLogger.flush()
            val loggerDirectory = AppFileLogger::class.java.getDeclaredField("logsDir").apply {
                isAccessible = true
            }.get(null) as File
            val rotationEvidence = "expected=${logsDir()}, actual=$loggerDirectory, " +
                "files=" + loggerDirectory.listFiles().orEmpty().joinToString { "${it.name}:${it.length()}" } +
                ", errors=" + ShadowLog.getLogsForTag(ModuleConfig.TAG).filter {
                    it.msg.startsWith("diagnostic append failed:")
                }.joinToString { it.msg }
            assertEquals(rotationEvidence, logsDir().canonicalFile, loggerDirectory.canonicalFile)
            assertTrue(rotationEvidence, File(logsDir(), "eta-app.1.log").isFile)
            setLogging(false)
            val zip = ByteArrayOutputStream()
            assertTrue(AppFileLogger.export(zip) >= 2)
            val entries = ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { input ->
                val result = linkedMapOf<String, String>()
                while (true) {
                    val entry = input.nextEntry ?: break
                    result[entry.name] = input.readBytes().toString(Charsets.UTF_8)
                }
                result
            }
            val text = entries.values.joinToString("\n")
            assertTrue(text.contains("\"stage\":\"before_clear\""))
            assertTrue(text.contains("\"stage\":\"after_rotation\""))
            assertFalse(text.contains("private-parent"))
            assertFalse(text.contains("private-agent"))
            assertTrue(entries.keys.any { it == "eta-app.1.log" || it == "eta-app.2.log" })

            AppFileLogger.clear()
            assertFalse(AppFileLogger.isEnabled())
            assertFalse(AppFileLogger.hasLogs())
            diagnostics.mark("after_disabled_clear")
            assertFalse(AppFileLogger.hasLogs())
            assertFalse(logcatDiagnostics().contains("after_disabled_clear"))

            setLogging(true)
            diagnostics.mark("before_enabled_clear")
            AppFileLogger.clear()
            assertTrue(AppFileLogger.isEnabled())
            assertFalse(appLogText().contains("before_enabled_clear"))
            assertFalse(appLogText().contains("before_clear"))
            diagnostics.mark("after_enabled_clear")
            assertTrue(appLogText().contains("after_enabled_clear"))
        }
    }

    private fun logsDir() = File(RuntimeEnvironment.getApplication().filesDir, AppFileLogger.DIRECTORY_NAME)
    private fun appLogText(): String = logsDir().listFiles().orEmpty()
        .filter { it.name.startsWith("eta-app") && it.isFile }
        .joinToString("\n") { it.readText() }
    private fun logcatDiagnostics(): String = ShadowLog.getLogsForTag(ModuleConfig.TAG)
        .filter { it.msg.startsWith("SubAgentDiag ") }.joinToString("\n") { it.msg }
}
