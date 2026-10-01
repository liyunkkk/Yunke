package io.github.mangi.eta.config

import org.junit.Assert.*
import org.junit.Test

class VivoBridgeConsentTest {
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
