package io.github.mangi.eta.ui.components

import androidx.compose.runtime.InternalComposeTracingApi
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalComposeTracingApi::class)
class ComposeSystemTraceTest {
    @Test fun disabledPathDoesNotEmitAnything() {
        val events = mutableListOf<String>()
        val tracer = MainThreadCompositionTracer(Thread.currentThread(), { false }, { events += it }, { events += "end" })
        assertFalse(tracer.isTraceInProgress())
        if (tracer.isTraceInProgress()) tracer.traceEventStart(1, 0, 0, "function")
        tracer.traceEventEnd()
        assertTrue(events.isEmpty())
    }

    @Test fun gateChangeAfterCompilerCheckDoesNotDropBegin() {
        var enabled = true
        val events = mutableListOf<String>()
        val tracer = MainThreadCompositionTracer(Thread.currentThread(), { enabled }, { events += it }, { events += "end" })
        assertTrue(tracer.isTraceInProgress())
        enabled = false
        tracer.traceEventStart(1, 0, 0, "accepted")
        assertTrue(tracer.isTraceInProgress())
        tracer.traceEventEnd()
        assertFalse(tracer.isTraceInProgress())
        assertEquals(listOf("Eta.compose:accepted", "end"), events)
    }

    @Test fun nestedSectionsRemainBalancedWhenGateTurnsOff() {
        var enabled = true
        val events = mutableListOf<String>()
        val tracer = MainThreadCompositionTracer(Thread.currentThread(), { enabled }, { events += it }, { events += "end" })
        assertTrue(tracer.isTraceInProgress())
        tracer.traceEventStart(1, 0, 0, "outer")
        enabled = false
        assertTrue(tracer.isTraceInProgress())
        tracer.traceEventStart(2, 0, 0, "inner")
        tracer.traceEventEnd()
        assertTrue(tracer.isTraceInProgress())
        tracer.traceEventEnd()
        assertFalse(tracer.isTraceInProgress())
        tracer.traceEventEnd() // never emit unmatched ends
        assertEquals(listOf("Eta.compose:outer", "Eta.compose:inner", "end", "end"), events)
    }

    @Test fun workerThreadCannotReadGateOrAffectMainThreadNesting() {
        val events = mutableListOf<String>()
        var gateReads = 0
        val tracer = MainThreadCompositionTracer(Thread.currentThread(), { gateReads++; true }, { events += it }, { events += "end" })
        tracer.traceEventStart(1, 0, 0, "main")
        val readsBefore = gateReads
        val task = FutureTask {
            assertFalse(tracer.isTraceInProgress())
            tracer.traceEventStart(2, 0, 0, "worker")
            tracer.traceEventEnd()
        }
        Thread(task).start()
        task.get(5, TimeUnit.SECONDS)
        assertEquals(readsBefore, gateReads)
        tracer.traceEventEnd()
        assertEquals(listOf("Eta.compose:main", "end"), events)
    }

    @Test fun labelsAreBoundedAndDoNotContainTraceControlCharacters() {
        val label = composeSystemTraceLabel("a|b\nc\r" + "x".repeat(200))
        assertEquals(127, label.length)
        assertFalse(label.contains('|'))
        assertFalse(label.contains('\n'))
        assertFalse(label.contains('\r'))
        val unicode = composeSystemTraceLabel("a".repeat(114) + "\uD83D\uDE00")
        assertFalse(unicode.last().isHighSurrogate())
    }

    @Test fun labelCacheIsBoundedWithoutDroppingTraceSections() {
        var starts = 0
        var ends = 0
        val tracer = MainThreadCompositionTracer(Thread.currentThread(), { true }, { starts++ }, { ends++ })
        repeat(COMPOSE_TRACE_LABEL_LIMIT + 10) {
            tracer.traceEventStart(it, 0, 0, "function-$it")
            tracer.traceEventEnd()
        }
        val cache = tracer.javaClass.getDeclaredField("labels").apply { isAccessible = true }.get(tracer) as Map<*, *>
        assertEquals(COMPOSE_TRACE_LABEL_LIMIT, cache.size)
        assertEquals(COMPOSE_TRACE_LABEL_LIMIT + 10, starts)
        assertEquals(starts, ends)
    }
}
