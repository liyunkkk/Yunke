package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.runtime.ChildTaskConfigPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class ChildTaskConfigPolicyTest {
    private data class Config(val model: String, val reasoning: String, val endpoint: String)
    private val worker = Policy.WorkerKey("conversation-a", "stable-worker", "review")
    private val task = Policy.TaskKey(worker, "old-generation", "old-task")
    private val old = Config("old-model", "off", "old-endpoint")
    private val edited = Config("new-model", "high", "new-endpoint")
    private val frozen = Policy.Snapshot(worker, task.generationId, "old-user-settings", old)
    private fun latest(key: Policy.WorkerKey = worker, revision: String = "new-user-settings") =
        Policy.Candidate(key, Policy.Availability.AVAILABLE, revision, edited)
    private fun failed() = Policy.Evidence(task, "review", "failed", "SUB_AGENT_PROVIDER_UNAVAILABLE",
        observationVersion = 5, handoffReadVersion = 5, executionStopped = true, canReplace = true)
    private fun successor(evidence: Policy.Evidence? = failed(), candidate: Policy.Candidate<Config> = latest()) =
        Policy.successor(task, worker, "review", frozen, evidence) { candidate }
    private fun assertDenied(code: Policy.Code, decision: Policy.Decision<Config>) {
        assertEquals(code, decision.code)
        assertEquals(Policy.Source.NONE, decision.source)
        assertFalse(decision.available)
        assertNull(decision.configuration)
        assertNull(decision.predecessor)
    }

    @Test fun healthyRetainedWorkNeverEvenResolvesEditedSettings() {
        val decision = Policy.ordinary(worker, frozen, retainedTasks = true) {
            error("Healthy retained work must not read latest model/reasoning/settings")
        }
        assertEquals(Policy.Source.FROZEN, decision.source)
        assertEquals(Policy.Code.ORIGINAL_CONFIGURATION_FROZEN, decision.code)
        assertSame(old, decision.configuration)
    }

    @Test fun ordinaryDispatchStillFrozenAfterProviderModelReasoningEdits() {
        val decision = Policy.ordinary(worker, frozen, retainedTasks = true) { latest() }
        assertSame(old, decision.configuration)
        assertNotEquals(edited, decision.configuration)
    }

    @Test fun retainedWorkWithLostSnapshotDoesNotReadLatestAsFallback() {
        assertDenied(Policy.Code.FROZEN_CONFIGURATION_MISSING,
            Policy.ordinary<Config>(worker, null, retainedTasks = true) { error("No fallback") })
    }

    @Test fun genuinelyNewDispatchUsesOnlySelectedCurrentConfiguration() {
        val decision = Policy.ordinary<Config>(worker, null, retainedTasks = false) { latest() }
        assertEquals(Policy.Source.CURRENT, decision.source)
        assertSame(edited, decision.configuration)
    }

    @Test fun ordinaryResolutionFailureReturnsAnExplicitReasonNotAnotherModel() {
        val decision = Policy.ordinary<Config>(worker, null, retainedTasks = false) {
            Policy.Candidate(worker, Policy.Availability.MODEL_UNAVAILABLE)
        }
        assertDenied(Policy.Code.NEW_CONFIGURATION_UNAVAILABLE, decision)
        assertEquals(Policy.Availability.MODEL_UNAVAILABLE, decision.availability)
        assertTrue(decision.reason.contains("missing or disabled"))
    }

    @Test fun manualPauseAndParentDisconnectCannotUnlockReplacement() {
        for (status in listOf("running", "queued", "awaiting_decision", "paused", "succeeded", "timed_out", "cancelled")) {
            val observed = failed().copy(status = status, errorCode = "SUB_AGENT_MANUAL_PAUSE")
            assertDenied(Policy.Code.CHILD_NOT_FAULTY, successor(observed))
        }
        // No child observation exists for a parent network exception.
        assertDenied(Policy.Code.CHILD_EVIDENCE_REQUIRED, successor(null))
    }

    @Test fun pausedContinuationUsesOriginalTaskConfiguration() {
        val evidence = failed().copy(status = "awaiting_decision", errorCode = "SUB_AGENT_MANUAL_PAUSE",
            executionStopped = false, canReplace = false)
        val decision = Policy.continuation(task, frozen, evidence)
        assertSame(old, decision.configuration)
        assertEquals(Policy.Source.FROZEN, decision.source)
        assertNull(decision.predecessor)
    }

    @Test fun cancelledTaskCannotBeDirectlyRevived() {
        assertDenied(Policy.Code.TASK_NOT_CONTINUABLE,
            Policy.continuation(task, frozen, failed().copy(status = "cancelled")))
        assertDenied(Policy.Code.CHILD_NOT_FAULTY, successor(failed().copy(status = "cancelled", errorCode = "")))
    }

    @Test fun actualChildFaultAndUserEditPermitExplicitLinkedSuccessor() {
        val decision = successor()
        assertTrue(decision.available)
        assertEquals(Policy.Source.EXPLICIT_SUCCESSOR, decision.source)
        assertEquals(Policy.Code.EXPLICIT_SUCCESSOR_AVAILABLE, decision.code)
        assertEquals(task, decision.predecessor)
        assertSame(edited, decision.configuration)
        assertSame(old, frozen.configuration)
    }

    @Test fun failedChildWithoutRelevantEditCannotSwitchToAnotherWorker() {
        assertDenied(Policy.Code.CONFIGURATION_UNCHANGED, successor(candidate = latest(revision = "old-user-settings")))
        assertDenied(Policy.Code.WORKER_MISMATCH, successor(candidate = latest(worker.copy(workerId = "other-worker"))))
    }

    @Test fun differentOwnerWorkerRoleTaskAndGenerationNeverShareEvidence() {
        val mismatches = listOf(
            Policy.Code.OWNER_MISMATCH to task.copy(worker = worker.copy(ownerId = "other-owner")),
            Policy.Code.WORKER_MISMATCH to task.copy(worker = worker.copy(workerId = "other-worker")),
            Policy.Code.ROLE_MISMATCH to task.copy(worker = worker.copy(role = "implementation")),
            Policy.Code.TASK_MISMATCH to task.copy(taskId = "other-task"),
            Policy.Code.GENERATION_MISMATCH to task.copy(generationId = "other-generation"),
        )
        mismatches.forEach { (code, other) -> assertDenied(code, successor(failed().copy(task = other))) }
    }

    @Test fun callerCannotChangeSuccessorWorkerOrRole() {
        assertDenied(Policy.Code.WORKER_MISMATCH,
            Policy.successor(task, worker.copy(workerId = "other"), "review", frozen, failed()) { latest() })
        assertDenied(Policy.Code.ROLE_MISMATCH,
            Policy.successor(task, worker, "research", frozen, failed()) { latest() })
    }

    @Test fun ordinarySnapshotAlsoRequiresExactOwnerWorkerAndRole() {
        for ((code, key) in listOf(
            Policy.Code.OWNER_MISMATCH to worker.copy(ownerId = "other-owner"),
            Policy.Code.WORKER_MISMATCH to worker.copy(workerId = "other-worker"),
            Policy.Code.ROLE_MISMATCH to worker.copy(role = "implementation"),
        )) {
            assertDenied(code, Policy.ordinary(key, frozen, true) { error("Must not resolve") })
        }
    }

    @Test fun differentGenerationSnapshotIsNotAReplacementConfiguration() {
        val wrong = Policy.Snapshot(worker, "other-generation", "old-user-settings", old)
        assertDenied(Policy.Code.GENERATION_MISMATCH,
            Policy.successor(task, worker, "review", wrong, failed()) { latest() })
    }

    @Test fun childMarkedFailedButStillExecutingOrPreparingMustNotBeRedispatched() {
        assertDenied(Policy.Code.STOP_NOT_CONFIRMED, successor(failed().copy(executionStopped = false)))
    }

    @Test fun handoffMustBeReadAndStillCurrent() {
        assertDenied(Policy.Code.HANDOFF_NOT_READ, successor(failed().copy(handoffReadVersion = null)))
        assertDenied(Policy.Code.HANDOFF_NOT_READ, successor(failed().copy(handoffReadVersion = 4)))
    }

    @Test fun onlySupportedNoProgressBlockCanBecomeStoppedSuccessor() {
        val blocked = failed().copy(status = "awaiting_decision", errorCode = "SUB_AGENT_NO_PROGRESS")
        assertDenied(Policy.Code.STOP_NOT_CONFIRMED, successor(blocked.copy(executionStopped = false)))
        val stopped = blocked.copy(status = "cancelled", errorCode = "REPLACED_AFTER_BLOCK")
        assertEquals(Policy.Code.EXPLICIT_SUCCESSOR_AVAILABLE, successor(stopped).code)
        assertDenied(Policy.Code.REPLACEMENT_NOT_SUPPORTED, successor(stopped.copy(canReplace = false)))
    }

    @Test fun healthyReplaceBypassRejectedBeforeReadingLatestConfiguration() {
        val result = Policy.successor(task, worker, "review", frozen,
            failed().copy(status = "running", errorCode = "")) { error("Healthy replace is forbidden") }
        assertDenied(Policy.Code.CHILD_NOT_FAULTY, result)
    }

    @Test fun unavailableEditedSelectionNeverFallsBackToFrozenOrAnotherModel() {
        for (availability in Policy.Availability.values().filter { it != Policy.Availability.AVAILABLE }) {
            val result = successor(candidate = Policy.Candidate(worker, availability))
            assertDenied(Policy.Code.NEW_CONFIGURATION_UNAVAILABLE, result)
            assertEquals(availability, result.availability)
            assertTrue(result.reason.contains(availability.reason))
        }
    }

    @Test fun uncertainPaidMediaNeverRedispatchedEvenIfReportedAsFailed() {
        assertDenied(Policy.Code.MEDIA_RESULT_UNCERTAIN, successor(failed().copy(mediaResultUncertain = true)))
        for (role in listOf("image_generation", "video_generation")) {
            val mediaWorker = worker.copy(role = role)
            val mediaTask = task.copy(worker = mediaWorker)
            val mediaFrozen = Policy.Snapshot(mediaWorker, task.generationId, "old-user-settings", old)
            val result = Policy.successor(mediaTask, mediaWorker, role, mediaFrozen,
                failed().copy(task = mediaTask, taskRole = role)) { latest(mediaWorker) }
            assertDenied(Policy.Code.MEDIA_REPLAY_NOT_SUPPORTED, result)
        }
    }

    @Test fun workspaceOwnershipAndManualReviewAreNotBypassedByNewConfiguration() {
        assertDenied(Policy.Code.WORKSPACE_REVIEW_REQUIRED, successor(failed().copy(workspaceId = "owned-workspace")))
        assertDenied(Policy.Code.WORKSPACE_REVIEW_REQUIRED, successor(failed().copy(workspacePath = "/workspace/project")))
        val implementationWorker = worker.copy(role = "implementation")
        val implementationTask = task.copy(worker = implementationWorker)
        val implementationFrozen = Policy.Snapshot(implementationWorker, task.generationId, "old-user-settings", old)
        assertDenied(Policy.Code.WORKSPACE_REVIEW_REQUIRED,
            Policy.successor(implementationTask, implementationWorker, "implementation", implementationFrozen,
                failed().copy(task = implementationTask, taskRole = "implementation")) { latest(implementationWorker) })
    }

    @Test fun unknownOrAlreadyClaimedDispatchCannotBeRetried() {
        assertDenied(Policy.Code.SUCCESSOR_ALREADY_CLAIMED, successor(failed().copy(successorClaimed = true)))
    }
}
