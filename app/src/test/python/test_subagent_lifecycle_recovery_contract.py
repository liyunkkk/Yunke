"""Source-level guardrails; these do not replace Kotlin execution tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[4]
class SubAgentLifecycleRecoveryContract(unittest.TestCase):
    def source(self, name):
        return (ROOT / "app/src/main/kotlin/io/github/mangi/eta" / name).read_text()
    def test_new_operation_captures_current_owner(self):
        text = self.source("ui/app/AgentAppState.kt")
        self.assertIn("source: SubAgentConfigKey? = subAgentConfigOwner,", text)
        self.assertNotIn("val source = if (!subAgentDraftReady) pendingSubAgentDraftSource", text)
    def test_initial_retry_rereads_original_pointer(self):
        text = self.source("ui/app/AgentAppState.kt")
        self.assertIn("subAgentDraftPointerReloadPending", text)
        self.assertIn("val recovered = readSubAgentDraftPointer(pendingSubAgentDraftSource)", text)
        self.assertIn("existingDraftOrNull(existing)", text)
    def test_editor_failure_is_dynamic_not_a_constructor_snapshot(self):
        text = self.source("ui/components/ConversationSubAgentEditor.kt")
        self.assertIn("lifecycleFailure?.invoke()?.let", text)
        self.assertIn("fun bindLifecycleState", text)
    def test_successor_close_cannot_remove_predecessor_claim(self):
        text = self.source("agent/runtime/AgentChildTaskGroups.kt")
        line = next(line for line in text.splitlines() if "claimed.entries.removeAll" in line)
        self.assertIn("predecessorGeneration == generation", line)
        self.assertNotIn("successorGeneration", line)
