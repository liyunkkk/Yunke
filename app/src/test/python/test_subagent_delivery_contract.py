"""Wiring guards only. Kotlin compilation/JUnit and device acceptance remain separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
AGENT = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/agent'


def source(path):
    return (AGENT / path).read_text()


class SubAgentDeliveryContractTest(unittest.TestCase):
    def test_finalization_does_not_discard_the_git_receipt(self):
        coordinator = source('delegation/SubAgentCoordinator.kt')
        block = coordinator.split('awaitFinalization(t)', 1)[1].split('} catch (error: Exception)', 1)[0]
        self.assertIn('workspace!!.sealImplementation(', block)
        self.assertIn('t.artifactEvidence = requireNotNull(implementationEvidence)', block)
        self.assertLess(block.index('workspace!!.sealImplementation('), block.index('t.state = "completed"'))
        self.assertNotIn('requireOperation(project, "seal"', block)
        self.assertGreaterEqual(block.count('t.controller.throwIfCancelled()'), 2)

    def test_model_claims_never_become_verified_implementation_result(self):
        coordinator = source('delegation/SubAgentCoordinator.kt')
        self.assertIn('if (role == "implementation") t.modelReport = report else t.result = report', coordinator)
        self.assertIn('if (role == "implementation" || t.result.isBlank()) t.result = when', coordinator)
        self.assertIn('.put("model_report_unverified", task.modelReport)', coordinator)
        self.assertIn('.put("acceptance_verified", false)', coordinator)
        self.assertIn('"artifact_ready_pending_review"', coordinator)
        self.assertIn('task.state == "completed" && task.artifactEvidence != null', coordinator)

    def test_workspace_seal_is_followed_by_fresh_inspect_and_strict_match(self):
        workspace = source('delegation/SubAgentWorkspace.kt')
        block = workspace.split('fun sealImplementation(', 1)[1].split('fun childExecutor(', 1)[0]
        self.assertIn('verify(requireOperation(project, "seal", id), id, expectedBase)', block)
        self.assertIn('verify(requireOperation(project, "inspect", id), id, expectedBase)', block)
        self.assertIn('sealed.getString("artifact_commit") != inspected.getString("artifact_commit")', block)
        self.assertIn('sealed.getLong("changed_file_count") != inspected.getLong("changed_file_count")', block)
        self.assertEqual(3, block.count('controller.throwIfCancelled()'))

    def test_verifier_refuses_empty_or_untrusted_evidence(self):
        verifier = source('delegation/SubAgentDeliveryEvidence.kt')
        for key in ('schema_version', 'source', 'workspace_id', 'base_commit', 'artifact_commit',
                    'net_diff_verified', 'base_is_ancestor', 'clean_worktree', 'head_matches_commit',
                    'changed_file_count', 'changed_files_truncated'):
            self.assertIn('"' + key + '"', verifier)
        self.assertIn('throw WorkspaceOperationException(INVALID)', verifier)
        self.assertIn('if (count == 0L) throw WorkspaceOperationException(EMPTY)', verifier)
        self.assertIn('countValue !is Int && countValue !is Long', verifier)
        self.assertNotIn('optBoolean(', verifier)
        self.assertNotIn('optInt(', verifier)

    def test_archival_preserves_evidence_separately_from_model_prose(self):
        groups = source('runtime/AgentChildTaskGroups.kt')
        archive = groups.split('private fun archiveSnapshot(', 1)[1].split('private fun retire(', 1)[0]
        for key in ('delivery_state', 'artifact_verified', 'artifact_evidence', 'acceptance_verified', 'model_report_unverified'):
            self.assertIn('"' + key + '"', archive)
        self.assertIn('snapshot.put(key, json.optString(key))', archive)
        self.assertNotIn('take(MAX_RESULT_CHARS)', archive)
        self.assertIn('coordinator.archiveRecord(id)', groups)
        self.assertIn('SubAgentResultPage.project(JSONObject(raw), JSONObject(call.argumentsJson))', groups)
        self.assertIn('put("text_evicted", true)', groups)
        listing = groups.split('private fun list(ownerId:', 1)[1]
        for key in ('delivery_state', 'artifact_verified', 'acceptance_verified', 'error_code', 'workspace_id', 'review_required', 'next_step'):
            self.assertIn('"' + key + '"', listing)

    def test_text_paging_is_wired_to_live_and_existing_task_tools(self):
        for path in ('delegation/SubAgentTools.kt', 'runtime/ExistingChildTaskTools.kt'):
            self.assertIn('SubAgentResultPage.addProperties(JSONObject())', source(path))
        coordinator = source('delegation/SubAgentCoordinator.kt')
        self.assertIn('SubAgentResultPage.validate(args)', coordinator)
        self.assertIn('SubAgentResultPage.project(record, textArgs)', coordinator)
        self.assertNotIn('answer.take(16000)', coordinator)
        self.assertIn('text_page.has_more', source('model/AgentPromptBuilder.kt'))

    def test_prompt_and_error_hints_distinguish_artifact_from_acceptance(self):
        tools = source('delegation/SubAgentTools.kt')
        for text in ('NO_IMPLEMENTATION_CHANGES', 'artifact_evidence', 'model_report_unverified', 'acceptance_verified'):
            self.assertIn(text, tools)
        self.assertIn('completed 仅表示子任务执行结束', source('model/AgentPromptBuilder.kt'))
        hints = source('delegation/SubAgentErrorHints.kt')
        self.assertIn('"NO_IMPLEMENTATION_CHANGES"', hints)
        self.assertIn('"IMPLEMENTATION_EVIDENCE_INVALID"', hints)


if __name__ == '__main__':
    unittest.main()
