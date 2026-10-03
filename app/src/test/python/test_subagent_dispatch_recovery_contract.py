"""Source wiring guards only; Kotlin runtime tests remain required before release."""
from pathlib import Path
import re
import unittest

AGENT = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta/agent'


class SubAgentDispatchRecoveryContract(unittest.TestCase):
    def source(self, name):
        return (AGENT / name).read_text()

    def test_typed_local_failure_is_connected_to_runtime_code_and_safe_result(self):
        source = self.source('delegation/SubAgentCoordinator.kt')
        self.assertIn('val executionFailure = SubAgentExecutionFailure.find(error)', source)
        self.assertIn('executionFailure != null -> executionFailure.code', source)
        self.assertIn('executionFailure.message + "\\n" + executionFailure.nextStep', source)
        self.assertIn('SubAgentExecutionFailure.nextStep(predecessor.errorCode) != null', source)
        self.assertIn('"local_tool_arguments_exhausted"', source)
        self.assertLess(source.index('executionFailure != null -> executionFailure.code'),
                        source.index('providerFailure != null -> "SUB_AGENT_PROVIDER_UNAVAILABLE"'))

    def test_local_classifier_does_not_infer_from_names_messages_or_causes(self):
        source = self.source('delegation/SubAgentExecutionFailure.kt')
        self.assertIn('error is AgentModelFailure && error.code == AgentInvalidToolArgumentsGuard.STOP_CODE', source)
        for forbidden in ('error.message', 'error.javaClass', 'error.cause', 'HTTP_', 'TimeoutException'):
            self.assertNotIn(forbidden, source)
        self.assertIn('不要据此替换子任务、恢复或自动重试', source)

    def test_recovery_waits_for_real_exit_and_hides_closed_group_controls(self):
        source = self.source('delegation/SubAgentCoordinator.kt')
        self.assertIn('closed || stopping || (task.state !in ACTIVE && active(task)) -> listOf("get_task_result")', source)
        self.assertIn('task.state !in ACTIVE && active(task) ->', source)
        self.assertIn('"can_continue", paused && !closed && !stopping', source)
        self.assertIn('(task.state in ACTIVE || !active(task)) &&', source)
        self.assertIn('task.role in MEDIA && task.state !in ACTIVE -> listOf("get_task_result")', source)

    def test_closed_and_stopping_never_advertise_replacement(self):
        source = self.source('delegation/SubAgentCoordinator.kt')
        self.assertIn('"can_replace", !closed && !stopping &&', source)

    def test_registry_allows_no_progress_pause_only_with_stop_and_read_evidence(self):
        source = self.source('runtime/AgentChildTaskGroups.kt')
        replacement = source.split('private fun replace(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('!ChildTaskReplacementSelection.eligibleStatus(snapshot)', replacement)
        self.assertIn('if (!snapshot.optBoolean("execution_stopped")', replacement)
        self.assertIn('!old.handoffs.matchesRead(predecessorId, handoffVersion)', replacement)
        self.assertIn('put("can_replace", false)', replacement)
        policy = self.source('runtime/ChildTaskReplacementSelection.kt')
        eligible = policy.split('fun eligibleStatus(', 1)[1].split('fun choose(', 1)[0]
        self.assertIn('"failed"', eligible)
        self.assertIn('"awaiting_decision"', eligible)
        self.assertIn('"SUB_AGENT_NO_PROGRESS"', eligible)

    def test_error_details_are_applied_before_tool_result_wrapping(self):
        source = self.source('runtime/AgentChildTaskGroups.kt')
        self.assertIn('details: JSONObject.() -> Unit = {}', source)
        self.assertIn('JSONObject().put("ok", false).put("code", code).apply(details)', source)
        self.assertIsNone(re.search(r'error\("[A-Z_]+"\)\s*\.put\(', source))

    def test_archival_keeps_recovery_fields_without_enabling_continuation(self):
        source = self.source('runtime/AgentChildTaskGroups.kt')
        archive = source.split('private fun archiveSnapshot', 1)[1].split('private fun retire', 1)[0]
        self.assertIn('"allowed_actions", "next_step"', archive)
        self.assertIn('put("can_continue", false)', archive)

    def test_worker_exception_diagnostic_is_emitted_after_classification(self):
        source = self.source('delegation/SubAgentCoordinator.kt')
        catch = source.split('} catch (error: Exception) {', 1)[1].split('} finally {', 1)[0]
        self.assertLess(catch.index('t.errorCode = when'), catch.index('diagnostic(t, "worker_exception", error)'))
        self.assertNotIn('error.message', catch.split('diagnostic(t, "worker_exception", error)', 1)[1])


if __name__ == '__main__':
    unittest.main()
