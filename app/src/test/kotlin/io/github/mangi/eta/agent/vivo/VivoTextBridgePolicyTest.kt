package io.github.mangi.eta.agent.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoTextBridgePolicyTest {
    private val policy = VivoTextBridgePolicy
    private fun request(id: Any? = "test_1", prompt: Any? = "hello") =
        mapOf("request_id" to id, "prompt" to prompt)

    @Test fun deviceGateIsExplicitAndExact() {
        assertTrue(policy.deviceAllowed(true, "V2419A", 35))
        assertFalse(policy.deviceAllowed(false, "V2419A", 35))
        assertFalse(policy.deviceAllowed(true, "v2419a", 35))
        assertFalse(policy.deviceAllowed(true, "PD2419", 35))
        assertFalse(policy.deviceAllowed(true, "V2419A", 36))
    }

    @Test fun exactCallerIsRequired() {
        fun allowed(uid: Int = 10260, packages: List<String> = listOf(policy.PACKAGE),
            version: Long = 6090021L, signers: List<String> = listOf(policy.SIGNER)) =
            policy.callerAllowed(uid, packages, version, signers)
        assertTrue(allowed())
        listOf(-1, 0, 1000, 2000, 9999, 20000, 99000, 110260).forEach { assertFalse(allowed(uid = it)) }
        assertFalse(allowed(packages = emptyList()))
        assertFalse(allowed(packages = listOf("com.vivo.agent")))
        assertFalse(allowed(packages = listOf(policy.PACKAGE, "shared.uid.attacker")))
        assertFalse(allowed(version = 6090022L))
        assertFalse(allowed(signers = emptyList()))
        assertFalse(allowed(signers = listOf("00".repeat(32))))
        assertFalse(allowed(signers = listOf(policy.SIGNER, "00".repeat(32))))
    }

    @Test fun requestHasExactShapeAndBoundedStrings() {
        assertEquals(VivoTextBridgePolicy.Command("test_1", "hello"), policy.command(policy.REQUEST, request()))
        assertNotNull(policy.command(policy.REQUEST, request("x".repeat(64), "x".repeat(policy.MAX_PROMPT))))
        listOf(null, 1, "", "a b", "../id", "中", "x".repeat(65)).forEach {
            assertNull(policy.command(policy.REQUEST, request(id = it)))
        }
        listOf(null, 1, "", " \n\t", "x\u0000y", "x".repeat(policy.MAX_PROMPT + 1)).forEach {
            assertNull(policy.command(policy.REQUEST, request(prompt = it)))
        }
        assertNull(policy.command(policy.REQUEST, request() + ("model" to "override")))
        assertNull(policy.command(policy.REQUEST, request() + ("uid" to 10260)))
        assertNull(policy.command(policy.REQUEST, request() - "prompt"))
        assertNull(policy.command(999, request()))
    }

    @Test fun cancelCannotCarryPromptOrAdditionalArguments() {
        assertEquals(VivoTextBridgePolicy.Command("r", null), policy.command(policy.CANCEL, mapOf("request_id" to "r")))
        assertNull(policy.command(policy.CANCEL, request()))
        assertNull(policy.command(policy.CANCEL, emptyMap()))
        assertNull(policy.command(policy.CANCEL, mapOf("request_id" to 1)))
    }

    @Test fun admissionIsSingleFlightAndCompletedIdsAreNeverReplayed() {
        val ledger = VivoTextBridgeLedger()
        assertEquals(VivoTextBridgeLedger.Admission.ACCEPTED, ledger.admit(10260, "a"))
        assertEquals(VivoTextBridgeLedger.Admission.DUPLICATE, ledger.admit(10260, "a"))
        assertEquals(VivoTextBridgeLedger.Admission.BUSY, ledger.admit(10260, "b"))
        ledger.release(10261, "a")
        ledger.release(10260, "other")
        assertEquals(VivoTextBridgeLedger.Admission.BUSY, ledger.admit(10260, "b"))
        ledger.release(10260, "a")
        assertEquals(VivoTextBridgeLedger.Admission.DUPLICATE, ledger.admit(10260, "a"))
        assertEquals(VivoTextBridgeLedger.Admission.ACCEPTED, ledger.admit(10260, "b"))
    }

    @Test fun replayFenceFailsClosedRatherThanEvictingIds() {
        val ledger = VivoTextBridgeLedger(limit = 2)
        listOf("a", "b").forEach {
            assertEquals(VivoTextBridgeLedger.Admission.ACCEPTED, ledger.admit(10260, it))
            ledger.release(10260, it)
        }
        assertEquals(VivoTextBridgeLedger.Admission.FULL, ledger.admit(10260, "c"))
        assertEquals(VivoTextBridgeLedger.Admission.DUPLICATE, ledger.admit(10260, "a"))
    }

    @Test fun cancelledWorkerStillOccupiesSlotUntilItActuallyExits() {
        val ledger = VivoTextBridgeLedger()
        ledger.admit(10260, "a")
        // Emitting CANCELLED is deliberately not a release operation.
        assertEquals(VivoTextBridgeLedger.Admission.BUSY, ledger.admit(10260, "b"))
        ledger.release(10260, "a")
        assertEquals(VivoTextBridgeLedger.Admission.ACCEPTED, ledger.admit(10260, "b"))
    }
}
