package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayManualRecoveryTest {
    @Test
    fun ownerGoneAlwaysClearsTheRecord() {
        assertEquals(
            VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.GONE, budgetBlocked = false),
        )
        assertEquals(
            VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.GONE, budgetBlocked = true),
        )
    }

    @Test
    fun blockedBudgetNeverRepeatsARelease() {
        assertEquals(
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.READABLE, budgetBlocked = true),
        )
        assertEquals(
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE, budgetBlocked = true),
        )
    }

    @Test
    fun unreadableOwnerWithoutBlockedBudgetStillGoesThroughVerification() {
        // 读不出状态不是「不能核验」：短路会让一次失败变成不可重试，且丢掉精确错误码。
        assertEquals(
            VirtualDisplayManualRecovery.Action.VERIFY_THEN_FINISH,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE, budgetBlocked = false),
        )
    }

    @Test
    fun readableOwnerWithoutBlockedBudgetGoesThroughTheNormalFinish() {
        assertEquals(
            VirtualDisplayManualRecovery.Action.VERIFY_THEN_FINISH,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.READABLE, budgetBlocked = false),
        )
        assertEquals(
            VirtualDisplayManualRecovery.Action.VERIFY_THEN_FINISH,
            VirtualDisplayManualRecovery.decide(VirtualDisplayManualRecovery.OwnerProbe.ABSENT, budgetBlocked = false),
        )
    }

    @Test
    fun leftoverSessionIsOnlyDroppedWhenTheOwnerIsVerifiedGone() {
        assertEquals(
            VirtualDisplayManualRecovery.Action.CLEAR_OWNER_GONE,
            VirtualDisplayManualRecovery.decideLeftover(VirtualDisplayManualRecovery.OwnerProbe.GONE),
        )
        // 连不上、读不出、或记录根本没有：都不足以证明 owner 消失，一律如实回报。
        assertEquals(
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED,
            VirtualDisplayManualRecovery.decideLeftover(VirtualDisplayManualRecovery.OwnerProbe.UNREADABLE),
        )
        assertEquals(
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED,
            VirtualDisplayManualRecovery.decideLeftover(VirtualDisplayManualRecovery.OwnerProbe.ABSENT),
        )
        assertEquals(
            VirtualDisplayManualRecovery.Action.REPORT_UNVERIFIED,
            VirtualDisplayManualRecovery.decideLeftover(VirtualDisplayManualRecovery.OwnerProbe.READABLE),
        )
    }

    @Test
    fun displayNameIsParsedFromTheRecordedUniqueId() {
        assertEquals(
            "eta-vd-cb4479a5d74a3ee114ab",
            VirtualDisplayManualRecovery.displayNameFromUniqueId("virtual:android,0,eta-vd-cb4479a5d74a3ee114ab,0"),
        )
        assertNull(VirtualDisplayManualRecovery.displayNameFromUniqueId("virtual:android,0,whatever,0"))
        assertNull(VirtualDisplayManualRecovery.displayNameFromUniqueId(""))
    }

    @Test
    fun summaryIsAllowlistedAndBounded() {
        val status = JSONObject()
            .put("ok", true)
            .put("releaseAttempted", false)
            .put("sourceTaskCount", 2)
            .put("sourceState", "occupied")
            .put("liveTaskIds", JSONArray(listOf(1, 2)))
            .put("token", "fb05edd54b4a89babebd32b6b153b349")
            .put("socket", "eta.vd.owner.2cd0b2af702e097ceefb8220592ceb81")
        val text = VirtualDisplayManualRecovery.ownerStatusSummary(status)
        assertTrue(text.contains("releaseAttempted=false"))
        assertTrue(text.contains("sourceTaskCount=2"))
        assertTrue(text.contains("liveTaskIds=[2]"))
        assertTrue(text.contains("finishing=absent"))
        assertFalse(text.contains("fb05edd54b4a89babebd32b6b153b349"))
        assertFalse(text.contains("eta.vd.owner."))
        assertTrue(text.length <= VirtualDisplayManualRecovery.SUMMARY_LIMIT)
    }

    @Test
    fun missingStatusIsReportedInsteadOfGuessed() {
        assertEquals("owner_status=missing", VirtualDisplayManualRecovery.ownerStatusSummary(null))
        // 带上 ok/error 时才能分辨「读不到」是连接失败还是字段缺失，且不因此变长或泄密。
        val failed = VirtualDisplayManualRecovery.ownerStatusSummary(null, false, "OWNER_DISCONNECTED")
        assertTrue(failed.contains("ok=false"))
        assertTrue(failed.contains("error=OWNER_DISCONNECTED"))
        assertTrue(failed.contains("owner_status=missing"))
        assertTrue(failed.length <= VirtualDisplayManualRecovery.SUMMARY_LIMIT)
        val text = VirtualDisplayManualRecovery.ownerStatusSummary(JSONObject().put("sourceEmpty", JSONObject.NULL))
        assertTrue(text.contains("sourceEmpty=null"))
        assertTrue(text.contains("mutationUncertain=absent"))
    }

    @Test
    fun recoveryRecordCarriesTheBoundedDiagnostic() {
        val fields: Map<String, Any> = mapOf(
            "socket" to "eta.vd.owner." + "a".repeat(32),
            "pid" to 42L,
            "display" to 14,
            "unique" to "virtual:android,0,x,12",
            "token" to "t",
            "run" to "run-1",
            "boot" to "6a7bbdb6-4825-4016-921e-736fc5dd2c36",
            VirtualDisplayRecoveryRecord.DIAG to "releaseAttempted=absent retainedCount=absent",
        )
        val record = VirtualDisplayRecoveryRecord.decode(fields)
        assertEquals("releaseAttempted=absent retainedCount=absent", record.diag)
        assertNull(VirtualDisplayRecoveryRecord.decode(fields - VirtualDisplayRecoveryRecord.DIAG).diag)
    }
}
