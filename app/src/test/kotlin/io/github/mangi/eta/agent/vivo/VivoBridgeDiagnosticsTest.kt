package io.github.mangi.eta.agent.vivo

import android.app.Application
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var lines: AtomicInteger
    private var previousCount = 0

    @Before fun isolateLogBudget() {
        lines = VivoBridgeDiagnostics::class.java.getDeclaredField("lines").apply {
            isAccessible = true
        }.get(null) as AtomicInteger
        previousCount = lines.getAndSet(0)
        ShadowLog.clear()
    }

    @After fun restoreLogBudget() {
        lines.set(previousCount)
        ShadowLog.clear()
    }

    @Test fun stagesAndCategoriesAreFixedInfoMetadata() {
        VivoBridgeDiagnostics.Stage.values().forEach { VivoBridgeDiagnostics.record(it) }
        VivoBridgeDiagnostics.Failure.values().forEach {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_INIT_FAILED, it)
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
        failures.forEach { (error, expected) ->
            val category = VivoBridgeDiagnostics.failureCategory(error)
            assertEquals(expected, category)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_INIT_FAILED, category)
        }
        ShadowLog.getLogsForTag("EtaVivoText").forEachIndexed { index, log ->
            assertEquals("v=1 stage=BOOTSTRAP_INIT_FAILED n=${index + 1} failure=${failures[index].second.name}", log.msg)
            assertNull(log.throwable)
            assertFalse(log.msg.contains(secret))
        }
    }

    @Test fun oneSharedEightyLineBudgetCoversBootstrapAndBusinessStages() {
        repeat(120) {
            if (it % 2 == 0) VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ENTERED)
            else VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.MODEL_FINISHED)
        }
        val logs = ShadowLog.getLogsForTag("EtaVivoText")
        assertEquals(80, logs.size)
        assertTrue(logs.first().msg.endsWith("n=1"))
        assertTrue(logs.last().msg.endsWith("n=80"))
    }
}
