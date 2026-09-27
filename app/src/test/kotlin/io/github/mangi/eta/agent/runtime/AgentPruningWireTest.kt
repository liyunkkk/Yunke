package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentPruningWireTest {
    private val label = "工具输出预算修剪（原文可回读）"

    @Test fun explicitFlagRoundTripsEvenWhenLabelDiffers() {
        val explicit = AgentEvent.ContextCompacted(148, true, 190, 190,
            compressorLabel = "new pruning label", pruningOnly = true)
        val wire = AgentRuntimeWire.eventToBundle(explicit)
        assertTrue(wire.getBoolean("pruning_only"))
        assertEquals(explicit, AgentRuntimeWire.eventFromBundle(wire))
        assertEquals(explicit, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(explicit)))

        // A false flag is authoritative even for a legacy-looking label.
        val summary = AgentEvent.ContextCompacted(149, true, 194, 69,
            compressorLabel = label, pruningOnly = false)
        assertEquals(summary, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(summary)))
        assertEquals(summary, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(summary)))
    }

    @Test fun missingFlagUsesLegacyLabelAndDirectConstructorDefaults() {
        assertTrue(AgentEvent.ContextCompacted(148, true, 190, 190,
            compressorLabel = label).pruningOnly)
        assertFalse(AgentEvent.ContextCompacted(149, true, 194, 69,
            compressorLabel = "summary").pruningOnly)
        val legacy = AgentRuntimeWire.eventToBundle(AgentEvent.ContextCompacted(148, true, 190, 190,
            compressorLabel = label))
        legacy.remove("pruning_only")
        assertTrue((AgentRuntimeWire.eventFromBundle(legacy) as AgentEvent.ContextCompacted).pruningOnly)
        legacy.putString("compressor_label", "summary")
        assertFalse((AgentRuntimeWire.eventFromBundle(legacy) as AgentEvent.ContextCompacted).pruningOnly)
    }
}
