package io.github.mangi.eta.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ContextEstimateDiagnosticsTest {
    private val snapshot = ContextEstimateDiagnostics.Snapshot(
        ContextEstimateDiagnostics.Basis.LOCAL_FALLBACK, 25754, 25273, 481)

    @Test fun firstTrustedReceiptLogsNumericErrorWithoutLearningOrExternalIdentifiers() {
        val logs = mutableListOf<String>()
        val diagnostics = ContextEstimateDiagnostics(enabled = { true }, sink = { logs += it })
        diagnostics.capture("PRIVATE_RUN_ID", snapshot)
        diagnostics.receipt("PRIVATE_RUN_ID", 37214, 1234,
            round = 1, historyTokens = 481, overheadTokens = 25273)
        diagnostics.receipt("PRIVATE_RUN_ID", 40000, 1234)
        assertEquals(1, logs.size)
        val fields = Json.parseToJsonElement(logs.single().removePrefix(ContextEstimateDiagnostics.PREFIX)).jsonObject
        assertEquals("local_fallback", fields.getValue("basis").jsonPrimitive.content)
        assertEquals(25754, fields.getValue("local_estimate_tokens").jsonPrimitive.int)
        assertEquals(11460, fields.getValue("estimate_error").jsonPrimitive.int)
        assertFalse(fields.containsKey("new_offset"))
        assertEquals("matched_request", fields.getValue("pairing").jsonPrimitive.content)
        assertEquals(1234, fields.getValue("cloud_cached").jsonPrimitive.int)
        // 显示学习已删除：诊断不再声称 learned，也不再输出样本/比率字段。
        assertFalse(fields.containsKey("calibration_learned"))
        assertFalse(fields.containsKey("calibration_samples"))
        assertFalse(fields.containsKey("calibration_ratio"))
        assertTrue(ContextEstimateDiagnostics.Basis.entries.none { it.wireValue == "learned_ratio" })
        assertFalse(logs.single().contains("PRIVATE_RUN_ID"))
        assertFalse(logs.single().contains("request body"))
    }

    @Test fun compressionAndRetryMismatchNeverEmitFalseError() {
        val logs = mutableListOf<String>()
        val diagnostics = ContextEstimateDiagnostics(enabled = { true }, sink = { logs += it })
        diagnostics.capture("run", snapshot.copy(localEstimateTokens = 70076))
        diagnostics.receipt("run", 27723, null, round = 2, historyTokens = 300, overheadTokens = 25273)
        assertEquals(1, logs.size)
        assertFalse(logs.single().contains("estimate_error"))
        assertTrue(logs.single().contains("request_basis_mismatch"))
        diagnostics.capture("run", snapshot)
        diagnostics.clear("run")
        diagnostics.receipt("run", 27723, null, round = 1, historyTokens = 481, overheadTokens = 25273)
        assertEquals(1, logs.size)
    }

    @Test fun disabledLoggerDoesNotCaptureOrWriteAndClearedRunsDoNotWrite() {
        var enabled = false
        val logs = mutableListOf<String>()
        val diagnostics = ContextEstimateDiagnostics(enabled = { enabled }, sink = { logs += it })
        diagnostics.capture("disabled", snapshot)
        enabled = true
        diagnostics.receipt("disabled", 37214, null)
        diagnostics.capture("cleared", snapshot)
        diagnostics.clear("cleared")
        diagnostics.receipt("cleared", 37214, null)
        diagnostics.capture("turned-off", snapshot)
        enabled = false
        diagnostics.receipt("turned-off", 37214, null)
        enabled = true
        diagnostics.receipt("turned-off", 37214, null)
        assertTrue(logs.isEmpty())
    }

    @Test fun pendingRunsAreBoundedAndBrokenSinkDoesNotInterruptUsageDelivery() {
        val logs = mutableListOf<String>()
        val diagnostics = ContextEstimateDiagnostics(enabled = { true }, sink = { logs += it })
        repeat(33) { diagnostics.capture("run-$it", snapshot) }
        repeat(33) { diagnostics.receipt("run-$it", 37214, null) }
        assertEquals(32, logs.size)
        val broken = ContextEstimateDiagnostics(enabled = { true }, sink = { error("sink failure") })
        broken.capture("run", snapshot)
        broken.receipt("run", 37214, null)
    }
}
