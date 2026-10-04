package io.github.mangi.eta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubAgentConfigurationResetLifecycleTest {
    private class Gate {
        val events = mutableListOf<String>()
        var held = false
        var recoveryFailure: Throwable? = null
        var resetFailure: Throwable? = null
        var durable = true
        var complete = true
        var resetSucceeded = true
        val lifecycle = SubAgentConfigurationResetLifecycle(
            beginMaintenance = { check(!held); held = true; events += "begin" },
            recoverInterruptedRestoreBeforeWork = {
                check(held); events += "recover"; recoveryFailure?.let { throw it }
            },
            recoverConfigurationDurability = { check(held); events += "durability"; durable },
            resetLegacyConfigurationOnce = {
                check(held); events += "reset"; resetFailure?.let { throw it }; resetSucceeded
            },
            isConfigurationResetComplete = { check(held); events += "confirm"; complete },
            endMaintenance = { check(held); events += "end"; held = false },
        )
    }

    @Test fun mainProcessRecoveryAndResetShareOneFenceBeforeWork() = runBlocking {
        val gate = Gate()
        assertTrue(gate.lifecycle.initializeBeforeWork(isMainProcess = true))
        assertEquals(listOf("begin", "recover", "durability", "reset", "confirm", "end"), gate.events)
        assertFalse(gate.held)
    }

    @Test fun childProcessNeverRecoversOrClearsConfiguration() = runBlocking {
        val gate = Gate()
        assertFalse(gate.lifecycle.initializeBeforeWork(isMainProcess = false))
        assertTrue(gate.events.isEmpty())
        assertFalse(gate.held)
    }

    @Test fun failedJournalRecoveryBlocksResetAndAdmissionUntilExplicitRetry() = runBlocking {
        val gate = Gate()
        val failure = IllegalStateException("journal failure")
        gate.recoveryFailure = failure
        val actual = runCatching { gate.lifecycle.initializeBeforeWork(true) }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(listOf("begin", "recover"), gate.events)
        assertTrue(gate.held)
        gate.recoveryFailure = null
        assertTrue(gate.lifecycle.initializeBeforeWork(true))
        assertEquals(listOf("begin", "recover", "recover", "durability", "reset", "confirm", "end"), gate.events)
        assertFalse(gate.held)
    }

    @Test fun resetFailureDoesNotReleaseFenceAndCanRecoverOriginalValuesOnRetry() = runBlocking {
        val gate = Gate()
        val failure = IllegalStateException("commit failure")
        gate.resetFailure = failure
        assertSame(failure, runCatching { gate.lifecycle.initializeBeforeWork(true) }.exceptionOrNull())
        assertTrue(gate.held)
        assertFalse(gate.events.contains("end"))
        gate.resetFailure = null
        assertTrue(gate.lifecycle.initializeBeforeWork(true))
        assertEquals(1, gate.events.count { it == "begin" })
        assertEquals(2, gate.events.count { it == "durability" })
        assertFalse(gate.held)
    }

    @Test fun unknownDurabilityBlocksResetAndAdmission() = runBlocking {
        val gate = Gate()
        gate.durable = false
        assertTrue(runCatching { gate.lifecycle.initializeBeforeWork(true) }.isFailure)
        assertEquals(listOf("begin", "recover", "durability"), gate.events)
        assertTrue(gate.held)
    }

    @Test fun completionRequiresBothSuccessfulResetAndConfirmedMarker() = runBlocking {
        val failed = Gate().apply { resetSucceeded = false }
        assertTrue(runCatching { failed.lifecycle.initializeBeforeWork(true) }.isFailure)
        assertTrue(failed.held)
        assertFalse(failed.events.contains("confirm"))
        assertFalse(failed.events.contains("end"))
        val unconfirmed = Gate().apply { complete = false }
        assertTrue(runCatching { unconfirmed.lifecycle.initializeBeforeWork(true) }.isFailure)
        assertTrue(unconfirmed.held)
        assertFalse(unconfirmed.events.contains("end"))
    }

    @Test fun successfulInitializationCannotBeUsedAsLiveResetEntryPoint() = runBlocking {
        val gate = Gate()
        assertTrue(gate.lifecycle.initializeBeforeWork(true))
        val startup = gate.events.toList()
        assertTrue(gate.lifecycle.initializeBeforeWork(true))
        assertEquals(startup, gate.events)
    }
}
