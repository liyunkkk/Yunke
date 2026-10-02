package io.github.mangi.eta.agent.vivo

import android.app.Application
import android.util.Log
import io.github.mangi.eta.hook.vivo.VivoNativePolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoBridgeDiagnosticsTest {
    private lateinit var diagnosticScope: VivoDiagnosticTestScope

    @Before fun isolateLogBudget() {
        diagnosticScope = VivoDiagnosticTestScope()
        ShadowLog.clear()
    }

    @After fun restoreLogBudget() {
        if (::diagnosticScope.isInitialized) diagnosticScope.restore()
        ShadowLog.clear()
    }

    @Test fun stagesAndCategoriesAreFixedInfoMetadata() {
        VivoBridgeDiagnostics.Stage.values().forEach { VivoBridgeDiagnostics.record(it) }
        VivoBridgeDiagnostics.Failure.values().forEachIndexed { index, failure ->
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.entries[index], failure)
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(VivoBridgeDiagnostics.Stage.values().size + VivoBridgeDiagnostics.Failure.values().size, logs.size)
        val stages = VivoBridgeDiagnostics.Stage.values().joinToString("|") { it.name }
        val failures = VivoBridgeDiagnostics.Failure.values().joinToString("|") { it.name }
        val shape = Regex("v=1 stage=($stages) n=[0-9]+( failure=($failures))?")
        logs.forEach {
            assertEquals(Log.INFO, it.type)
            assertTrue(shape.matches(it.msg))
            assertNull(it.throwable)
        }
    }

    @Test fun errorMessagesAndOtherDynamicSensitiveValuesNeverReachDiagnostics() {
        val secret = "prompt=test-body request_id=test-id signer=test-cert endpoint=https://test.invalid key=test-key"
        val failures = listOf(
            ClassNotFoundException(secret) to VivoBridgeDiagnostics.Failure.CLASS,
            NoSuchMethodException(secret) to VivoBridgeDiagnostics.Failure.METHOD,
            NoSuchFieldException(secret) to VivoBridgeDiagnostics.Failure.FIELD,
            NoClassDefFoundError(secret) to VivoBridgeDiagnostics.Failure.LINKAGE,
            IllegalStateException(secret) to VivoBridgeDiagnostics.Failure.OTHER,
        )
        failures.forEachIndexed { index, (error, expected) ->
            val category = VivoBridgeDiagnostics.failureCategory(error)
            assertEquals(expected, category)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.entries[index], category)
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(failures.size, logs.size)
        logs.forEachIndexed { index, log ->
            assertEquals("v=1 stage=${VivoBridgeDiagnostics.Stage.entries[index].name} n=${index + 1} failure=${failures[index].second.name}", log.msg)
            assertNull(log.throwable)
            assertFalse(log.msg.contains(secret))
        }
    }

    @Test fun policyRejectionLogsContainNeitherNativeValuesNorErrorDetails() {
        val secret = "prompt=private request_id=private endpoint=https://private.invalid token=private"
        val plain = VivoNativePolicy.Shape("little_v", 0, "BottomInput", true, false, false, false, false, false)
        val cases = listOf(plain.copy(agentId = secret) to "AGENT_ID", plain.copy(bizSource = secret) to "BIZ_SOURCE")
        cases.forEach { (shape, _) ->
            val reason = requireNotNull(VivoNativePolicy.rejection(shape))
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MAPPER_SHAPE_REJECTED, reason = reason)
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(cases.size, logs.size)
        logs.forEachIndexed { index, log ->
            assertEquals("v=1 stage=MAPPER_SHAPE_REJECTED n=${index + 1} reason=${cases[index].second}", log.msg)
            assertFalse(log.msg.contains(secret))
            assertNull(log.throwable)
        }
    }

    @Test fun rejectionReasonVocabularyAndLogShapeAreStable() {
        val expected = """
            MAPPED_NULL MAPPED_TYPE REQUEST_NULL REQUEST_TYPE MODEL_NULL MODEL_TYPE
            DIALOG_ID_TYPE CONVERSATION_ID_TYPE DIALOG_ID CONVERSATION_ID
            AGENT_ID_TYPE INPUT_TYPE_TYPE BIZ_SOURCE_TYPE RENDER_TEXT_TYPE SHORTCUT_TYPE
            REGENERATE_TYPE SKIP_REMOTE_TYPE RECOMMENDED_TYPE
            AGENT_ID INPUT_TYPE BIZ_SOURCE RENDER_TEXT SHORTCUT REGENERATE SKIP_REMOTE
            RECOMMENDED SPECIALIZED
            ATTACHMENT CAMERA_CONTEXT PS_AGENT_CONTEXT TWS_NOTIFICATION_CONTEXT EXTRA_PARAMS
            SCHEDULE_CONTEXT_TYPE SCHEDULE_CONTEXT BOT_TYPE_TYPE BOT_TYPE
            INTENTIONS_TYPE INTENTION_TEXT_TYPE INTENTIONS
            NEW_QUERY_PARAMS_TYPE NEW_QUERY_PARAMS DISPLAY_QUERY_TYPE SERVER_QUERY_TYPE PROMPT
        """.trimIndent().split(Regex("\\s+"))
        assertEquals(expected, VivoBridgeDiagnostics.Reason.entries.map { it.name })
        VivoBridgeDiagnostics.Reason.entries.forEach { reason ->
            // Only test isolation refills the budget so every enum's exact wire name is checked.
            diagnosticScope.reset()
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MAPPER_SHAPE_REJECTED, reason = reason)
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(expected.size, logs.size)
        logs.forEachIndexed { index, log ->
            assertEquals("v=1 stage=MAPPER_SHAPE_REJECTED n=1 reason=${expected[index]}", log.msg)
            assertEquals(Log.INFO, log.type)
            assertNull(log.throwable)
        }
    }

    @Test fun changingReasonsDoesNotCreateNewStageOrTotalBudgets() {
        VivoBridgeDiagnostics.Reason.entries.forEach { reason ->
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MAPPER_SHAPE_REJECTED, reason = reason)
        }
        assertEquals(4, ShadowLog.getLogsForTag("EtaVivoText").size)
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_FINISHED)
        assertEquals("v=1 stage=MODEL_FINISHED n=5", ShadowLog.getLogsForTag("EtaVivoText").last().msg)
        repeat(200) { index ->
            val stages = VivoBridgeDiagnostics.Stage.entries
            VivoBridgeDiagnostics.record(stages[index % stages.size])
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(80, logs.size)
        assertEquals(4, logs.count { it.msg.contains("stage=MAPPER_SHAPE_REJECTED ") })
        assertTrue(logs.last().msg.contains(" n=80"))
    }

    @Test fun oneSharedEightyLineBudgetCoversBootstrapAndBusinessStages() {
        repeat(120) {
            val stages = VivoBridgeDiagnostics.Stage.entries
            VivoBridgeDiagnostics.record(stages[it % stages.size])
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(80, logs.size)
        assertTrue(logs.first().msg.endsWith("n=1"))
        assertTrue(logs.last().msg.endsWith("n=80"))
    }

    @Test fun noisyStageHasFourEntriesAndLeavesRoomForOtherStages() {
        repeat(120) { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ENTERED) }
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_FINISHED)
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(5, logs.size)
        assertEquals(4, logs.count { it.msg.contains("stage=BOOTSTRAP_ENTERED ") })
        assertEquals("v=1 stage=MODEL_FINISHED n=5", logs.last().msg)
    }

}
