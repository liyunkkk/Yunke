from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class IncrementalStreamSnapshotContract(unittest.TestCase):
    def test_budget_is_shared_and_reset_only_by_real_frame_callback(self):
        app = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.assertIn('private val runFrameEventBudget = AgentFrameEventBudget', app)
        self.assertEqual(1, app.count('runFrameEventBudget.beginFrame('))
        self.assertGreaterEqual(app.count('runFrameEventBudget.measureWork {'), 3)
        self.assertIn('Choreographer.FrameCallback { frameTimeNs ->', app)
        self.assertIn('runFrameEventBudget.beginFrame(frameTimeNs)', app)
        self.assertIn('queue.drain(force, frameBudget = runFrameEventBudget', app)
        self.assertIn('singleEvent = true', app)
        self.assertIn('runEventBudgetCallbacks.remove(nextRun)', app)
        self.assertIn('runEventDeferredFlushes[runId] = {', app)
        self.assertIn('runFrameEventBudget.record(System.nanoTime() - started)', app)
        budget = (ROOT / 'ui/app/AgentRunEventBudget.kt').read_text()
        self.assertIn('if (!force && frameBudget?.exhausted == true) break', budget)
        self.assertIn('(clock() - workStartedNs).coerceAtLeast(0L) >= budgetNs - spentNs', budget)
        self.assertIn('if (workDepth > 0) return', budget)
        self.assertLess(budget.index('consume(value)'), budget.index('pending.removeFirst()'))

    def test_projector_and_visible_filter_preserve_immutable_replacement_evidence(self):
        projector = (ROOT / 'ui/app/AgentRunMessageProjector.kt').read_text()
        self.assertIn('incrementalSnapshot().replacing(lastIndex, message)', projector)
        self.assertIn('lastDeltaProjection = WeakReference(snapshot)', projector)
        body = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        self.assertIn('visibleMessagesCache.project(messages, messageEdit?.targetMessageId)', body)
        visible = (ROOT / 'ui/components/AgentVisibleMessagesCache.kt').read_text()
        self.assertIn('singleReplacementFrom(previous)', visible)
        self.assertIn('hidden(old) == hidden(current)', visible)
        self.assertIn('source = null', visible)

    def test_projection_and_rows_accept_only_exact_certified_previous_snapshot(self):
        for name in ('AgentTimelineProjectionCache.kt', 'AgentTimelineRows.kt'):
            source = (ROOT / 'ui/components' / name).read_text()
            self.assertIn('singleReplacementFrom(previous)', source)
            self.assertIn('old.isStreaming && current.isStreaming', source)
            self.assertIn('current.content.startsWith(old.content)', source)
            self.assertIn('if (changed == null)', source)
            self.assertIn('fullProjection(input', source)
        source = (ROOT / 'ui/model/AgentIncrementalList.kt').read_text()
        self.assertIn('previous?.get() === source', source)
        self.assertIn('WeakReference(this)', source)
        self.assertIn('chunks[chunkIndex].toMutableList()', source)
        self.assertNotIn('@Stable', source)
        self.assertNotIn('@Immutable', source)
