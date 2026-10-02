package io.github.mangi.eta.ui.model

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.RequestOverheadCalibrationStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class RequestOverheadCalibrationStoreTest {
    @Test fun persistedSamplesAreProviderModelScopedAndSurviveReceiptInvalidation() {
        val provider = "calibration-test-${UUID.randomUUID()}"
        val first = requireNotNull(RequestOverheadCalibration.learn(null, 37214, 481, 25273,
            requestId = "run-1:1", routeSignature = "config"))
        val other = requireNotNull(RequestOverheadCalibration.learn(null, 19000, 481, 25273,
            requestId = "run-2:1", routeSignature = "other-config"))
        io.github.mangi.eta.config.Prefs.putString("agent_request_overhead_calibration_v1:${provider.length}:$provider:6:legacy",
            "{\"offsetTokens\":11460,\"samples\":300,\"measuredOverheadTokens\":25273}")
        assertNull(RequestOverheadCalibrationStore.read(provider, "legacy"))
        assertNull(RequestOverheadCalibrationStore.read(provider, "model"))
        RequestOverheadCalibrationStore.save(provider, "model", first)
        RequestOverheadCalibrationStore.save("$provider:other", "model", other)
        RequestOverheadCalibrationStore.save(provider, "other:model", other)
        val receipt = CloudContextUsageState(ContextUsageScope("old-conversation", provider, "model", 0))
            .receive(ContextUsageScope("old-conversation", provider, "model", 0), TokenUsageUi(inputTokens = 37214))
        assertNull(receipt.invalidate().inputTokens)
        assertEquals(first, RequestOverheadCalibrationStore.read(provider, "model"))
        assertEquals(other, RequestOverheadCalibrationStore.read("$provider:other", "model"))
        assertEquals(other, RequestOverheadCalibrationStore.read(provider, "other:model"))
        // These two pairs would collide if persisted as provider + ':' + model.
        assertNull(RequestOverheadCalibrationStore.read("$provider:other", "other:model"))
    }
}
