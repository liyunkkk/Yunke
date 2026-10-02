package io.github.mangi.eta.config

import org.junit.Assert.*
import org.junit.Test

class VivoBridgeConsentTest {
    @Test fun stalePersistedGrantsNeverAuthorizeANewProcess() {
        val original = VivoConsentSession()
        original.confirm(true, true)
        assertTrue(original.allows(true, true))
        original.revoke() // Both backing writes may now throw or fail.
        assertFalse(original.allows(true, true))
        val restarted = VivoConsentSession()
        assertFalse(restarted.allows(true, true))
        restarted.confirm(true, false)
        assertFalse(restarted.allows(true, true))
        restarted.confirm(true, true)
        assertTrue(restarted.allows(true, true))
        assertFalse(restarted.allows(true, false))
    }
    @Test fun revokeListenerRunsEvenWhenBothWritesThrow() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        VivoBridgeConsent.addRevocationListener(listener)
        try {
            assertFalse(VivoBridgeConsent.commit(false, { error("remote failure") }, { error("local failure") }))
            assertEquals(1, calls)
        } finally { VivoBridgeConsent.removeRevocationListener(listener) }
    }
    @Test fun separateConsentDefaultsOffAndIsNeverGenericReconciled() {
        assertEquals(false, Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.VIVO_TEXT_BRIDGE])
        assertFalse(Prefs.Keys.VIVO_TEXT_BRIDGE in Prefs.Keys.LOCAL_AGENT_KEYS)
    }
    @Test fun enableRequiresBothCommitsInOrder() {
        val calls = mutableListOf<String>()
        assertTrue(VivoBridgeConsent.commit(true, { calls.add("remote:$it"); true }, { calls.add("local:$it"); true }))
        assertEquals(listOf("remote:true", "local:true"), calls)
    }
    @Test fun localFailureRevokesBothStores() {
        val calls = mutableListOf<String>()
        assertFalse(VivoBridgeConsent.commit(true, { calls.add("remote:$it"); true }, { calls.add("local:$it"); !it }))
        assertEquals(listOf("remote:true", "local:true", "local:false", "remote:false"), calls)
    }
    @Test fun remoteFailureDoesNotGrantLocalConsent() {
        val calls = mutableListOf<String>()
        assertFalse(VivoBridgeConsent.commit(true, { calls.add("remote:$it"); !it }, { calls.add("local:$it"); true }))
        assertEquals(listOf("remote:true", "local:false", "remote:false"), calls)
    }
    @Test fun disableRevokesLocalFirstAndStillTriesRemoteOnFailure() {
        val calls = mutableListOf<String>()
        assertFalse(VivoBridgeConsent.commit(false, { calls.add("remote:$it"); true }, { calls.add("local:$it"); false }))
        assertEquals(listOf("local:false", "remote:false"), calls)
    }
    @Test fun exceptionDuringEnableAlsoRevokesLocal() {
        val calls = mutableListOf<Boolean>()
        assertFalse(VivoBridgeConsent.commit(true, { if (it) error("binder unavailable"); true }, { calls.add(it); true }))
        assertEquals(listOf(false), calls)
    }
}
